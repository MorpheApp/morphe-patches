/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.MwebSignIn;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Sign in of the Mobile Web client, in a WebView.
 */
@SuppressWarnings({"unused", "deprecation"})
public abstract class MwebSignInPreference extends Preference implements Preference.OnPreferenceClickListener {

    /**
     * Hosts the sign in can navigate to. Any other link is opened in the browser.
     */
    private static final String[] ALLOWED_HOST_SUFFIXES = {
            "google.com",
            "youtube.com",
            "gstatic.com",
            "googleusercontent.com",
    };

    {
        setOnPreferenceClickListener(this);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, str) -> {
        // Settings may not be updated yet, so the UI is updated after the other listeners.
        Utils.runOnMainThread(this::updateUI);
    };

    @Nullable
    private Dialog signInDialog;

    public MwebSignInPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateUI();
    }

    public MwebSignInPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateUI();
    }

    public MwebSignInPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateUI();
    }

    public MwebSignInPreference(Context context) {
        super(context);
        updateUI();
    }

    /**
     * @return If the spoof settings use the Mobile Web client.
     */
    protected abstract boolean isSettingEnabled();

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        updateUI();
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
        dismissSignInDialog();
    }

    protected void updateUI() {
        final boolean isSettingEnabled = isSettingEnabled();
        setEnabled(isSettingEnabled);

        String summaryKey;
        if (!isSettingEnabled) {
            summaryKey = "morphe_spoof_video_streams_sign_in_mweb_about_summary_disabled";
        } else if (MwebSignIn.isSignedIn()) {
            summaryKey = "morphe_spoof_video_streams_sign_in_mweb_about_summary_signed_in";
        } else {
            summaryKey = "morphe_spoof_video_streams_sign_in_mweb_about_summary";
        }
        setSummary(str(summaryKey));
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        final boolean signedIn = MwebSignIn.isSignedIn();

        CustomDialog.create(
                getContext(),
                // Title.
                str(signedIn
                        ? "morphe_spoof_video_streams_sign_in_mweb_about_summary_signed_in"
                        : "morphe_spoof_video_streams_sign_in_mweb_dialog_title"),
                // Message.
                BulletPointPreference.formatIntoBulletPoints(str(signedIn
                        ? "morphe_spoof_video_streams_sign_in_mweb_success_dialog_message"
                        : "morphe_spoof_video_streams_sign_in_mweb_dialog_not_signed_in_message")),
                // No EditText.
                null,
                // OK button text.
                str(signedIn
                        ? "morphe_spoof_video_streams_sign_in_mweb_dialog_sign_out"
                        : "gms_core_dialog_continue_text"),
                // OK button action.
                signedIn
                        ? () -> {
                            MwebSignIn.signOut();
                            // The cookies are removed on the main thread after this.
                            Utils.runOnMainThread(this::updateUI);
                        }
                        : this::showSignInDialog,
                // Cancel button action.
                () -> {
                },
                // Neutral button text.
                null,
                // Neutral button action.
                null,
                // Dismiss dialog when onNeutralClick.
                true
        ).first.show();

        return true;
    }

    private void dismissSignInDialog() {
        Dialog dialog = signInDialog;
        signInDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static boolean isAllowedHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    // JavaScript is required by the Google sign in page.
    @SuppressLint("SetJavaScriptEnabled")
    private void showSignInDialog() {
        Context context = getContext();
        WebView webView;
        try {
            webView = new WebView(context);
        } catch (Exception ex) {
            // WebView is missing or disabled.
            Logger.printException(() -> "Could not create the sign in WebView", ex);
            Utils.showToastLong(str("morphe_spoof_video_streams_sign_in_mweb_toast_webview_missing"));
            return;
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSaveFormData(false);
        settings.setSupportMultipleWindows(false);
        // Google refuses to sign in to a page in an app, which it knows by "wv" in the user agent.
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> {
            if (signInDialog == d) {
                signInDialog = null;
            }
            webView.stopLoading();
            webView.destroy();
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && isAllowedHost(uri.getHost())) {
                    return false;
                }
                // The sign in ends with an intent link to m.youtube.com, which would open the YouTube website
                // in the browser. It is opened here instead.
                if ("intent".equals(uri.getScheme())) {
                    try {
                        Uri data = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getData();
                        if (data != null && "https".equals(data.getScheme()) && isAllowedHost(data.getHost())) {
                            if (!checkSignInFinished(dialog, data.toString())) {
                                view.loadUrl(data.toString());
                            }
                            return true;
                        }
                    } catch (Exception ex) {
                        Logger.printInfo(() -> "Could not read intent link", ex);
                    }
                }
                // Help, privacy and other links of the sign in page.
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Could not open link", ex);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                checkSignInFinished(dialog, url);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // All WebViews of the app share the renderer, which Android can stop to free memory.
                // Without this, the app is closed. The sign in can be started again.
                Logger.printInfo(() -> "Mobile Web sign in renderer is gone, crashed: " + detail.didCrash());
                if (signInDialog == dialog) {
                    dismissSignInDialog();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                checkSignInFinished(dialog, url);
            }
        });

        signInDialog = dialog;
        dialog.show();
        webView.loadUrl(MwebSignIn.SIGN_IN_URL);
    }

    /**
     * The sign in ends on YouTube, which then has the cookies of the account.
     *
     * @return If the sign in has finished.
     */
    private boolean checkSignInFinished(Dialog dialog, @Nullable String url) {
        if (signInDialog != dialog || url == null) {
            return false;
        }
        String host = Uri.parse(url).getHost();
        if (host == null || !(host.equals("youtube.com") || host.endsWith(".youtube.com"))
                || host.equals("accounts.youtube.com") || !MwebSignIn.isSignedIn()) {
            return false;
        }

        Logger.printDebug(() -> "Mobile Web sign in finished");
        CookieManager.getInstance().flush();
        MwebSignIn.onSignedIn();
        dismissSignInDialog();
        updateUI();

        CustomDialog.create(
                getContext(),
                // Title.
                str("morphe_spoof_video_streams_sign_in_mweb_success_dialog_title"),
                // Message.
                BulletPointPreference.formatIntoBulletPoints(
                        str("morphe_spoof_video_streams_sign_in_mweb_success_dialog_message")
                ),
                // No EditText.
                null,
                // OK button text.
                null,
                // OK button action.
                () -> {
                },
                // Cancel button action.
                null,
                // Neutral button text.
                null,
                // Neutral button action.
                null,
                // Dismiss dialog when onNeutralClick.
                true
        ).first.show();
        return true;
    }
}
