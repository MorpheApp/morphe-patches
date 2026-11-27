/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Finds Cast devices on the local network with DNS service discovery.
 */
final class CastDeviceFinder {

    record CastDevice(String name, InetAddress host, int port) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final String SERVICE_TYPE = "_googlecast._tcp";
    /**
     * TXT record key of the name the user gave the device.
     */
    private static final String FRIENDLY_NAME_KEY = "fn";
    /**
     * TXT record key of the capabilities of the device, a bit mask in decimal.
     */
    private static final String CAPABILITIES_KEY = "ca";
    private static final int CAPABILITY_VIDEO_OUT = 1;
    private static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int ANDROID_17 = 37;
    private static final int LOCAL_NETWORK_REQUEST_CODE = 0x4341;
    /**
     * How long the user has to answer the permission request.
     */
    private static final long PERMISSION_ANSWER_MILLISECONDS = 60_000;
    private static final long PERMISSION_CHECK_MILLISECONDS = 500;

    /**
     * Time to resolve a device on Android 14 and later, after which the next device is resolved.
     * A device that was switched off can stay in the discovery results without ever resolving.
     */
    private static final long RESOLVE_TIMEOUT_MILLISECONDS = 5_000;

    /**
     * Android 17 asks the user before an app may find and reach devices of the local network. Without the
     * permission, finding devices fails, or for apps that target Android 17 makes the system ask for a single
     * device instead.
     *
     * @return If the app may find and reach the devices.
     */
    static boolean hasLocalNetworkPermission(Context context) {
        return Build.VERSION.SDK_INT < ANDROID_17
                || context.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks the user for the local network permission.
     *
     * @param onGranted Runs on the main thread once the user allows it. The result goes to the activity of the
     *                  app, so the permission is checked until the user answers.
     */
    static void requestLocalNetworkPermission(Runnable onGranted) {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            Logger.printException(() -> "No activity to request the local network permission");
            return;
        }
        activity.requestPermissions(new String[]{ACCESS_LOCAL_NETWORK}, LOCAL_NETWORK_REQUEST_CODE);

        Context context = activity.getApplicationContext();
        final long deadline = System.currentTimeMillis() + PERMISSION_ANSWER_MILLISECONDS;
        Utils.runOnMainThreadDelayed(new Runnable() {
            @Override
            public void run() {
                if (hasLocalNetworkPermission(context)) {
                    onGranted.run();
                } else if (System.currentTimeMillis() < deadline) {
                    Utils.runOnMainThreadDelayed(this, PERMISSION_CHECK_MILLISECONDS);
                }
            }
        }, PERMISSION_CHECK_MILLISECONDS);
    }

    private final NsdManager manager;
    private final Consumer<CastDevice> onFound;
    /**
     * Services waiting to be resolved. Older Android versions resolve one service at a time.
     */
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving;
    private boolean stopped;
    /**
     * If the discovery started, or is starting. The system removes the listener when the discovery fails to
     * start, and stopping it then throws.
     */
    private boolean discovering;
    /**
     * Cancels the resolve that is running, on Android 14 and later.
     */
    @Nullable
    private Runnable cancelResolve;

    private final NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
        @Override
        public void onServiceFound(NsdServiceInfo service) {
            Utils.runOnMainThread(() -> {
                pending.add(service);
                resolveNext();
            });
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Logger.printException(() -> "Cast device discovery failed: " + errorCode);
            Utils.runOnMainThread(() -> discovering = false);
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public void onServiceLost(NsdServiceInfo service) {
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }
    };

    /**
     * @param onFound Called on the main thread for each device found.
     */
    CastDeviceFinder(Context context, Consumer<CastDevice> onFound) {
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.onFound = onFound;
    }

    void start() {
        Utils.verifyOnMainThread();
        discovering = true;
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    void stop() {
        Utils.verifyOnMainThread();
        if (stopped) {
            return;
        }
        stopped = true;
        pending.clear();
        if (discovering) {
            discovering = false;
            try {
                manager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ex) {
                // The discovery failed to start, and its failure was not handled yet.
                Logger.printDebug(() -> "Cast device discovery was not running");
            }
        }
        if (cancelResolve != null) {
            cancelResolve.run();
        }
    }

    private void resolveNext() {
        if (resolving || stopped || pending.isEmpty()) {
            return;
        }
        resolving = true;
        NsdServiceInfo service = pending.poll();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveWithCallback(service);
        } else {
            resolveLegacy(service);
        }
    }

