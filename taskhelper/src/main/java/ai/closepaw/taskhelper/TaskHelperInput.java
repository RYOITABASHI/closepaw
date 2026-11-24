package ai.closepaw.taskhelper;

/** Primitive fields and validation shared by both ends of the input wire. */
public final class TaskHelperInput {
    public static final int TAP = 1;
    public static final int LONG_PRESS = 2;
    public static final int SWIPE = 3;
    public static final int KEY = 4;
    public static final int MOTION = 5;
    public static final long MAX_DURATION_MS = 30_000;

    public static final String ACCEPTED = "accepted";
    public static final String REFLECTION_OK = "reflectionOk";
    public static final String DOWN = "down";
    public static final String UP = "up";
    public static final String ERROR = "error";

    private TaskHelperInput() {}

    public static boolean valid(int kind, int displayId, float x, float y, float endX, float endY,
            long duration, int action, long downTime, long eventTime) {
        if (displayId <= 0 || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(endX) || !Float.isFinite(endY)
                || duration < 0 || duration > MAX_DURATION_MS) return false;
        switch (kind) {
            case TAP:
            case LONG_PRESS:
            case SWIPE:
                return true;
            case KEY:
                // BACK, HOME, APP_SWITCH and ENTER, the production system-button surface.
                return action == 4 || action == 3 || action == 187 || action == 66;
            case MOTION:
                return action >= 0 && action <= 3 && downTime >= 0 && eventTime >= downTime;
            default:
                return false;
        }
    }
}
