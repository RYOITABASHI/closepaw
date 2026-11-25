package ai.closepaw.onboarding

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Before
import org.junit.Test

class OnboardingStoreTest {

    @get:Rule
    val files = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var plainBacking: MutableMap<String, Any?>

    @Before
    fun setUp() {
        plainBacking = mutableMapOf()
        val plainPrefs = fakePrefs(plainBacking)
        context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences("onboarding_prefs", any()) } returns plainPrefs
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun fakePrefs(backing: MutableMap<String, Any?>): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            backing[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            backing[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg<String>())
            editor
        }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            backing[firstArg()] as? String ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            backing[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            backing[firstArg()] as? Int ?: secondArg()
        }
        every { prefs.contains(any()) } answers { backing.containsKey(firstArg()) }
        return prefs
    }

    @Test
    fun `saveOutcome persists and loadOutcomes round-trips each step`() {
        val store = OnboardingStore(context)

        store.saveOutcome(WizardStep.Accessibility, StepOutcome.Done)
        store.saveOutcome(WizardStep.Overlay, StepOutcome.Skipped)
        store.saveOutcome(WizardStep.Battery, StepOutcome.Done)

        val outcomes = OnboardingStore(context).loadOutcomes()
        assertThat(outcomes.accessibility).isEqualTo(StepOutcome.Done)
        assertThat(outcomes.overlay).isEqualTo(StepOutcome.Skipped)
        assertThat(outcomes.battery).isEqualTo(StepOutcome.Done)
    }

    @Test
    fun `migrateIfNeeded on a new install without evidence leaves onboarding incomplete`() {
        OnboardingStore(context).migrateIfNeeded { false }

        assertThat(plainBacking["schema_version"]).isEqualTo(2)
        assertThat(plainBacking["onboarding_completed"]).isEqualTo(false)
    }

    @Test
    fun `migrateIfNeeded detects existing user and marks onboarding complete`() {
        OnboardingStore(context).migrateIfNeeded { true }

        assertThat(plainBacking["schema_version"]).isEqualTo(2)
        assertThat(plainBacking["onboarding_completed"]).isEqualTo(true)
    }

    @Test
    fun `migrateIfNeeded from schema v1 strips legacy auth_method key`() {
        plainBacking["schema_version"] = 1
        plainBacking["auth_method"] = "oauth"
        plainBacking["onboarding_completed"] = true

        OnboardingStore(context).migrateIfNeeded { false }

        assertThat(plainBacking["schema_version"]).isEqualTo(2)
        assertThat(plainBacking).doesNotContainKey("auth_method")
        // Completion flag preserved across migration
        assertThat(plainBacking["onboarding_completed"]).isEqualTo(true)
    }

    @Test
    fun `a surviving cloud credential alone restores onboarding completion`() {
        every { context.filesDir } returns files.root
        every { context.getSharedPreferences("agent_prefs", any()) } returns fakePrefs(mutableMapOf())
        val credential = """{"type":"api_key","key":"fixture"}"""
        for (provider in listOf("OPENAI_API", "OPENROUTER")) {
            val authPrefs = fakePrefs(mutableMapOf(provider to credential))
            for (schema in listOf(null, 2)) {
                plainBacking.clear()
                if (schema != null) {
                    plainBacking["schema_version"] = schema
                    plainBacking["onboarding_completed"] = false
                }

                val store = OnboardingStore(context) { authPrefs }

                assertThat(store.isCompleted).isTrue()
                assertThat(plainBacking["onboarding_completed"]).isEqualTo(true)
            }
        }
    }

    @Test
    fun `only a readable surviving credential counts as prior use`() {
        every { context.filesDir } returns files.root
        every { context.getSharedPreferences("agent_prefs", any()) } returns fakePrefs(mutableMapOf())
        val authPrefs = fakePrefs(mutableMapOf(
            "OPENAI_CODEX" to """{"type":"oauth","accessToken":"fixture"}""",
            "OPENAI_API" to """{"type":"api_key","key":null}""",
        ))

        assertThat(OnboardingStore(context) { authPrefs }.isCompleted).isFalse()
    }

    @Test
    fun `migrateIfNeeded does not reconcile when no evidence exists`() {
        plainBacking["schema_version"] = 2
        plainBacking["onboarding_completed"] = false

        OnboardingStore(context).migrateIfNeeded { false }

        assertThat(plainBacking["onboarding_completed"]).isEqualTo(false)
    }

    @Test
    fun `migrateIfNeeded leaves already-complete flag untouched`() {
        plainBacking["schema_version"] = 2
        plainBacking["onboarding_completed"] = true

        OnboardingStore(context).migrateIfNeeded { true }

        assertThat(plainBacking["onboarding_completed"]).isEqualTo(true)
    }
}
