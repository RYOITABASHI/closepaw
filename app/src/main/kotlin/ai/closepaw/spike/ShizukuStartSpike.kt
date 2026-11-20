package ai.closepaw.spike

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rikka.shizuku.Shizuku

/**
 * SPIKE (throwaway, never merges): can the app pair with and connect to the device's OWN wireless
 * debugging adbd (127.0.0.1), run Shizuku's official starter script as the shell user, and bring
 * a stopped Shizuku server (13.6.0) back — with no PC involved?
 *
 * Same discipline as [SelfUpdateSpike]: every step appends a stage record to
 * filesDir/[STAGE_FILE] before moving on; failures become a `failed` stage and never escape.
 * The pairing code is never recorded or logged.
 */
internal object ShizukuStartSpike {
    private const val TAG = "ClosePawSpikeStart"
    const val STAGE_FILE = "closepaw-spike-start.json"

    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val SHELL_TIMEOUT_MS = 20_000L
    private const val VERIFY_TIMEOUT_MS = 30_000L
    private const val VERIFY_POLL_MS = 500L
    private const val MAX_SHELL_BYTES = 64 * 1024

    private val running = AtomicBoolean(false)
    private val fileLock = Any()

    // ── Stage file ──

    private fun stageFile(ctx: Context) = File(ctx.filesDir, STAGE_FILE)

    fun readRecords(ctx: Context): List<SpikeStageRecord> = synchronized(fileLock) {
        val f = stageFile(ctx)
        SpikeStageLog.decode(runCatching { if (f.exists()) f.readText() else null }.getOrNull())
    }

