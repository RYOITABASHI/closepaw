package ai.closepaw.update

import ai.closepaw.BuildConfig
import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.mcp.McpSelfUpdate
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.platform.virtualdisplay.ShizukuServiceProxyProvider
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.IBinder
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/** Reinstalls this ClosePaw build using its Shelly release APK. */
internal object SelfUpdate {
    private const val TAG = "ClosePawSelfUpdate"
    const val STAGE_FILE = "spike-result.json"
    const val ACTION_RESULT = "ai.closepaw.action.SPIKE_INSTALL_RESULT"
    private const val TARGET_PACKAGE = "ai.closepaw"
    private const val INSTALLER_PACKAGE = "com.android.shell"
    private const val APK_SPLIT_NAME = "base.apk"
    private const val INSTALL_CALLBACK_TIMEOUT_MS = 180_000L
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    /** PackageManager.INSTALL_REPLACE_EXISTING (hidden). `pm install` sets it; shell callers don't get it implicitly. */
    private const val INSTALL_REPLACE_EXISTING = 0x00000002

    private val running = AtomicBoolean(false)
    private val fileLock = Any()
    private var installFinished: CompletableDeferred<Unit>? = null
    private var unpersistedFailure: UpdateStageRecord? = null

    // ── Stage file ──

    private fun stageFile(ctx: Context) = AtomicFile(File(ctx.filesDir, STAGE_FILE))

    private fun readRecords(ctx: Context): List<UpdateStageRecord> = synchronized(fileLock) {
        UpdateStageLog.read(File(ctx.filesDir, STAGE_FILE))
    }

    /** Appends a stage and confirms the atomic replace reached the stage file. */
    fun record(ctx: Context, stage: String, vararg fields: Pair<String, JsonPrimitive>): Boolean =
        record(ctx, UpdateStageRecord(stage, System.currentTimeMillis(), fields.toMap()))

    private fun record(ctx: Context, rec: UpdateStageRecord): Boolean = synchronized(fileLock) {
        Log.i(TAG, "stage ${rec.toJson()}")
        val saved = runCatching {
            val f = stageFile(ctx)
            val content = UpdateStageLog.encode(readRecords(ctx) + rec).encodeToByteArray()
            val stream = f.startWrite()
            try {
                stream.write(content)
                stream.fd.sync()
                f.finishWrite(stream)
            } catch (t: Throwable) {
                f.failWrite(stream)
                throw t
            }
            // AtomicFile.finishWrite can log a rename failure without throwing.
            check(f.readFully().contentEquals(content)) { "Stage write could not be verified" }
        }.onFailure { Log.w(TAG, "stage write failed: ${UpdateStageLog.describe(it)}") }.isSuccess
        if (rec.stage == "failed") unpersistedFailure = rec.takeUnless { saved }
        saved
    }

    private fun resetStageFile(ctx: Context) = synchronized(fileLock) {
        check(ShizukuUpdateSession.pending(readRecords(ctx)) == null) { "A Shizuku install is pending" }
        check(!AdbSelfUpdate.pending(ctx)) { "An ADB install is pending" }
        stageFile(ctx).delete()
        unpersistedFailure = null
    }

    fun statusJson(ctx: Context): JsonObject {
        val lastUpdate = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrNull()
        val active = running.get()
        val adbStatus = UpdateStageLog.readStatus(lastUpdate, active) {
            UpdateStageLog.statusJson(AdbSelfUpdate.records(ctx), lastUpdate, active)
        }
        val shizukuStatus = synchronized(fileLock) {
            UpdateStageLog.readStatus(lastUpdate, active) {
                val records = readRecords(ctx)
                val pending = ShizukuUpdateSession.pending(records)
                val waiting = pending != null && System.currentTimeMillis() < pending.deadlineAtMs
                val stages = records + listOfNotNull(unpersistedFailure)
                UpdateStageLog.statusJson(stages, lastUpdate, running.get() || waiting)
            }
        }
        return JsonObject(shizukuStatus + ("selfUpdateAdb" to adbStatus))
    }

    // ── Entry point ──

