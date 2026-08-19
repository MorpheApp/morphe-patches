/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2510
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests;

import org.chromium.net.CronetEngine;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.proxy.CronetFallbackHttpURLConnection;
import app.morphe.extension.shared.requests.proxy.CronetHttpURLConnection;
import app.morphe.extension.shared.requests.proxy.DeferredHttpURLConnection;
import app.morphe.extension.shared.requests.proxy.PlaintextHttpURLConnection;

public final class CronetTransport {
    private static final String JAVA_CRONET_ENGINE_VERSION_PREFIX = "CronetHttpURLConnection/";
    private static final long ENGINE_WAIT_TIMEOUT_MILLISECONDS = 5000;
    private static final Object ENGINE_LOCK = new Object();
    private static final ArrayList<EngineRegistration> ENGINE_REGISTRATIONS = new ArrayList<>();

    private static WeakReference<CronetEngine> mainEngineReference = new WeakReference<>(null);

    private CronetTransport() {
    }

    /**
     * Registers an engine returned by {@code CronetEngine.Builder.buildExperimental()}.
     */
    public static boolean registerCronetEngine(CronetEngine engine, boolean proxyConfigured) {
        if (!isUsableEngine(engine)) {
            return false;
        }

        boolean registrationChanged = false;
        synchronized (ENGINE_LOCK) {
            removeReleasedEnginesLocked();

            EngineRegistration registration = findRegistrationLocked(engine);
            if (registration == null) {
                ENGINE_REGISTRATIONS.add(new EngineRegistration(engine, proxyConfigured));
                registrationChanged = true;
            } else if (proxyConfigured && !registration.proxyConfigured) {
                registration.proxyConfigured = true;
                registrationChanged = true;
            }

            ENGINE_LOCK.notifyAll();
        }

        if (registrationChanged) {
            Logger.printDebug(() -> "Registered Cronet engine, proxy configured: "
                    + proxyConfigured);
        }
        return true;
    }

    /**
     * Gives the application-wide engine priority over other registered engines.
     */
    public static boolean setMainCronetEngine(CronetEngine engine) {
        if (!isUsableEngine(engine)) {
            return false;
        }

        synchronized (ENGINE_LOCK) {
            removeReleasedEnginesLocked();
            if (findRegistrationLocked(engine) == null) {
                ENGINE_REGISTRATIONS.add(new EngineRegistration(engine, false));
            }
            mainEngineReference = new WeakReference<>(engine);
            ENGINE_LOCK.notifyAll();
        }
        return true;
    }

    /**
     * Opens a connection without falling back to the platform HTTPS stack.
     *
     * @param requireProxy Whether the preferred engine must have proxy options.
     * @param allowDirectFallback Whether an unproxied engine, or platform HTTP, may be used.
     */
    public static HttpURLConnection openConnection(
            URL url,
            boolean requireProxy,
            boolean allowDirectFallback
    ) throws IOException {
        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        List<CronetEngine> engines = getEligibleEngines(
                requireProxy,
                allowDirectFallback
        );
        HttpURLConnection connection = createCronetConnectionChain(url, engines);
        if (connection != null) {
            return addDirectHttpFallback(url, connection, allowDirectFallback);
        }

        if (!requireProxy && allowDirectFallback && !https) {
            return openDirectHttpConnection(url);
        }

        return new DeferredHttpURLConnection(
                url,
                connectTimeout -> openConnectionAfterWaiting(
                        url,
                        requireProxy,
                        allowDirectFallback,
                        connectTimeout
                )
        );
    }

    private static HttpURLConnection openConnectionAfterWaiting(
            URL url,
            boolean requireProxy,
            boolean allowDirectFallback,
            int connectTimeoutMilliseconds
    ) throws IOException {
        long waitTimeoutMilliseconds = connectTimeoutMilliseconds > 0
                ? Math.min(connectTimeoutMilliseconds, ENGINE_WAIT_TIMEOUT_MILLISECONDS)
                : ENGINE_WAIT_TIMEOUT_MILLISECONDS;
        List<CronetEngine> engines = awaitEngines(
                requireProxy,
                allowDirectFallback,
                waitTimeoutMilliseconds
        );

        HttpURLConnection connection = createCronetConnectionChain(url, engines);
        if (connection != null) {
            return addDirectHttpFallback(url, connection, allowDirectFallback);
        }

        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        if (allowDirectFallback && !https) {
            return openDirectHttpConnection(url);
        }

        if (connectTimeoutMilliseconds > 0
                && waitTimeoutMilliseconds == connectTimeoutMilliseconds
                && !Utils.isCurrentlyOnMainThread()) {
            throw new SocketTimeoutException("Timed out waiting for a Cronet engine");
        }

        throw new IOException(
                https
                        ? "Cronet engine is not available; platform HTTPS fallback is disabled"
                        : "Cronet engine is not available"
        );
    }

    private static HttpURLConnection addDirectHttpFallback(
            URL url,
            HttpURLConnection connection,
            boolean allowDirectFallback
    ) throws IOException {
        if (!allowDirectFallback || "https".equalsIgnoreCase(url.getProtocol())) {
            return connection;
        }

        return new CronetFallbackHttpURLConnection(
                connection,
                openDirectHttpConnection(url),
                ex -> Logger.printInfo(
                        () -> "Cronet engine is unavailable; using direct HTTP fallback",
                        ex
                )
        );
    }

