package ai.closepaw.platform.virtualdisplay

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal fun interface HelperRowSource {
    fun queryRows(displayId: Int): List<TaskHelperRow>?
}

internal class TaskReadSourceStats {
    val helperServed = AtomicInteger()
    val fallback = AtomicInteger()
}

internal class RoutedTaskRead(
        val stats: TaskReadSourceStats = TaskReadSourceStats(),
        private val onSource: (ServedSource) -> Unit = {},
) {
    fun helperRows(displayId: Int, sdkInt: Int, routeEnabled: Boolean, source: HelperRowSource?): List<TaskHelperRow>? {
        if (displayId < 0 || !TaskObservationPolicy.useHelperRoute(sdkInt, routeEnabled, source != null)) {
            return null
        }
        val rows = source?.queryRows(displayId)
        if (rows == null) {
            stats.fallback.incrementAndGet()
            onSource(ServedSource.INCUMBENT)
        } else {
            stats.helperServed.incrementAndGet()
            onSource(ServedSource.HELPER)
        }
        return rows
    }

    /**
     * Serves one routed read end to end: when the helper answers, its rows are
     * mapped through [mapRows]; otherwise the [incumbent] call's own result is
     * served. Returns null only when the helper did not answer AND the incumbent
     * returned null. A negative display id serves nothing (null) and consults
     * neither route, matching the existing call sites.
     */
    fun <T> served(
            displayId: Int,
            sdkInt: Int,
            routeEnabled: Boolean,
            source: HelperRowSource?,
            incumbent: () -> T?,
            mapRows: (List<TaskHelperRow>) -> T,
    ): Served<T>? {
        if (displayId < 0) return null
        val rows = helperRows(displayId, sdkInt, routeEnabled, source)
        if (rows != null) return Served(mapRows(rows), ServedSource.HELPER)
        return incumbent()?.let { Served(it, ServedSource.INCUMBENT) }
    }
}

internal enum class ServedSource { HELPER, INCUMBENT }

internal data class SourcedDisplayTasks(val tasks: List<DisplayTask>, val source: ServedSource)

/** One routed read's outcome: the value and which route served it. */
internal data class Served<T>(val value: T, val source: ServedSource)

internal fun awaitTaskHelperRows(
        answer: CompletableFuture<List<TaskHelperRow>?>,
        remainingNanos: Long,
): List<TaskHelperRow>? {
    if (remainingNanos <= 0) return null
    return try {
        answer.get(remainingNanos, TimeUnit.NANOSECONDS)
    } catch (_: TimeoutException) {
        null
    }
}
