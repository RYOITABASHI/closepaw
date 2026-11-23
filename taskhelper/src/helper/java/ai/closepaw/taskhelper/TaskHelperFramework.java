package ai.closepaw.taskhelper;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;


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

    static final class Tasks {
        private static final String ACTIVITY_TASK_MANAGER = "android.app.IActivityTaskManager";
        /** WindowConfiguration.WINDOWING_MODE_PINNED (hidden). */
        private static final int WINDOWING_MODE_PINNED = 2;

        private Tasks() {}

        static String queryRootTasks(int displayId) {
            try {
                IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "activity_task");
                Object manager = proxy(ACTIVITY_TASK_MANAGER, service);
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
