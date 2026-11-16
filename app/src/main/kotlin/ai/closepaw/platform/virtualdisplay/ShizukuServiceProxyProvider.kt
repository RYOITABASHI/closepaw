package ai.closepaw.platform.virtualdisplay

import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Lazily resolves and caches Shizuku-wrapped system service proxies. */
internal class ShizukuServiceProxyProvider {
        @Volatile private var cachedDisplayProxy: Any? = null
        @Volatile private var cachedInputProxy: Any? = null
        @Volatile private var cachedActivityTaskProxy: Any? = null
        @Volatile private var cachedWindowManagerProxy: Any? = null

        fun clear() {
                cachedDisplayProxy = null
                cachedInputProxy = null
                cachedActivityTaskProxy = null
                cachedWindowManagerProxy = null
        }

        fun displayManagerProxy(): Any {
                cachedDisplayProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("display")
                                ?: throw IllegalStateException(
                                        "Cannot obtain display service binder"
                                )
                val proxy = asInterface(binder, "android.hardware.display.IDisplayManager\$Stub")
                cachedDisplayProxy = proxy
                return proxy
        }

        fun inputManagerProxy(): Any {
                cachedInputProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("input")
                                ?: throw IllegalStateException("Cannot obtain input service binder")
                val proxy = asInterface(binder, "android.hardware.input.IInputManager\$Stub")
                cachedInputProxy = proxy
                return proxy
        }

        fun activityTaskManagerProxy(): Any {
                cachedActivityTaskProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("activity_task")
                                ?: throw IllegalStateException(
                                        "Cannot obtain activity_task service binder"
                                )
                val proxy = asInterface(binder, "android.app.IActivityTaskManager\$Stub")
                cachedActivityTaskProxy = proxy
                return proxy
        }

        fun windowManagerProxy(): Any {
                cachedWindowManagerProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("window")
                                ?: throw IllegalStateException("Cannot obtain window service binder")
                val proxy = asInterface(binder, "android.view.IWindowManager\$Stub")
                cachedWindowManagerProxy = proxy
                return proxy
        }

        private fun asInterface(serviceBinder: IBinder, stubClassName: String): Any {
                val wrapped = ShizukuBinderWrapper(serviceBinder)
                val stubClass = Class.forName(stubClassName)
                return stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                        ?: throw IllegalStateException("$stubClassName.asInterface returned null")
        }
}
