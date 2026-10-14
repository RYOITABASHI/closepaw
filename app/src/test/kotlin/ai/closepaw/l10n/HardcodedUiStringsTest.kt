package ai.closepaw.l10n

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Guards the en/ja localization: user-visible text in the UI layers must come from string
 * resources. Flags the common hardcoded shapes — `Text("…")`, `Toast.makeText(ctx, "…", …)`
 * and `title/label/placeholder/contentDescription/text = "…"` — in key screens.
 *
 * A match is allowed only when its line carries the marker `// l10n-ok` (brand names, symbols,
 * debug-only UI), so new hardcoded strings fail CI instead of shipping untranslated.
 */
class HardcodedUiStringsTest {

    private val roots = listOf(
        "src/main/kotlin/ai/closepaw/ui",
        "src/main/kotlin/ai/closepaw/app",
        "src/main/kotlin/ai/closepaw/onboarding",
    )

    private val patterns = listOf(
        Regex("""\bText\(\s*(text\s*=\s*)?"[^"$]*[A-Za-z]{2}"""),
        Regex("""Toast\.makeText\([^,]+,\s*"[^"$]*[A-Za-z]{2}"""),
        Regex("""\b(title|label|placeholder|contentDescription|text|subtitle)\s*=\s*"[^"$]*[A-Za-z]{2}[^"]*"\s*[,)]"""),
    )

    /** Compose animation/transition debug labels (`label = "pulseAlpha"`) are not UI text. */
    private val animationLabel = Regex("""\blabel\s*=\s*"([a-z][A-Za-z-]*|[A-Z][a-z]+[A-Z][A-Za-z]*)"""")

    @Test
    fun `no hardcoded user-visible strings in key screens`() {
        val moduleDir = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        val offenders = mutableListOf<String>()
        roots.map { File(moduleDir, it) }.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { i, raw ->
                    val line = raw.trim()
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("Log.")) return@forEachIndexed
                    if ("l10n-ok" in line) return@forEachIndexed
                    if (animationLabel.containsMatchIn(line)) return@forEachIndexed
                    if (patterns.any { it.containsMatchIn(line) }) {
                        offenders += "${file.relativeTo(moduleDir).invariantSeparatorsPath}:${i + 1}: $line"
                    }
                }
            }
        }
        assertWithMessage(
            "Hardcoded UI strings found — move them to res/values{,-ja}/strings_*.xml " +
                "(or mark a deliberate exception with // l10n-ok):\n" + offenders.joinToString("\n")
        ).that(offenders).isEmpty()
    }
}
