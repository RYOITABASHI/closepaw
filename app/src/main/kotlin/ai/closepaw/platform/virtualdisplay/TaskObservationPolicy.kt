package ai.closepaw.platform.virtualdisplay

internal object TaskObservationPolicy {
    @Volatile var HELPER_ROUTE_ENABLED = true
    const val READ_TIMEOUT_MS = 1_000L
    private const val MIN_HELPER_SDK = 33

    fun helperRouteEnabled(sdkInt: Int, routeEnabled: Boolean = HELPER_ROUTE_ENABLED): Boolean =
            sdkInt >= MIN_HELPER_SDK && routeEnabled

    fun useHelperRoute(sdkInt: Int, routeEnabled: Boolean, sourceAttached: Boolean): Boolean =
            helperRouteEnabled(sdkInt, routeEnabled) && sourceAttached
}
