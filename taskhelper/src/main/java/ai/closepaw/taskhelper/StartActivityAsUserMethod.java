package ai.closepaw.taskhelper;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;

/** The IActivityTaskManager signature used by both shell-identity launch paths. */
public final class StartActivityAsUserMethod {
    private StartActivityAsUserMethod() {}

    public static Method resolve(Class<?> api) throws ReflectiveOperationException {
        Method method = api.getMethod("startActivityAsUser",
                Class.forName("android.app.IApplicationThread"),
                String.class, String.class, Intent.class, String.class, IBinder.class,
                String.class, int.class, int.class, Class.forName("android.app.ProfilerInfo"),
                Bundle.class, int.class);
        if (method.getReturnType() != int.class) {
            throw new NoSuchMethodException("IActivityTaskManager.startActivityAsUser must return int");
        }
        return method;
    }
}
