/*
 * Copyright (C) 2026 Morphe
 *
 * This file is part of the morphe-patches project:
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original author(s):
 * - Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Fullscreen dialog with a WebView that handles the Yandex OAuth flow.
 * <p>
 * The flow:
 * <ol>
 *   <li>Loads {@code https://rust-server-531j.onrender.com/v1/auth/handle}</li>
 *   <li>Server redirects to Yandex OAuth ({@code oauth.yandex.ru/authorize})</li>
 *   <li>User logs in and grants access</li>
 *   <li>Yandex redirects to {@code .../auth/callback#access_token=...&expires_in=...}</li>
 *   <li>WebViewClient intercepts the callback URL, extracts the token, and closes</li>
 * </ol>
 */
@SuppressWarnings("deprecation")
public class VotAuthWebViewDialog extends Dialog {

    private static final String AUTH_HANDLE_URL = "https://rust-server-531j.onrender.com/v1/auth/handle";
    private static final String CALLBACK_PATH = "/auth/callback";

    private WebView webView;
    private ProgressBar progressBar;
    private final OnTokenReceivedListener listener;

    /**
     * Callback invoked when a token is successfully received from the OAuth flow.
     */
    public interface OnTokenReceivedListener {
        /**
         * Called with the extracted access token and expiration time in seconds.
         *
         * @param token     the Yandex OAuth access token
         * @param expiresIn token lifetime in seconds (may be 0 if unknown)
         */
        void onTokenReceived(@NonNull String token, long expiresIn);

        /**
         * Called when the user dismisses the dialog without completing auth.
         */
        void onCancelled();
    }

    /**
     * Parses a query-string-like fragment (from URL hash) into a key-value map.
     */
    @NonNull
    private static Map<String, String> parseQueryString(@NonNull String query) {
        Map<String, String> result = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                result.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return result;
    }

    public VotAuthWebViewDialog(@NonNull Context context, @NonNull OnTokenReceivedListener listener) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.listener = listener;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build the layout programmatically: WebView + close button + progress bar
        Context context = getContext();

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Close button bar
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.END);
        topBar.setBackgroundColor(Color.argb(220, 0, 0, 0));
        topBar.setPadding(0, dpToPx(8), dpToPx(8), dpToPx(4));

        ImageButton closeButton = new ImageButton(context);
        closeButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeButton.setBackgroundColor(Color.TRANSPARENT);
        closeButton.setOnClickListener(v -> {
            listener.onCancelled();
            dismiss();
        });
        topBar.addView(closeButton, new LinearLayout.LayoutParams(
                dpToPx(48), dpToPx(48)));

        rootLayout.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Progress bar (shown during page loads)
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        rootLayout.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(3)));

        // WebView
        webView = new WebView(context);
        configureWebView();

        rootLayout.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));

        setContentView(rootLayout);

        // Make the dialog fullscreen
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Logger.printDebug(() -> "VotAuthWebView: shouldOverrideUrlLoading " + url);

                // Intercept the callback URL — Yandex redirects here after auth
                if (url.contains(CALLBACK_PATH)) {
                    String token = null;
                    long expiresIn = 0;

                    // Token is in the URL fragment: #access_token=xxx&expires_in=yyy
                    String fragment = request.getUrl().getFragment();
                    if (fragment == null || fragment.isEmpty()) {
                        // Try extracting fragment from the raw URL string
                        // (Android may parse the fragment differently)
                        int hashIdx = url.indexOf('#');
                        if (hashIdx >= 0) {
                            fragment = url.substring(hashIdx + 1);
                        }
                    }

                    if (fragment != null && !fragment.isEmpty()) {
                        Map<String, String> params = parseQueryString(fragment);
                        token = params.get("access_token");
                        String expiresStr = params.get("expires_in");
                        if (expiresStr != null) {
                            try {
                                expiresIn = Long.parseLong(expiresStr);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    if (token != null && !token.isEmpty()) {
                        Logger.printDebug(() -> "VotAuthWebView: token extracted successfully");
                        listener.onTokenReceived(token, expiresIn);
                        dismiss();
                    } else {
                        Logger.printDebug(() -> "VotAuthWebView: callback URL without token — auth declined?");
                        listener.onCancelled();
                        dismiss();
                    }
                    return true; // Intercept the navigation
                }

                return false; // Let WebView handle all other URLs
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                Logger.printDebug(() -> "VotAuthWebView: error loading page: " + error.getDescription());
            }
        });

        webView.loadUrl(AUTH_HANDLE_URL);
    }

    @Override
    public void dismiss() {
        // Clean up WebView to prevent memory leaks
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.dismiss();
    }

    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
