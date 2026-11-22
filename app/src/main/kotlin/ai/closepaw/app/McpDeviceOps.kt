package ai.closepaw.app

import ai.closepaw.mcp.boundedLogText
import ai.closepaw.mcp.boundedUiTree
import ai.closepaw.mcp.CaptureMode
import ai.closepaw.mcp.effectiveLogLines
import ai.closepaw.mcp.execProcess
import ai.closepaw.mcp.LOG_BYTE_LIMIT
import ai.closepaw.mcp.logcatArgs
import ai.closepaw.mcp.McpTypedOpError
import ai.closepaw.mcp.parseLogLines
import ai.closepaw.mcp.pidofArgs
import ai.closepaw.mcp.readLogsNotGrantedNote
import ai.closepaw.mcp.secureSettingError
import ai.closepaw.mcp.SecureSettingNamespace
import ai.closepaw.mcp.secureSettingNamespace
import ai.closepaw.mcp.validateLogcatOptions
import ai.closepaw.mcp.validateLogcatPackage
import ai.closepaw.platform.BitmapUtils
import ai.closepaw.trace.A11yTreeDumper
import ai.closepaw.util.recycleCompat
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun Context.canWriteSecureSettings(): Boolean =
    checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

internal class McpDeviceOps(private val app: Context, private val audit: (String) -> Unit) {
    fun call(name: String, args: JsonObject): JsonObject = when (name) {
        "secure_setting" -> secureSetting(args)
        "read_logcat" -> readLogcat(args)
        "capture_screen" -> {
            checkKeys(args)
            captureScreen()
        }
        "get_ui_tree" -> {
            checkKeys(args)
            getUiTree()
        }
        else -> error("Unknown typed operation")
    }

    private fun secureSetting(args: JsonObject): JsonObject {
        val action = args.string("action")
        val key = args.string("key")
        var value = args.string("value")
        try {
            checkKeys(args, "action", "key", "value")
            val validation = secureSettingError(
                action, key, value, canWrite = action == "put" && app.canWriteSecureSettings(),
            )
            when (validation) {
                "key_not_allowed" -> fail(validation, "Secure setting key is outside the allowlist")
                "permission_not_granted" -> fail(validation, "WRITE_SECURE_SETTINGS is required for put")
                null -> Unit
                else -> fail(validation, "Expected get or put, a key, and a valid string value for put")
            }
            val setting = checkNotNull(key)
            val namespace = checkNotNull(secureSettingNamespace(setting))
            val resolver = app.contentResolver
            if (action == "get") {
                if ("value" in args) fail("invalid_params", "value is accepted only for put")
                value = when (namespace) {
                    SecureSettingNamespace.SECURE -> Settings.Secure.getString(resolver, setting)
                    SecureSettingNamespace.GLOBAL -> Settings.Global.getString(resolver, setting)
                }
            } else {
                val written = try {
                    when (namespace) {
                        SecureSettingNamespace.SECURE -> Settings.Secure.putString(resolver, setting, value)
                        SecureSettingNamespace.GLOBAL -> Settings.Global.putString(resolver, setting, value)
                    }
                } catch (_: SecurityException) {
                    fail("permission_not_granted", "WRITE_SECURE_SETTINGS is required for put")
                }
                if (!written) fail("write_failed", "Settings rejected the write")
            }
            return buildJsonObject {
                put("action", action)
                put("key", setting)
                put("value", value)
            }
        } finally {
            // Suppress raw values for all keys, including rejected or malformed calls.
            val details = value?.let { " valueLength=${it.length} valueHash=${it.hashCode()}" }.orEmpty()
            audit("mcp secure_setting action=${auditField(action)} key=${auditField(key)}$details")
        }
    }

