package ai.closepaw.taskhelper;

import android.content.AttributionSource;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;


/** Framework Binder calls made directly from the app_process shell identity. */
final class TaskHelperFramework {
    private static final String SHELL_PACKAGE = "com.android.shell";

    private TaskHelperFramework() {}

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
            throw new IllegalStateException("Task helper rendezvous provider unavailable");
        }
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Bundle request = new Bundle();
            request.putBinder(TaskHelperProtocol.HELPER, helper);
            request.putInt(TaskHelperProtocol.PID, android.os.Process.myPid());
            request.putString(TaskHelperProtocol.SESSION_SECRET, secret);
            AttributionSource source = new AttributionSource.Builder(TaskHelperProtocol.SHELL_UID)
                    .setPid(android.os.Process.myPid())
                    .setPackageName(SHELL_PACKAGE).build();
            Bundle response = (Bundle) Class.forName("android.content.IContentProvider")
                    .getMethod("call", AttributionSource.class, String.class, String.class,
                            String.class, Bundle.class)
                    .invoke(provider, source, authority, TaskHelperProtocol.REGISTER, registration, request);
            if (response == null) {
                throw new SecurityException("Task helper registration rejected");
            }
        } finally {
            am.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, reference, userId);
        }
    }

    /** API 33+ lifecycle using a callback Binder owned by this shell process. */
    static final class Display {
        private static final String MANAGER = "android.hardware.display.IDisplayManager";
        private static final String CONFIG = "android.hardware.display.VirtualDisplayConfig";
        private static final String CALLBACK = "android.hardware.display.IVirtualDisplayCallback";

        private final Class<?> managerType = Class.forName(MANAGER);
        private final Object manager = service(MANAGER, "display");
        private final Class<?> callbackType = Class.forName(CALLBACK);
        final IBinder token = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws android.os.RemoteException {
                if (code >= FIRST_CALL_TRANSACTION && code <= FIRST_CALL_TRANSACTION + 2) {
                    data.enforceInterface(CALLBACK);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        private final Object callback = proxy(CALLBACK, token);
        int id = -1;
        boolean imeAtCreation;

        Display() throws Exception {}

        void create(Surface surface, int width, int height, int density, int flags,
                    int imePolicy, boolean skipImeBuilder) throws Exception {
            Class<?> builderType = Class.forName(CONFIG + "$Builder");
            Object builder = builderType.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("closepaw_agent_display", width, height, density);
            builderType.getMethod("setSurface", Surface.class).invoke(builder, surface);
            builderType.getMethod("setFlags", int.class).invoke(builder, flags);
            if (!skipImeBuilder) {
                try {
                    builderType.getMethod("setDisplayImePolicy", int.class).invoke(builder, imePolicy);
                    imeAtCreation = true;
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // The app can request the standalone WindowManager path (Q7).
                }
            }
            Object config = builderType.getMethod("build").invoke(builder);
            id = (int) managerType.getMethod("createVirtualDisplay", Class.forName(CONFIG),
                    callbackType, Class.forName("android.media.projection.IMediaProjection"), String.class)
                    .invoke(manager, config, callback, null, SHELL_PACKAGE);
        }

        void setSurface(Surface surface) throws Exception {
            managerType.getMethod("setVirtualDisplaySurface", callbackType, Surface.class)
                    .invoke(manager, callback, surface);
        }

        void release() throws Exception {
            managerType.getMethod("releaseVirtualDisplay", callbackType).invoke(manager, callback);
        }

        // Q7: standalone helper call is device-unproven, including viewer policy changes.
        void setImePolicy(int policy) throws Exception {
            String windows = "android.view.IWindowManager";
            Class.forName(windows).getMethod("setDisplayImePolicy", int.class, int.class)
                    .invoke(service(windows, "window"), id, policy);
        }

        private static Object service(String descriptor, String name) throws Exception {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, name);
            return proxy(descriptor, binder);
        }
    }

    static final class Tasks {
        private static final String ACTIVITY_TASK_MANAGER = "android.app.IActivityTaskManager";
        /** WindowConfiguration.WINDOWING_MODE_PINNED (hidden). */
        private static final int WINDOWING_MODE_PINNED = 2;

        private Tasks() {}

        static String queryRootTasks(int displayId) {
            try {
                Object manager = manager();
                Object result = manager.getClass()
                        .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                        .invoke(manager, displayId);
                if (result == null) return null;
                java.util.List<String> rows = new java.util.ArrayList<>();
                for (Object info : (java.util.List<?>) result) {
                    if (info == null) return null;
                    rows.add(encodeRow(info));
                }
                return TaskHelperProtocol.encodeRootTaskRows(rows);
            } catch (Exception | LinkageError failure) {
                return null;
            }
        }

        private static Object manager() throws Exception {
            IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "activity_task");
            return proxy(ACTIVITY_TASK_MANAGER, service);
        }

        @SuppressWarnings("deprecation")
        static boolean write(int code, Bundle payload) throws Exception {
            Object manager = manager();
            Class<?> api = Class.forName(ACTIVITY_TASK_MANAGER);
            Object optionsValue = payload.get(TaskHelperWire.OPTIONS);
            if (optionsValue != null && !(optionsValue instanceof Bundle)) {
                throw new IllegalArgumentException("Invalid activity options");
            }
            Bundle options = (Bundle) optionsValue;
            if (code == TaskHelperWire.START_ACTIVITY_AS_USER) {
                Object intent = payload.get(TaskHelperWire.INTENT);
                if (!(intent instanceof Intent)) throw new IllegalArgumentException("Missing intent");
                return startSucceeded(StartActivityAsUserMethod.resolve(api).invoke(manager,
                        null, SHELL_PACKAGE, null, intent, null, null, null, 0, 0, null,
                        options, -2)); // UserHandle.USER_CURRENT
            }
            int taskId = requireId(payload, TaskHelperWire.TASK_ID);
            switch (code) {
                case TaskHelperWire.REMOVE_TASK:
                    return (Boolean) api.getMethod("removeTask", int.class).invoke(manager, taskId);
                case TaskHelperWire.MOVE_ROOT_TASK_TO_DISPLAY:
                    api.getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(manager,
                            taskId, requireId(payload, TaskHelperWire.DISPLAY_ID));
                    return true;
                case TaskHelperWire.START_ACTIVITY_FROM_RECENTS:
                    return startSucceeded(api.getMethod("startActivityFromRecents", int.class,
                            Bundle.class).invoke(manager, taskId, options));
                case TaskHelperWire.MOVE_TASK_TO_FRONT:
                    api.getMethod("moveTaskToFront", Class.forName("android.app.IApplicationThread"),
                            String.class, int.class, int.class, Bundle.class)
                            .invoke(manager, null, SHELL_PACKAGE, taskId, 0, null);
                    return true;
                default:
                    throw new IllegalArgumentException("Unknown task write");
            }
        }

        private static boolean startSucceeded(Object result) {
            int code = (Integer) result;
            return code >= 0 && code < 100;
        }

        private static int requireId(Bundle payload, String key) {
            int value = payload.getInt(key, -1);
            if (value < 0) throw new IllegalArgumentException("Invalid " + key);
            return value;
        }

        private static String encodeRow(Object info) throws Exception {
            int taskId = info.getClass().getField("taskId").getInt(info);
            String top = packageOf(field(info, "topActivity"));
            String base = packageOf(field(info, "baseActivity"));
            java.util.List<String> packages = new java.util.ArrayList<>();
            for (String name : new String[]{"baseActivity", "topActivity", "realActivity", "origActivity"}) {
                String pkg = packageOf(field(info, name));
                if (pkg != null && !packages.contains(pkg)) packages.add(pkg);
            }
            Object intent = field(info, "baseIntent");
            if (intent != null) {
                Object component = intent.getClass().getMethod("getComponent").invoke(intent);
                String pkg = packageOf(component);
                if (pkg != null && !packages.contains(pkg)) packages.add(pkg);
            }
            boolean visible = info.getClass().getField("visible").getBoolean(info);
            Object config = field(info, "configuration");
            Object windowConfig = config == null ? null : field(config, "windowConfiguration");
            Integer mode = windowConfig == null ? null
                    : (Integer) windowConfig.getClass().getMethod("getWindowingMode").invoke(windowConfig);
            boolean pinned = mode != null && mode == WINDOWING_MODE_PINNED;
            return TaskHelperProtocol.encodeRootTaskRow(taskId, top, base, packages, visible, pinned);
        }

        // A successful read may return null. Reflection failures propagate to the whole query.
        private static Object field(Object target, String name) throws ReflectiveOperationException {
            return target.getClass().getField(name).get(target);
        }

        private static String packageOf(Object componentName) throws Exception {
            if (componentName == null) return null;
            return (String) componentName.getClass().getMethod("getPackageName").invoke(componentName);
        }
    }
}