    /**
     * Resolves with the callback that replaces {@link NsdManager#resolveService} on Android 14 and later.
     * The callback is removed after the first answer, or when the device does not answer in time.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void resolveWithCallback(NsdServiceInfo service) {
        var callback = new NsdManager.ServiceInfoCallback() {
            /**
             * If the callback is removed. Callbacks already posted to the main thread can still run.
             */
            private boolean done;

            @Override
            public void onServiceUpdated(NsdServiceInfo resolved) {
                List<InetAddress> addresses = resolved.getHostAddresses();
                if (done || addresses.isEmpty()) {
                    return;
                }
                unregister();
                // An IPv4 address, which every Cast device answers on.
                InetAddress host = addresses.get(0);
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address) {
                        host = address;
                        break;
                    }
                }
                onResolved(resolved, host);
            }

            @Override
            public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                if (done) {
                    return;
                }
                Logger.printDebug(() -> "Could not resolve Cast device: " + service.getServiceName()
                        + " error: " + errorCode);
                // The system did not register the callback, so it is not unregistered.
                done = true;
                cancelResolve = null;
                resolveNextAfterFailure();
            }

            @Override
            public void onServiceLost() {
                if (!done) {
                    unregister();
                    resolveNextAfterFailure();
                }
            }

            @Override
            public void onServiceInfoCallbackUnregistered() {
            }

            void onTimeout() {
                if (!done) {
                    Logger.printDebug(() -> "Cast device did not resolve in time: " + service.getServiceName());
                    unregister();
                    resolveNextAfterFailure();
                }
            }

            void unregister() {
                if (done) {
                    return;
                }
                done = true;
                cancelResolve = null;
                try {
                    manager.unregisterServiceInfoCallback(this);
                } catch (IllegalArgumentException ex) {
                    Logger.printDebug(() -> "Cast device resolve callback was not registered");
                }
            }
        };
        cancelResolve = callback::unregister;
        manager.registerServiceInfoCallback(service, Utils::runOnMainThread, callback);
        Utils.runOnMainThreadDelayed(callback::onTimeout, RESOLVE_TIMEOUT_MILLISECONDS);
    }

    private void resolveNextAfterFailure() {
        resolving = false;
        resolveNext();
    }

    @SuppressWarnings("deprecation")
    private void resolveLegacy(NsdServiceInfo service) {
        manager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onServiceResolved(NsdServiceInfo resolved) {
                Utils.runOnMainThread(() -> onResolved(resolved, resolved.getHost()));
            }

            @Override
            public void onResolveFailed(NsdServiceInfo failed, int errorCode) {
                Logger.printDebug(() -> "Could not resolve Cast device: " + failed.getServiceName()
                        + " error: " + errorCode);
                Utils.runOnMainThread(CastDeviceFinder.this::resolveNextAfterFailure);
            }
        });
    }

    /**
     * Called on the main thread.
     */
    private void onResolved(NsdServiceInfo service, @Nullable InetAddress host) {
        resolving = false;
        if (!stopped && host != null && hasVideoOutput(service)) {
            onFound.accept(new CastDevice(getName(service), host, service.getPort()));
        }
        resolveNext();
    }

    /**
     * @return If the device shows video. Speakers and speaker groups do not. A device without the
     *         capabilities in its record is kept.
     */
    private static boolean hasVideoOutput(NsdServiceInfo service) {
        @Nullable byte[] capabilities = service.getAttributes().get(CAPABILITIES_KEY);
        if (capabilities == null || capabilities.length == 0) {
            return true;
        }
        try {
            return (Integer.parseInt(new String(capabilities, StandardCharsets.UTF_8)) & CAPABILITY_VIDEO_OUT) != 0;
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Cast device capabilities not a number: " + service.getServiceName());
            return true;
        }
    }

    private static String getName(NsdServiceInfo service) {
        Map<String, byte[]> attributes = service.getAttributes();
        @Nullable byte[] name = attributes.get(FRIENDLY_NAME_KEY);
        return name != null && name.length > 0
                ? new String(name, StandardCharsets.UTF_8)
                : service.getServiceName();
    }
}
