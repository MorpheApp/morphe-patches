/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference.about;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Displays HTML content as a full screen dialog, the way Morphe itself shows its own. Any links a
 * user taps on are opened in an external browser.
 */
class AboutWebViewDialog extends Dialog {

    private final String htmlContent;

    public AboutWebViewDialog(@NonNull Context context, @NonNull String htmlContent) {
        super(context, android.R.style.Theme_DeviceDefault_NoActionBar);
        this.htmlContent = htmlContent;
    }

    /**
     * @return The client driving the page. Override to handle navigation that stays inside the app;
     *         the default sends every link to an external browser.
     */
    protected WebViewClient createWebViewClient() {
        return new AboutLinksWebClient(getContext(), this);
    }

    // JS required to hide any broken images. No remote JavaScript is ever loaded.
    @SuppressWarnings("deprecation")
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE); // Remove default title bar.

        // The window fills the screen, so its own background and the navigation bar are painted the
        // same color the page is, rather than whatever the device theme happens to be.
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(ThemeUtils.getDialogBackgroundColor()));
            window.setNavigationBarColor(ThemeUtils.getDialogBackgroundColor());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(true);
            }
        }

        WebView webView = new WebView(getContext());
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setVerticalScrollBarEnabled(false); // Disable the vertical scrollbar.
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.getSettings().setJavaScriptEnabled(true);
        // A WebView paints white until the page is parsed, which flashes as the dialog opens.
        webView.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());
        webView.setWebViewClient(createWebViewClient());
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null);

        setContentView(webView);
        applyInsetsToContentView();
    }

    /**
     * Keeps the page clear of the system bars and the display cutout.
     */
    private void applyInsetsToContentView() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        Window window = getWindow();
        if (window == null) return;

        ViewGroup rootView = (ViewGroup) window.getDecorView()
                .findViewById(android.R.id.content)
                .getParent();

        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets statusInsets = insets.getInsets(WindowInsets.Type.statusBars());
            Insets navInsets = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets cutoutInsets = insets.getInsets(WindowInsets.Type.displayCutout());

            v.setPadding(
                    cutoutInsets.left,
                    statusInsets.top,
                    cutoutInsets.right,
                    navInsets.bottom
            );
            return insets;
        });
    }
}
