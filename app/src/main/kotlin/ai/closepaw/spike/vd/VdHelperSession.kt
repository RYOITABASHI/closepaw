package ai.closepaw.spike.vd

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.platform.virtualdisplay.VirtualDisplayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest

data class VdOpenResult(
    val displayId: Int,
    val helperPid: Int,
    val flagsUsed: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val firstFrameBytes: Int,
)

/** SHA-256 of 25 row-major RGBA samples across the central half of the image. */
data class VdFrameEvidence(
    val frameWidth: Int,
    val frameHeight: Int,
    val bytes: Int,
    val digest: String,
)

data class VdLaunchInfo(val stagingDir: String, val helperPid: Int?, val displayId: Int?)

/**
 * One root task as reported by the helper's typed query (stage-2 Increment 1).
 * Mirrors the incumbent contract: [packageName] is top-else-base; the separate
 * top/base fields are the candidate's extra fidelity, not part of the contract.
 */
data class VdRootTaskRow(
    val taskId: Int,
    val topPackage: String?,
    val basePackage: String?,
    val packages: Set<String>,
    val visible: Boolean?,
    val pinned: Boolean,
) {
    val packageName: String?
        get() = topPackage ?: basePackage
}

/** Parse the wire codec (VdProtocol) into rows. Throws on malformed input. */
internal fun parseVdRootTaskRows(encoded: String): List<VdRootTaskRow> =
    VdProtocol.parseRootTaskRows(encoded).map { fields ->
        VdRootTaskRow(
            taskId = fields[0].toInt(),
            topPackage = fields[1].takeUnless { it == "-" },
            basePackage = fields[2].takeUnless { it == "-" },
            packages = if (fields[3].isEmpty()) emptySet() else fields[3].split(",").toSet(),
            visible = fields[4].takeUnless { it == "-" }?.toBooleanStrict(),
            pinned = fields[5].toBooleanStrict(),
        )
    }

internal class VdHelperSession(private val context: Context, private val shell: AdbShellChannel) {
    private val mutex = Mutex()
    private var launch: Launch? = null

    @Volatile private var active: IBinder? = null
    @Volatile private var latestLaunch: Launch? = null

    /** Last attempted launch, including bookkeeping retained after teardown. */
    val launchInfo: VdLaunchInfo?
        get() = latestLaunch?.info

    val isOpen: Boolean
        get() = active?.isBinderAlive == true

