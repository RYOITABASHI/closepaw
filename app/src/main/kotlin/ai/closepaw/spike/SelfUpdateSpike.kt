package ai.closepaw.spike

import ai.closepaw.BuildConfig
import ai.closepaw.mcp.McpSpikeHook
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.platform.virtualdisplay.ShizukuServiceProxyProvider
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * SPIKE (throwaway, never merges): can an app holding Shizuku permission reinstall ITSELF through
 * the Shizuku-wrapped IPackageManager -> IPackageInstaller session (create -> write -> commit)?
 *
 * Every step appends a stage record to filesDir/spike-result.json BEFORE moving on, so the
 * outcome survives the process death a successful self-install causes. Never throws.
 */
internal object SelfUpdateSpike {
    private const val TAG = "ClosePawSpike"
    const val STAGE_FILE = "spike-result.json"
    const val ACTION_RESULT = "ai.closepaw.action.SPIKE_INSTALL_RESULT"
    private const val TARGET_PACKAGE = "ai.closepaw"
    private const val INSTALLER_PACKAGE = "com.android.shell"
    private const val APK_SPLIT_NAME = "base.apk"
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    /** PackageManager.INSTALL_REPLACE_EXISTING (hidden). `pm install` sets it; shell callers don't get it implicitly. */
    private const val INSTALL_REPLACE_EXISTING = 0x00000002

    private val running = AtomicBoolean(false)
    private val fileLock = Any()

    // ── Stage file ──

    private fun stageFile(ctx: Context) = File(ctx.filesDir, STAGE_FILE)

    fun readRecords(ctx: Context): List<SpikeStageRecord> = synchronized(fileLock) {
        val f = stageFile(ctx)
        SpikeStageLog.decode(runCatching { if (f.exists()) f.readText() else null }.getOrNull())
    }

