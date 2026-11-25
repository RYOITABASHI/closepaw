package ai.closepaw.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.closepaw.ui.viewer.VirtualDisplayViewerActivity

internal fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

internal fun openOverlaySettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    context.startActivity(intent)
}

internal fun openViewer(context: Context) {
    context.startActivity(Intent(context, VirtualDisplayViewerActivity::class.java))
}
