package ai.closepaw.llm

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.CodexHeaders
import ai.closepaw.auth.MissingCredential
import ai.closepaw.auth.OAuthRefreshFailed
import ai.closepaw.auth.parseChatgptAccountId
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Fetches the model list for the stored Codex credential on explicit request. */
class CodexModelProbe internal constructor(
    private val clientVersion: String,
    private val headers: suspend () -> CodexHeaders,
    private val exchange: (URL, Map<String, String>) -> HttpResult,
    private val storedSecrets: suspend () -> List<String> = { emptyList() },
    private val reportOutcome: (String) -> Unit = {},
) {
    constructor(authStore: AuthStore, clientVersion: String) : this(
        clientVersion = clientVersion,
        headers = { authStore.codexHeaders(LLMProvider.OPENAI_CODEX) },
        exchange = ::getModels,
        storedSecrets = {
            when (val credential = authStore.get(LLMProvider.OPENAI_CODEX)) {
                is AuthCredential.OAuth -> listOfNotNull(
                    credential.accessToken,
                    credential.refreshToken,
                    credential.idToken,
                    credential.idToken?.let(::parseChatgptAccountId),
                )
                is AuthCredential.ApiKey -> listOf(credential.key)
                null -> emptyList()
            }
        },
        reportOutcome = { Log.i(TAG, it) },
    )

    data class Model(
        val slug: String,
        val visibility: String?,
        val displayName: String?,
        val description: String?,
    )

    sealed interface Result {
        data class Success(val models: List<Model>) : Result
        data class Failure(val status: Int?, val detail: String) : Result {
            fun diagnostic(): String =
                when {
                    status == null -> detail
                    detail.isEmpty() -> "HTTP $status"
                    else -> "HTTP $status $detail"
                }
        }
    }

    internal data class HttpResult(val status: Int, val body: String)

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val secrets = mutableSetOf<String>()
        val result = try {
            secrets += storedSecrets()
            val auth = headers()
            secrets += auth.accessToken
            auth.chatgptAccountId?.let { secrets += it }
            // The shared AuthStore may have rotated the stored credentials.
            secrets += storedSecrets()

            val url = URL("$MODELS_URL?client_version=${URLEncoder.encode(clientVersion, "UTF-8")}")
            val requestHeaders = buildMap {
                put("Authorization", "Bearer ${auth.accessToken}")
                put("originator", "pi")
                put("Accept", "application/json")
                auth.chatgptAccountId?.let { put("ChatGPT-Account-Id", it) }
            }

            val redact = credentialRedactor(secrets)
            val response = exchange(url, requestHeaders)
            if (response.status != HttpURLConnection.HTTP_OK) {
                Result.Failure(response.status, "")
            } else {
                val models = parseModels(response.body, redact)
                if (models == null) Result.Failure(response.status, "Invalid Codex models response")
                else Result.Success(models)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MissingCredential) {
            Result.Failure(null, "No credential for provider " + e.provider.name)
        } catch (e: OAuthRefreshFailed) {
            // AuthStore wraps refresh errors, which may include OAuth response text.
            (e.cause as? CancellationException)?.let { throw it }
            Result.Failure(null, "OAuth refresh failed for ${e.provider.name}")
        } catch (e: Exception) {
            Result.Failure(null, e.javaClass.simpleName.ifEmpty { e.javaClass.name })
        }

        ensureActive()
        reportOutcome(
            when (result) {
                is Result.Success -> result.models.joinToString("\n") { it.slug }
                    .ifEmpty { "No entitled Codex models" }
                is Result.Failure -> result.diagnostic()
            },
        )
        result
    }

    private companion object {
        const val TAG = "CodexModelProbe"
        const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"

        fun parseObject(body: String): JsonObject? =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                // JSON parser errors can include raw input.
                null
            }

        fun parseModels(body: String, redact: (String) -> String): List<Model>? {
            val models = parseObject(body)?.get("models") as? JsonArray ?: return null
            val usable = models.mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                val slug = model.stringField("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val visibility = (model["visibility"] as? JsonPrimitive)?.takeUnless { it is JsonNull }
                Model(
                    slug = redact(slug),
                    visibility = visibility?.content?.let(redact),
                    displayName = model.stringField("display_name")?.let(redact),
                    description = model.stringField("description")?.let(redact),
                )
            }
            return usable.takeUnless { models.isNotEmpty() && it.isEmpty() }
        }

        fun JsonObject.stringField(name: String): String? =
            (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun credentialRedactor(secrets: Set<String>): (String) -> String {
            val patterns = secrets.filter { it.isNotEmpty() }.flatMap {
                listOf(it, JsonPrimitive(it).toString().removeSurrounding("\""), URLEncoder.encode(it, "UTF-8"))
            }.distinct().sortedByDescending { it.length }
            if (patterns.isEmpty()) return { it }
            val pattern = Regex(patterns.joinToString("|", transform = Regex::escape))
            return { text -> pattern.replace(text) { "[redacted]" } }
        }

        fun getModels(url: URL, headers: Map<String, String>): HttpResult {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 15_000
                readTimeout = 30_000
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                val status = connection.responseCode
                val body = try {
                    val stream = if (status < 400) connection.inputStream else connection.errorStream
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (e: IOException) {
                    if (status == HttpURLConnection.HTTP_OK) throw e
                    ""
                }
                return HttpResult(status, body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
