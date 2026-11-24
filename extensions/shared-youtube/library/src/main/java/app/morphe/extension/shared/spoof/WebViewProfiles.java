/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5208
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import android.annotation.SuppressLint;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Profiles of the WebView, each with its own cookies and storage, so each web client has its own web sign in:
 * Mobile Web uses the default profile, and Web uses a profile of its own.
 * <p>
 * Android has no API for profiles. The androidx library of the app calls them through the interfaces
 * of the support library of the WebView, and the same calls are made here: the WebView gives each interface
 * as an InvocationHandler, which runs the method of the WebView with the same name.
 * If the WebView has no profiles, Web uses the default profile, and the web clients share the web sign in.
 * <p>
 * The profiles are used on the main thread only, as the androidx library uses them.
 */
public final class WebViewProfiles {
    /**
     * Profile of the Web client.
     */
    private static final String WEB_PROFILE_NAME = "MorpheWeb";

    private static final String MULTI_PROFILE_FEATURE = "MULTI_PROFILE";

    private static final String SUPPORT_LIBRARY_GLUE_CLASS = "org.chromium.support_lib_glue.SupportLibReflectionUtil";

    private static final String BOUNDARY_INTERFACE_PACKAGE = "org.chromium.support_lib_boundary.";

    /**
     * How long a call off the main thread waits for the main thread to find the profile.
     */
    private static final long MAIN_THREAD_TIMEOUT_MILLISECONDS = 5000;

    /**
     * The support library of the WebView, or null if the profile of Web was not found yet,
     * or the WebView has no profiles. Main thread only.
     */
    @Nullable
    private static InvocationHandler webViewFactory;

    /**
     * The profile of Web, or null if it was not found yet, or the WebView has no profiles. Main thread only.
     */
    @Nullable
    private static InvocationHandler webProfile;

    /**
     * The cookies of Web: of its profile, or the default cookies if the WebView has no profiles.
     * Null until the profile is looked for.
     */
    @Nullable
    private static volatile CookieManager webCookieManager;

    private WebViewProfiles() {
    }

    /**
     * Calls a method of an interface of the support library of the WebView.
     */
    @Nullable
    private static Object call(InvocationHandler handler, String interfaceName, String methodName,
                               Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = Class.forName(BOUNDARY_INTERFACE_PACKAGE + interfaceName)
                .getMethod(methodName, parameterTypes);
        try {
            return handler.invoke(null, method, arguments);
        } catch (Exception ex) {
            throw ex;
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }

    /**
     * Looks for the profile of Web once. Main thread only.
     *
     * @return The cookies of Web.
     */
    @SuppressLint("NewApi")
    private static CookieManager findWebProfile() {
        Utils.verifyOnMainThread();
        CookieManager cookieManager = webCookieManager;
        if (cookieManager != null) {
            return cookieManager;
        }
        try {
            Class<?> glue = Class.forName(SUPPORT_LIBRARY_GLUE_CLASS, false, WebView.getWebViewClassLoader());
            InvocationHandler factory = (InvocationHandler)
                    glue.getDeclaredMethod("createWebViewProviderFactory").invoke(null);
            String[] features = factory == null ? null : (String[]) call(factory,
                    "WebViewProviderFactoryBoundaryInterface", "getSupportedFeatures", new Class<?>[0]);
            if (features != null && Arrays.asList(features).contains(MULTI_PROFILE_FEATURE)) {
                InvocationHandler store = (InvocationHandler) call(factory,
                        "WebViewProviderFactoryBoundaryInterface", "getProfileStore", new Class<?>[0]);
                InvocationHandler profile = store == null ? null : (InvocationHandler) call(store,
                        "ProfileStoreBoundaryInterface", "getOrCreateProfile",
                        new Class<?>[]{String.class}, WEB_PROFILE_NAME);
                cookieManager = profile == null ? null : (CookieManager) call(profile,
                        "ProfileBoundaryInterface", "getCookieManager", new Class<?>[0]);
                if (cookieManager != null) {
                    webViewFactory = factory;
                    webProfile = profile;
                }
            } else {
                Logger.printDebug(() -> "The WebView has no profiles, the web clients share the web sign in");
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not use the profiles of the WebView", ex);
        }
        if (cookieManager == null) {
            webViewFactory = null;
            webProfile = null;
            cookieManager = CookieManager.getInstance();
        }
        webCookieManager = cookieManager;
        return cookieManager;
    }

    /**
     * The cookies of the profile of a web client. Off the main thread, it waits for the main thread
     * the first time.
     *
     * @return The cookies, or null if the WebView is missing or broken,
     *         or the main thread could not find the profile in time.
     */
    @Nullable
    public static CookieManager getCookieManager(ClientType client) {
        try {
            if (client != ClientType.WEB) {
                return CookieManager.getInstance();
            }
            CookieManager cookieManager = webCookieManager;
            if (cookieManager != null) {
                return cookieManager;
            }
            if (Utils.isCurrentlyOnMainThread()) {
                return findWebProfile();
            }
            FutureTask<CookieManager> task = new FutureTask<>(WebViewProfiles::findWebProfile);
            Utils.runOnMainThread(task);
            return task.get(MAIN_THREAD_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            // WebView is missing or broken.
            Logger.printInfo(() -> "Could not read the cookies of " + client, ex);
            return null;
        }
    }

    /**
     * The storage of the websites of the profile of a web client. Main thread only.
     */
    public static WebStorage getWebStorage(ClientType client) {
        if (client == ClientType.WEB) {
            findWebProfile();
            InvocationHandler profile = webProfile;
            if (profile != null) {
                try {
                    WebStorage storage = (WebStorage) call(profile, "ProfileBoundaryInterface",
                            "getWebStorage", new Class<?>[0]);
                    if (storage != null) {
                        return storage;
                    }
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Could not read the storage of the profile of Web", ex);
                }
            }
        }
        return WebStorage.getInstance();
    }

    /**
     * Makes a new WebView use the profile of a web client.
     * Called right after the WebView is made, before it loads anything. Main thread only.
     */
    public static void setProfile(WebView webView, ClientType client) {
        if (client != ClientType.WEB) {
            return;
        }
        findWebProfile();
        InvocationHandler factory = webViewFactory;
        if (factory == null) {
            return;
        }
        try {
            InvocationHandler provider = (InvocationHandler) call(factory,
                    "WebViewProviderFactoryBoundaryInterface", "createWebView",
                    new Class<?>[]{WebView.class}, webView);
            if (provider != null) {
                call(provider, "WebViewProviderBoundaryInterface", "setProfile",
                        new Class<?>[]{String.class}, WEB_PROFILE_NAME);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not set the profile of Web", ex);
        }
    }
}
