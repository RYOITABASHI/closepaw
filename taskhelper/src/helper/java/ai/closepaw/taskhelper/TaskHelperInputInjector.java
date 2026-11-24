package ai.closepaw.taskhelper;

import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Runs on the authenticated helper's worker, using shell's input manager identity. */
final class TaskHelperInputInjector {
    private static final int WAIT_FOR_FINISH = 2;
    private static final int SWIPE_STEPS = 20;
    private Method setDisplayId;
    private Method getDisplayId;
    private Method inject;
    private Object inputManager;
    private boolean reflectionChecked;
    private boolean reflectionOk;
    private int boundDisplayId;

    Bundle execute(Bundle request, int ownedDisplayId) {
        Bundle result = new Bundle();
        int id = request.getInt(TaskHelperWire.DISPLAY_ID, -1);
        result.putInt(TaskHelperWire.REQUEST_ID, request.getInt(TaskHelperWire.REQUEST_ID));
        result.putInt(TaskHelperWire.DISPLAY_ID, id);
        int kind = request.getInt(TaskHelperWire.OPERATION);
        float x = request.getFloat("x", Float.NaN);
        float y = request.getFloat("y", Float.NaN);
        float endX = request.getFloat("endX", Float.NaN);
        float endY = request.getFloat("endY", Float.NaN);
        long duration = request.getLong("duration", -1);
        int action = request.getInt("action", -1);
        long downTime = request.getLong("downTime", -1);
        long eventTime = request.getLong("eventTime", -1);
        long deadline = request.getLong("deadline", -1);
        long now = SystemClock.uptimeMillis();
        if (!TaskHelperInput.valid(kind, id, x, y, endX, endY, duration, action, downTime, eventTime)
                || (ownedDisplayId != -1 && ownedDisplayId != id)
                || (boundDisplayId != 0 && boundDisplayId != id)
                || (kind == TaskHelperInput.MOTION && eventTime > now)) {
            return error(result, "invalid_target_or_request");
        }
        if (deadline <= now || deadline - now > TaskHelperInput.MAX_DURATION_MS + 5_000) {
            return error(result, "timeout");
        }
        boundDisplayId = id;
        if (!checkReflection(id)) {
            result.putBoolean(TaskHelperInput.REFLECTION_OK, false);
            return error(result, "reflection_failed");
        }
        result.putBoolean(TaskHelperInput.REFLECTION_OK, true);
        try {
            ensureInputManager();
            if (kind == TaskHelperInput.MOTION) {
                boolean accepted = motion(id, downTime, eventTime, action, x, y, deadline);
                result.putBoolean(TaskHelperInput.ACCEPTED, accepted);
                return accepted ? result : error(result, "rejected");
            }
            injectGesture(result, kind, id, x, y, endX, endY, duration, action, deadline);
        } catch (Expired expired) {
            return error(result, "timeout");
        } catch (Exception | LinkageError failure) {
            System.err.println("Task helper input failed: " + failure.getClass().getSimpleName());
            return error(result, "exception");
        }
        return result;
    }

