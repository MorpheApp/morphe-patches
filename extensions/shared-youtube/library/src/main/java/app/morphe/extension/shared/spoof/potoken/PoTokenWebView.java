/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2533
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class PoTokenWebView {
    private static final String JS_INTERFACE = "PoTokenWebView";

    private final WebView webView;
    private final Handler mainHandler;
    private final String modifiedHtml = Objects.requireNonNull(ResourceUtils.getRawResource("po_token"))
            .replaceFirst(
            "</script>",
            "\n" + JS_INTERFACE + ".launchBotGuard()</script>"
            );
    
    private final Map<String, CompletableFuture<String>> poTokenContinuations = new ConcurrentHashMap<>();
    private final CompletableFuture<PoTokenWebView> initializationFuture;

    private long expirationMs = -1L;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private PoTokenWebView(CompletableFuture<PoTokenWebView> initFuture) {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.initializationFuture = initFuture;
        
        // Initialize WebView on Main Thread
        this.webView = new WebView(Utils.getContext());
        WebSettings webViewSettings = webView.getSettings();
        webViewSettings.setJavaScriptEnabled(true);
        webViewSettings.setDomStorageEnabled(true);
        webViewSettings.setSafeBrowsingEnabled(false);
        webViewSettings.setUserAgentString(BotGuardManager.getUserAgent());
        webViewSettings.setBlockNetworkLoads(true); // No internet for WebView

        webView.addJavascriptInterface(this, JS_INTERFACE);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.message().contains("Uncaught")) {
                    String fmt = "\"" + m.message() + "\", source: " + m.sourceId() + " (" + m.lineNumber() + ")";
                    onInitializationErrorCloseAndCancel(new PoTokenException.BadWebViewException(fmt));
                }
                return super.onConsoleMessage(m);
            }
        });
    }

    public static CompletableFuture<PoTokenWebView> newPoTokenGenerator() {
        CompletableFuture<PoTokenWebView> future = new CompletableFuture<>();
        Utils.runOnMainThread(() -> {
            try {
                PoTokenWebView instance = new PoTokenWebView(future);
                instance.loadHtmlAndObtainBotGuard();
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }

    private void loadHtmlAndObtainBotGuard() {
        mainHandler.post(() -> webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                modifiedHtml,
                "text/html",
                "utf-8",
                null
        ));
    }

    @JavascriptInterface
    public void launchBotGuard() {
        String js = String.format(Locale.US,
            """
            try {
                data = %s;
                runBotGuard(data).then(function (result) {
                    this.webPoSignalOutput = result.webPoSignalOutput;
                    %s.onRunBotGuardResult(result.botGuardResult);
                }, function (error) {
                    %s.onJsInitializationError();
                });
            } catch (error) {
                %s.onJsInitializationError();
            }
            """, BotGuardManager.getChallengeData(), JS_INTERFACE, JS_INTERFACE, JS_INTERFACE);
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    @JavascriptInterface
    public void onJsInitializationError() {
        onJsInitializationError("");
    }

    @JavascriptInterface
    public void onJsInitializationError(String error) {
        onInitializationErrorCloseAndCancel(PoTokenException.buildExceptionForJsError(error));
    }

    @JavascriptInterface
    public void onRunBotGuardResult(String botGuardResult) {
        BotGuardManager.IntegrityToken integrityToken = BotGuardManager.getIntegrityToken(botGuardResult);
        if (integrityToken != null) {
            expirationMs = integrityToken.expirationMs();
            mainHandler.post(() -> webView.evaluateJavascript(
                    "this.integrityToken = " + integrityToken.token(),
                    value -> initializationFuture.complete(this)
            ));
        } else {
            onInitializationErrorCloseAndCancel(new PoTokenException("Null integrity token"));
        }
    }

    public CompletableFuture<String> generatePoToken(String identifier) {
        CompletableFuture<String> future = new CompletableFuture<>();
        poTokenContinuations.put(identifier, future);

        String u8Identifier = BotGuardUtil.stringToU8(identifier);
        String js = String.format(Locale.US,
            """
            try {
                identifier = "%s";
                u8Identifier = %s;
                poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier);
                poTokenU8String = "";
                for (i = 0; i < poTokenU8.length; i++) {
                    if (i != 0) poTokenU8String += ",";
                    poTokenU8String += poTokenU8[i];
                }
                %s.onObtainPoTokenResult(identifier, poTokenU8String);
            } catch (error) {
                %s.onObtainPoTokenError(identifier);
            }
            """, identifier, u8Identifier, JS_INTERFACE, JS_INTERFACE);

        mainHandler.post(() -> webView.evaluateJavascript(js, null));

        return future;
    }

    @JavascriptInterface
    public void onObtainPoTokenError(String identifier) {
        onObtainPoTokenError(identifier, "");
    }

    @JavascriptInterface
    public void onObtainPoTokenError(String identifier, String error) {
        CompletableFuture<String> future = poTokenContinuations.remove(identifier);
        if (future != null) {
            future.completeExceptionally(PoTokenException.buildExceptionForJsError(error));
        }
    }

    @JavascriptInterface
    public void onObtainPoTokenResult(String identifier, String poTokenU8) {
        CompletableFuture<String> future = poTokenContinuations.remove(identifier);
        if (future != null) {
            try {
                String poToken = BotGuardUtil.u8ToBase64(poTokenU8);
                future.complete(poToken);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expirationMs;
    }

    private void onInitializationErrorCloseAndCancel(Throwable error) {
        mainHandler.post(() -> {
            close();
            if (!initializationFuture.isDone()) {
                initializationFuture.completeExceptionally(error);
            }
        });
    }

    public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::close);
            return;
        }

        webView.clearHistory();
        webView.clearCache(true);
        webView.loadUrl("about:blank");
        webView.onPause();
        webView.removeAllViews();
        webView.destroy();
    }
}