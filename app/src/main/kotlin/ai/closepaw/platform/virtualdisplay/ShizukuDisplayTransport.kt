package ai.closepaw.platform.virtualdisplay

import android.hardware.display.IVirtualDisplayCallback
import android.os.Build
import android.util.Log
import android.view.Surface
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fail-open creation with optional display flags: [create] (returns a display id, < 0 on
 * failure) is tried with [flags] | [optionalFlags]; if that fails it is retried once with
 * [flags] alone and [onRejected] is told which optional flags were dropped. Without optional
 * flags this is exactly one [create] call with [flags] (the pre-build-61 behavior).
 */
internal fun createWithOptionalFlags(
        flags: Int,
        optionalFlags: Int,
        onRejected: (Int) -> Unit = {},
        create: (Int) -> Int,
): Int {
        val extra = optionalFlags and flags.inv()
        if (extra == 0) return create(flags)
        val id = create(flags or extra)
        if (id >= 0) return id
        onRejected(extra)
        return create(flags)
}

/** Transport layer for virtual display lifecycle calls through IDisplayManager. */
internal class ShizukuDisplayTransport(
        private val proxyProvider: ShizukuServiceProxyProvider
) {
        companion object {
                private const val TAG = "ShizukuDisplayTrans"
                private val PACKAGE_NAME_CANDIDATES: List<String?> =
                        listOf(
                                "com.android.shell",
                                null,
                                "moe.shizuku.privileged.api",
                        )
        }

        /**
         * Stored callbacks from createVirtualDisplay, keyed by displayId.
         * Required for setVirtualDisplaySurface on ROMs that validate callback tokens.
         */
        private val displayCallbacks = ConcurrentHashMap<Int, IVirtualDisplayCallback>()

        /** Displays whose IME policy was set on the API 33+ config builder at creation. */
        private val imePolicyAtCreation = ConcurrentHashMap.newKeySet<Int>()

        fun clear() {
                displayCallbacks.clear()
                imePolicyAtCreation.clear()
        }

        fun imePolicyAppliedAtCreation(displayId: Int): Boolean = displayId in imePolicyAtCreation

        /**
         * [optionalFlags] (e.g. the API 34+ focus-isolation flags) are requested together with
         * [flags]; a creation that fails with them is retried once with [flags] alone, so a
         * rejected optional flag never aborts VD creation (see [createWithOptionalFlags]).
         */
        fun createVirtualDisplay(
                name: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                surface: Surface,
                flags: Int,
                imePolicy: Int? = null,
                optionalFlags: Int = 0
        ): Int =
                createWithOptionalFlags(
                        flags = flags,
                        optionalFlags = optionalFlags,
                        onRejected = { rejected ->
                                Log.w(
                                        TAG,
                                        "createVirtualDisplay with optional flags 0x${Integer.toHexString(rejected)} " +
                                                "failed; retrying without them"
                                )
                        }
                ) { attemptFlags ->
                        createVirtualDisplayOnce(name, width, height, densityDpi, surface, attemptFlags, imePolicy)
                }

        private fun createVirtualDisplayOnce(
                name: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                surface: Surface,
                flags: Int,
                imePolicy: Int?
        ): Int {
                Log.i(TAG, "createVirtualDisplay: flags=0x${Integer.toHexString(flags)}")
                return try {
                        val proxy = proxyProvider.displayManagerProxy()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                createVirtualDisplayApi33(
                                        proxy,
                                        name,
                                        width,
                                        height,
                                        densityDpi,
                                        surface,
                                        flags,
                                        imePolicy
                                )
                        } else {
                                createVirtualDisplayLegacy(
                                        proxy,
                                        name,
                                        width,
                                        height,
                                        densityDpi,
                                        surface,
                                        flags
                                )
                        }
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to create virtual display", e)
                        -1
                }
        }

        fun setVirtualDisplaySurface(displayId: Int, surface: Surface): Boolean {
                if (displayId < 0) return false
                return try {
                        val proxy = proxyProvider.displayManagerProxy()
                        val callback = displayCallbacks[displayId]
                        if (callback == null) {
                                Log.e(TAG, "No callback token for display $displayId")
                                return false
                        }
                        val method =
                                proxy.javaClass.getMethod(
                                        "setVirtualDisplaySurface",
                                        IVirtualDisplayCallback::class.java,
                                        Surface::class.java
                                )
                        method.invoke(proxy, callback, surface)
                        Log.d(TAG, "Set virtual display $displayId surface")
                        true
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to set virtual display $displayId surface", e)
                        false
                }
        }

        fun releaseVirtualDisplay(displayId: Int) {
                if (displayId < 0) return
                try {
                        val proxy = proxyProvider.displayManagerProxy()
                        val callback = displayCallbacks[displayId]
                        if (callback != null) {
                                val method =
                                        proxy.javaClass.getMethod(
                                                "releaseVirtualDisplay",
                                                IVirtualDisplayCallback::class.java
                                        )
                                method.invoke(proxy, callback)
                        } else {
                                Log.w(TAG, "No callback token for display $displayId, skipping release")
                        }
                        displayCallbacks.remove(displayId)
                        imePolicyAtCreation.remove(displayId)
                        Log.d(TAG, "Released virtual display $displayId")
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to release virtual display $displayId", e)
                }
        }

        /** API 33+ (Tiramisu): Uses VirtualDisplayConfig parameter object. */
        private fun createVirtualDisplayApi33(
                proxy: Any,
                name: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                surface: Surface,
                flags: Int,
                imePolicy: Int?
        ): Int {
                val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
                val builder =
                        builderClass
                                .getConstructor(
                                        String::class.java,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType
                                )
                                .newInstance(name, width, height, densityDpi)

                builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
                builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
                val imePolicySet = imePolicy != null && applyImePolicy(builderClass, builder, imePolicy)

                val config =
                        builderClass.getMethod("build").invoke(builder)
                                ?: throw IllegalStateException(
                                        "VirtualDisplayConfig.Builder.build() returned null"
                                )

                val callback = newDisplayCallback()

                val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
                val method =
                        proxy.javaClass.getMethod(
                                "createVirtualDisplay",
                                configClass,
                                IVirtualDisplayCallback::class.java,
                                Class.forName("android.media.projection.IMediaProjection"),
                                String::class.java
                        )

                var lastError: Throwable? = null
                for (packageName in PACKAGE_NAME_CANDIDATES) {
                        try {
                                val displayId =
                                        method.invoke(proxy, config, callback, null, packageName) as Int
                                if (imePolicySet && displayId >= 0) imePolicyAtCreation.add(displayId)
                                return registerDisplayCallback(
                                        displayId,
                                        callback,
                                        "API33+ package=${packageName ?: "<null>"}"
                                )
                        } catch (e: InvocationTargetException) {
                                if (isPackageUidMismatch(e)) {
                                        lastError = e
                                        Log.w(
                                                TAG,
                                                "createVirtualDisplay API33 rejected package=${packageName ?: "<null>"}; retrying"
                                        )
                                        continue
                                }
                                throw e
                        }
                }
                throw lastError ?: IllegalStateException("createVirtualDisplay API33 failed")
        }

        /** API 31-32: Legacy method with individual parameters. */
        private fun createVirtualDisplayLegacy(
                proxy: Any,
                name: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                surface: Surface,
                flags: Int
        ): Int {
                val callback = newDisplayCallback()
                val projectionClass = Class.forName("android.media.projection.IMediaProjection")

                return try {
                        val method =
                                proxy.javaClass.getMethod(
                                        "createVirtualDisplay",
                                        IVirtualDisplayCallback::class.java,
                                        projectionClass,
                                        String::class.java,
                                        String::class.java,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Surface::class.java,
                                        Int::class.javaPrimitiveType,
                                        String::class.java
                                )
                        var lastError: Throwable? = null
                        for (packageName in PACKAGE_NAME_CANDIDATES) {
                                try {
                                        val displayId =
                                                method.invoke(
                                                        proxy,
                                                        callback,
                                                        null,
                                                        packageName,
                                                        name,
                                                        width,
                                                        height,
                                                        densityDpi,
                                                        surface,
                                                        flags,
                                                        null
                                                ) as Int
                                        return registerDisplayCallback(
                                                displayId,
                                                callback,
                                                "legacy package=${packageName ?: "<null>"}"
                                        )
                                } catch (e: InvocationTargetException) {
                                        if (isPackageUidMismatch(e)) {
                                                lastError = e
                                                Log.w(
                                                        TAG,
                                                        "createVirtualDisplay legacy rejected package=${packageName ?: "<null>"}; retrying"
                                                )
                                                continue
                                        }
                                        throw e
                                }
                        }
                        throw lastError ?: IllegalStateException("createVirtualDisplay legacy failed")
                } catch (e: NoSuchMethodException) {
                        Log.w(TAG, "Legacy createVirtualDisplay signature not found, trying alternative")
                        createVirtualDisplayLegacyAlt(
                                proxy,
                                name,
                                width,
                                height,
                                densityDpi,
                                surface,
                                flags,
                                callback,
                                projectionClass
                        )
                }
        }

        /** Alternative legacy signature for some API 31 builds. */
        private fun createVirtualDisplayLegacyAlt(
                proxy: Any,
                name: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                surface: Surface,
                flags: Int,
                callback: IVirtualDisplayCallback,
                projectionClass: Class<*>
        ): Int {
                val method =
                        proxy.javaClass.getMethod(
                                "createVirtualDisplay",
                                IVirtualDisplayCallback::class.java,
                                projectionClass,
                                String::class.java,
                                Surface::class.java,
                                Int::class.javaPrimitiveType,
                                String::class.java
                        )
                var lastError: Throwable? = null
                for (packageName in PACKAGE_NAME_CANDIDATES) {
                        try {
                                val displayId =
                                        method.invoke(
                                                proxy,
                                                callback,
                                                null,
                                                packageName,
                                                surface,
                                                flags,
                                                name
                                        ) as Int
                                return registerDisplayCallback(
                                        displayId,
                                        callback,
                                        "legacy-alt package=${packageName ?: "<null>"}"
                                )
                        } catch (e: InvocationTargetException) {
                                if (isPackageUidMismatch(e)) {
                                        lastError = e
                                        Log.w(
                                                TAG,
                                                "createVirtualDisplay legacy-alt rejected package=${packageName ?: "<null>"}; retrying"
                                        )
                                        continue
                                }
                                throw e
                        }
                }
                throw lastError ?: IllegalStateException("createVirtualDisplay legacy-alt failed")
        }

        /**
         * Probe the hidden VirtualDisplayConfig.Builder.setDisplayImePolicy(int) and set [imePolicy]
         * so the policy holds from the display's first frame. Fail-open: false when the method is
         * absent (the caller then sets it through IWindowManager) or the call fails.
         */
        private fun applyImePolicy(builderClass: Class<*>, builder: Any, imePolicy: Int): Boolean {
                return try {
                        builderClass
                                .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType)
                                .invoke(builder, imePolicy)
                        Log.i(TAG, "VirtualDisplayConfig IME policy=$imePolicy set at creation")
                        true
                } catch (e: NoSuchMethodException) {
                        Log.i(TAG, "VirtualDisplayConfig.Builder.setDisplayImePolicy absent; IWindowManager fallback")
                        false
                } catch (e: Exception) {
                        Log.w(TAG, "VirtualDisplayConfig.Builder.setDisplayImePolicy failed; IWindowManager fallback", e)
                        false
                } catch (e: LinkageError) {
                        Log.w(TAG, "VirtualDisplayConfig.Builder.setDisplayImePolicy unavailable; IWindowManager fallback", e)
                        false
                }
        }

        private fun newDisplayCallback(): IVirtualDisplayCallback {
                return object : IVirtualDisplayCallback.Stub() {
                        override fun onPaused() {}
                        override fun onResumed() {}
                        override fun onStopped() {}
                }
        }

        private fun registerDisplayCallback(
                displayId: Int,
                callback: IVirtualDisplayCallback,
                variant: String
        ): Int {
                if (displayId >= 0) {
                        displayCallbacks[displayId] = callback
                }
                Log.d(TAG, "Created virtual display ($variant): displayId=$displayId")
                return displayId
        }

        private fun isPackageUidMismatch(error: InvocationTargetException): Boolean {
                val message = error.cause?.message ?: return false
                return message.contains("packageName must match the calling uid")
        }
}
