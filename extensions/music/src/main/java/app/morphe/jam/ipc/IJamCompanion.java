/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\\Users\\BosoYolo\\AppData\\Local\\Android\\Sdk\\build-tools\\37.0.0\\aidl.exe -ID:\\Projects\\Android\\morphe-jam\\jam-shared\\aidl -oD:\\Projects\\Android\\morphe-jam\\jam-shared\\java D:\\Projects\\Android\\morphe-jam\\jam-shared\\aidl\\app\\morphe\\jam\\ipc\\IJamCompanion.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package app.morphe.jam.ipc;

public interface IJamCompanion extends android.os.IInterface {
  /** Default implementation for IJamCompanion. */
  public static class Default implements app.morphe.jam.ipc.IJamCompanion {

    @Override
    public java.lang.String call(
      java.lang.String capability,
      java.lang.String request
    ) throws android.os.RemoteException {
      return null;
    }

    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }

  /** Local-side IPC implementation stub class. */
  public abstract static class Stub
    extends android.os.Binder
    implements app.morphe.jam.ipc.IJamCompanion
  {

    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub() {
      this.attachInterface(this, DESCRIPTOR);
    }

    /**
     * Cast an IBinder object into an app.morphe.jam.ipc.IJamCompanion interface,
     * generating a proxy if needed.
     */
    public static app.morphe.jam.ipc.IJamCompanion asInterface(
      android.os.IBinder obj
    ) {
      if (obj == null) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (iin != null && iin instanceof app.morphe.jam.ipc.IJamCompanion) {
        return (app.morphe.jam.ipc.IJamCompanion) iin;
      }
      return new app.morphe.jam.ipc.IJamCompanion.Stub.Proxy(obj);
    }

    @Override
    public android.os.IBinder asBinder() {
      return this;
    }

    @Override
    public boolean onTransact(
      int code,
      android.os.Parcel data,
      android.os.Parcel reply,
      int flags
    ) throws android.os.RemoteException {
      if (
        code >= android.os.IBinder.FIRST_CALL_TRANSACTION &&
        code <= android.os.IBinder.LAST_CALL_TRANSACTION
      ) {
        data.enforceInterface(DESCRIPTOR);
      }
      switch (code) {
        case TRANSACTION_call: {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _result = this.call(_arg0, _arg1);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        default: {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }

    private static final class Proxy
      implements app.morphe.jam.ipc.IJamCompanion
    {

      private android.os.IBinder mRemote;

      Proxy(android.os.IBinder remote) {
        mRemote = remote;
      }

      @Override
      public android.os.IBinder asBinder() {
        return mRemote;
      }

      public final java.lang.String getInterfaceDescriptor() {
        return DESCRIPTOR;
      }

      @Override
      public java.lang.String call(
        java.lang.String capability,
        java.lang.String request
      ) throws android.os.RemoteException {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(capability);
          _data.writeString(request);
          boolean _status = mRemote.transact(
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
      android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
  }

  /** @hide */
  public static final java.lang.String DESCRIPTOR =
    "app.morphe.jam.ipc.IJamCompanion";
  public java.lang.String call(
    java.lang.String capability,
    java.lang.String request
  ) throws android.os.RemoteException;
}
