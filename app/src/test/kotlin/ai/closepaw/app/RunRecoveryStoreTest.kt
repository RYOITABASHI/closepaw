package ai.closepaw.app

import ai.closepaw.mcp.McpRunRegistry
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecoveryStoreTest {
    private val disk = mutableMapOf<String, String>()
    private var time = 100_000L
    private val pair = ShellyBackgroundRun.parseResultCorrelation(
        "1234567890-deadbeef", "0123456789abcdef0123456789abcdef",
    )!!

    private val cancellations = mutableMapOf<String, String>()

    private fun reopen(writeAllowed: () -> Boolean = { true }) = RunRecoveryStore(
        disk, { key, value ->
            writeAllowed().also { if (it) disk[key] = value }
        }, { disk.remove(it) }, now = { time },
        savedCancellations = cancellations,
        writeCancellation = { key, value ->
            cancellations[key] = value
            true
        },
        removeCancellation = {
            cancellations.remove(it)
            true
        },
    )

    private fun RunRecoveryStore.sweep(): List<ShellyResultCallback.Payload> =
        buildList { recover { add(it) } }

    @Test fun onlyPriorProcessRunsPastGraceCloseAndSendOnce() {
        val initial = reopen()
        initial.begin("lost", pair)
        initial.markRunning("lost")
        initial.begin("no_callback", null)
        val recovered = reopen()
        val live = recovered.begin("live", null)
        time += RunRecoveryStore.GRACE_MS - 1
        assertTrue(recovered.sweep().isEmpty())
        time++
        val payload = recovered.sweep().single()
        assertEquals(pair.reqId, payload.reqId)
        assertEquals(pair.nonce, payload.nonce)
        assertEquals("failed", payload.status)
        assertEquals("process_lost", payload.summary)
        assertEquals(McpRunRegistry.Status.FAILED, recovered.snapshot("lost")?.status)
        assertEquals("process_lost", recovered.snapshot("no_callback")?.summary)
        assertNull(recovered.snapshot("live"))
        assertTrue(recovered.sweep().isEmpty())
        assertNull(live.claimCancelled(time))
        val restarted = reopen()
        assertEquals(recovered.snapshot("lost"), restarted.snapshot("lost"))
        assertEquals(McpRunRegistry.Status.CANCELLED, restarted.snapshot("live")?.status)
        assertTrue(restarted.sweep().isEmpty())
    }

    @Test fun liveCompletionReplaysAfterDeathBeforeDispatch() {
        val pending = reopen().begin("live", pair)
        pending.claimBrainTerminal(ShellyRunState.Finished.Kind.DONE, "Finished", time, "session")

        val payload = reopen().sweep().single()
        assertEquals("completed", payload.status)
        assertEquals("Finished", payload.summary)
        assertEquals(time, payload.finishedAtMs)
        assertEquals("session", payload.sessionId)
        assertTrue(reopen().sweep().isEmpty())
    }

    @Test fun stopClaimsSharedGuardAndSurvivesRestart() {
        val store = reopen()
        val pending = store.begin("stopped", pair)
        val first = pending.claimCancelled(time)
        assertEquals("cancelled", first?.status)
        assertNull(pending.claimFailed("late failure", time))
        time += RunRecoveryStore.GRACE_MS
        val restarted = reopen()
        assertEquals("cancelled", restarted.sweep().single().status)
        assertEquals(McpRunRegistry.Status.CANCELLED, restarted.snapshot("stopped")?.status)
    }

    @Test fun cancelledCallbackDoesNotWaitForFailedPrimaryCommit() {
        var writable = true
        val store = reopen { writable }
        val pending = store.begin("live", pair)
        writable = false
        val payload = pending.claimBrainTerminal(
            ShellyRunState.Finished.Kind.STOPPED, "Stopped by request", time, "session",
        )!!
        assertEquals("cancelled", payload.status)
        assertNull(payload.durability)
        assertNull(pending.claimFailed("late failure", time))

        time += RunRecoveryStore.GRACE_MS
        val restarted = reopen()
        assertEquals("cancelled", restarted.sweep().single().status)
        assertEquals(McpRunRegistry.Status.CANCELLED, restarted.snapshot("live")?.status)
        restarted.flushTerminals().forEach { it.complete(Unit) }
        assertTrue(cancellations.isEmpty())
        assertTrue(restarted.sweep().isEmpty())
        val afterMarker = reopen()
        assertEquals(McpRunRegistry.Status.CANCELLED, afterMarker.snapshot("live")?.status)
        assertTrue(afterMarker.sweep().isEmpty())
    }

    @Test fun failedLiveCallbackStillWaitsForTerminalCommit() {
        var writable = true
        val store = reopen { writable }
        val pending = store.begin("live", pair)
        writable = false
        val durability = pending.claimFailed("failed", time)!!.durability!!
        assertFalse(durability.isCompleted)
        assertNull(pending.claimCancelled(time))
        assertTrue(store.flushTerminals().isEmpty())
        writable = true
        store.flushTerminals().single().complete(Unit)
        assertTrue(durability.isCompleted)
        assertEquals(McpRunRegistry.Status.FAILED, reopen().snapshot("live")?.status)
    }

    @Test fun recoveredCallbackIsReplayedAfterDeathBeforeDispatch() {
        reopen().begin("lost", pair)
        time += RunRecoveryStore.GRACE_MS
        val recovering = reopen()
        val interrupted = runCatching { recovering.recover { error("Simulated death before dispatch") } }
        assertTrue(interrupted.isFailure)
        assertEquals(McpRunRegistry.Status.FAILED, recovering.snapshot("lost")?.status)

        val restarted = reopen()
        val payload = restarted.sweep().single()
        assertEquals(pair.reqId, payload.reqId)
        assertEquals(pair.nonce, payload.nonce)
        assertEquals("process_lost", payload.summary)
        assertTrue(restarted.sweep().isEmpty())
        assertTrue(reopen().sweep().isEmpty())
    }

    @Test fun failedRecoverySendStaysPendingUntilAcceptedAfterRestart() {
        reopen().begin("lost", pair)
        time += RunRecoveryStore.GRACE_MS
        val store = reopen()
        var attempts = 0
        store.recover {
            attempts++
            false
        }
        assertEquals(1, attempts)
        assertFalse(store.hasPendingWork())
        assertTrue(store.sweep().isEmpty())

        assertEquals("process_lost", reopen().sweep().single().summary)
        assertTrue(reopen().sweep().isEmpty())
    }

    @Test fun failedLiveSendStaysPendingUntilAcceptedAfterRestart() {
        val store = reopen()
        val payload = store.begin("live", pair).claimFailed("Original failure", time)!!
        val dispatch = checkNotNull(payload.dispatch)
        var attempts = 0
        dispatch {
            attempts++
            false
        }
        dispatch { error("Live callback attempted again") }
        assertEquals(1, attempts)
        assertTrue(store.sweep().isEmpty())

        assertEquals("Original failure", reopen().sweep().single().summary)
        assertTrue(reopen().sweep().isEmpty())
    }

    @Test fun acceptedLiveSendClearsPending() {
        val store = reopen()
        val payload = store.begin("live", pair).claimCancelled(time)!!
        val dispatch = checkNotNull(payload.dispatch)
        dispatch { true }
        assertTrue(reopen().sweep().isEmpty())
    }

    @Test fun writeFailuresStayVisibleUntilAWriteSucceeds() {
        var writable = true
        val store = reopen { writable }
        val pending = store.begin("live", pair)
        writable = false
        pending.claimCancelled(time)
        assertEquals(RunRecoveryStore.WriteStatus(true, time), store.writeStatus)
        writable = true
        store.flushTerminals()
        assertEquals(RunRecoveryStore.WriteStatus(false, time), store.writeStatus)
    }

    @Test fun failedDispatchMarkerWriteRetriesWithoutSendingAgainInThisProcess() {
        reopen().begin("lost", pair)
        time += RunRecoveryStore.GRACE_MS
        var writable = true
        val store = reopen { writable }
        val sent = mutableListOf<ShellyResultCallback.Payload>()
        store.recover {
            sent.add(it)
            writable = false
            true
        }
        assertTrue(store.hasPendingWork())
        assertTrue(store.sweep().isEmpty())
        writable = true
        assertTrue(store.sweep().isEmpty())
        assertEquals(1, sent.size)
        assertTrue(reopen().sweep().isEmpty())
    }

    @Test fun throwingPreferenceOpenUsesEmptyStore() {
        val store = RunRecoveryStore.open({ error("open failed") }, { error("open failed") })
        assertEmptyStartup(store)
    }

    @Test fun throwingPreferenceReadUsesEmptyStore() {
        val unreadable = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, _, _ -> error("read failed") } as SharedPreferences
        val store = RunRecoveryStore.open({ unreadable }, { error("open failed") })
        assertEmptyStartup(store)
    }

    private fun assertEmptyStartup(store: RunRecoveryStore) {
        assertTrue(store.sweep().isEmpty())
        assertNull(store.snapshot("unknown"))
        val pending = store.begin("live", pair)
        assertEquals("cancelled", pending.claimCancelled(time)?.status)
    }
}
