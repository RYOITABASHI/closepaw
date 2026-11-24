package ai.closepaw.platform.virtualdisplay

import ai.closepaw.adb.AdbShellChannel
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the existing input commands over the session's ADB shell. */
internal class VirtualDisplayShellInput(
    private val channel: AdbShellChannel,
    private val displayIdProvider: () -> Int,
    private val isDispatchCancelled: () -> Boolean,
) {
    /** A cached connection can survive a cut. Require a successful remote exit marker. */
    suspend fun confirmChannel(reconnect: Boolean): Boolean {
        return try {
            if (reconnect && !channel.isConnected() && !channel.connectKnownPorts()) return false
            withTimeoutOrNull(CHANNEL_PROBE_TIMEOUT_MS) {
                channel.exec("true", timeoutMs = CHANNEL_PROBE_TIMEOUT_MS).exitCode == 0
            } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun execute(command: Array<String>): Int {
        val displayId = command.getOrNull(2)?.toIntOrNull() ?: return -1
        val operation = command.getOrNull(3)
        if (displayId <= 0 || command.getOrNull(0) != "input" || command.getOrNull(1) != "-d" ||
            operation !in setOf("tap", "swipe", "keyevent", "text")
        ) return -1
        val isCurrent = { displayIdProvider() == displayId && !isDispatchCancelled() }
        if (!isCurrent()) return -1
        return try {
            if (!channel.isConnected() && !channel.connectKnownPorts()) {
                Log.w(TAG, "input route=adb outcome=channel_unavailable")
                return -1
            }
            // Quote every argument, including printable ASCII supplied to input text.
            val script = command.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            val result = channel.execGuarded(script, timeoutMs = 32_000L, guard = isCurrent)
            val exitCode = result?.exitCode ?: -1
            Log.i(TAG, "input route=adb outcome=${if (exitCode == 0) "exit_ok" else "failed"}")
            exitCode
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "input route=adb outcome=failed (${failure.javaClass.simpleName})")
            -1
        }
    }

    private companion object {
        const val CHANNEL_PROBE_TIMEOUT_MS = 1_500L
        const val TAG = "VDInput"
    }
}
