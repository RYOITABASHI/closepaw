package ai.closepaw.draft

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One image for an editor that does not take it from the pasted HTML. */
class DraftImage(val name: String, val mime: String, val base64: String)

class SaveDraftRequest(
    val site: DraftSite,
    val title: String,
    val html: String,
    val samples: List<String>,
    val images: List<DraftImage>,
)

/**
 * Parser for the one-shot file Shelly hands over:
 * `{"version":1,"title":"…","html":"…","images":[{"name":"…","mime":"…","base64":"…"}]}`.
 * `images` is optional and is used on note only. Unknown keys are refused, so a newer Shelly
 * fails visibly instead of having a field silently ignored.
 */
object SaveDraftRequestParser {
    /** Upper bound for the whole file, enforced while reading it. */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_TITLE_CHARS = 300
    private const val MAX_HTML_CHARS = 1024 * 1024
    private const val MAX_IMAGES = 4
    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    private val TOP_KEYS = setOf("version", "title", "html", "images")
    private val IMAGE_KEYS = setOf("name", "mime", "base64")
    private val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    private val IMAGE_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val CONTROL_RE = Regex("\\p{Cntrl}")

    sealed interface Parsed {
        class Ok(val request: SaveDraftRequest) : Parsed
        class Bad(val reason: String) : Parsed
    }

    fun parse(site: DraftSite, fileText: String): Parsed {
        val root = try {
            Json.parseToJsonElement(fileText) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return Parsed.Bad("file-not-json")
        if (!TOP_KEYS.containsAll(root.keys)) return Parsed.Bad("file-unknown-key")
        if (string(root, "version") != "1") return Parsed.Bad("file-version")

        val title = string(root, "title")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_CHARS && !CONTROL_RE.containsMatchIn(it) }
            ?: return Parsed.Bad("title-invalid")
        val html = string(root, "html")?.takeIf { it.length <= MAX_HTML_CHARS }
            ?: return Parsed.Bad("html-invalid")
        ArticleHtmlPolicy.rejection(html)?.let { return Parsed.Bad(it) }
        val samples = ArticleHtmlPolicy.samples(html)
        if (samples.isEmpty()) return Parsed.Bad("html-no-sample")

        val images = when (val raw = root["images"]) {
            null -> emptyList()
            is JsonArray -> parseImages(raw) ?: return Parsed.Bad("images-invalid")
            else -> return Parsed.Bad("images-invalid")
        }
        return Parsed.Ok(
            SaveDraftRequest(
                site = site,
                title = title,
                html = html,
                samples = samples,
                images = if (site.platform == DraftPlatform.NOTE) images else emptyList(),
            ),
        )
    }

    private fun parseImages(raw: JsonArray): List<DraftImage>? {
        if (raw.size > MAX_IMAGES) return null
        return raw.map { element ->
            val item = element as? JsonObject ?: return null
            if (item.keys != IMAGE_KEYS) return null
            val name = string(item, "name")?.takeIf { IMAGE_NAME_RE.matches(it) } ?: return null
            val mime = string(item, "mime")?.takeIf { it in MIME_TYPES } ?: return null
            val base64 = string(item, "base64") ?: return null
            if (decodedSize(base64) !in 1..MAX_IMAGE_BYTES) return null
            DraftImage(name, mime, base64)
        }
    }

    private fun decodedSize(base64: String): Int = try {
        Base64.getDecoder().decode(base64).size
    } catch (e: IllegalArgumentException) {
        -1
    }

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content
}
