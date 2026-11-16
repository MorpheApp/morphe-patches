package app.morphe.extension.shared.spoof.js;

import android.webkit.CookieManager;

import androidx.javascriptengine.JavaScriptSandbox;

import app.morphe.extension.shared.Logger;

/**
 * Checks whether the device supports the JavaScriptEngine (backed by the system WebView's V8).
 */
@SuppressWarnings("unused")
public class JavaScriptEngineSupport {
    private static volatile boolean DEVICE_SUPPORTS_JS_ENGINE;

    static {
        boolean sandBoxSupport = false;
        try {
            // Checks if the system WebView is supported.
            CookieManager.getInstance();
            // Checks if the system WebView supports the JavaScript sandbox.
            sandBoxSupport = JavaScriptSandbox.isSupported();
        } catch (Exception ex) {
            Logger.printException(() -> "JavaScriptSandbox support check failed", ex);
        }
        DEVICE_SUPPORTS_JS_ENGINE = sandBoxSupport;

        Logger.printDebug(() -> DEVICE_SUPPORTS_JS_ENGINE
                ? "Device supports JavaScriptEngine"
                : "Device does not support JavaScriptEngine");
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean supportsJavaScriptEngine() {
        return DEVICE_SUPPORTS_JS_ENGINE;
    }

    public static void setDeviceSupportsJsEngine(boolean value) {
        DEVICE_SUPPORTS_JS_ENGINE = value;
    }
}
