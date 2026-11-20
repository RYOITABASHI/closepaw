package ai.closepaw.spike.vd;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import java.lang.reflect.InvocationTargetException;
import java.util.Timer;
import java.util.TimerTask;

/** Standalone process. All display operations run on one thread under the shell identity. */
public final class VdResidentMain {
    private final Handler worker;
    private final Timer watchdog = new Timer("closepaw-vd-watchdog", true);
    private final TimerTask startupDeadline = killTask();
    private final String secret = VdProtocol.newChallenge();
    private IBinder receiver;
    private FrameworkServices.Display display;
    private int displayId = -1;
    private boolean frameConfirmed;

    private VdResidentMain(Handler worker) {
        this.worker = worker;
        watchdog.schedule(startupDeadline, 40_000);
        watchdog.schedule(killTask(), 600_000);
    }

    public static void main(String[] args) {
        if (Process.myUid() != VdProtocol.SHELL_UID || args.length != 4
                || android.os.Build.VERSION.SDK_INT < 33
                || VdProtocol.decodeRegistration(args[1]) == null) {
            System.err.println("Invalid VD helper launch");
            kill();
            return;
        }
        Looper.prepare();
        VdResidentMain resident = new VdResidentMain(new Handler(Looper.myLooper()));
        try {
            int appUid = Integer.parseInt(args[2]);
            int appPid = Integer.parseInt(args[3]);
            if (appUid < 10_000 || appPid <= 0) {
                throw new SecurityException("Invalid VD app identity");
            }
            FrameworkServices.register(args[0], args[1], appUid,
                    resident.endpoint(appUid, appPid), resident.secret);
            Looper.loop();
        } catch (Exception | LinkageError failure) {
            System.err.println(describe(failure));
        } finally {
            kill();
        }
    }

    private IBinder endpoint(int appUid, int appPid) {
        return new VdWire.Endpoint(VdWire.COMMANDS) {
            {
                bindPeer(appUid, appPid, secret);
            }

            @Override
            protected void receive(int code, Bundle payload) {
                worker.post(() -> handle(code, payload));
            }
        };
    }

    private void handle(int code, Bundle payload) {
        try {
            switch (code) {
                case VdWire.CREATE:
                    create(payload);
                    break;
                case VdWire.FRAME:
                    if (displayId <= 0 || frameConfirmed) {
                        throw new IllegalStateException("Unexpected VD frame confirmation");
                    }
                    frameConfirmed = true;
                    startupDeadline.cancel();
                    event(VdWire.FRAME, null);
                    break;
                case VdWire.CLOSE:
                    try {
                        release();
                        event(VdWire.CLOSE, null);
                    } catch (Exception | LinkageError failure) {
                        reportFailure(failure);
                    } finally {
                        kill();
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown VD command");
            }
        } catch (Exception | LinkageError failure) {
            reportFailure(failure);
        }
    }

    private void create(Bundle payload) throws Exception {
        if (receiver != null) throw new IllegalStateException("VD already requested");
        receiver = payload.getBinder(VdWire.RECEIVER);
        if (receiver == null) throw new IllegalArgumentException("Missing VD app lease");
        receiver.linkToDeath(VdResidentMain::kill, 0);
        try (SurfaceLease lease = new SurfaceLease(payload)) {
            Surface surface = lease.surface;
            int width = payload.getInt(VdWire.WIDTH);
            int height = payload.getInt(VdWire.HEIGHT);
            int density = payload.getInt(VdWire.DENSITY);
            if (!surface.isValid() || width <= 0 || height <= 0 || density <= 0) {
                throw new IllegalArgumentException("Invalid VD surface parameters");
            }
            int flags = VdProtocol.preferredFlags(android.os.Build.VERSION.SDK_INT);
            display = new FrameworkServices.Display();
            try {
                displayId = display.create(surface, width, height, density, flags);
            } catch (InvocationTargetException rejected) {
                // The framework rejected creation itself. Release the attempted callback first.
                if (flags == VdProtocol.REQUIRED_FLAGS) throw rejected;
            }
            if (displayId <= 0 && flags != VdProtocol.REQUIRED_FLAGS) {
                release();
                flags = VdProtocol.REQUIRED_FLAGS;
                display = new FrameworkServices.Display();
                displayId = display.create(surface, width, height, density, flags);
            }
            if (displayId <= 0) throw new IllegalStateException("Framework rejected VD creation");
            Bundle result = new Bundle();
            result.putInt(VdWire.DISPLAY_ID, displayId);
            result.putInt(VdWire.FLAGS, flags);
            event(VdWire.CREATE, result);
        }
    }

    private void release() throws Exception {
        if (display != null) {
            display.release();
            display = null;
        }
        displayId = -1;
    }

    private void event(int code, Bundle payload) throws android.os.RemoteException {
        if (receiver != null) VdWire.send(receiver, VdWire.EVENTS, code, secret, payload);
    }

    private void reportFailure(Throwable failure) {
        String detail = describe(failure);
        System.err.println(detail);
        try {
            Bundle result = new Bundle();
            result.putString(VdWire.DETAIL, detail);
            event(VdWire.ERROR, result);
        } catch (android.os.RemoteException gone) {
            kill();
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return failure.getClass().getSimpleName() + ": " + cause;
    }

    private static TimerTask killTask() {
        return new TimerTask() {
            @Override public void run() { kill(); }
        };
    }

    private static void kill() {
        Process.killProcess(Process.myPid());
    }

    private static final class SurfaceLease implements AutoCloseable {
        private final Surface surface;

        SurfaceLease(Bundle payload) {
            surface = payload.getParcelable(VdWire.SURFACE, Surface.class);
            if (surface == null) throw new IllegalArgumentException("Missing VD surface");
        }

        @Override public void close() {
            surface.release();
        }
    }
}
