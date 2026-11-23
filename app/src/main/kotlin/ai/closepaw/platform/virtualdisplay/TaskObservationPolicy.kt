package ai.closepaw.platform.virtualdisplay

internal object TaskObservationPolicy {
    const val HELPER_ROUTE_ENABLED = true
    const val READ_TIMEOUT_MS = 1_000L
    private const val MIN_HELPER_SDK = 33

    fun useHelperRoute(sdkInt: Int, routeEnabled: Boolean, sourceAttached: Boolean): Boolean =
            sdkInt >= MIN_HELPER_SDK && routeEnabled && sourceAttached
}
