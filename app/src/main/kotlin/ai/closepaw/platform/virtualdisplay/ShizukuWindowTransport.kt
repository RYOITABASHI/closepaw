package ai.closepaw.platform.virtualdisplay

import android.util.Log
import java.lang.reflect.InvocationTargetException

/** Transport layer for display-scoped window policy calls through IWindowManager. */
internal class ShizukuWindowTransport(
        private val windowManagerProxy: () -> Any
) {
        constructor(proxyProvider: ShizukuServiceProxyProvider) : this(
                { proxyProvider.windowManagerProxy() }
        )

        companion object {
                private const val TAG = "ShizukuWindowTrans"
        }

        /**
         * IWindowManager.setDisplayImePolicy(displayId, imePolicy) (hidden, Android 12+; needs
         * INTERNAL_SYSTEM_WINDOW, which the shell holds). Fail-open: a missing method, a
         * SecurityException (e.g. an OEM build refusing it) or any reflective failure is logged
         * and reported as false; it never throws.
         */
        fun setDisplayImePolicy(displayId: Int, imePolicy: Int): Boolean {
                if (displayId < 0) return false
                return try {
                        val proxy = windowManagerProxy()
                        proxy.javaClass
                                .getMethod(
                                        "setDisplayImePolicy",
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType
                                )
                                .invoke(proxy, displayId, imePolicy)
                        Log.i(TAG, "setDisplayImePolicy(display=$displayId, policy=$imePolicy) ok")
                        true
                } catch (e: Exception) {
                        Log.w(
                                TAG,
                                "setDisplayImePolicy(display=$displayId, policy=$imePolicy) failed; " +
                                        "continuing without it: ${e.summary()}"
                        )
                        false
                } catch (e: LinkageError) {
                        Log.w(
                                TAG,
                                "setDisplayImePolicy(display=$displayId, policy=$imePolicy) unavailable; " +
                                        "continuing without it: $e"
                        )
                        false
                }
        }

        private fun Throwable.summary(): String {
                val cause = if (this is InvocationTargetException) targetException else this
                val name = cause::class.java.simpleName
                val message = cause.message
                return if (message.isNullOrBlank()) name else "$name: $message"
        }
}
