package androidx.car.app.model;

import android.os.RemoteException;

import androidx.car.app.IOnDoneCallback;

// Used only while compiling; YouTube Music provides the real class at runtime.
public interface IOnCheckedChangeListener {
    void onCheckedChange(boolean isChecked, IOnDoneCallback callback) throws RemoteException;
}