    private static List<CronetEngine> awaitEngines(
            boolean requireProxy,
            boolean allowDirectFallback,
            long waitTimeoutMilliseconds
    ) throws IOException {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(waitTimeoutMilliseconds);

        synchronized (ENGINE_LOCK) {
            while (true) {
                List<CronetEngine> engines = getEligibleEnginesLocked(
                        requireProxy,
                        allowDirectFallback
                );
                if (!engines.isEmpty() || Utils.isCurrentlyOnMainThread()) {
                    return engines;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return engines;
                }

                try {
                    long waitMilliseconds = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                    int waitNanoseconds = (int) (remainingNanos
                            - TimeUnit.MILLISECONDS.toNanos(waitMilliseconds));
                    ENGINE_LOCK.wait(waitMilliseconds, waitNanoseconds);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for a Cronet engine", ex);
                }
            }
        }
    }

    private static List<CronetEngine> getEligibleEngines(
            boolean requireProxy,
            boolean allowDirectFallback
    ) {
        synchronized (ENGINE_LOCK) {
            return getEligibleEnginesLocked(requireProxy, allowDirectFallback);
        }
    }

    private static List<CronetEngine> getEligibleEnginesLocked(
            boolean requireProxy,
            boolean allowDirectFallback
    ) {
        removeReleasedEnginesLocked();

        ArrayList<CronetEngine> engines = new ArrayList<>();
        appendEnginesLocked(engines, requireProxy);
        if (requireProxy && allowDirectFallback) {
            appendEnginesLocked(engines, false);
        }
        return engines;
    }

    private static void appendEnginesLocked(
            ArrayList<CronetEngine> engines,
            boolean proxyConfigured
    ) {
        CronetEngine mainEngine = mainEngineReference.get();
        EngineRegistration mainRegistration = findRegistrationLocked(mainEngine);
        if (mainRegistration != null && mainRegistration.proxyConfigured == proxyConfigured) {
            engines.add(mainEngine);
        }

        for (int i = ENGINE_REGISTRATIONS.size() - 1; i >= 0; i--) {
            EngineRegistration registration = ENGINE_REGISTRATIONS.get(i);
            CronetEngine engine = registration.engineReference.get();
            if (engine != null
                    && engine != mainEngine
                    && registration.proxyConfigured == proxyConfigured) {
                engines.add(engine);
            }
        }
    }

    private static HttpURLConnection createCronetConnectionChain(
            URL url,
            List<CronetEngine> engines
    ) throws IOException {
        HttpURLConnection connection = null;
        IOException openFailure = null;
        for (int i = engines.size() - 1; i >= 0; i--) {
            CronetEngine engine = engines.get(i);
            try {
                HttpURLConnection engineConnection = new CronetHttpURLConnection(
                        (HttpURLConnection) engine.openConnection(url),
                        ex -> discardCronetEngine(engine, ex)
                );
                connection = connection == null
                        ? engineConnection
                        : new CronetFallbackHttpURLConnection(
                                engineConnection,
                                connection,
                                ex -> discardCronetEngine(engine, ex)
                        );
            } catch (IllegalStateException ex) {
                discardCronetEngine(engine, ex);
            } catch (IOException ex) {
                if (openFailure != null) {
                    ex.addSuppressed(openFailure);
                }
                openFailure = ex;
            }
        }
        if (connection == null && openFailure != null) {
            throw openFailure;
        }
        return connection;
    }

    private static boolean isUsableEngine(CronetEngine engine) {
        if (engine == null) {
            return false;
        }

        try {
            String version = engine.getVersionString();
            if (version != null && version.startsWith(JAVA_CRONET_ENGINE_VERSION_PREFIX)) {
                Logger.printInfo(() -> "Ignoring Java fallback Cronet engine for extension requests");
                return false;
            }
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Cronet engine validation failure", ex);
            return false;
        }
    }

    private static void discardCronetEngine(CronetEngine engine, IllegalStateException ex) {
        boolean removed = false;
        synchronized (ENGINE_LOCK) {
            for (int i = ENGINE_REGISTRATIONS.size() - 1; i >= 0; i--) {
                if (ENGINE_REGISTRATIONS.get(i).engineReference.get() == engine) {
                    ENGINE_REGISTRATIONS.remove(i);
                    removed = true;
                }
            }
            if (mainEngineReference.get() == engine) {
                mainEngineReference = new WeakReference<>(null);
            }
        }

        if (removed) {
            Logger.printInfo(() -> "Cronet engine is no longer available", ex);
        }
    }

    private static EngineRegistration findRegistrationLocked(CronetEngine engine) {
        if (engine == null) {
            return null;
        }

        for (EngineRegistration registration : ENGINE_REGISTRATIONS) {
            if (registration.engineReference.get() == engine) {
                return registration;
            }
        }
        return null;
    }

    private static void removeReleasedEnginesLocked() {
        for (int i = ENGINE_REGISTRATIONS.size() - 1; i >= 0; i--) {
            if (ENGINE_REGISTRATIONS.get(i).engineReference.get() == null) {
                ENGINE_REGISTRATIONS.remove(i);
            }
        }
        if (mainEngineReference.get() == null) {
            mainEngineReference = new WeakReference<>(null);
        }
    }

    private static HttpURLConnection openDirectHttpConnection(URL url) throws IOException {
        if (!"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Platform connection is restricted to plaintext HTTP");
        }

        return new PlaintextHttpURLConnection(
                (HttpURLConnection) url.openConnection()
        );
    }

    private static final class EngineRegistration {
        private final WeakReference<CronetEngine> engineReference;
        private boolean proxyConfigured;

        private EngineRegistration(CronetEngine engine, boolean proxyConfigured) {
            this.engineReference = new WeakReference<>(engine);
            this.proxyConfigured = proxyConfigured;
        }
    }
}