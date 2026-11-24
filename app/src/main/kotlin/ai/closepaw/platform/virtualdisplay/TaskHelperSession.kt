package ai.closepaw.platform.virtualdisplay

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.taskhelper.HelperRegistration
import ai.closepaw.taskhelper.TaskHelperInput
import ai.closepaw.taskhelper.TaskHelperProtocol
import ai.closepaw.taskhelper.TaskHelperRendezvousProvider
import ai.closepaw.taskhelper.TaskHelperWire
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal class TaskHelperSession(
    private val context: Context,
    private val shell: AdbShellChannel,
    private val ownership: TaskHelperOwnership,
) : HelperRowSource {
    private val lifecycle = Mutex()
    private var process: TaskHelperProcess? = null
    private var connection: Connection? = null
    @Volatile private var active: Connection? = null

    val isAlive: Boolean get() = active?.isAlive == true

    /** Sweeps leftover helper staging directories and refreshes the persistent stale record. */
    suspend fun sweepStaleStaging() {
        withContext(Dispatchers.IO) {
            runCatching { TaskHelperProcess(shell).sweepStaleDirectories() }
                    .onSuccess { writeStaleStaging(it) }
        }
    }

    suspend fun open(token: TaskHelperOwnership.Token, shouldOpen: () -> Boolean = { true }) = lifecycle.withLock {
        if (!ownership.isCurrent(token)) return@withLock
        if (ownership.installedGeneration() == token.generation && active?.isAlive == true) return@withLock
        if (!shouldOpen()) return@withLock
        sweepStaleStaging()
        val previous = ownership.installedGeneration()
        if (previous != 0) {
            // A newer start replaces an older generation whose close has not run
            // (or was superseded by this open): retire the old helper first.
            cleanup()?.let { Log.w(TAG, "Task helper cleanup unconfirmed", it) }
            ownership.markRetired(previous)
        }
        check(process == null) { "Previous task helper cleanup is pending" }
        try {
            val current = withContext(Dispatchers.IO) {
                val launch = TaskHelperProcess(shell)
                val challenge = TaskHelperProtocol.newChallenge()
                val registration = TaskHelperRendezvousProvider.expect(challenge)
                process = launch
                try {
                    launch.start(context, challenge) { TaskHelperRendezvousProvider.setLaunchPid(challenge, it) }
                    withTimeout(OPEN_TIMEOUT_MS) {
                        Connection(registration.await()).also {
                            connection = it
                            it.attach()
                        }
                    }
                } finally {
                    TaskHelperRendezvousProvider.forget(challenge)
                }
            }
            check(current.isAlive) { "Task helper exited during attach" }
            ownership.markInstalled(token)
            // Keep the publication check and the connection write atomic with mint and detach.
            val published = synchronized(ownership) {
                ownership.tryPublish(token).also { if (it) active = current }
            }
            if (!published) {
                Log.w(TAG, "Task helper open superseded before publish (generation ${token.generation}); retiring unpublished helper")
                cleanup()?.let { Log.w(TAG, "Task helper cleanup unconfirmed", it) }
                ownership.markRetired(token.generation)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { cleanup()?.let(failure::addSuppressed) }
            }
            ownership.markRetired(token.generation)
            throw failure
        }
    }

    fun displayConnection(token: TaskHelperOwnership.Token): Connection? = synchronized(ownership) {
        active?.takeIf { ownership.isCurrent(token) && ownership.isPublished(token) && it.isAlive }
    }

    override fun queryRows(displayId: Int): List<TaskHelperRow>? =
            queryRows(displayId, TaskObservationPolicy.READ_TIMEOUT_MS)

    fun queryRows(displayId: Int, timeoutMs: Long): List<TaskHelperRow>? =
            if (displayId < 0 || timeoutMs <= 0) null else active?.queryRows(displayId, timeoutMs)

    fun write(code: Int, payload: Bundle): TaskWriteResult =
            active?.write(code, payload) ?: TaskWriteResult.FAILED

    suspend fun close(token: TaskHelperOwnership.Token, shouldClose: () -> Boolean = { true }) = withContext(NonCancellable) {
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                // Only the generation that opened the helper may close it: a delayed
                // close posted for an older generation must never kill a replacement
                // session's helper, and a close for a generation that never opened
                // (or already closed) is a no-op.
                if (!ownership.claimInstalled(token)) return@withLock
                if (shouldClose()) {
                    cleanup()?.let { Log.w(TAG, "Task helper cleanup unconfirmed", it) }
                    ownership.markRetired(token.generation)
                }
            }
        }
    }

    /**
     * Invalidates the ownership so an in-flight [open] can no longer publish:
     * its tryPublish fails and it retires the helper it launched itself. A
     * connection that is already published keeps serving until the posted
     * close's cleanup runs — invalidation revokes future publication, not
     * present service.
     */
    fun invalidateOwnership() {
        synchronized(ownership) { ownership.invalidate() }
    }

    /**
     * Generation-scoped invalidation: invalidates only while [token] is
     * still the ownership's current token. A callback that captured the
     * ending session's token and resumes after a replacement session has
     * minted must not invalidate the replacement's in-flight open.
     */
    fun invalidateOwnershipIfCurrent(token: TaskHelperOwnership.Token) {
        synchronized(ownership) { ownership.invalidateIfCurrent(token) }
    }

    /**
     * Stops reads from resolving this session's helper immediately (the platform
     * restarting from Broken must not be served by the broken session's helper);
     * the posted close completes the teardown.
     */
    fun detachActive() {
        synchronized(ownership) {
            active = null
            invalidateOwnership()
        }
    }

    private suspend fun cleanup(): Throwable? {
        active = null
        val current = connection
        current?.beginClose()
        val deathConfirmed = current?.awaitHelperDeath() == true
        val launch = process
        var failure: Throwable? = null
        if (launch != null) {
            val attempts = if (deathConfirmed) 1 else CLEANUP_ATTEMPTS
            for (attempt in 1..attempts) {
                failure = runCatching { launch.cleanup() }.exceptionOrNull()
                if (failure == null) break
                if (attempt < attempts) delay(CLEANUP_RETRY_DELAY_MS)
            }
        } else if (current != null && !deathConfirmed) {
            failure = IOException("Task helper death unconfirmed")
        }
        if (failure != null && (launch != null || current != null)) {
            // Death and/or staging removal is unconfirmed: the connection is NOT
            // dropped on a guess. The app-lifetime reaper takes ownership of the
            // pair and keeps retiring it in the background; this session forgets
            // it only once the reaper owns it. The stale record stays as the
            // persistent backstop for the staging directory.
            if (deathConfirmed) current?.drop()
            TaskHelperReaper.enroll(launch, if (deathConfirmed) null else current)
            if (launch != null) recordStaleStaging(launch.directoryPath)
            process = null
            connection = null
            return failure
        }
        current?.drop()
        connection = null
        if (launch != null && !launch.pending) process = null
        return failure
    }

    private fun readStaleStaging(): List<String> = runCatching {
        File(context.filesDir, STALE_STAGING_FILE).readLines()
    }.getOrDefault(emptyList())

    private fun writeStaleStaging(directories: List<String>) {
        runCatching {
            val file = File(context.filesDir, STALE_STAGING_FILE)
            if (directories.isEmpty()) file.delete()
            else file.writeText(directories.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun recordStaleStaging(directory: String) {
        writeStaleStaging((readStaleStaging() + directory).distinct())
    }

    class Connection(private val registration: HelperRegistration) {
        private val events = Events(registration)
        private val queryLock = ReentrantLock()
        private val displayLock = ReentrantLock()
        private val inputLock = ReentrantLock()
        private val displayCommandsUsable = AtomicBoolean(true)
        // Keep a stalled Binder transaction outside the caller's deadline.
        private val sender = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "closepaw-task-helper-send").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val ids = AtomicInteger()
        private val binder = registration.binder
        private var deathRecipient: IBinder.DeathRecipient? = null
        private val closed = AtomicBoolean()
        val isAlive: Boolean get() = !closed.get() && !events.dead.isCompleted && binder.isBinderAlive

        /** Whether helper death has been genuinely observed (death recipient fired or binder gone). */
        fun isDeathObserved(): Boolean = events.dead.isCompleted || !binder.isBinderAlive

        suspend fun attach() {
            binder.linkToDeath(events, 0)
            deathRecipient = events
            sender.submit {
                runCatching {
                    send(TaskHelperWire.ATTACH, Bundle().apply { putBinder(TaskHelperWire.RECEIVER, events) })
                }.onFailure { events.attached.completeExceptionally(it) }
            }
            events.attached.await()
        }

        fun queryRows(displayId: Int, timeoutMs: Long): List<TaskHelperRow>? {
            if (!isAlive) return null
            val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val started = System.nanoTime()
            try {
                if (!queryLock.tryLock(budget, TimeUnit.NANOSECONDS)) return null
                try {
                    if (!isAlive || System.nanoTime() - started >= budget) return null
                    val query = TaskHelperQuery(ids.incrementAndGet(), displayId)
                    events.pending.set(query)
                    try {
                        if (!isAlive) return null
                        val sending = sender.submit {
                            if (events.pending.get() === query && isAlive &&
                                    System.nanoTime() - started < budget) {
                                runCatching {
                                    send(TaskHelperWire.QUERY_ROOT_TASKS, Bundle().apply {
                                        putInt(TaskHelperWire.QUERY_ID, query.id)
                                        putInt(TaskHelperWire.DISPLAY_ID, displayId)
                                    })
                                }.onFailure { query.complete(null) }
                            } else {
                                query.complete(null)
                            }
                        }
                        try {
                            val remaining = budget - (System.nanoTime() - started)
                            return query.await(remaining).takeIf { isAlive }
                        } finally {
                            sending.cancel(false)
                        }
                    } finally {
                        events.pending.compareAndSet(query, null)
                    }
                } finally {
                    queryLock.unlock()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                return null
            }
        }

        suspend fun awaitDeath() = events.dead.await()

        /** Null denotes an unconfirmed mutation. Never retry a create on that result. */
        fun displayCommand(operation: Int, displayId: Int = -1, payload: Bundle = Bundle()): Bundle? {
            // An unconfirmed surface switch can be undone on the same command queue.
            val disableOnUnconfirmed = operation != TaskHelperWire.SET_DISPLAY_SURFACE
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISPLAY_TIMEOUT_MS)
            try {
                if (!displayLock.tryLock(DISPLAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    if (disableOnUnconfirmed) displayCommandsUsable.set(false)
                    return null
                }
                try {
                    if (!isAlive || !displayCommandsUsable.get()) return null
                    var confirmed = false
                    val request = DisplayRequest(ids.incrementAndGet(), operation, displayId)
                    events.displayPending.set(request)
                    try {
                        payload.putInt(TaskHelperWire.REQUEST_ID, request.id)
                        payload.putInt(TaskHelperWire.DISPLAY_ID, displayId)
                        val sending = sender.submit {
                            if (events.displayPending.get() === request && isAlive &&
                                    displayCommandsUsable.get() && System.nanoTime() < deadline) {
                                runCatching { send(operation, payload) }
                                        .onFailure { request.reply.complete(null) }
                            } else {
                                request.reply.complete(null)
                            }
                        }
                        try {
                            return request.reply.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                                    .takeIf { isAlive && displayCommandsUsable.get() }
                                    .also { confirmed = it != null }
                        } finally {
                            sending.cancel(false)
                        }
                    } finally {
                        if (!confirmed && disableOnUnconfirmed) displayCommandsUsable.set(false)
                        events.displayPending.compareAndSet(request, null)
                    }
                } finally {
                    displayLock.unlock()
                }
            } catch (_: InterruptedException) {
                if (disableOnUnconfirmed) displayCommandsUsable.set(false)
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                return null
            }
        }

        fun write(code: Int, payload: Bundle): TaskWriteResult {
            if (!isAlive) return TaskWriteResult.FAILED
            val budget = TimeUnit.MILLISECONDS.toNanos(TaskObservationPolicy.READ_TIMEOUT_MS)
            val deadline = System.nanoTime() + budget
            val dispatch = TaskWriteDispatch(deadline)
            try {
                if (!queryLock.tryLock(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                    return TaskWriteResult.FAILED
                }
                try {
                    if (!isAlive) return TaskWriteResult.FAILED
                    val write = PendingWrite(ids.incrementAndGet())
                    payload.putInt(TaskHelperWire.QUERY_ID, write.id)
                    events.pendingWrite.set(write)
                    try {
                        val sending = sender.submit {
                            if (events.pendingWrite.get() === write && isAlive &&
                                    dispatch.tryStart()) {
                                // A send exception can race with transaction delivery.
                                runCatching { send(code, payload) }
                                        .onFailure { write.answer.complete(TaskWriteResult.UNKNOWN) }
                            } else {
                                write.answer.complete(TaskWriteResult.FAILED)
                            }
                        }
                        try {
                            val remaining = deadline - System.nanoTime()
                            val result = write.answer.get(remaining, TimeUnit.NANOSECONDS)
                            return if (result == TaskWriteResult.UNKNOWN) dispatch.abort() else result
                        } finally {
                            sending.cancel(false)
                        }
                    } finally {
                        events.pendingWrite.compareAndSet(write, null)
                    }
                } finally {
                    queryLock.unlock()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return dispatch.abort()
            } catch (_: Exception) {
                return dispatch.abort()
            }
        }

        fun injectInput(input: TaskHelperInputRequest, isCurrentTarget: () -> Boolean): InputOutcome {
            if (!input.valid) return InputOutcome.INVALID_TARGET_OR_REQUEST
            val deadline = SystemClock.uptimeMillis() + input.timeoutMs
            // A single pending mutation keeps input dispatch ordered, with the lock wait
            // included in the caller's deadline.
            try {
                if (!inputLock.tryLock(input.timeoutMs, TimeUnit.MILLISECONDS)) return InputOutcome.TIMEOUT
                try {
                    if (!isAlive) return InputOutcome.HELPER_DIED
                    val request = InputRequest(ids.incrementAndGet(), input)
                    events.inputPending.set(request)
                    try {
                        val sending = sender.submit {
                            when {
                                !isAlive -> request.reply.complete(InputOutcome.HELPER_DIED)
                                !isCurrentTarget() -> request.reply.complete(InputOutcome.INVALID_TARGET_OR_REQUEST)
                                SystemClock.uptimeMillis() >= deadline -> request.reply.complete(InputOutcome.TIMEOUT)
                                events.inputPending.get() === request -> {
                                    runCatching {
                                        send(TaskHelperWire.INJECT_INPUT, input.payload(request.id, deadline))
                                    }.onFailure {
                                        request.reply.complete(
                                            if (isAlive) InputOutcome.EXCEPTION else InputOutcome.HELPER_DIED,
                                        )
                                    }
                                }
                            }
                        }
                        try {
                            val result = request.reply.get(deadline - SystemClock.uptimeMillis(),
                                TimeUnit.MILLISECONDS)
                            return when {
                                !isAlive -> InputOutcome.HELPER_DIED
                                !isCurrentTarget() -> InputOutcome.INVALID_TARGET_OR_REQUEST
                                else -> result
                            }
                        } finally {
                            sending.cancel(false)
                        }
                    } finally {
                        events.inputPending.compareAndSet(request, null)
                    }
                } finally {
                    inputLock.unlock()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return InputOutcome.UNCONFIRMED
            } catch (_: TimeoutException) {
                return if (isAlive) InputOutcome.TIMEOUT else InputOutcome.HELPER_DIED
            } catch (_: Exception) {
                return if (isAlive) InputOutcome.EXCEPTION else InputOutcome.HELPER_DIED
            }
        }

        fun beginClose() {
            closed.set(true)
            events.pending.get()?.complete(null)
            events.pendingWrite.get()?.answer?.complete(TaskWriteResult.UNKNOWN)
            events.displayPending.get()?.reply?.complete(null)
            events.inputPending.get()?.reply?.complete(InputOutcome.HELPER_DIED)
        }

        suspend fun awaitHelperDeath(): Boolean {
            if (events.dead.isCompleted) return true
            if (!binder.isBinderAlive) return events.dead.isCompleted
            runCatching {
                sender.submit {
                    runCatching { send(TaskHelperWire.CLOSE, Bundle()) }
                }
            }
            return withTimeoutOrNull(CLOSE_WAIT_MS) { events.dead.await() } != null
        }

        fun drop() {
            sender.shutdownNow()
            deathRecipient?.let { runCatching { binder.unlinkToDeath(it, 0) } }
            deathRecipient = null
        }

        private fun send(code: Int, payload: Bundle?) =
                TaskHelperWire.send(binder, TaskHelperWire.COMMANDS, code, registration.secret, payload)
    }

    private class PendingWrite(val id: Int) {
        val answer = CompletableFuture<TaskWriteResult>()
    }

    private class DisplayRequest(val id: Int, val operation: Int, val displayId: Int) {
        val reply = CompletableFuture<Bundle?>()
    }

    private class InputRequest(val id: Int, val input: TaskHelperInputRequest) {
        val reply = CompletableFuture<InputOutcome>()
    }

    private class Events(registration: HelperRegistration) :
            TaskHelperWire.Endpoint(TaskHelperWire.EVENTS), IBinder.DeathRecipient {
        val attached = CompletableDeferred<Unit>()
        val dead = CompletableDeferred<Unit>()
        val pending = AtomicReference<TaskHelperQuery?>()
        val pendingWrite = AtomicReference<PendingWrite?>()
        val displayPending = AtomicReference<DisplayRequest?>()
        val inputPending = AtomicReference<InputRequest?>()

        init {
            bindPeer(TaskHelperProtocol.SHELL_UID, registration.pid, registration.secret)
        }

        @Suppress("DEPRECATION") // Bundle.get preserves the wire type for strict validation.
        override fun receive(code: Int, payload: Bundle) {
            when (code) {
                TaskHelperWire.ATTACH -> attached.complete(Unit)
                TaskHelperWire.ROOT_TASKS -> {
                    val query = pending.get() ?: return
                    val rows = runCatching {
                        val id = payload.get(TaskHelperWire.QUERY_ID)
                        if (id is Int && id != query.id) return
                        if (id != query.id ||
                                payload.get(TaskHelperWire.DISPLAY_ID) != query.displayId) {
                            null
                        } else {
                            TaskHelperRows.replyRows(
                                    queryNull = payload.containsKey(TaskHelperWire.QUERY_NULL),
                                    encodedRows = payload.get(TaskHelperWire.ROWS) as? String,
                            )
                        }
                    }.getOrNull()
                    query.complete(rows)
                }
                TaskHelperWire.DISPLAY_RESULT -> {
                    val request = displayPending.get() ?: return
                    val reply = runCatching {
                        if (payload.get(TaskHelperWire.REQUEST_ID) != request.id) return
                        val displayId = payload.get(TaskHelperWire.DISPLAY_ID)
                        val success = payload.get(TaskHelperWire.SUCCESS)
                        payload.takeIf {
                            matchesDisplayResult(
                                    request.operation, request.displayId,
                                    payload.get(TaskHelperWire.OPERATION), displayId, success,
                            ) && (request.operation != TaskHelperWire.CREATE_DISPLAY || success != true ||
                                    (payload.getBinder(TaskHelperWire.DISPLAY_CALLBACK) != null &&
                                            payload.get(TaskHelperWire.IME_AT_CREATION) is Boolean))
                        }
                    }.getOrNull()
                    request.reply.complete(reply)
                }
                TaskHelperWire.WRITE_RESULT -> {
                    val write = pendingWrite.get() ?: return
                    runCatching {
                        if (payload.get(TaskHelperWire.QUERY_ID) == write.id) {
                            write.answer.complete(decodeTaskWriteResult(payload.get(TaskHelperWire.WRITE_OK)))
                        }
                    }
                }
                TaskHelperWire.INPUT_RESULT -> {
                    val request = inputPending.get() ?: return
                    val outcome = runCatching {
                        if (payload.get(TaskHelperWire.REQUEST_ID) != request.id) return
                        if (payload.get(TaskHelperWire.DISPLAY_ID) != request.input.displayId) {
                            InputOutcome.UNCONFIRMED
                        } else {
                            val motion = request.input.kind == TaskHelperInput.MOTION
                            inputOutcome(
                                motion,
                                payload.get(TaskHelperInput.REFLECTION_OK),
                                payload.get(if (motion) TaskHelperInput.ACCEPTED else TaskHelperInput.DOWN),
                                payload.get(TaskHelperInput.UP),
                                payload.get(TaskHelperInput.ERROR),
                            )
                        }
                    }.getOrDefault(InputOutcome.UNCONFIRMED)
                    request.reply.complete(outcome)
                }
                TaskHelperWire.ERROR -> {
                    val id = payload.get(TaskHelperWire.QUERY_ID)
                    if (id is Int) {
                        pendingWrite.get()?.takeIf { it.id == id }
                                ?.answer?.complete(TaskWriteResult.FAILED)
                        pending.get()?.takeIf { it.id == id }?.complete(null)
                    } else {
                        attached.completeExceptionally(IOException(payload.getString(TaskHelperWire.DETAIL)))
                    }
                }
            }
        }

        override fun binderDied() {
            dead.complete(Unit)
            attached.completeExceptionally(IOException("Task helper Binder died"))
            pending.get()?.binderDied()
            pendingWrite.get()?.answer?.complete(TaskWriteResult.UNKNOWN)
            displayPending.get()?.reply?.complete(null)
            inputPending.get()?.reply?.complete(InputOutcome.HELPER_DIED)
        }
    }

    companion object {
        const val DEX_ASSET = "closepaw-task-helper-v1.dex"
        private const val TAG = "TaskHelperSession"
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val DISPLAY_TIMEOUT_MS = 1_500L
        private const val CLOSE_WAIT_MS = 1_000L
        private const val CLEANUP_ATTEMPTS = 3
        private const val CLEANUP_RETRY_DELAY_MS = 250L
        private const val STALE_STAGING_FILE = "closepaw-task-helper-stale.txt"
    }
}


/** Reject mismatched targets and malformed replies before allowing a fallback or a commit. */
internal fun matchesDisplayResult(
    operation: Int,
    requestedDisplayId: Int,
    replyOperation: Any?,
    replyDisplayId: Any?,
    success: Any?,
): Boolean {
    if (operation != replyOperation || replyDisplayId !is Int || success !is Boolean) return false
    return if (operation == TaskHelperWire.CREATE_DISPLAY && success) replyDisplayId > 0
    else replyDisplayId == requestedDisplayId
}
