package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuProxyInvalidationTest {
    @Test fun invalidateAllAdvancesTheProcessWideGeneration() {
        val before = ShizukuServiceProxyProvider.currentGeneration()
        ShizukuServiceProxyProvider.invalidateAll()
        ShizukuServiceProxyProvider.invalidateAll()
        assertEquals(before + 2, ShizukuServiceProxyProvider.currentGeneration())
    }

    @Test fun providersCanBeCreatedWithoutALiveShizukuRuntime() {
        // Hook installation must never throw in a process without Shizuku (JVM tests, cold start).
        ShizukuServiceProxyProvider()
        ShizukuServiceProxyProvider()
    }
}
