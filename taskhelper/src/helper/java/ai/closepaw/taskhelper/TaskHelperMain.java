package ai.closepaw.taskhelper;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

/** Standalone shell process serving task observations on one worker thread. */
public final class TaskHelperMain {
    private static final long STARTUP_TIMEOUT_MS = 40_000L;
    private static final int MIN_SDK = 33;
    private static final int MIN_APP_UID = 10_000;
    private static final Pattern STAGED_DEX =
            Pattern.compile("/data/local/tmp/closepaw-vd-[0-9a-f]{24}/helper\\.dex");

    private final Handler worker;
    private final Timer startupTimer = new Timer("closepaw-task-helper-startup", true);
    private final TimerTask startupDeadline = killTask();
    private final String secret = TaskHelperProtocol.newChallenge();
    private volatile IBinder receiver;

    private TaskHelperMain(Handler worker) {
        this.worker = worker;
        startupTimer.schedule(startupDeadline, STARTUP_TIMEOUT_MS);
    }

    public static void main(String[] args) {
        if (Process.myUid() != TaskHelperProtocol.SHELL_UID || args.length != 4
                || android.os.Build.VERSION.SDK_INT < MIN_SDK
                || TaskHelperProtocol.decodeRegistration(args[1]) == null) {
            System.err.println("Invalid task helper launch");
            kill();
            return;
        }
        Looper.prepare();
        TaskHelperMain helper = new TaskHelperMain(new Handler(Looper.myLooper()));
        try {
            int appUid = Integer.parseInt(args[2]);
            int appPid = Integer.parseInt(args[3]);
            if (appUid < MIN_APP_UID || appPid <= 0) {
                throw new SecurityException("Invalid task helper app identity");
            }
            TaskHelperFramework.register(args[0], args[1], appUid,
                    helper.endpoint(appUid, appPid), helper.secret);
            Looper.loop();
        } catch (Exception | LinkageError failure) {
            System.err.println(describe(failure));
        } finally {
            kill();
        }
    }

    private IBinder endpoint(int appUid, int appPid) {
        return new TaskHelperWire.Endpoint(TaskHelperWire.COMMANDS) {
            {
                bindPeer(appUid, appPid, secret);
            }

            @Override
            protected void receive(int code, Bundle payload) {
                if (code == TaskHelperWire.CLOSE) {
                    closeNow();
                    return;
                }
                worker.post(() -> handle(code, payload));
            }
        };
    }

    private void handle(int code, Bundle payload) {
        try {
            switch (code) {
                case TaskHelperWire.ATTACH:
                    attach(payload);
                    break;
                case TaskHelperWire.QUERY_ROOT_TASKS:
                    queryRootTasks(payload);
                    break;
                case TaskHelperWire.CLOSE:
                    closeNow();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown task helper command");
            }
        } catch (Exception | LinkageError failure) {
            Integer queryId = code == TaskHelperWire.QUERY_ROOT_TASKS
                    ? payload.getInt(TaskHelperWire.QUERY_ID) : null;
            reportFailure(failure, queryId);
        }
    }

    private void closeNow() {
        try {
            event(TaskHelperWire.CLOSE, null);
        } catch (Exception | LinkageError failure) {
            reportFailure(failure, null);
        } finally {
            kill();
        }
    }

    private void attach(Bundle payload) throws Exception {
        if (receiver != null) throw new IllegalStateException("Task helper already attached");
        IBinder lease = payload.getBinder(TaskHelperWire.RECEIVER);
        if (lease == null) throw new IllegalArgumentException("Missing task helper app lease");
        lease.linkToDeath(TaskHelperMain::kill, 0);
        receiver = lease;
        event(TaskHelperWire.ATTACH, null);
        startupTimer.cancel();
    }

    private void queryRootTasks(Bundle payload) throws Exception {
        if (receiver == null) throw new IllegalStateException("Task helper has no app lease");
        int queryId = payload.getInt(TaskHelperWire.QUERY_ID, -1);
        int displayId = payload.getInt(TaskHelperWire.DISPLAY_ID, -1);
        String rows = displayId < 0 ? null : TaskHelperFramework.Tasks.queryRootTasks(displayId);
        Bundle result = new Bundle();
        result.putInt(TaskHelperWire.QUERY_ID, queryId);
        result.putInt(TaskHelperWire.DISPLAY_ID, displayId);
        if (rows == null) {
            result.putBoolean(TaskHelperWire.QUERY_NULL, true);
        } else {
            result.putString(TaskHelperWire.ROWS, rows);
        }
        event(TaskHelperWire.ROOT_TASKS, result);
    }

    private void event(int code, Bundle payload) throws android.os.RemoteException {
        if (receiver != null) TaskHelperWire.send(receiver, TaskHelperWire.EVENTS, code, secret, payload);
    }

    private void reportFailure(Throwable failure, Integer queryId) {
        String detail = describe(failure);
        System.err.println(detail);
        try {
            Bundle result = new Bundle();
            result.putString(TaskHelperWire.DETAIL, detail);
            if (queryId != null) result.putInt(TaskHelperWire.QUERY_ID, queryId);
            event(TaskHelperWire.ERROR, result);
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
        try {
            removeStaging();
        } catch (IOException | SecurityException failure) {
            System.err.println("Task helper staging cleanup failed: " + failure.getClass().getSimpleName());
        } finally {
            Process.killProcess(Process.myPid());
        }
    }

    // The app may be gone (lease death) or unable to reconnect over ADB.
    private static void removeStaging() throws IOException {
        if (Process.myUid() != TaskHelperProtocol.SHELL_UID) return;
        String classpath = System.getenv("CLASSPATH");
        if (classpath == null || !STAGED_DEX.matcher(classpath).matches()) return;
        File dex = new File(classpath);
        File directory = dex.getParentFile();
        Files.deleteIfExists(dex.toPath());
        Files.deleteIfExists(new File(directory, "go").toPath());
        Files.deleteIfExists(new File(directory, "pid").toPath());
        Files.deleteIfExists(new File(directory, "log").toPath());
        Files.deleteIfExists(directory.toPath());
    }
}