    /** Starts the flow on a background thread; returns the current stage at once. */
    private fun start(
        ctx: Context,
        adb: AdbShellChannel,
        manualPort: Int?,
        recoverOnly: Boolean = false,
    ): JsonObject {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) {
            return statusJson(app).let { JsonObject(it + ("accepted" to JsonPrimitive(false))) }
        }
        var decision = UpdateStartDecision(false)
        try {
            decision = synchronized(fileLock) {
                startUpdateIfReady(
                    readShizuku = { readRecords(app) },
                    hasAdbTicket = { AdbSelfUpdate.pending(app) },
                    handlePendingShizuku = { pending ->
                        pending.expired(System.currentTimeMillis())?.let { record(app, it) }
                    },
                ) { recoverAdb ->
                    Thread({
                        try {
                            runBlocking {
                                if (recoverAdb) {
                                    AdbSelfUpdate.collectResult(app, adb, manualPort)
                                } else if (!recoverOnly) {
                                    val connected = adb.connectAuto() || (manualPort != null && adb.connect(manualPort))
                                    runFlow(app, adb.takeIf { connected })
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "self-update worker failed: ${UpdateStageLog.describe(t)}")
                            if (t !is UnreadableUpdateStageStore) recordFailedBestEffort(app, t)
                        } finally {
                            running.set(false)
                        }
                    }, "closepaw-self-update").start()
                }
            }
        } catch (t: Throwable) {
            recordFailedBestEffort(app, t)
        } finally {
            if (!decision.accepted) running.set(false)
        }
        val result = mapOf("accepted" to JsonPrimitive(decision.accepted))
        return try {
            val status = statusJson(app)
            val error = decision.error
            JsonObject(status + result + if (error != null) mapOf("error" to JsonPrimitive(error)) else emptyMap())
        } catch (t: Throwable) {
            JsonObject(result + ("error" to UpdateStageLog.field(decision.error ?: UpdateStageLog.describe(t))))
        }
    }

    /** Records a `failed` stage; can never throw (recording itself is guarded). */
    fun recordFailedBestEffort(ctx: Context, t: Throwable) {
        try {
            val atStage = runCatching { UpdateStageLog.lastCompletedStage(readRecords(ctx)) }.getOrDefault("unknown")
            record(ctx, "failed", "atStage" to UpdateStageLog.field(atStage),
                "error" to UpdateStageLog.field(UpdateStageLog.describe(t)))
        } catch (_: Throwable) {
            // nothing left to do
        }
    }

    // ── The flow ──

    private suspend fun runFlow(ctx: Context, adb: AdbShellChannel?) {
        var installer: Any? = null
        var sessionId: Int? = null
        var committed = false
        var apkFile: File? = null
        try {
            resetStageFile(ctx)
            if (adb != null) {
                AdbSelfUpdate.started(ctx)
            } else {
                record(ctx, "started")
                val shizuku = ShizukuClient()
                val ready = runCatching { shizuku.isAvailable() && shizuku.hasPermission() }.getOrDefault(false)
                if (!ready) {
                    record(ctx, "aborted", "reason" to UpdateStageLog.field("shizuku-not-ready"))
                    return
                }
                shizuku.bypassHiddenApis()
            }

            // 1. Download own release APK.
            val asset = UpdateAsset.fromVersionName(BuildConfig.VERSION_NAME)
            if (asset == null) {
                if (adb != null) {
                    AdbSelfUpdate.fail(ctx, IllegalStateException("Unparseable version name"))
                    return
                }
                record(ctx, "aborted", "reason" to UpdateStageLog.field("unparseable-version-name"),
                    "versionName" to UpdateStageLog.field(BuildConfig.VERSION_NAME))
                return
            }
            val apk = File(ctx.cacheDir, "self-update-${asset.fileName}")
            apkFile = apk
            val (bytes, sha256) = download(asset.url, apk)
            if (adb != null) {
                AdbSelfUpdate.install(ctx, adb, apk, bytes)
                return
            }
            record(ctx, "downloaded", "bytes" to UpdateStageLog.field(bytes), "sha256" to UpdateStageLog.field(sha256),
                "url" to UpdateStageLog.field(asset.url))

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
            record(ctx, "session-created", "sessionId" to UpdateStageLog.field(sid),
                "userId" to UpdateStageLog.field(userId), "shizukuUid" to UpdateStageLog.field(shizukuUid),
                "signature" to UpdateStageLog.field(createSignature),
                "installFlags" to UpdateStageLog.field(flagsField.getInt(params)))

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
            record(ctx, "written", "bytes" to UpdateStageLog.field(bytes), "stream" to UpdateStageLog.field(streamClass))

            // Persist the expected session before commit so a cold-started receiver can correlate it.
            val intent = Intent(ACTION_RESULT).setClass(ctx, UpdateInstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                ctx, sid, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val finished = CompletableDeferred<Unit>()
            val deadline = System.currentTimeMillis() + INSTALL_CALLBACK_TIMEOUT_MS
            try {
                synchronized(fileLock) {
                    check(record(ctx, "commit-sent", "sessionId" to UpdateStageLog.field(sid),
                        "deadlineAtMs" to UpdateStageLog.field(deadline))) {
                        "Could not persist Shizuku install session $sid before commit"
                    }
                    installFinished = finished
                }
                session.commit(pending.intentSender)
                committed = true
            } finally {
                runCatching { session.close() }
            }
            runCatching { apk.delete() }
            withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceIn(0L, INSTALL_CALLBACK_TIMEOUT_MS)) {
                finished.await()
            }
            synchronized(fileLock) {
                if (!finished.isCompleted) {
                    ShizukuUpdateSession.pending(readRecords(ctx))
                        ?.expired(System.currentTimeMillis())?.let { record(ctx, it) }
                }
            }
        } catch (t: Throwable) {
            if (t is UnreadableUpdateStageStore) throw t
            if (adb != null) {
                if (t is Exception && !AdbSelfUpdate.pending(ctx)) AdbSelfUpdate.fail(ctx, t)
                return
            }
            val atStage = runCatching { UpdateStageLog.lastCompletedStage(readRecords(ctx)) }.getOrDefault("unknown")
            if (!committed) runCatching { apkFile?.delete() }
            val sid = sessionId
            val inst = installer
            // Never abandon a committed session: that could cancel the install being measured.
            if (sid != null && inst != null && !committed) {
                runCatching {
                    inst.javaClass.getMethod("abandonSession", Int::class.javaPrimitiveType).invoke(inst, sid)
                    record(ctx, "abandoned", "sessionId" to UpdateStageLog.field(sid))
                }.onFailure {
                    record(ctx, "abandon-failed", "sessionId" to UpdateStageLog.field(sid),
                        "error" to UpdateStageLog.field(UpdateStageLog.describe(it)))
                }
            }
            record(ctx, "failed", "atStage" to UpdateStageLog.field(atStage),
                "error" to UpdateStageLog.field(UpdateStageLog.describe(t)))
        } finally {
            if (adb != null) apkFile?.delete()
            synchronized(fileLock) { installFinished = null }
        }
    }