    /** Appends [stage] (atomic replace). Logging failures must never break the flow. */
    fun record(ctx: Context, stage: String, vararg fields: Pair<String, JsonPrimitive>) {
        val rec = SpikeStageRecord(stage, System.currentTimeMillis(), fields.toMap())
        Log.i(TAG, "stage ${rec.toJson()}")
        synchronized(fileLock) {
            runCatching {
                val f = stageFile(ctx)
                val existing = if (f.exists()) f.readText() else null
                val tmp = File(ctx.filesDir, "$STAGE_FILE.tmp")
                tmp.writeText(SpikeStageLog.append(existing, rec))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "stage write failed: ${SpikeStageLog.describe(it)}") }
        }
    }

    private fun resetStageFile(ctx: Context) = synchronized(fileLock) { runCatching { stageFile(ctx).delete() } }

    fun statusJson(ctx: Context): JsonObject {
        val lastUpdate = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrNull()
        return SpikeStageLog.statusJson(readRecords(ctx), lastUpdate, running.get())
    }

    // ── Entry point ──

    /** Starts the flow on a background thread; returns the current stage at once. */
    fun start(ctx: Context): JsonObject {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) {
            return statusJson(app).let { JsonObject(it + ("accepted" to JsonPrimitive(false))) }
        }
        var launched = false
        try {
            resetStageFile(app)
            record(app, "started")
            Thread({
                try {
                    runFlow(app)
                } catch (t: Throwable) {
                    // runFlow already catches; this is the last-resort guard.
                    Log.e(TAG, "spike thread crashed: ${SpikeStageLog.describe(t)}")
                    recordFailedBestEffort(app, t)
                } finally {
                    running.set(false)
                }
            }, "closepaw-spike-self-update").start()
            launched = true
        } catch (t: Throwable) {
            running.set(false)
            recordFailedBestEffort(app, t)
        }
        return try {
            statusJson(app).let { JsonObject(it + ("accepted" to JsonPrimitive(launched))) }
        } catch (t: Throwable) {
            JsonObject(mapOf(
                "accepted" to JsonPrimitive(launched),
                "error" to SpikeStageLog.field(SpikeStageLog.describe(t)),
            ))
        }
    }

    /** Records a `failed` stage; can never throw (recording itself is guarded). */
    fun recordFailedBestEffort(ctx: Context, t: Throwable) {
        try {
            val atStage = runCatching { SpikeStageLog.lastCompletedStage(readRecords(ctx)) }.getOrDefault("unknown")
            record(ctx, "failed", "atStage" to SpikeStageLog.field(atStage),
                "error" to SpikeStageLog.field(SpikeStageLog.describe(t)))
        } catch (_: Throwable) {
            // nothing left to do
        }
    }

    // ── The flow ──

    private fun runFlow(ctx: Context) {
        var installer: Any? = null
        var sessionId: Int? = null
        var committed = false
        var apkFile: File? = null
        try {
            val shizuku = ShizukuClient()
            val ready = runCatching { shizuku.isAvailable() && shizuku.hasPermission() }.getOrDefault(false)
            if (!ready) {
                record(ctx, "aborted", "reason" to SpikeStageLog.field("shizuku-not-ready"))
                return
            }
            // Hidden AIDL stubs need the exemption (VirtualDisplayPlatform does the same before its reflection).
            shizuku.bypassHiddenApis()

            // 1. Download own release APK.
            val asset = SpikeAsset.fromVersionName(BuildConfig.VERSION_NAME)
            if (asset == null) {
                record(ctx, "aborted", "reason" to SpikeStageLog.field("unparseable-version-name"),
                    "versionName" to SpikeStageLog.field(BuildConfig.VERSION_NAME))
                return
            }
            val apk = File(ctx.cacheDir, "spike-${asset.fileName}")
            apkFile = apk
            val (bytes, sha256) = download(asset.url, apk)
            record(ctx, "downloaded", "bytes" to SpikeStageLog.field(bytes), "sha256" to SpikeStageLog.field(sha256),
                "url" to SpikeStageLog.field(asset.url))

            // 2. IPackageManager (Shizuku-wrapped) -> getPackageInstaller() -> IPackageInstaller.
            val pm = ShizukuServiceProxyProvider().packageManagerProxy()
            val rawInstaller = pm.javaClass.getMethod("getPackageInstaller").invoke(pm)
                ?: throw IllegalStateException("IPackageManager.getPackageInstaller returned null")
            // The returned interface carries the system's raw binder: calls on it would go out with
            // OUR uid. Re-wrap so they are made as the Shizuku server (shell/root) instead.
            installer = rewrap(rawInstaller, "android.content.pm.IPackageInstaller\$Stub")

            // 3. createSession.
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(TARGET_PACKAGE)
            params.setSize(bytes)
            val flagsField = PackageInstaller.SessionParams::class.java.getField("installFlags")
            flagsField.setInt(params, flagsField.getInt(params) or INSTALL_REPLACE_EXISTING)
            val userId = android.os.Process.myUserHandle().hashCode() // UserHandle.hashCode() == user id
            val shizukuUid = runCatching { Shizuku.getUid() }.getOrDefault(-1) // 2000 = adb/shell, 0 = root
            val (sid, createSignature) = createSession(installer, params, userId)
            sessionId = sid
            record(ctx, "session-created", "sessionId" to SpikeStageLog.field(sid),
                "userId" to SpikeStageLog.field(userId), "shizukuUid" to SpikeStageLog.field(shizukuUid),
                "signature" to SpikeStageLog.field(createSignature),
                "installFlags" to SpikeStageLog.field(flagsField.getInt(params)))

            // 4. openSession -> framework PackageInstaller.Session -> openWrite / fsync / close.
            val rawSession = installer.javaClass.getMethod("openSession", Int::class.javaPrimitiveType)
                .invoke(installer, sid) ?: throw IllegalStateException("openSession returned null")
            val sessionBinderIface = rewrap(rawSession, "android.content.pm.IPackageInstallerSession\$Stub")
            val session = frameworkSession(sessionBinderIface)
            var streamClass = "?"
            FileInputStream(apk).use { input ->
                val out: OutputStream = session.openWrite(APK_SPLIT_NAME, 0, bytes)
                streamClass = out.javaClass.name
                out.use {
                    copy(input, it)
                    session.fsync(it)
                }
            }
            record(ctx, "written", "bytes" to SpikeStageLog.field(bytes), "stream" to SpikeStageLog.field(streamClass))

            // 5. commit (record FIRST: success kills this process).
            val intent = Intent(ACTION_RESULT).setClass(ctx, SpikeInstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            record(ctx, "commit-sent", "sessionId" to SpikeStageLog.field(sid))
            session.commit(pending.intentSender)
            committed = true
            session.close()
            apk.delete()
        } catch (t: Throwable) {
            val atStage = SpikeStageLog.lastCompletedStage(readRecords(ctx))
            record(ctx, "failed", "atStage" to SpikeStageLog.field(atStage),
                "error" to SpikeStageLog.field(SpikeStageLog.describe(t)))
            if (!committed) runCatching { apkFile?.delete() }
            val sid = sessionId
            val inst = installer
            // Never abandon a committed session: that could cancel the install being measured.
            if (sid != null && inst != null && !committed) {
                runCatching {
                    inst.javaClass.getMethod("abandonSession", Int::class.javaPrimitiveType).invoke(inst, sid)
                    record(ctx, "abandoned", "sessionId" to SpikeStageLog.field(sid))
                }.onFailure {
                    record(ctx, "abandon-failed", "sessionId" to SpikeStageLog.field(sid),
                        "error" to SpikeStageLog.field(SpikeStageLog.describe(it)))
                }
            }
        }
    }

    // ── Reflection helpers (same shape as ShizukuServiceProxyProvider.asInterface) ──

    private fun rewrap(iface: Any, stubClassName: String): Any {
        val binder = iface.javaClass.getMethod("asBinder").invoke(iface) as IBinder
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName(stubClassName)
        return stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
            ?: throw IllegalStateException("$stubClassName.asInterface returned null")
    }

    /**
     * IPackageInstaller.createSession: API 30+ is
     * `int createSession(SessionParams, String installerPackageName, String installerAttributionTag, int userId)`;
     * API <= 29 was `(SessionParams, String installerPackageName, int userId)`. Resolved by exact
     * parameter types; the attribution tag is null (what `pm install` passes).
     */
    private fun createSession(installer: Any, params: PackageInstaller.SessionParams, userId: Int): Pair<Int, String> {
        val p = PackageInstaller.SessionParams::class.java
        val s = String::class.java
        val i = Int::class.javaPrimitiveType
        val cls = installer.javaClass
        runCatching { cls.getMethod("createSession", p, s, s, i) }.getOrNull()?.let { m ->
            return (m.invoke(installer, params, INSTALLER_PACKAGE, null, userId) as Int) to
                "createSession(SessionParams,String,String,int)"
        }
        val legacy = cls.getMethod("createSession", p, s, i)
        return (legacy.invoke(installer, params, INSTALLER_PACKAGE, userId) as Int) to
            "createSession(SessionParams,String,int)"
    }

    /**
     * Hidden `PackageInstaller.Session(IPackageInstallerSession)` constructor. Using the framework
     * Session (public openWrite/fsync/commit) instead of the raw IPackageInstallerSession.openWrite
     * PFD matters: unless fw.revocable_fd is set, the PFD is a FileBridge socket that expects
     * FileBridge's framed protocol (CMD_WRITE/CMD_FSYNC/CMD_CLOSE), which Session's stream speaks.
     */
    private fun frameworkSession(sessionIface: Any): PackageInstaller.Session {
        val ifaceClass = Class.forName("android.content.pm.IPackageInstallerSession")
        val ctor = PackageInstaller.Session::class.java.getDeclaredConstructor(ifaceClass)
        ctor.isAccessible = true
        return ctor.newInstance(sessionIface)
    }

    // ── Download ──

    private fun download(url: String, dest: File): Pair<Long, String> {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "closepaw-spike")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("HTTP $code without Location")
                    val next = URL(current, loc)
                    if (!SpikeRedirectPolicy.isAllowed(next.protocol, next.host)) {
                        throw IllegalStateException("redirect rejected: host '${next.host}' (${next.protocol}) not allowed")
                    }
                    current = next
                    if (++redirects > 5) throw IllegalStateException("too many redirects")
                    continue
                }
                if (code != 200) throw IllegalStateException("HTTP $code for $current")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_APK_BYTES) throw IllegalStateException("APK larger than $MAX_APK_BYTES bytes")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (total == 0L) throw IllegalStateException("empty download")
                return total to digest.digest().joinToString("") { "%02x".format(it) }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    /** MCP hook bound to the app context. */
    fun mcpHook(ctx: Context): McpSpikeHook {
        val app = ctx.applicationContext
        return object : McpSpikeHook {
            override fun startSelfReinstall(): JsonObject = start(app)
            override fun status(): JsonObject = statusJson(app)
        }
    }
}

/** Receives the PackageInstaller commit result (cold-starts the app after a successful self-install). */
class SpikeInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != SelfUpdateSpike.ACTION_RESULT) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            SelfUpdateSpike.record(
                context.applicationContext, "result",
                "status" to SpikeStageLog.field(status),
                "message" to SpikeStageLog.field(message),
                "sessionId" to SpikeStageLog.field(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)),
                // STATUS_PENDING_USER_ACTION (-1) carries a confirmation Intent: also data.
                "hasUserActionIntent" to JsonPrimitive(intent.hasExtra(Intent.EXTRA_INTENT)),
            )
        } catch (t: Throwable) {
            try {
                SelfUpdateSpike.recordFailedBestEffort(context.applicationContext, t)
            } catch (_: Throwable) {
            }
        }
    }
}
