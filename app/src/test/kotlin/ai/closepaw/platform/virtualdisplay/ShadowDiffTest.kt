package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShadowDiffTest {
    @Test fun classifiesInRootSetOrderFieldPrecedence() {
        val front = DisplayTask(9, "org.top")
        val back = DisplayTask(2, null)
        val served = listOf(front, back)
        assertEquals(ShadowDiff.Kind.NONE, ShadowDiff.classify(served, served).kind)
        assertEquals(ShadowDiff.Kind.ORDER, ShadowDiff.classify(served, served.reversed()).kind)
        assertEquals(ShadowDiff.Kind.FIELD, ShadowDiff.classify(served, listOf(front.copy(pinned = true), back)).kind)
        assertEquals(ShadowDiff.Result(ShadowDiff.Kind.ROOT_SET, setOf(2, 4)),
            ShadowDiff.classify(served, listOf(front, DisplayTask(4, null))))
        assertEquals(ShadowDiff.Result(ShadowDiff.Kind.NULLNESS, setOf(9, 2)),
            ShadowDiff.classify(served, null))
    }

    @Test fun reportsOneDeferredDiscrepancyAndPreservesTheServedAnswer() {
        val tasks = listOf(DisplayTask(9, "org.top"))
        val queued = mutableListOf<() -> Unit>()
        val reports = mutableListOf<ShadowDiff.Result>()
        for (source in listOf(ServedSource.HELPER, ServedSource.INCUMBENT)) {
            val served = SourcedDisplayTasks(tasks, source)
            assertSame(tasks, ShadowDiff.observe(
                served, { queued.add(it) }, { listOf(DisplayTask(9, "org.changed")) }, { reports.add(it) },
            ))
        }
        assertEquals(emptyList<ShadowDiff.Result>(), reports)
        queued.single().invoke()
        assertEquals(listOf(ShadowDiff.Result(ShadowDiff.Kind.FIELD)), reports)
    }
}
