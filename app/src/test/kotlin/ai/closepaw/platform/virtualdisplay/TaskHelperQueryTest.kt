package ai.closepaw.platform.virtualdisplay

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TaskHelperQueryTest {
    private val rows = listOf(TaskHelperRow(7, "org.top", null, setOf("org.top"), true, false))

    @Test fun replyBeforeDeathIsServed() {
        val query = TaskHelperQuery(1, 0)
        assertTrue(query.complete(rows))
        assertSame(rows, query.await(TimeUnit.MILLISECONDS.toNanos(100)))
    }

    @Test fun deathBeforeReplyDropsTheReply() {
        val query = TaskHelperQuery(1, 0)
        query.binderDied()
        assertTrue(query.isBinderDead)
        assertFalse(query.complete(rows))
        assertNull(query.await(TimeUnit.MILLISECONDS.toNanos(100)))
    }

    @Test fun deathAfterReplyStillYieldsNull() {
        val query = TaskHelperQuery(1, 0)
        assertTrue(query.complete(rows))
        query.binderDied()
        assertNull(query.await(TimeUnit.MILLISECONDS.toNanos(100)))
    }

    @Test(timeout = 5_000) fun deathMidAwaitSettlesImmediatelyAsNull() {
        val query = TaskHelperQuery(1, 0)
        val killer = Thread {
            Thread.sleep(50)
            query.binderDied()
        }
        val start = System.nanoTime()
        killer.start()
        assertNull(query.await(TimeUnit.MILLISECONDS.toNanos(TaskObservationPolicy.READ_TIMEOUT_MS)))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(
            "Death-settled await took $elapsedMs ms",
            elapsedMs < TaskObservationPolicy.READ_TIMEOUT_MS,
        )
        killer.join(1_000)
    }
}
