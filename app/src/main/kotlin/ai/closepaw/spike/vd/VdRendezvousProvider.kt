package ai.closepaw.spike.vd

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/** The only exported operation is the per-launch Binder registration from uid 2000. */
class VdRendezvousProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != VdProtocol.REGISTER || Binder.getCallingUid() != VdProtocol.SHELL_UID) {
            return null
        }
        val challenge = VdProtocol.decodeRegistration(arg) ?: return null
        val helper = extras?.getBinder(VdProtocol.HELPER) ?: return null
        val pid = Binder.getCallingPid()
        val claimedPid = extras.getInt(VdProtocol.PID)
        val secret = extras.getString(VdProtocol.SESSION_SECRET) ?: return null
        synchronized(pending) {
            val entry = pending[challenge] ?: return null
            if (!entry.permit.claim(Binder.getCallingUid(), entry.launchPid, claimedPid, pid, arg, secret)) {
                return null
            }
            pending.remove(challenge)
            entry.endpoint.complete(HelperRegistration(helper, pid, secret))
        }
        return Bundle.EMPTY
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = unsupported()

    override fun getType(uri: Uri): String? = unsupported()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        unsupported()

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("VD registration only")

    companion object {
        private data class Pending(
            val permit: VdProtocol.Registration,
            val endpoint: CompletableDeferred<HelperRegistration>,
            var launchPid: Int = 0,
        )

        private val pending = mutableMapOf<String, Pending>()

        internal fun expect(challenge: String): CompletableDeferred<HelperRegistration> =
            synchronized(pending) {
                check(challenge !in pending)
                val endpoint = CompletableDeferred<HelperRegistration>()
                pending[challenge] = Pending(VdProtocol.Registration(challenge), endpoint)
                endpoint
            }

        internal fun setLaunchPid(challenge: String, pid: Int) {
            synchronized(pending) {
                val entry = checkNotNull(pending[challenge])
                check(pid > 0 && entry.launchPid == 0)
                entry.launchPid = pid
            }
        }

        internal fun forget(challenge: String) {
            synchronized(pending) { pending.remove(challenge) }
        }
    }
}

internal class HelperRegistration(val binder: IBinder, val pid: Int, val secret: String)
