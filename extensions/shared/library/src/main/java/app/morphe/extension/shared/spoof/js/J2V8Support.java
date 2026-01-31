package app.morphe.extension.shared.spoof.js;

import java.io.File;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * In general, there is no error in 'System.loadLibrary()' because the patched app contains the J2V8 native library (libj2v8.so).
 * If the app is installed via mount, it cannot find the native library, causing a runtime exception and a fatal crash.
 * Therefore, before loading the library, the app first checks whether the native library exists.
 * If the native library does not exist, the JavaScript client is skipped.
 */
public class J2V8Support {
    private static final boolean DEVICE_SUPPORT_J2V8;

    static {
        boolean j2v8Support = false;
        try {
            String libraryDir = Utils.getContext()
                    .getApplicationContext()
                    .getApplicationInfo()
                    .nativeLibraryDir;
            File j2v8File = new File(libraryDir + "/libj2v8.so");
            j2v8Support = j2v8File.exists();
        } catch (Exception ex) {
            Logger.printException(() -> "J2V8 native library not found", ex);
        }
        DEVICE_SUPPORT_J2V8 = j2v8Support;

        Logger.printDebug(() -> DEVICE_SUPPORT_J2V8
                ? "Device supports J2V8"
                : "Device does not support J2V8");
    }

    public static boolean supportJ2V8() {
        return DEVICE_SUPPORT_J2V8;
    }
}
