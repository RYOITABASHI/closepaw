package ai.closepaw.platform.virtualdisplay

import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Lazily resolves and caches Shizuku-wrapped system service proxies. */
internal class ShizukuServiceProxyProvider {
        companion object {
                private const val TAG = "ShizukuProxies"
                private val generation = AtomicLong(0)
                private val hooksInstalled = AtomicBoolean(false)

                /** Heal v2 H-4: every provider in the process drops its proxies on the next use. */
                fun invalidateAll() {
                        generation.incrementAndGet()
                }

                internal fun currentGeneration(): Long = generation.get()

                /** Registers process-wide binder lifecycle hooks once; any binder change invalidates. */
                fun installBinderLifecycleHooks() {
                        if (!hooksInstalled.compareAndSet(false, true)) return
                        try {
                                Shizuku.addBinderDeadListener { invalidateAll() }
                                Shizuku.addBinderReceivedListenerSticky { invalidateAll() }
                        } catch (t: Throwable) {
                                hooksInstalled.set(false)
                                Log.w(TAG, "Binder lifecycle hooks unavailable: ${t.javaClass.simpleName}")
                        }
                }
        }

        init {
                installBinderLifecycleHooks()
        }

        @Volatile private var cachedGeneration: Long = generation.get()

        private fun syncGeneration() {
                val current = generation.get()
                if (current != cachedGeneration) {
                        clear()
                        cachedGeneration = current
                }
        }

        @Volatile private var cachedDisplayProxy: Any? = null
        @Volatile private var cachedInputProxy: Any? = null
        @Volatile private var cachedActivityTaskProxy: Any? = null
        @Volatile private var cachedWindowManagerProxy: Any? = null
        @Volatile private var cachedPackageManagerProxy: Any? = null

        fun clear() {
                cachedDisplayProxy = null
                cachedInputProxy = null
                cachedActivityTaskProxy = null
                cachedWindowManagerProxy = null
                cachedPackageManagerProxy = null
        }

        fun displayManagerProxy(): Any {
                syncGeneration()
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
                syncGeneration()
                cachedInputProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("input")
                                ?: throw IllegalStateException("Cannot obtain input service binder")
                val proxy = asInterface(binder, "android.hardware.input.IInputManager\$Stub")
                cachedInputProxy = proxy
                return proxy
        }

        fun activityTaskManagerProxy(): Any {
                syncGeneration()
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
                syncGeneration()
                cachedWindowManagerProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("window")
                                ?: throw IllegalStateException("Cannot obtain window service binder")
                val proxy = asInterface(binder, "android.view.IWindowManager\$Stub")
                cachedWindowManagerProxy = proxy
                return proxy
        }

        /** Shizuku-wrapped IPackageManager for the self-update fallback. */
        fun packageManagerProxy(): Any {
                syncGeneration()
                cachedPackageManagerProxy?.let { return it }
                val binder =
                        SystemServiceHelper.getSystemService("package")
                                ?: throw IllegalStateException("Cannot obtain package service binder")
                val proxy = asInterface(binder, "android.content.pm.IPackageManager\$Stub")
                cachedPackageManagerProxy = proxy
                return proxy
        }

        private fun asInterface(serviceBinder: IBinder, stubClassName: String): Any {
                val wrapped = ShizukuBinderWrapper(serviceBinder)
                val stubClass = Class.forName(stubClassName)
                return stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                        ?: throw IllegalStateException("$stubClassName.asInterface returned null")
        }
}
