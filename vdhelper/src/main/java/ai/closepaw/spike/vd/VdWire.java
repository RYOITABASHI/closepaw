package ai.closepaw.spike.vd;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Fixed v2 Binder messages. Separate descriptors for helper commands and app events. */
public final class VdWire {
    public static final String COMMANDS = "ai.closepaw.spike.vd.commands.v2";
    public static final String EVENTS = "ai.closepaw.spike.vd.events.v2";

    public static final int CREATE = IBinder.FIRST_CALL_TRANSACTION;
    public static final int FRAME = CREATE + 1;
    public static final int CLOSE = CREATE + 2;
    public static final int ERROR = CREATE + 3;
    /** Command: typed root-task query (stage-2 Increment 1 experiment). */
    public static final int QUERY_ROOT_TASKS = CREATE + 4;
    /** Event: root-task query response, correlated by QUERY_ID. */
    public static final int ROOT_TASKS = CREATE + 5;

    public static final String WIDTH = "width";
    public static final String HEIGHT = "height";
    public static final String DENSITY = "density";
    public static final String SURFACE = "surface";
    public static final String RECEIVER = "receiver";
    public static final String DISPLAY_ID = "displayId";
    public static final String FLAGS = "flags";
    public static final String DETAIL = "detail";
    /** Query correlation id (int) echoed by the ROOT_TASKS event. */
    public static final String QUERY_ID = "queryId";
    /** Encoded root-task rows (VdProtocol.encodeRootTaskRows); absent when QUERY_NULL. */
    public static final String ROWS = "rows";
    /** True when the helper query failed and the answer is null (fail closed). */
    public static final String QUERY_NULL = "queryNull";

    private VdWire() {}

    public static void send(IBinder target, String descriptor, int code, String secret, Bundle payload)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeString(secret);
            data.writeBundle(payload);
            if (!target.transact(code, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("VD Binder transaction rejected");
            }
        } finally {
            data.recycle();
        }
    }

    public abstract static class Endpoint extends Binder {
        private final String descriptor;
        private volatile VdProtocol.Peer peer;

        protected Endpoint(String descriptor) {
            this.descriptor = descriptor;
        }

        protected final void bindPeer(int uid, int pid, String secret) {
            peer = new VdProtocol.Peer(uid, pid, secret);
        }

        @Override
        protected final boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code < CREATE || code > ROOT_TASKS) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(descriptor);
            VdProtocol.Peer authenticated = peer;
            if (authenticated == null
                    || !authenticated.accepts(Binder.getCallingUid(), Binder.getCallingPid(),
                            data.readString())) {
                throw new SecurityException("Unauthenticated VD Binder caller");
            }
            Bundle payload = data.readBundle(VdWire.class.getClassLoader());
            data.enforceNoDataAvail();
            receive(code, payload == null ? Bundle.EMPTY : payload);
            return true;
        }

        protected abstract void receive(int code, Bundle payload);
    }
}
