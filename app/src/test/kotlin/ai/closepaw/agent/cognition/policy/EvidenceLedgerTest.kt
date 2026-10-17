package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvidenceLedgerTest {

    @Test
    fun `counts confirmation texts and ignores everything else`() {
        var now = 0L
        val ledger = EvidenceLedger(clock = { now })
        assertThat(ledger.offer("Added to Smells Like 1991")).isTrue()
        now += 5_000
        assertThat(ledger.offer("Saved to Your Library")).isTrue()
        now += 5_000
        assertThat(ledger.offer("Lithium")).isFalse()
        assertThat(ledger.offer("Add to playlist")).isFalse()
        assertThat(ledger.offer("Add to this playlist")).isFalse()
        assertThat(ledger.offer(null)).isFalse()
        assertThat(ledger.count).isEqualTo(2)
        assertThat(ledger.samples).isEqualTo(5)
    }

    @Test
    fun `the same toast reported twice within the window counts once`() {
        var now = 0L
        val ledger = EvidenceLedger(clock = { now })
        assertThat(ledger.offer("Added to Smells Like 1991")).isTrue()
        now += 500
        assertThat(ledger.offer("Added to Smells Like 1991")).isFalse()  // tree capture of the same toast
        now += 10_000
        assertThat(ledger.offer("Added to Smells Like 1991")).isTrue()   // next song, same playlist
        assertThat(ledger.count).isEqualTo(2)
        ledger.reset()
        assertThat(ledger.count).isEqualTo(0)
    }
}
