package ai.closepaw.taskhelper;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Fixed v1 Binder messages. Separate descriptors for helper commands and app events. */
public final class TaskHelperWire {
    public static final String COMMANDS = "ai.closepaw.taskhelper.commands.v1";
    public static final String EVENTS = "ai.closepaw.taskhelper.events.v1";

    public static final int ATTACH = IBinder.FIRST_CALL_TRANSACTION;
    public static final int QUERY_ROOT_TASKS = ATTACH + 1;
    public static final int ROOT_TASKS = ATTACH + 2;
    public static final int CLOSE = ATTACH + 3;
    public static final int ERROR = ATTACH + 4;

    public static final int INJECT_TAP = ATTACH + 5;
    public static final int TAP_RESULT = ATTACH + 6;

    public static final String RECEIVER = "receiver";
    public static final String DISPLAY_ID = "displayId";
    public static final String DETAIL = "detail";
    /** Query correlation id (int) echoed by the ROOT_TASKS event. */
    public static final String QUERY_ID = "queryId";
    /** Encoded root-task rows (TaskHelperProtocol.encodeRootTaskRows); absent when QUERY_NULL. */
    public static final String ROWS = "rows";
    /** True when the helper query failed and the answer is null (fail closed). */
    public static final String QUERY_NULL = "queryNull";

    private TaskHelperWire() {}

    public static void send(IBinder target, String descriptor, int code, String secret, Bundle payload)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeString(secret);
            data.writeBundle(payload);
            if (!target.transact(code, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("Task helper Binder transaction rejected");
            }
        } finally {
            data.recycle();
        }
    }

    public abstract static class Endpoint extends Binder {
        private final String descriptor;
        private volatile TaskHelperProtocol.Peer peer;

        protected Endpoint(String descriptor) {
            this.descriptor = descriptor;
        }

        protected final void bindPeer(int uid, int pid, String secret) {
            peer = new TaskHelperProtocol.Peer(uid, pid, secret);
        }

        @Override
        protected final boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code < ATTACH || code > TAP_RESULT) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(descriptor);
            TaskHelperProtocol.Peer authenticated = peer;
            if (authenticated == null
                    || !authenticated.accepts(Binder.getCallingUid(), Binder.getCallingPid(),
                            data.readString())) {
                throw new SecurityException("Unauthenticated Task helper Binder caller");
            }
            Bundle payload = data.readBundle(TaskHelperWire.class.getClassLoader());
            data.enforceNoDataAvail();
            receive(code, payload == null ? Bundle.EMPTY : payload);
            return true;
        }

        protected abstract void receive(int code, Bundle payload);
    }
}
