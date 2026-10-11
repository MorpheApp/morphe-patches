package app.morphe.extension.music.patches.jam.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;

/** Versioned, bounded JSON commands; every call is caller- and capability-checked. */
public interface IJamBridge extends IInterface {
    /** Default implementation for IJamBridge. */
    class Default implements IJamBridge {

        @Override
        public String call(
            String capability,
            String request
        ) throws RemoteException {
            return null;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    /** Local-side IPC implementation stub class. */
    abstract class Stub
        extends Binder
        implements IJamBridge
    {

        /** Construct the stub and attach it to the interface. */
        @SuppressWarnings("this-escape")
        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        /**
         * Cast an IBinder object into an IJamBridge interface,
         * generating a proxy if needed.
         */
        @SuppressWarnings("unused")
        public static IJamBridge asInterface(
            IBinder obj
        ) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IJamBridge) {
                return (IJamBridge) iin;
            }
            return new IJamBridge.Stub.Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(
                int code,
                @NonNull Parcel data,
                Parcel reply,
                int flags
        ) throws RemoteException {
            if (
                code >= IBinder.FIRST_CALL_TRANSACTION &&
                code <= IBinder.LAST_CALL_TRANSACTION
            ) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == TRANSACTION_call) {
                String _arg0;
                _arg0 = data.readString();
                String _arg1;
                _arg1 = data.readString();
                String _result = this.call(_arg0, _arg1);
                reply.writeNoException();
                reply.writeString(_result);
            } else {
                return super.onTransact(code, data, reply, flags);
            }
            return true;
        }

        private record Proxy(IBinder mRemote)
                implements IJamBridge {

            @Override
                    public IBinder asBinder() {
                        return mRemote;
                    }

                    @SuppressWarnings({"FinalMethodInFinalClass", "unused"})
                    public final String getInterfaceDescriptor() {
                        return DESCRIPTOR;
                    }

                    @Override
                    public String call(
                            String capability,
                            String request
                    ) throws RemoteException {
                        Parcel _data = Parcel.obtain();
                        Parcel _reply = Parcel.obtain();
                        String _result;
                        try {
                            _data.writeInterfaceToken(DESCRIPTOR);
                            _data.writeString(capability);
                            _data.writeString(request);
                            @SuppressWarnings("unused") boolean _status = mRemote.transact(
                                    Stub.TRANSACTION_call,
                                    _data,
                                    _reply,
                                    0
                            );
                            _reply.readException();
                            _result = _reply.readString();
                        } finally {
                            _reply.recycle();
                            _data.recycle();
                        }
                        return _result;
                    }
                }

        static final int TRANSACTION_call =
                IBinder.FIRST_CALL_TRANSACTION;
    }

    // Must match the descriptor used by the Jam companion app, so it does not follow the package name.
    /** @hide */
    String DESCRIPTOR =
        "app.morphe.jam.ipc.IJamBridge";
    String call(
            String capability,
            String request
    ) throws RemoteException;
}