    private fun readLogcat(args: JsonObject): JsonObject {
        val hasReadLogs = app.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        checkKeys(args, "lines", "filter", "packageName")
        val lines = args["lines"]?.let {
            parseLogLines(it)
                ?: fail("invalid_params", "lines must be a positive integer")
        }
        val filter = args.optionalString("filter")
        val packageName = args.optionalString("packageName")
        readLogsNotGrantedNote(hasReadLogs)?.let { note ->
            validated {
                packageName?.let(::validateLogcatPackage)
                validateLogcatOptions(lines, filter)
            }
            return buildJsonObject {
                put("text", "")
                put("note", note)
            }
        }

        val lookup = packageName?.let { validated { pidofArgs(it) } }
        val command = validated { logcatArgs(lines, filter, null) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        try {
            val pid = lookup?.let {
                val result = execProcess(it, deadline, 1024, CaptureMode.PREFIX)
                val resolved = result.text.decodeToString().trim().split(Regex("\\s+"))
                    .firstNotNullOfOrNull { candidate -> candidate.toIntOrNull()?.takeIf { it > 0 } }
                if (result.exit != 0 || resolved == null) {
                    fail("package_pid_unavailable", "No PID found for packageName")
                }
                resolved
            }
            val argv = if (pid == null) command else logcatArgs(lines, filter, pid)
            val result = execProcess(argv, deadline, LOG_BYTE_LIMIT + 1, CaptureMode.TAIL)
            if (result.exit != 0) fail("logcat_failed", "logcat exited with code ${result.exit}")
            return buildJsonObject {
                put("text", boundedLogText(result.text, maxLines = effectiveLogLines(lines)))
            }
        } catch (_: TimeoutException) {
            fail("logcat_timeout", "Logcat operation exceeded 10 seconds")
        }
    }

    private fun captureScreen(): JsonObject {
        val service = AgentService.instance ?: fail("accessibility_not_bound", "Accessibility service is not bound")
        val result = CompletableFuture<JsonObject>()
        service.mainExecutor.execute {
            if (!result.isDone) {
                try {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                screenshot.hardwareBuffer.use {
                                    if (!result.isDone) {
                                        try {
                                            result.complete(encodeScreenshot(screenshot))
                                        } catch (e: Exception) {
                                            result.completeExceptionally(e)
                                        }
                                    }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                result.completeExceptionally(
                                    McpTypedOpError("screenshot_failed", "Accessibility screenshot failed ($errorCode)"),
                                )
                            }
                        })
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }
        }
        try {
            return result.get(10, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            fail("screenshot_timeout", "Accessibility screenshot exceeded 10 seconds")
        } catch (e: ExecutionException) {
            throw (e.cause as? McpTypedOpError ?: e)
        } finally {
            result.cancel(false)
        }
    }

    private fun encodeScreenshot(screenshot: AccessibilityService.ScreenshotResult): JsonObject {
        val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
            ?: fail("screenshot_failed", "Cannot wrap screenshot buffer")
        val software = try {
            hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardware.recycle()
        } ?: fail("screenshot_failed", "Cannot copy screenshot buffer")

        try {
            for ((edge, quality) in listOf(1080 to 70, 1080 to 55, 864 to 55, 648 to 45, 432 to 40)) {
                val scaled = BitmapUtils.scaleBitmapIfNeeded(software, edge)
                try {
                    val jpeg = BitmapUtils.compressJpeg(scaled, quality)
                        ?: fail("screenshot_failed", "Cannot encode screenshot")
                    if (jpeg.size <= 1_125_000) return buildJsonObject {
                        put("mime", "image/jpeg")
                        put("width", scaled.width)
                        put("height", scaled.height)
                        put("base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    }
                } finally {
                    if (scaled !== software) scaled.recycle()
                }
            }
            fail("screenshot_failed", "Cannot fit screenshot in the base64 limit")
        } finally {
            software.recycle()
        }
    }

    private fun getUiTree(): JsonObject {
        val service = AgentService.instance ?: fail("accessibility_not_bound", "Accessibility service is not bound")
        val root = service.rootInActiveWindow ?: fail("no_active_window", "No accessibility root for the active window")
        val acquired = mutableListOf(root)
        try {
            return boundedUiTree(root, ::nodeFields) { node ->
                (0 until node.childCount).asSequence().mapNotNull(node::getChild).onEach { acquired.add(it) }
            }
        } finally {
            acquired.asReversed().forEach { it.recycleCompat() }
        }
    }

    private fun nodeFields(node: AccessibilityNodeInfo) = buildJsonObject {
        val fields = A11yTreeDumper.describe(node)
        put("className", fields.className)
        put("packageName", boundedField(fields.packageName))
        put("text", if (fields.password) null else fields.text)
        put("contentDescription", if (fields.password) null else fields.contentDescription)
        put("viewIdResourceName", fields.viewIdResourceName)
        putJsonArray("bounds") { fields.bounds.orEmpty().forEach { add(JsonPrimitive(it)) } }
        put("clickable", fields.clickable)
        put("editable", fields.editable)
        put("focused", fields.focused)
        put("checkable", fields.checkable)
    }

    private fun boundedField(value: CharSequence?): String? {
        if (value == null || value.length <= 2048) return value?.toString()
        val end = if (Character.isHighSurrogate(value[2047])) 2047 else 2048
        return value.subSequence(0, end).toString() + " [truncated]"
    }

    private fun checkKeys(args: JsonObject, vararg allowed: String) {
        if (args.keys.any { it !in allowed }) fail("invalid_params", "Unexpected argument")
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.optionalString(key: String): String? =
        if (key !in this) null else string(key) ?: fail("invalid_params", "$key must be a string")

    private fun auditField(value: String?): String =
        value?.filter { it in '!'..'~' }?.take(64) ?: "-"

    private fun <T> validated(build: () -> T): T = try {
        build()
    } catch (_: IllegalArgumentException) {
        fail("invalid_params", "Invalid lines, filter, or packageName")
    }

    private fun fail(code: String, message: String): Nothing = throw McpTypedOpError(code, message)
}
