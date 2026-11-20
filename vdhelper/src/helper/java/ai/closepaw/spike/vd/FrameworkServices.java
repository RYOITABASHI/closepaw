package ai.closepaw.spike.vd;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;

import java.lang.reflect.InvocationTargetException;

/** Framework Binder calls made directly from the app_process shell identity. */
final class FrameworkServices {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String DISPLAY_MANAGER = "android.hardware.display.IDisplayManager";
    private static final String DISPLAY_CONFIG = "android.hardware.display.VirtualDisplayConfig";
    private static final String DISPLAY_CALLBACK = "android.hardware.display.IVirtualDisplayCallback";
    private static final int IME_HIDE = 2;

    private FrameworkServices() {}

    static Object proxy(String interfaceName, IBinder binder) throws Exception {
        if (binder == null) {
            throw new IllegalStateException("Missing Binder for " + interfaceName);
        }
        return Class.forName(interfaceName + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    static void register(String authority, String registration, int appUid, IBinder helper, String secret)
            throws Exception {
        Class<?> am = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManager")
                .getMethod("getService").invoke(null);
        int userId = (int) Class.forName("android.os.UserHandle")
                .getMethod("getUserId", int.class).invoke(null, appUid);
        IBinder reference = new Binder();
        Object holder = am.getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class)
                .invoke(manager, authority, userId, reference, "closepaw-vd");
        if (holder == null) {
            throw new IllegalStateException("VD rendezvous provider unavailable");
        }
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Bundle request = new Bundle();
            request.putBinder(VdProtocol.HELPER, helper);
            request.putInt(VdProtocol.PID, android.os.Process.myPid());
            request.putString(VdProtocol.SESSION_SECRET, secret);
            AttributionSource source = new AttributionSource.Builder(VdProtocol.SHELL_UID)
                    .setPid(android.os.Process.myPid())
                    .setPackageName(SHELL_PACKAGE).build();
            Bundle response = (Bundle) Class.forName("android.content.IContentProvider")
                    .getMethod("call", AttributionSource.class, String.class, String.class,
                            String.class, Bundle.class)
                    .invoke(provider, source, authority, VdProtocol.REGISTER, registration, request);
            if (response == null) {
                throw new SecurityException("VD registration rejected");
            }
        } finally {
            am.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, reference, userId);
        }
    }

    static final class Display {
        private final Class<?> managerType = Class.forName(DISPLAY_MANAGER);
        private final Object manager;
        private final Class<?> callbackType = Class.forName(DISPLAY_CALLBACK);
        private final Object callback;

        Display() throws Exception {
            IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "display");
            manager = proxy(DISPLAY_MANAGER, service);
            // asInterface returns a typed framework proxy backed by our local Binder token.
            callback = proxy(DISPLAY_CALLBACK, new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws android.os.RemoteException {
                    if (code >= FIRST_CALL_TRANSACTION && code <= FIRST_CALL_TRANSACTION + 2) {
                        data.enforceInterface(DISPLAY_CALLBACK);
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            });
        }

        int create(Surface surface, int width, int height, int density, int flags) throws Exception {
            Class<?> config = Class.forName(DISPLAY_CONFIG);
            Class<?> builderType = Class.forName(DISPLAY_CONFIG + "$Builder");
            Object builder = builderType.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("closepaw_phase0", width, height, density);
            builderType.getMethod("setSurface", Surface.class).invoke(builder, surface);
            builderType.getMethod("setFlags", int.class).invoke(builder, flags);
            boolean imeAtCreation = false;
            try {
                builderType.getMethod("setDisplayImePolicy", int.class).invoke(builder, IME_HIDE);
                imeAtCreation = true;
            } catch (NoSuchMethodException | InvocationTargetException ignored) {
                // Use WindowManager if this build lacks the config-builder setter.
            }
            Object request = builderType.getMethod("build").invoke(builder);
            int id = (int) managerType.getMethod("createVirtualDisplay", config, callbackType,
                    Class.forName("android.media.projection.IMediaProjection"), String.class)
                    .invoke(manager, request, callback, null, SHELL_PACKAGE);
            if (id > 0 && !imeAtCreation) {
                try {
                    IBinder window = (IBinder) Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class).invoke(null, "window");
                    String windows = "android.view.IWindowManager";
                    Class.forName(windows).getMethod("setDisplayImePolicy", int.class, int.class)
                            .invoke(proxy(windows, window), id, IME_HIDE);
                } catch (Exception failure) {
                    // Fail closed if we cannot apply the background-run IME policy.
                    throw new IllegalStateException("VD IME policy failed", failure);
                }
            }
            return id;
        }

        void release() throws Exception {
            managerType.getMethod("releaseVirtualDisplay", callbackType).invoke(manager, callback);
        }
    }
}