    suspend fun open(): VdOpenResult = mutex.withLock {
        check(launch == null) { "Close the previous VD helper session before opening another" }
        check(Build.VERSION.SDK_INT >= 33) { "The VD spike requires Android 13 or later" }
        val current = Launch(context, shell)
        launch = current
        latestLaunch = current
        try {
            val result = current.open()
            active = current.helper?.binder
            check(isOpen) { "VD helper exited during open" }
            result
        } catch (failure: Throwable) {
            try {
                closeLocked()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    suspend fun captureFrame(timeoutMs: Long): VdFrameEvidence? = withTimeoutOrNull(timeoutMs) {
        val frames = mutex.withLock { if (isOpen) launch?.capture else null }
        frames?.awaitFrame()
    }

    /**
     * Stage-2 Increment 1: typed root-task query over the helper wire.
     * Returns the rows (possibly empty), or null when the helper query failed,
     * timed out, or the session is closed -- null always means fail closed.
     */
    suspend fun queryRootTasks(displayId: Int, timeoutMs: Long = 5_000): List<VdRootTaskRow>? {
        val current = mutex.withLock { if (isOpen) launch else null } ?: return null
        return current.queryRootTasks(displayId, timeoutMs)
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { closeLocked() }
    }

    private suspend fun closeLocked() = withContext(NonCancellable + Dispatchers.IO) {
        active = null
        val current = launch ?: return@withContext
        try {
            current.close()
        } finally {
            // Retain the remote path for an explicit retry if shell cleanup was unconfirmed.
            if (!current.remotePending) launch = null
        }
    }

    private class Launch(private val context: Context, shell: AdbShellChannel) {
        private val challenge = VdProtocol.newChallenge()
        private val remote = ResidentProcess(shell)
        @Volatile var info: VdLaunchInfo? = null
            private set
        private val events = HelperEvents { displayId ->
            info = checkNotNull(info).copy(displayId = displayId)
        }
        var capture: FrameCapture? = null
            private set
        private var creationConfirmed = false
        private var linked = false
        var helper: HelperRegistration? = null
            private set

        val remotePending: Boolean
            get() = remote.pending

        suspend fun open(): VdOpenResult = withContext(Dispatchers.IO) {
            val config = VirtualDisplayConfig.fromPhysicalDisplay(context)
            val frames = FrameCapture(config).also { capture = it }
            val endpoint = VdRendezvousProvider.expect(challenge)
            val registered = try {
                remote.start(
                    context,
                    challenge,
                    onStaging = { info = VdLaunchInfo(remote.directory, null, null) },
                    onPid = { pid ->
                        info = checkNotNull(info).copy(helperPid = pid)
                        VdRendezvousProvider.setLaunchPid(challenge, pid)
                    },
                )
                try {
                    withTimeout(10_000) { endpoint.await() }
                } catch (timeout: TimeoutCancellationException) {
                    throw IOException("VD helper registration timed out${remote.startupDiagnostic()}", timeout)
                }
            } finally {
                VdRendezvousProvider.forget(challenge)
            }
            helper = registered
            events.authenticate(registered)
            registered.binder.linkToDeath(events, 0)
            linked = true

            VdWire.send(registered.binder, VdWire.COMMANDS, VdWire.CREATE, registered.secret, Bundle().apply {
                putParcelable(VdWire.SURFACE, frames.reader.surface)
                putBinder(VdWire.RECEIVER, events)
                putInt(VdWire.WIDTH, config.width)
                putInt(VdWire.HEIGHT, config.height)
                putInt(VdWire.DENSITY, config.densityDpi)
            })
            val display = awaitStage("display creation") { events.created.await() }
            creationConfirmed = true
            val id = display.getInt(VdWire.DISPLAY_ID, -1)
            check(id > 0) { "Invalid VD display ID" }
            val flags = VdProtocol.flagNames(display.getInt(VdWire.FLAGS))

            frames.start()
            val frame = awaitStage("first ImageReader frame") { checkNotNull(frames.awaitFrame()) }
            VdWire.send(registered.binder, VdWire.COMMANDS, VdWire.FRAME, registered.secret, null)
            awaitStage("frame acknowledgement") { events.frame.await() }
            VdOpenResult(id, registered.pid, flags, frame.frameWidth, frame.frameHeight, frame.bytes)
        }

        private val queryIds = java.util.concurrent.atomic.AtomicInteger(0)

        suspend fun queryRootTasks(displayId: Int, timeoutMs: Long): List<VdRootTaskRow>? {
            val connected = helper ?: return null
            if (connected.binder.isBinderAlive != true) return null
            val queryId = queryIds.incrementAndGet()
            val response = CompletableDeferred<Bundle>()
            events.taskQueries[queryId] = response
            try {
                VdWire.send(connected.binder, VdWire.COMMANDS, VdWire.QUERY_ROOT_TASKS,
                    connected.secret, Bundle().apply {
                        putInt(VdWire.QUERY_ID, queryId)
                        putInt(VdWire.DISPLAY_ID, displayId)
                    })
                val payload = withTimeoutOrNull(timeoutMs) { response.await() } ?: return null
                if (payload.getBoolean(VdWire.QUERY_NULL, false)) return null
                val encoded = payload.getString(VdWire.ROWS) ?: return null
                return parseVdRootTaskRows(encoded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return null
            } finally {
                events.taskQueries.remove(queryId)
            }
        }

        suspend fun close() {
            val connected = helper
            val binderFailure = try {
                if (connected?.binder?.isBinderAlive == true) {
                    VdWire.send(connected.binder, VdWire.COMMANDS, VdWire.CLOSE, connected.secret, null)
                    if (creationConfirmed) {
                        withTimeout(3_000) { events.closed.await() }
                    }
                    withTimeout(3_000) { events.dead.await() }
                } else if (creationConfirmed) {
                    throw IOException("VD helper exited before explicit release")
                }
                null
            } catch (failure: Exception) {
                failure
            } finally {
                if (linked && connected != null) {
                    runCatching { connected.binder.unlinkToDeath(events, 0) }
                    linked = false
                }
                helper = null
            }
            val captureFailure = try {
                capture?.close()
                null
            } catch (failure: Exception) {
                failure
            } finally {
                capture = null
            }
            val releaseWasExpected = creationConfirmed
            creationConfirmed = false
            try {
                remote.cleanup()
            } catch (failure: Exception) {
                binderFailure?.let(failure::addSuppressed)
                captureFailure?.let(failure::addSuppressed)
                throw failure
            }
            if (binderFailure != null && releaseWasExpected) {
                val failure = IOException("VD helper stopped without a confirmed release", binderFailure)
                captureFailure?.let(failure::addSuppressed)
                throw failure
            }
            if (captureFailure != null) throw captureFailure
        }

        private suspend fun <T> awaitStage(stage: String, block: suspend () -> T): T =
            try {
                withTimeout(10_000) { block() }
            } catch (timeout: TimeoutCancellationException) {
                throw IOException("VD helper timed out waiting for $stage", timeout)
            }
    }

    private class HelperEvents(private val onDisplay: (Int) -> Unit) :
        VdWire.Endpoint(VdWire.EVENTS), IBinder.DeathRecipient {
        val created = CompletableDeferred<Bundle>()
        val frame = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val dead = CompletableDeferred<Unit>()
        val taskQueries = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        fun authenticate(registration: HelperRegistration) {
            bindPeer(VdProtocol.SHELL_UID, registration.pid, registration.secret)
        }

        override fun receive(code: Int, payload: Bundle) {
            when (code) {
                VdWire.CREATE -> {
                    val id = payload.getInt(VdWire.DISPLAY_ID, -1)
                    if (id > 0) onDisplay(id)
                    created.complete(payload)
                }
                VdWire.FRAME -> frame.complete(Unit)
                VdWire.CLOSE -> closed.complete(Unit)
                VdWire.ROOT_TASKS -> {
                    val queryId = payload.getInt(VdWire.QUERY_ID, -1)
                    taskQueries.remove(queryId)?.complete(payload)
                }
                VdWire.ERROR -> {
                    val failure = IOException(payload.getString(VdWire.DETAIL, "VD helper error"))
                    failOpenWaiters(failure)
                    closed.completeExceptionally(failure)
                }
            }
        }

        override fun binderDied() {
            dead.complete(Unit)
            failOpenWaiters(IOException("VD helper Binder died"))
        }

        private fun failOpenWaiters(failure: IOException) {
            created.completeExceptionally(failure)
            frame.completeExceptionally(failure)
            // Pending task queries fail closed: completing with QUERY_NULL keeps
            // the awaiting caller on the null path instead of hanging it.
            val pending = taskQueries.values.toList()
            taskQueries.clear()
            pending.forEach {
                it.complete(Bundle().apply { putBoolean(VdWire.QUERY_NULL, true) })
            }
        }
    }

    private class FrameCapture(config: VirtualDisplayConfig) {
        val reader = ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 2)
        private val available = Channel<Unit>(Channel.CONFLATED)
        private val captureMutex = Mutex()
        private val thread = HandlerThread("closepaw-vd-frames").apply { start() }
        private val handler = Handler(thread.looper)
        private var closed = false

        fun start() {
            reader.setOnImageAvailableListener({ available.trySend(Unit) }, handler)
            // A buffer may already be queued when the create acknowledgement arrives.
            available.trySend(Unit)
        }

        suspend fun awaitFrame(): VdFrameEvidence? = captureMutex.withLock {
            while (available.receiveCatching().isSuccess) {
                val frame = synchronized(reader) {
                    if (closed) null else reader.acquireLatestImage()?.use { image ->
                        val bytes = image.planes.sumOf { it.buffer.remaining() }
                        if (bytes > 0) {
                            VdFrameEvidence(image.width, image.height, bytes, digest(image))
                        } else null
                    }
                }
                if (frame != null) return@withLock frame
            }
            null
        }

        // Sample a 5x5 grid across the frame's central half, in row-major RGBA order.
        private fun digest(image: android.media.Image): String {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val hash = MessageDigest.getInstance("SHA-256")
            for (row in 2..6) {
                val y = (image.height - 1) * row / 8
                for (column in 2..6) {
                    val x = (image.width - 1) * column / 8
                    val offset = buffer.position() + y * plane.rowStride + x * plane.pixelStride
                    for (channel in 0..3) hash.update(buffer.get(offset + channel))
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }

        fun close() {
            available.close()
            try {
                synchronized(reader) {
                    closed = true
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                }
            } finally {
                thread.quitSafely()
            }
        }
    }
}
