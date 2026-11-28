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
 * Read-only, shell-only view of the MCP bearer token, so that an operator agent with an adb
 * route can recover it with no owner action:
 *
 *     adb shell content query --uri content://ai.closepaw.mcpcredential/token
 *
 * Access rules live in [McpCredentialPolicy]. This provider never creates or rotates the token,
 * and it never logs the token. Audit lines carry only the caller uid and the outcome.
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
        val uid = Binder.getCallingUid()
        val context = context ?: throw IllegalStateException("provider not attached")
        // Keystore and disk reads run with this app's identity, not the caller's.
        val identity = Binder.clearCallingIdentity()
        val decision = try {
            val store = McpSettingsStore(context.applicationContext)
            McpCredentialPolicy.decide(uid, uri.path, store::isEnabled, store::token)
        } catch (e: Exception) {
            Log.w(TAG, "mcp credential uid=$uid outcome=error(${e.javaClass.simpleName})")
            McpCredentialPolicy.Decision.Empty("error")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        val cursor = MatrixCursor(arrayOf(McpCredentialPolicy.COLUMN_TOKEN))
        when (decision) {
            McpCredentialPolicy.Decision.Denied -> {
                Log.w(TAG, "mcp credential uid=$uid outcome=denied")
                throw SecurityException("MCP credential is readable from the adb shell only")
            }
            is McpCredentialPolicy.Decision.Empty ->
                Log.i(TAG, "mcp credential uid=$uid outcome=empty(${decision.reason})")
            is McpCredentialPolicy.Decision.Token -> {
                Log.i(TAG, "mcp credential uid=$uid outcome=served")
                cursor.addRow(arrayOf<Any>(decision.token))
            }
        }
        return cursor
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
