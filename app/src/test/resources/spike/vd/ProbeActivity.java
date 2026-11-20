package ai.closepaw.vdprobe;

import android.app.Activity;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.UUID;

/** Disposable, local-only receiver for the spike's tap and ASCII probes. */
public final class ProbeActivity extends Activity {
    private final String instance = UUID.randomUUID().toString();
    private String run;
    private String role;
    private EditText field;
    private boolean drawn;
    private int taps;
    private int keys;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        run = getIntent().getStringExtra("vdRun");
        role = getIntent().getStringExtra("vdRole");
        if (run == null || !run.matches("[a-f0-9-]{36}") || role == null ||
                !role.matches("main|vd|owned")) {
            finishAndRemoveTask();
            return;
        }
        field = new EditText(this);
        field.setSingleLine();
        field.setHint("ClosePaw VD probe: " + role);
        field.setShowSoftInputOnFocus(false);
        if ("vd".equals(role)) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            FrameLayout layout = new FrameLayout(this);
            // Lane A samples 25 pixels in the central half. Keep the editor above that grid.
            layout.setBackgroundColor(0xff19d387);
            layout.addView(field, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            layout.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                int editorHeight = Math.min(field.getMeasuredHeight(), (bottom - top) / 8);
                if (editorHeight > 0 && field.getLayoutParams().height != editorHeight) {
                    field.getLayoutParams().height = editorHeight;
                    field.requestLayout();
                }
            });
            setContentView(layout);
        } else {
            setContentView(field);
        }
        field.requestFocus();
        field.getViewTreeObserver().addOnDrawListener(() -> drawn = true);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) taps++;
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) keys++;
        return super.dispatchKeyEvent(event);
    }

    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        if (field == null) return;
        View root = getWindow().getDecorView();
        Display display = root.getDisplay();
        int[] xy = new int[2];
        field.getLocationOnScreen(xy);
        writer.println(prefix + "VD_PHASE0 run=" + run + " role=" + role + " instance=" + instance +
                " display=" + (display == null ? -1 : display.getDisplayId()) + " task=" + getTaskId() +
                " attached=" + root.isAttachedToWindow() + " drawn=" + drawn + " focused=" + hasWindowFocus() +
                " x=" + (xy[0] + field.getWidth() / 2) + " y=" + (xy[1] + field.getHeight() / 2) +
                " taps=" + taps + " keys=" + keys + " text=" +
                field.getText().toString().replaceAll("[^a-zA-Z0-9]", "_"));
    }
}
