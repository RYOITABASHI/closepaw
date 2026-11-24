package ai.closepaw.taskhelper;

import android.os.*;
import android.view.*;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Throwaway Inc.4 Q2 probe, executed only inside the shell helper. */
final class SpikeTap {
    static Bundle inject(Bundle args) {
        Bundle result = new Bundle();
        result.putBoolean("reflectionOk", false);
        List<String> errors = new ArrayList<>();
        try {
            TaskHelperProtocol.TapRequest request =
                    TaskHelperProtocol.parseTapRequest(args.getString("tapRequest"));
            Method setDisplay = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplay.setAccessible(true);
            Object binder = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class).invoke(null, "input");
            Class<?> manager = Class.forName("android.hardware.input.IInputManager");
            Object input = Class.forName("android.hardware.input.IInputManager$Stub")
                    .getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
            Method inject = manager.getMethod("injectInputEvent", InputEvent.class, int.class);
            long downTime = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                boolean isDown = action == MotionEvent.ACTION_DOWN;
                MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        action, request.x, request.y, 0);
                try {
                    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    setDisplay.invoke(event, request.displayId);
                    boolean ok = (Boolean) inject.invoke(input, event, 2); // WAIT_FOR_FINISH
                    if (isDown) result.putBoolean("reflectionOk", true);
                    result.putBoolean(isDown ? "down" : "up", ok);
                    if (!ok) errors.add("inject rejected: " + (isDown ? "DOWN" : "UP") + " returned false");
                } finally { event.recycle(); }
            }
        } catch (Exception | LinkageError failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            errors.add(failure + ": " + cause);
        }
        if (!errors.isEmpty()) result.putString("error", String.join("\n", errors));
        result.putString("tapResult", TaskHelperProtocol.encodeTapResult(result.getBoolean("reflectionOk"),
                result.containsKey("down") ? result.getBoolean("down") : null,
                result.containsKey("up") ? result.getBoolean("up") : null, result.getString("error")));
        Log.i("SpikeInject", "InputEvent.setDisplayId -> IInputManager.injectInputEvent WAIT_FOR_FINISH " + result);
        return result;
    }
}
