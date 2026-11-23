package ai.closepaw.platform.virtualdisplay

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RoutedTaskReadTest {
    @Test fun servedRetriesAfterFailureAndFallsBackAgainAfterLoss() {
        val rows = listOf(TaskHelperRow(7, null, null, emptySet(), null, false))
        var calls = 0
        val source = HelperRowSource { if (++calls == 2) rows else null }
        val read = RoutedTaskRead()
        var incumbentCalls = 0
        val incumbent = {
            incumbentCalls++
            listOf(3)
        }
        fun answer() = read.served(0, 33, true, source, incumbent) { it.map { row -> row.taskId } }
        assertEquals(Served(listOf(3), ServedSource.INCUMBENT), answer())
        assertEquals(Served(listOf(7), ServedSource.HELPER), answer())
        assertEquals(Served(listOf(3), ServedSource.INCUMBENT), answer())
        assertEquals(3, calls)
        assertEquals(2, incumbentCalls)
        assertEquals(1, read.stats.helperServed.get())
        assertEquals(2, read.stats.fallback.get())
    }

    @Test fun countsEveryNullAndSkipsDisabledSource() {
        val read = RoutedTaskRead()
        val source = HelperRowSource { null }
        repeat(2) { assertNull(read.helperRows(0, 33, true, source)) }
        assertNull(read.helperRows(0, 33, false, HelperRowSource { error("disabled") }))
        assertNull(read.helperRows(-1, 33, true, HelperRowSource { error("invalid display") }))
        assertEquals(2, read.stats.fallback.get())
        assertEquals(0, read.stats.helperServed.get())
    }

    @Test(timeout = 1_000) fun servedDeadlineFallsBackToTheIncumbentThroughTheSeam() {
        val read = RoutedTaskRead()
        val answer = CompletableFuture<List<TaskHelperRow>?>()
        val source = HelperRowSource { awaitTaskHelperRows(answer, TimeUnit.MILLISECONDS.toNanos(20)) }

        val served = read.served(0, 33, true, source, incumbent = { listOf(8) }) {
            it.map { row -> row.taskId }
        }
        assertEquals(Served(listOf(8), ServedSource.INCUMBENT), served)
        assertEquals(1, read.stats.fallback.get())
        assertFalse(answer.isDone)
    }

    @Test fun servedFailureModesAllServeTheIncumbentExactly() {
        val read = RoutedTaskRead()
        val incumbentValue = listOf(41, 17)
        val expectedIncumbent = Served(incumbentValue, ServedSource.INCUMBENT)
        val noAnswer = HelperRowSource { null }
        val rows = listOf(TaskHelperRow(7, null, null, emptySet(), null, false))
        fun serve(source: HelperRowSource?, incumbent: () -> List<Int>? = { incumbentValue }) =
            read.served(0, 33, true, source, incumbent) { it.map { row -> row.taskId } }

        assertEquals(expectedIncumbent, serve(noAnswer))
        assertEquals(expectedIncumbent, serve(null))
        val session = FakeHelperSession(rows)
        assertEquals(Served(listOf(7), ServedSource.HELPER), serve(session))
        session.kill()
        assertEquals(expectedIncumbent, serve(session))
        assertNull(serve(noAnswer) { null })
    }

    @Test(timeout = 10_000) fun helperDeathBetweenCallsServesTheIncumbentWithinTheReadBound() {
        val read = RoutedTaskRead()
        val rows = listOf(TaskHelperRow(7, null, null, emptySet(), null, false))
        val session = FakeHelperSession(rows)
        val incumbentValue = listOf(8, 12)
        fun serve() = read.served(0, 33, true, session, incumbent = { incumbentValue }) {
            it.map { row -> row.taskId }
        }

        assertEquals(Served(listOf(7), ServedSource.HELPER), serve())
        session.kill()
        val start = System.nanoTime()
        val served = serve()
        val elapsedNanos = System.nanoTime() - start
        assertEquals(Served(incumbentValue, ServedSource.INCUMBENT), served)
        assertTrue(
            "Read after helper death took ${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)} ms",
            elapsedNanos < TimeUnit.MILLISECONDS.toNanos(TaskObservationPolicy.READ_TIMEOUT_MS + 250),
        )
    }

    @Test(timeout = 10_000) fun helperDeathMidQueryServesTheIncumbentWithinTheReadBound() {
        val read = RoutedTaskRead()
        val rows = listOf(TaskHelperRow(7, null, null, emptySet(), null, false))
        val session = FakeHelperSession(rows)
        session.blockQueries = true
        val incumbentValue = listOf(8, 12)
        var served: Served<List<Int>>? = null
        var elapsedNanos = 0L
        val caller = Thread {
            val start = System.nanoTime()
            served = read.served(0, 33, true, session, incumbent = { incumbentValue }) {
                it.map { row -> row.taskId }
            }
            elapsedNanos = System.nanoTime() - start
        }
        caller.start()
        assertTrue(session.awaitQueryInFlight(2_000))
        session.kill()
        caller.join(5_000)
        assertFalse(caller.isAlive)
        assertEquals(Served(incumbentValue, ServedSource.INCUMBENT), served)
        assertTrue(
            "Mid-query death read took ${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)} ms",
            elapsedNanos < TimeUnit.MILLISECONDS.toNanos(TaskObservationPolicy.READ_TIMEOUT_MS + 250),
        )
    }

    @Test(timeout = 10_000) fun successfulReadThenUnansweredReadFallsBackWithinTheReadBound() {
        val read = RoutedTaskRead()
        val rows = listOf(TaskHelperRow(7, null, null, emptySet(), null, false))
        val answer = CompletableFuture<List<TaskHelperRow>?>()
        val timeoutMs = TaskObservationPolicy.READ_TIMEOUT_MS
        val incumbentValue = listOf(8, 12)
        var calls = 0
        val source = HelperRowSource {
            if (++calls == 1) rows
            else awaitTaskHelperRows(answer, TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        }
        fun serve() = read.served(0, 33, true, source, incumbent = { incumbentValue }) {
            it.map { row -> row.taskId }
        }

        assertEquals(Served(listOf(7), ServedSource.HELPER), serve())
        val start = System.nanoTime()
        val served = serve()
        val elapsedNanos = System.nanoTime() - start
        assertEquals(Served(incumbentValue, ServedSource.INCUMBENT), served)
        assertTrue(
            "Unanswered read took ${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)} ms",
            elapsedNanos < TimeUnit.MILLISECONDS.toNanos(timeoutMs + 250),
        )
        assertFalse(answer.isDone)
    }

    @Test fun switchOffServesExactlyTheIncumbent() {
        val read = RoutedTaskRead()
        val incumbentValue = listOf(23, 11)
        val source = HelperRowSource { error("helper route is unavailable") }
        fun serve(sdkInt: Int, routeEnabled: Boolean) =
            read.served(0, sdkInt, routeEnabled, source, incumbent = { incumbentValue }) {
                it.map { row -> row.taskId }
            }

        assertEquals(Served(incumbentValue, ServedSource.INCUMBENT), serve(33, routeEnabled = false))
        assertEquals(Served(incumbentValue, ServedSource.INCUMBENT), serve(32, routeEnabled = true))
    }

    @Test fun emitsOneFallbackMarkerPerFailedRead() {
        val sources = mutableListOf<ServedSource>()
        val read = RoutedTaskRead(onSource = { sources.add(it) })
        repeat(2) { read.helperRows(0, 33, true, HelperRowSource { null }) }
        assertEquals(listOf(ServedSource.INCUMBENT, ServedSource.INCUMBENT), sources)
    }

    @Test fun freshShizukuClientDoesNotConsultAnotherClientsHelper() {
        val attached = ShizukuClient()
        val independent = ShizukuClient()
        val rows = emptyList<TaskHelperRow>()
        var calls = 0
        attached.attachTaskHelper {
            calls++
            rows
        }

        assertNull(independent.helperRows(0, sdkInt = 33))
        assertEquals(0, calls)
        assertSame(rows, attached.helperRows(0, sdkInt = 33))
        assertEquals(1, calls)
    }
}

/**
 * A faithful stand-in for TaskHelperSession's death behavior: an explicit alive
 * state that queryRows consults (a dead session answers null at once, like the
 * real session with no active connection), and a kill() that fires binder death
 * into the in-flight production query seam, settling it as null exactly as the
 * real session's death recipient does.
 */
private class FakeHelperSession(private val rows: List<TaskHelperRow>) : HelperRowSource {
    @Volatile private var alive = true
    @Volatile private var inFlight: TaskHelperQuery? = null
    private val queryStarted = CountDownLatch(1)
    @Volatile var blockQueries = false

    fun kill() {
        alive = false
        inFlight?.binderDied()
    }

    fun awaitQueryInFlight(timeoutMs: Long): Boolean =
            queryStarted.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun queryRows(displayId: Int): List<TaskHelperRow>? {
        if (!alive) return null
        val query = TaskHelperQuery(1, displayId)
        inFlight = query
        queryStarted.countDown()
        if (!blockQueries) query.complete(rows)
        return query.await(TimeUnit.MILLISECONDS.toNanos(TaskObservationPolicy.READ_TIMEOUT_MS))
    }
}