    private boolean checkReflection(int id) {
        if (!reflectionChecked) {
            reflectionChecked = true;
            MotionEvent probe = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
            try {
                setDisplayId = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                setDisplayId.setAccessible(true);
                getDisplayId = InputEvent.class.getMethod("getDisplayId");
                bind(probe, id);
                // KeyEvent has its own implementation of the hidden setter.
                bind(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), id);
                reflectionOk = true;
            } catch (Exception | LinkageError failure) {
                System.err.println("Task helper display binding self-test failed: "
                        + failure.getClass().getSimpleName());
            } finally {
                probe.recycle();
            }
        }
        return reflectionOk;
    }

    private void ensureInputManager() throws Exception {
        if (inputManager != null) return;
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        Class<?> manager = Class.forName("android.hardware.input.IInputManager");
        inject = manager.getMethod("injectInputEvent", InputEvent.class, int.class);
        inputManager = Class.forName(manager.getName() + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (inputManager == null) throw new IllegalStateException("Input manager unavailable");
    }

    private void injectGesture(Bundle result, int kind, int id, float x, float y,
            float endX, float endY, long duration, int action, long deadline) throws Exception {
        long start = SystemClock.uptimeMillis();
        boolean up = false;
        boolean downAttempted = false;
        float lastX = x;
        float lastY = y;
        try {
            checkDeadline(deadline);
            downAttempted = true;
            boolean down = kind == TaskHelperInput.KEY
                    ? key(id, start, KeyEvent.ACTION_DOWN, action, 0, deadline)
                    : motion(id, start, start, MotionEvent.ACTION_DOWN, x, y, deadline);
            result.putBoolean(TaskHelperInput.DOWN, down);
            if (!down) {
                error(result, "rejected");
                return;
            }
            if (kind == TaskHelperInput.SWIPE) {
                for (int step = 1; step <= SWIPE_STEPS; step++) {
                    sleepUntil(start + duration * step / SWIPE_STEPS, deadline);
                    float fraction = (float) step / SWIPE_STEPS;
                    lastX = x + (endX - x) * fraction;
                    lastY = y + (endY - y) * fraction;
                    if (!motion(id, start, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_MOVE, lastX, lastY, deadline)) {
                        error(result, "rejected");
                        return;
                    }
                }
            } else if (kind == TaskHelperInput.LONG_PRESS) {
                sleepUntil(SystemClock.uptimeMillis() + duration, deadline);
            }
            up = kind == TaskHelperInput.KEY
                    ? key(id, start, KeyEvent.ACTION_UP, action, 0, deadline)
                    : motion(id, start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                            lastX, lastY, deadline);
            result.putBoolean(TaskHelperInput.UP, up);
            if (!up) error(result, "rejected");
        } finally {
            if (downAttempted && !up) {
                try {
                    if (kind == TaskHelperInput.KEY) {
                        key(id, start, KeyEvent.ACTION_UP, action, KeyEvent.FLAG_CANCELED, Long.MAX_VALUE);
                    } else {
                        motion(id, start, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                                lastX, lastY, Long.MAX_VALUE);
                    }
                } catch (Exception | LinkageError failure) {
                    System.err.println("Task helper input cleanup failed: " + failure.getClass().getSimpleName());
                }
            }
        }
    }

    private static void sleepUntil(long time, long deadline) throws Expired {
        if (time >= deadline) throw new Expired();
        long remaining = time - SystemClock.uptimeMillis();
        if (remaining > 0) SystemClock.sleep(remaining);
        checkDeadline(deadline);
    }

    private boolean motion(int id, long downTime, long eventTime, int action, float x, float y,
            long deadline) throws Exception {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            return dispatch(id, event, deadline);
        } finally {
            event.recycle();
        }
    }

    private boolean key(int id, long start, int action, int keyCode, int flags, long deadline)
            throws Exception {
        return dispatch(id, new KeyEvent(start, SystemClock.uptimeMillis(), action, keyCode, 0,
                0, -1, 0, flags, InputDevice.SOURCE_KEYBOARD), deadline);
    }

    private void bind(InputEvent event, int id) throws Exception {
        setDisplayId.invoke(event, id);
        if (id <= 0 || !Integer.valueOf(id).equals(getDisplayId.invoke(event))) {
            throw new IllegalStateException("Input event was not bound to the session display");
        }
    }

    private boolean dispatch(int id, InputEvent event, long deadline) throws Exception {
        bind(event, id);
        checkDeadline(deadline);
        return Boolean.TRUE.equals(inject.invoke(inputManager, event, WAIT_FOR_FINISH));
    }

    private static void checkDeadline(long deadline) throws Expired {
        if (SystemClock.uptimeMillis() >= deadline) throw new Expired();
    }

    private static Bundle error(Bundle result, String code) {
        result.putString(TaskHelperInput.ERROR, code);
        return result;
    }

    private static final class Expired extends Exception {}
}