    fun onInstallResult(ctx: Context, intent: Intent) = synchronized(fileLock) {
        val sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val records = readRecords(ctx)
        val expected = ShizukuUpdateSession.pending(records)
        if (expected == null || sid != expected.id || status == Int.MIN_VALUE ||
            System.currentTimeMillis() >= expected.deadlineAtMs
        ) {
            Log.w(TAG, "Unknown or stale install callback: sessionId=$sid expected=${expected?.id} status=$status")
            return@synchronized
        }

        val terminal = status != PackageInstaller.STATUS_PENDING_USER_ACTION
        if (!terminal && records.lastOrNull()?.stage == "pending-user-action") return@synchronized
        val saved = record(ctx, if (terminal) "result" else "pending-user-action",
            "status" to UpdateStageLog.field(status),
            "message" to UpdateStageLog.field(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            "sessionId" to UpdateStageLog.field(sid),
            "hasUserActionIntent" to JsonPrimitive(intent.hasExtra(Intent.EXTRA_INTENT)))
        if (terminal && saved) installFinished?.complete(Unit)
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
                setRequestProperty("User-Agent", "closepaw-self-update")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("HTTP $code without Location")
                    val next = URL(current, loc)
                    if (!UpdateRedirectPolicy.isAllowed(next.protocol, next.host)) {
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

    fun mcpHook(ctx: Context, adb: AdbShellChannel): McpSelfUpdate {
        val app = ctx.applicationContext
        return object : McpSelfUpdate {
            override fun start(connectPort: Int?): JsonObject = start(app, adb, connectPort)

            override fun status(): JsonObject {
                if (!running.get()) {
                    try {
                        if (AdbSelfUpdate.pending(app)) start(app, adb, null, recoverOnly = true)
                    } catch (_: UnreadableUpdateStageStore) {
                        // statusJson reports the unreadable ADB store.
                    }
                }
                return statusJson(app)
            }
        }
    }
}

/** Receives the PackageInstaller commit result (cold-starts the app after a successful self-install). */
class UpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != SelfUpdate.ACTION_RESULT) return
            SelfUpdate.onInstallResult(context.applicationContext, intent)
        } catch (t: Throwable) {
            Log.w("ClosePawSelfUpdate", "Install callback could not be processed", t)
        }
    }
}
