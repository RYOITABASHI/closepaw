package ai.closepaw.app

import ai.closepaw.mcp.McpCredentialPolicy
import ai.closepaw.mcp.McpSettingsStore
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Log

/**
 * Read-only MCP bearer token for the adb shell and root:
 * `adb shell content query --uri content://ai.closepaw.mcpcredential/token`.
 *
 * Lets an operator agent with an adb route (Hayato) recover the token with no owner action after
 * a lost credential file. Every other caller gets a SecurityException ([McpCredentialPolicy]).
 * Never creates or regenerates the token; logs only the caller uid and the outcome.
 */
class McpCredentialProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // Read the caller before clearing identity; Keystore and disk reads then run as this app.
        val uid = Binder.getCallingUid()
        if (!McpCredentialPolicy.isTrustedCaller(uid)) denied(uid)
        val appContext = context?.applicationContext ?: throw IllegalStateException("provider not attached")
        val identity = Binder.clearCallingIdentity()
        val decision = try {
            val store = McpSettingsStore(appContext)
            McpCredentialPolicy.decide(uid, uri.path, store::isEnabled, store::token)
        } catch (e: Exception) {
            McpCredentialPolicy.Decision.Empty("error(${e.javaClass.simpleName})")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        val cursor = MatrixCursor(arrayOf(McpCredentialPolicy.COLUMN_TOKEN))
        when (decision) {
            McpCredentialPolicy.Decision.Denied -> denied(uid)
            is McpCredentialPolicy.Decision.Empty ->
                Log.i(TAG, "mcp credential uid=$uid outcome=empty(${decision.reason})")
            is McpCredentialPolicy.Decision.Token -> {
                Log.i(TAG, "mcp credential uid=$uid outcome=served")
                cursor.addRow(arrayOf<Any>(decision.token))
            }
        }
        return cursor
    }

    private fun denied(uid: Int): Nothing {
        Log.w(TAG, "mcp credential uid=$uid outcome=denied")
        throw SecurityException("MCP credential is readable from the adb shell only")
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private companion object {
        const val TAG = "ClosePawMcpAudit"
    }
}
