package ai.closepaw.app

import ai.closepaw.adb.AdbShellChannel
import android.content.Context

object VirtualDisplayReadiness {
    /** The background-run precheck requires pairing plus a live connection or a recorded port. */
    fun isReady(paired: Boolean, connected: Boolean, hasKnownPort: Boolean): Boolean =
        paired && (connected || hasKnownPort)
}

internal fun probeVirtualDisplayReady(context: Context, channel: AdbShellChannel? = null): Boolean = try {
    val source = channel ?: AdbShellChannel(context.applicationContext)
    val ports = source.portStatus()
    VirtualDisplayReadiness.isReady(
        paired = source.paired,
        connected = channel?.isConnected() ?: false,
        hasKnownPort = ports.lastPort != null || ports.discoveredPort != null,
    )
} catch (e: Throwable) {
    false
}
