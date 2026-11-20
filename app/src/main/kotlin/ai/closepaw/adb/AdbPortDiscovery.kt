package ai.closepaw.adb

internal fun Int?.validAdbPort(): Int? = this?.takeIf { it in 1..65535 }

internal suspend fun connectUsingPorts(
    lastPort: Int?,
    discover: suspend () -> Int?,
    connect: suspend (Int) -> Boolean,
): Boolean {
    val cached = lastPort.validAdbPort()
    if (cached != null && connect(cached)) return true
    val discovered = discover().validAdbPort() ?: return false
    return discovered != cached && connect(discovered)
}
