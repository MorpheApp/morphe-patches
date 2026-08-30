/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2533
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

import android.annotation.SuppressLint;
import android.webkit.CookieManager;

import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public class PoTokenGenerator {
    @SuppressLint("SimpleDateFormat")
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private final ReentrantLock webPoTokenGenLock = new ReentrantLock();
    private String webPoTokenSessionIdentifier;
    private String webPoTokenStreamingPot;
    private PoTokenWebView webPoTokenGenerator;
    private long webPoTokenExpirationMS = -1L;

    private volatile boolean webViewBadImpl;

    private record GeneratorState(PoTokenWebView generator, String sessionIdentifier,
                                  String streamingPot, long expirationMs,
                                  boolean hasBeenRecreated) {
    }

    public PoTokenResult getWebClientPoToken(String videoId, String visitorId) throws Exception {
        if (!isWebViewSupported() || webViewBadImpl) {
            return null;
        }

        try {
            return getWebClientPoToken(videoId, visitorId, false);
        } catch (PoTokenException.BadWebViewException ex) {
            Logger.printException(() -> "Could not obtain poToken because WebView is broken", ex);
            webViewBadImpl = true;
            return null;
        }
    }

    private boolean isWebViewSupported() {
        try {
            CookieManager.getInstance();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private PoTokenResult getWebClientPoToken(String videoId, String visitorId, boolean forceRecreate) throws Exception {
        GeneratorState state;

        webPoTokenGenLock.lock();
        try {
            if (visitorId.isEmpty()) {
                throw new PoTokenException("Session identifier is null");
            }

            PoTokenWebView oldGen = webPoTokenGenerator;
            boolean shouldRecreate = oldGen == null
                                     || forceRecreate 
                                     || oldGen.isExpired();

            if (shouldRecreate) {
                webPoTokenSessionIdentifier = visitorId;
                if (oldGen != null) {
                    Utils.runOnMainThread(oldGen::close);
                }

                try {
                    // Blocks until initialized
                    webPoTokenGenerator = PoTokenWebView.newPoTokenGenerator().get();
                    
                    // Blocks until token generated
                    webPoTokenStreamingPot = webPoTokenGenerator.generatePoToken(webPoTokenSessionIdentifier).get();
                    webPoTokenExpirationMS = webPoTokenGenerator.getExpirationMs();

                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new RuntimeException(cause);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while creating PoTokenGenerator", ex);
                }
            }
            
            state = new GeneratorState(
                    webPoTokenGenerator,
                    webPoTokenSessionIdentifier,
                    webPoTokenStreamingPot,
                    webPoTokenExpirationMS,
                    shouldRecreate
            );
        } finally {
            webPoTokenGenLock.unlock();
        }

        try {
            String playerPot = state.generator.generatePoToken(videoId).get();
            String streamingPot = state.streamingPot;
            final long expirationMs = state.expirationMs;
            Logger.printDebug(() -> "poToken for " + videoId + ": playerPot=" + playerPot +
                    ", streamingPot=" + streamingPot + ", sessionIdentifier=" + webPoTokenSessionIdentifier +
                    ", expirationDate=" + sdf.format(expirationMs)
            );
            return new PoTokenResult(playerPot, streamingPot, expirationMs);
        } catch (Throwable throwable) {
            if (state.hasBeenRecreated) {
                throw throwable;
            }
            Logger.printException(() -> "Failed to obtain poToken, retrying");
            return getWebClientPoToken(videoId, visitorId, true);
        }
    }
}