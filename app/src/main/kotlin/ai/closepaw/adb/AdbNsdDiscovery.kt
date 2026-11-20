package ai.closepaw.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Resolves one candidate at a time so older Android versions can resolve multiple advertisements. */
@Suppress("DEPRECATION")
internal suspend fun discoverAdbPort(context: Context): Int? {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return null
    val candidates = Channel<NsdServiceInfo>(Channel.BUFFERED)
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) { candidates.close() }
        override fun onStartDiscoveryFailed(type: String, error: Int) { candidates.close() }
        override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) { candidates.trySend(info) }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
    }
    try {
        return withTimeoutOrNull(5_000) {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            for (candidate in candidates) {
                val service = resolve(nsd, candidate) ?: continue
                val host = service.host ?: continue
                // NSD sees other devices on the network too. Only use this phone's advertisements.
                val local = host.isLoopbackAddress || NetworkInterface.getByInetAddress(host) != null
                if (local) service.port.validAdbPort()?.let { return@withTimeoutOrNull it }
            }
            null
        }
    } finally {
        candidates.cancel()
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}

@Suppress("DEPRECATION")
private suspend fun resolve(nsd: NsdManager, candidate: NsdServiceInfo): NsdServiceInfo? =
    suspendCancellableCoroutine { continuation ->
        nsd.resolveService(candidate, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = continuation.resume(null)
            override fun onServiceResolved(info: NsdServiceInfo) = continuation.resume(info)
        })
    }
