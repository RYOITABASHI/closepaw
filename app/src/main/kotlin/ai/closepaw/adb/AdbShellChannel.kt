package ai.closepaw.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AdbShellChannel(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("closepaw-adb-channel", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val managerDelegate = lazy { AdbConnectionManager.create(app) }
    private val manager by managerDelegate
    @Volatile private var activeConnection: AdbConnection? = null

    val paired: Boolean
        get() = File(app.filesDir, AdbConnectionManager.KEY_FILE).isFile &&
            File(app.filesDir, AdbConnectionManager.CERT_FILE).isFile

    val lastPort: Int?
        get() = prefs.getInt("lastPort", 0).validAdbPort()

    fun isConnected(): Boolean = activeConnection?.let { it.isConnected && it.isConnectionEstablished } == true

    suspend fun connect(port: Int): Boolean = mutex.withLock {
        port.validAdbPort()?.let { connectLocked(it) } ?: false
    }

    data class PortStatus(
        val lastPort: Int?,
        val lastSuccessfulAt: Long?,
        val discoveredPort: Int?,
        val discoveredAt: Long?,
        val discoverySource: String?,
    )

    fun portStatus(): PortStatus {
        val values = prefs.all
        return PortStatus(
            lastPort = (values["lastPort"] as? Int).validAdbPort(),
            lastSuccessfulAt = values["lastSuccessfulAt"] as? Long,
            discoveredPort = (values["discoveredPort"] as? Int).validAdbPort(),
            discoveredAt = values["discoveredAt"] as? Long,
            discoverySource = values["discoverySource"] as? String,
        )
    }

    suspend fun connectAuto(): Boolean = mutex.withLock {
        isConnected() || withContext(Dispatchers.IO) {
            connectUsingPorts(lastPort, ::discoverPort, ::recordDiscovery, ::connectLocked)
        }
    }

    /** Maintenance can use recorded candidates without starting another NSD scan. */
    suspend fun connectKnownPorts(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isConnected()) return@withLock true
            val status = portStatus()
            connectUsingPorts(status.lastPort, { status.discoveredPort }, ::connectLocked)
        }
    }

    /** Record every valid observation. Only a disconnected channel can promote it. */
    suspend fun rediscover(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val discovered = discoverPort().validAdbPort() ?: return@withLock false
            recordDiscovery(discovered)
            if (shouldAttemptPromotion(isConnected(), lastPort, discovered)) {
                connectLocked(discovered)
            } else {
                true
            }
        }
    }

    private suspend fun discoverPort(): Int? = try {
        discoverAdbPort(app)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun recordDiscovery(port: Int) {
        prefs.edit()
            .putInt("discoveredPort", port)
            .putLong("discoveredAt", System.currentTimeMillis())
            .putString("discoverySource", "nsd")
            .apply()
    }

    private suspend fun connectLocked(port: Int): Boolean {
        if (isConnected() && lastPort == port) return true
        activeConnection = null
        try {
            val connection = runInterruptible(Dispatchers.IO) {
                manager.disconnect()
                manager.setTimeout(5_000, TimeUnit.MILLISECONDS)
                if (manager.connect(port)) manager.adbConnection else null
            } ?: return false
            val nonce = UUID.randomUUID().toString()
            val probe = checkNotNull(runShell(connection, "echo $nonce && id -u", 5_000) {})
            // This checks shell behavior and UID. A full shell impostor can answer both probes
            // and is outside the scope of this check.
            if (!isShellIdentityProbeValid(probe, nonce)) return false
            runInterruptible(Dispatchers.IO) {
                prefs.edit()
                    .putInt("lastPort", port)
                    .putLong("lastSuccessfulAt", System.currentTimeMillis())
                    .commit()
            }
            activeConnection = connection
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } finally {
            if (activeConnection == null && managerDelegate.isInitialized()) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { manager.disconnect() }
                }
            }
        }
    }

    suspend fun exec(command: String, timeoutMs: Long = 20_000): ShellResult =
        checkNotNull(shell(command, timeoutMs) {})

    /**
     * Returns null when [guard] rejects dispatch without opening a shell stream.
     * The guard runs under the channel mutex immediately before dispatch. It must be
     * fast and non-blocking, using only in-process state reads.
     * Binder transactions (including Shizuku or other Binder pings), I/O, blocking,
     * sleeping, and callbacks into this channel are prohibited. The mutex is non-reentrant.
     */
    suspend fun execGuarded(
        command: String,
        timeoutMs: Long = 20_000,
        guard: () -> Boolean,
    ): ShellResult? = shell(command, timeoutMs, guard) {}

    /** A length-delimited raw shell transfer leaves the stream open for the remote exit marker. */
    suspend fun writeToRemote(path: String, bytes: InputStream, size: Long) {
        require(size > 0)
        val destination = "'" + path.replace("'", "'\\''") + "'"
        val result = shell(
            "umask 077; rm -f $destination && head -c $size > $destination && " +
                "[ \"\$(wc -c < $destination)\" -eq $size ]",
            timeoutMs = 180_000,
        ) { stream ->
            // ADB guarantees a minimum packet payload of 4096 bytes.
            val chunk = ByteArray(4096)
            var remaining = size
            while (remaining > 0) {
                val count = bytes.read(chunk, 0, minOf(remaining, chunk.size.toLong()).toInt())
                if (count < 0) throw EOFException("APK ended during upload")
                stream.write(chunk, 0, count)
                remaining -= count
            }
        }
        if (checkNotNull(result).exitCode != 0) throw IOException("ADB upload failed")
    }

    private suspend fun shell(
        command: String,
        timeoutMs: Long,
        guard: (() -> Boolean)? = null,
        write: (AdbStream) -> Unit,
    ): ShellResult? = mutex.withLock {
        val connection = activeConnection ?: throw IOException("ADB channel is disconnected")
        runShell(connection, command, timeoutMs, guard, write)
    }

    private suspend fun runShell(
        connection: AdbConnection,
        command: String,
        timeoutMs: Long,
        guard: (() -> Boolean)? = null,
        write: (AdbStream) -> Unit,
    ): ShellResult? {
        // The single choke point every shell command passes through,
        // including the connection identity probe's direct call.
        requireAsciiShellCommand(command)
        return (withTimeoutOrNull<Result<ShellResult?>>(timeoutMs) {
        runInterruptible(Dispatchers.IO) {
            val markerSuffix = ":" + UUID.randomUUID().toString()
            val service = "shell:" + withExitCode(command, markerSuffix)
            // Wrap the result so guard rejection stays distinct from a timeout.
            if (guard?.invoke() == false) return@runInterruptible Result.success(null)
            val result = connection.open(service).use { stream ->
                write(stream)
                val capture = ShellOutput(markerSuffix)
                val buffer = ByteArray(8192)
                while (true) {
                    val count = try {
                        stream.read(buffer, 0, buffer.size)
                    } catch (e: IOException) {
                        // libadb reports a peer close as an IOException when no bytes are queued.
                        if (!stream.isClosed) throw e
                        break
                    }
                    if (count < 0) break
                    capture.append(buffer, count)
                    // Do not wait for another read once the marker arrives. In libadb 3.1.1,
                    // its pending-close path can leave a reader waiting on an empty queue.
                    if (capture.hasExitMarker()) break
                }
                capture.result()
            }
            Result.success(result)
        }
    } ?: throw SocketTimeoutException("ADB shell timed out")).getOrThrow()
    }
}
