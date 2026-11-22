package ai.closepaw.adb

internal fun Int?.validAdbPort(): Int? = this?.takeIf { it in 1..65535 }

internal suspend fun connectUsingPorts(
    lastPort: Int?,
    discover: suspend () -> Int?,
    connect: suspend (Int) -> Boolean,
): Boolean = connectUsingPorts(lastPort, discover, {}, connect)

internal suspend fun connectUsingPorts(
    lastPort: Int?,
    discover: suspend () -> Int?,
    onDiscovered: (Int) -> Unit,
    connect: suspend (Int) -> Boolean,
): Boolean {
    val cached = lastPort.validAdbPort()
    if (cached != null && connect(cached)) return true
    val discovered = discover().validAdbPort() ?: return false
    onDiscovered(discovered)
    return adbPortToPromote(cached, discovered)?.let { connect(it) } ?: false
}

internal fun adbPortToPromote(lastPort: Int?, discoveredPort: Int?): Int? =
    discoveredPort.validAdbPort()?.takeUnless { it == lastPort }

internal fun shouldAttemptPromotion(isConnected: Boolean, lastPort: Int?, discoveredPort: Int?): Boolean =
    !isConnected && adbPortToPromote(lastPort, discoveredPort) != null
