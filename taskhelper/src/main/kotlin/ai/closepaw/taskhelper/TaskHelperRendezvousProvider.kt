package ai.closepaw.taskhelper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/** The only exported operation is the per-launch Binder registration from uid 2000. */
class TaskHelperRendezvousProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != TaskHelperProtocol.REGISTER || Binder.getCallingUid() != TaskHelperProtocol.SHELL_UID) {
            return null
        }
        val challenge = TaskHelperProtocol.decodeRegistration(arg) ?: return null
        val helper = extras?.getBinder(TaskHelperProtocol.HELPER) ?: return null
        val pid = Binder.getCallingPid()
        val claimedPid = extras.getInt(TaskHelperProtocol.PID)
        val secret = extras.getString(TaskHelperProtocol.SESSION_SECRET) ?: return null
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

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Task helper registration only")

    companion object {
        private data class Pending(
            val permit: TaskHelperProtocol.Registration,
            val endpoint: CompletableDeferred<HelperRegistration>,
            var launchPid: Int = 0,
        )

        private val pending = mutableMapOf<String, Pending>()

        fun expect(challenge: String): CompletableDeferred<HelperRegistration> =
            synchronized(pending) {
                check(challenge !in pending)
                val endpoint = CompletableDeferred<HelperRegistration>()
                pending[challenge] = Pending(TaskHelperProtocol.Registration(challenge), endpoint)
                endpoint
            }

        fun setLaunchPid(challenge: String, pid: Int) {
            synchronized(pending) {
                val entry = checkNotNull(pending[challenge])
                check(pid > 0 && entry.launchPid == 0)
                entry.launchPid = pid
            }
        }

        fun forget(challenge: String) {
            synchronized(pending) { pending.remove(challenge) }
        }
    }
}

class HelperRegistration(val binder: IBinder, val pid: Int, val secret: String)
