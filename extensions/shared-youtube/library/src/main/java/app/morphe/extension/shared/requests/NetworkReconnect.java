/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5062
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Requests that fail while the device has no connection to the internet are not failures of the server,
 * so they do not pause the requests to the server, and are sent again as soon as the device is online again
 * instead of after the time used for the failures of the server.
 */
public final class NetworkReconnect {

    /**
     * Actions that run once the device is online again, in the order they were first added.
     */
    @GuardedBy("actions")
    private static final Set<Runnable> actions = new LinkedHashSet<>();

    /**
     * Callback of the default network, registered only while actions are waiting.
     */
    @GuardedBy("actions")
    @Nullable
    private static ConnectivityManager.NetworkCallback callback;

    private NetworkReconnect() {
    }

    /**
     * Can be called on any thread.
     *
     * @return If the device has a network with a working connection to the internet,
     *         or true if it's not known.
     */
    @SuppressLint("MissingPermission")
    public static boolean isOnline() {
        try {
            Context context = Utils.getContext();
            if (context == null) {
                return true;
            }
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ex) {
            Logger.printException(() -> "isOnline failure", ex);
            return true;
        }
    }

    /**
     * Runs the action on the main thread once the device is online again, if it's offline now.
     * An action added again before it runs only once. Can be called on any thread.
     *
     * @return If the device is offline, and the action runs once it's online again.
     */
    @SuppressLint("MissingPermission")
    public static boolean runWhenOnline(Runnable action) {
        if (isOnline()) {
            return false;
        }
        synchronized (actions) {
            actions.add(action);
            if (callback != null) {
                return true;
            }
            ConnectivityManager manager = Utils.getContext().getSystemService(ConnectivityManager.class);
            ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(@NonNull Network network, NetworkCapabilities capabilities) {
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        return;
                    }
                    List<Runnable> readyActions;
                    synchronized (actions) {
                        if (callback != this) {
                            return;
                        }
                        callback = null;
                        readyActions = new ArrayList<>(actions);
                        actions.clear();
                    }
                    manager.unregisterNetworkCallback(this);
                    Logger.printDebug(() -> "Online again, running: " + readyActions.size() + " actions");
                    for (Runnable readyAction : readyActions) {
                        try {
                            readyAction.run();
                        } catch (Exception ex) {
                            Logger.printException(() -> "Online action failure", ex);
                        }
                    }
                }
            };
            try {
                // Also called at once with the current network, if it's online again meanwhile.
                manager.registerDefaultNetworkCallback(networkCallback, new Handler(Looper.getMainLooper()));
                callback = networkCallback;
            } catch (Exception ex) {
                // The requests are sent again after the time used for the failures of the server.
                Logger.printException(() -> "runWhenOnline failure", ex);
                actions.clear();
                return false;
            }
        }
        return true;
    }
}
