package ai.closepaw.app

import ai.closepaw.auth.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredCredentialMigrationTest {
    @Test
    fun removesOnlyCodexAndCanBeRunAgain() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("OPENAI_CODEX", "test OAuth bundle")
            .putString("OPENAI_API", "unrelated entry")
            .putString("OTHER", "another entry")
            .commit()

        assertTrue(removeRetiredCodexCredential(prefs))
        assertFalse(prefs.contains("OPENAI_CODEX"))
        assertEquals(
            mapOf("OPENAI_API" to "unrelated entry", "OTHER" to "another entry"),
            prefs.all,
        )
        assertFalse(removeRetiredCodexCredential(prefs))
    }
}