    fun record(ctx: Context, stage: String, vararg fields: Pair<String, JsonPrimitive>) {
        val rec = SpikeStageRecord(stage, System.currentTimeMillis(), fields.toMap())
        Log.i(TAG, "stage ${rec.toJson()}")
        synchronized(fileLock) {
            runCatching {
                val f = stageFile(ctx)
                val existing = if (f.exists()) f.readText() else null
                val tmp = File(ctx.filesDir, "$STAGE_FILE.tmp")
                tmp.writeText(SpikeStageLog.append(existing, rec))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "stage write failed: ${SpikeStageLog.describe(it)}") }
        }
    }

    private fun resetStageFile(ctx: Context) = synchronized(fileLock) { runCatching { stageFile(ctx).delete() } }

    fun statusJson(ctx: Context): JsonObject = ShizukuStartStatus.json(readRecords(ctx), running.get())

    private fun recordFailed(ctx: Context, t: Throwable) {
        try {
            val atStage = runCatching { SpikeStageLog.lastCompletedStage(readRecords(ctx)) }.getOrDefault("unknown")
            record(ctx, "failed", "atStage" to SpikeStageLog.field(atStage),
                "error" to SpikeStageLog.field(SpikeStageLog.describe(t)))
        } catch (_: Throwable) {
        }
    }

    // ── Entry point ──

    /** Starts the flow on a background thread; returns the current stage at once. Never throws. */
    fun start(ctx: Context, args: ShizukuStartArgs): JsonObject {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) {
            return statusJson(app).let { JsonObject(it + ("accepted" to JsonPrimitive(false))) }
        }
        var launched = false
        try {
            resetStageFile(app)
            val aliveBefore = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            // NOTE: pairCode is deliberately absent.
            record(app, "started",
                "connectPort" to SpikeStageLog.field(args.connectPort),
                "hasPair" to JsonPrimitive(args.hasPair),
                "shizukuAliveBefore" to JsonPrimitive(aliveBefore))
            Thread({
                try {
                    runFlow(app, args)
                } catch (t: Throwable) {
                    recordFailed(app, t)
                } finally {
                    running.set(false)
                }
            }, "closepaw-spike-shizuku-start").start()
            launched = true
        } catch (t: Throwable) {
            running.set(false)
            recordFailed(app, t)
        }
        return try {
            statusJson(app).let { JsonObject(it + ("accepted" to JsonPrimitive(launched))) }
        } catch (t: Throwable) {
            JsonObject(mapOf(
                "accepted" to JsonPrimitive(launched),
                "error" to SpikeStageLog.field(SpikeStageLog.describe(t)),
            ))
        }
    }

    // ── The flow ──

    private fun runFlow(ctx: Context, args: ShizukuStartArgs) {
        var manager: SpikeAdbManager? = null
        try {
            val m = SpikeAdbManager.create(ctx)
            manager = m
            m.setTimeout(CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)

            // 1. Pair (only when a pairing port + code were supplied).
            if (args.hasPair) {
                val ok = m.pair(SpikeAdbManager.LOCALHOST, args.pairPort!!, args.pairCode!!)
                record(ctx, "paired", "ok" to JsonPrimitive(ok))
                if (!ok) throw IllegalStateException("pair() returned false")
            }

            // 2. Connect (TLS; throws AdbPairingRequiredException when this key was never paired).
            if (!m.connect(SpikeAdbManager.LOCALHOST, args.connectPort)) {
                throw IllegalStateException("connect() returned false")
            }
            record(ctx, "connected")

            // 3. Is the starter script there?
            val check = shell(m, ShizukuStartShell.CHECK_COMMAND)
            val exists = !check.timedOut && ShizukuStartShell.starterExists(check.result)
            record(ctx, "starter-check",
                "exists" to JsonPrimitive(exists),
                "exitCode" to exitCodeField(check.result),
                "timedOut" to JsonPrimitive(check.timedOut),
                "output" to SpikeStageLog.field(check.result.output.take(ShizukuStartShell.CHECK_OUTPUT_CHARS)))
            if (!exists) throw IllegalStateException("starter script not found at ${ShizukuStartShell.STARTER_PATH}")

            // 4. Run it. A timeout is recorded, not fatal: the server may still have come up.
            val ran = shell(m, ShizukuStartShell.RUN_COMMAND)
            record(ctx, "starter-ran",
                "exitCode" to exitCodeField(ran.result),
                "timedOut" to JsonPrimitive(ran.timedOut),
                "output" to SpikeStageLog.field(ran.result.output.take(ShizukuStartShell.RUN_OUTPUT_CHARS)))

            // 5. Verify from the app side: does Shizuku's binder answer?
            val t0 = System.currentTimeMillis()
            var alive: Boolean
            while (true) {
                alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val waited = System.currentTimeMillis() - t0
                if (alive || waited >= VERIFY_TIMEOUT_MS) break
                Thread.sleep(VERIFY_POLL_MS)
            }
            record(ctx, "verified",
                "shizukuAlive" to JsonPrimitive(alive),
                "waitedMs" to SpikeStageLog.field(System.currentTimeMillis() - t0))
        } catch (t: Throwable) {
            recordFailed(ctx, t)
        } finally {
            // disconnect(), not close(): close() also destroys the (persisted) private key object.
            runCatching { manager?.disconnect() }
        }
    }

    // ── Shell ──

    /** -1 when the exit-code marker never arrived (e.g. timeout). */
    private fun exitCodeField(r: ShizukuStartShell.Result): JsonPrimitive = SpikeStageLog.field(r.exitCode ?: -1)

    private class ShellRun(val result: ShizukuStartShell.Result, val timedOut: Boolean)

    /**
     * `shell:<cmd>` read to EOF, bounded by [SHELL_TIMEOUT_MS]. AdbStream.read blocks without a
     * timeout, so a reader thread drains it and the stream is closed to unblock it on timeout.
     */
    private fun shell(m: SpikeAdbManager, cmd: String): ShellRun {
        val stream: AdbStream = m.openStream("shell:" + ShizukuStartShell.withExitCode(cmd))
        val buf = ByteArrayOutputStream()
        val reader = Thread({
            val chunk = ByteArray(8 * 1024)
            try {
                while (true) {
                    val n = stream.read(chunk, 0, chunk.size)
                    if (n < 0) break
                    synchronized(buf) { if (buf.size() < MAX_SHELL_BYTES) buf.write(chunk, 0, n) }
                }
            } catch (_: IOException) {
                // libadb throws "Stream closed." on a read after the peer's close: that is EOF here.
            }
        }, "closepaw-spike-shell-reader")
        reader.isDaemon = true
        reader.start()
        reader.join(SHELL_TIMEOUT_MS)
        val timedOut = reader.isAlive
        if (timedOut) {
            runCatching { stream.close() }
            reader.join(1_000)
        } else {
            runCatching { stream.close() }
        }
        val raw = synchronized(buf) { buf.toString(Charsets.UTF_8.name()) }
        return ShellRun(ShizukuStartShell.parse(raw), timedOut)
    }
}
