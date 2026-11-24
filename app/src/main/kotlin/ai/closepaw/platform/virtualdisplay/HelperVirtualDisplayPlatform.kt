package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperWire
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.io.IOException

internal object VirtualDisplayLifecyclePolicy {
    // Opt in pending the Tier H matrix. Task observations retain their own policy.
    const val HELPER_ENABLED = false
    // Q7 diagnostic build: force initial HIDE through standalone WindowManager.
    const val SKIP_IME_BUILDER = false

    fun useHelper(sdk: Int, enabled: Boolean): Boolean = enabled && sdk >= 33
}

/** Routes lifecycle mutations for a VD owned by the existing session helper. */
internal class HelperVirtualDisplayPlatform private constructor(
    val connection: TaskHelperSession.Connection,
    val displayId: Int,
    val imeAtCreation: Boolean,
    private val callback: IBinder,
) {
    fun setSurface(surface: Surface, shizuku: ShizukuClient): Boolean =
            when (surfaceCommand(surface)) {
                true -> true
                false -> reassertSurface(surface, shizuku)
                null -> throw IOException("Helper VD surface result unconfirmed")
            }

    fun restoreSurface(surface: Surface): Boolean = surfaceCommand(surface) == true

    fun reassertSurface(surface: Surface, shizuku: ShizukuClient): Boolean =
            surface.isValid && shizuku.setHelperDisplaySurface(callback, surface)

    private fun surfaceCommand(surface: Surface): Boolean? =
            command(TaskHelperWire.SET_DISPLAY_SURFACE, Bundle().apply {
                putParcelable(TaskHelperWire.SURFACE, surface)
            })

    fun setImePolicy(policy: Int, shizuku: ShizukuClient): Boolean =
            // Q7: device-unproven, including use after creation for viewer takeover.
            when (command(TaskHelperWire.SET_DISPLAY_IME_POLICY, Bundle().apply {
                putInt(TaskHelperWire.IME_POLICY, policy)
            })) {
                true -> true
                false -> shizuku.setDisplayImePolicy(displayId, policy)
                null -> throw IOException("Helper VD IME result unconfirmed")
            }

    fun release(shizuku: ShizukuClient): Boolean =
            // Retrying release with the same token is safe, including on a lost reply.
            command(TaskHelperWire.RELEASE_DISPLAY) == true || shizuku.releaseHelperDisplay(callback)

    private fun command(code: Int, payload: Bundle = Bundle()): Boolean? {
        val result = connection.displayCommand(code, displayId, payload)?.getBoolean(TaskHelperWire.SUCCESS)
        val operation = when (code) {
            TaskHelperWire.RELEASE_DISPLAY -> "release"
            TaskHelperWire.SET_DISPLAY_SURFACE -> "surface"
            else -> "standalone IME policy ${payload.getInt(TaskHelperWire.IME_POLICY)} (Q7)"
        }
        Log.d("HelperVD", "$operation displayId=$displayId helperResult=$result")
        return result
    }

    companion object {
        /** Null is a confirmed rejection. A missing or malformed reply fails the start. */
        fun create(
            connection: TaskHelperSession.Connection,
            width: Int,
            height: Int,
            density: Int,
            surface: Surface,
            flags: Int,
            optionalFlags: Int,
            imePolicy: Int,
        ): HelperVirtualDisplayPlatform? {
            var display: HelperVirtualDisplayPlatform? = null
            createWithOptionalFlags(flags, optionalFlags) { attemptFlags ->
                Log.i("HelperVD", "Creating display flags=0x${attemptFlags.toString(16)}")
                val reply = connection.displayCommand(TaskHelperWire.CREATE_DISPLAY, payload = Bundle().apply {
                    putInt(TaskHelperWire.WIDTH, width)
                    putInt(TaskHelperWire.HEIGHT, height)
                    putInt(TaskHelperWire.DENSITY, density)
                    putParcelable(TaskHelperWire.SURFACE, surface)
                    putInt(TaskHelperWire.FLAGS, attemptFlags)
                    putInt(TaskHelperWire.IME_POLICY, imePolicy)
                    putBoolean(TaskHelperWire.SKIP_IME_BUILDER, VirtualDisplayLifecyclePolicy.SKIP_IME_BUILDER)
                }) ?: throw IOException("Helper VD creation result unconfirmed")
                if (!reply.getBoolean(TaskHelperWire.SUCCESS)) return@createWithOptionalFlags -1
                val id = reply.getInt(TaskHelperWire.DISPLAY_ID)
                Log.i("HelperVD", "Created displayId=$id imeAtCreation=${reply.getBoolean(TaskHelperWire.IME_AT_CREATION)}")
                display = HelperVirtualDisplayPlatform(
                        connection, id, reply.getBoolean(TaskHelperWire.IME_AT_CREATION),
                        checkNotNull(reply.getBinder(TaskHelperWire.DISPLAY_CALLBACK)),
                )
                id
            }
            return display
        }
    }
}
