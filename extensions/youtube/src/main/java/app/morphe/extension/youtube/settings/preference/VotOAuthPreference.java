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

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.voiceovertranslation.VotApiClient;
import app.morphe.extension.youtube.patches.voiceovertranslation.VotAuthWebViewDialog;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Custom preference for managing the Yandex OAuth token used by Voice Over Translation.
 * <p>
 * When the user is not signed in, clicking shows a dialog with two options:
 * "Sign in with Yandex" (opens a WebView OAuth flow) or "Enter token manually".
 * When signed in, clicking shows account info with options to sign out or switch token.
 */
@SuppressWarnings("deprecation")
public class VotOAuthPreference extends Preference implements Preference.OnPreferenceClickListener {

    /** Cached profile display name (static to survive preference recreation). */
    @Nullable
    private static String cachedDisplayName;

    /** Timeout values for profile fetch. */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    {
        setOnPreferenceClickListener(this);
    }

    //region Constructors ----------------------------------------------------------------

    public VotOAuthPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public VotOAuthPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public VotOAuthPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VotOAuthPreference(Context context) {
        super(context);
        init();
    }

    //endregion

    private void init() {
        updateUI();
    }

    //region UI update ------------------------------------------------------------------

    /**
     * Returns {@code true} if a non-empty OAuth token is currently saved.
     */
    private boolean isSignedIn() {
        String token = Settings.VOT_OAUTH_TOKEN.get();
        return token != null && !token.isEmpty();
    }

    /**
     * Updates the summary text and appearance based on sign-in state.
     */
    private void updateUI() {
        if (isSignedIn()) {
            if (cachedDisplayName != null && !cachedDisplayName.isEmpty()) {
                setSummary(str("morphe_vot_oauth_signed_in_summary", cachedDisplayName));
            } else {
                setSummary(str("morphe_vot_oauth_signed_in_summary", "Yandex"));
                // Try to load profile info in the background
                loadProfileAsync();
            }
        } else {
            cachedDisplayName = null;
            setSummary(str("morphe_vot_oauth_not_signed_in_summary"));
        }
    }

    //endregion

    //region Click handling -------------------------------------------------------------

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (isSignedIn()) {
            showAccountManagementDialog();
        } else {
            showAuthMethodDialog();
        }
        return true;
    }

    //endregion

    //region Dialogs --------------------------------------------------------------------

    /**
     * Dialog shown when the user is NOT signed in.
     * Offers two options: "Sign in with Yandex" (WebView) or "Enter token manually".
     */
    private void showAuthMethodDialog() {
        Context context = getContext();

        CustomDialog.create(
                context,
                str("morphe_vot_oauth_auth_method_title"),
                str("morphe_vot_oauth_auth_method_message"),
                null,
                str("morphe_vot_oauth_sign_in_button"),
                () -> openWebViewAuth(context),
                () -> { /* Cancel — do nothing */ },
                str("morphe_vot_oauth_enter_manually_button"),
                () -> showManualTokenDialog(context),
                false
        ).first.show();
    }

    /**
     * Dialog shown when the user IS signed in.
     * Shows account info and offers "Sign out" or "Use another token".
     */
    private void showAccountManagementDialog() {
        Context context = getContext();

        String message = cachedDisplayName != null
                ? cachedDisplayName
                : str("morphe_vot_oauth_signed_in_summary", "Yandex");

        CustomDialog.create(
                context,
                str("morphe_vot_oauth_account_management_title"),
                message,
                null,
                str("morphe_vot_oauth_sign_out_button"),
                () -> signOut(),
                () -> { /* Cancel — do nothing */ },
                str("morphe_vot_oauth_switch_token_button"),
                () -> showAuthMethodDialog(),
                false
        ).first.show();
    }

    /**
     * Shows the manual token entry dialog with an EditText.
     */
    private void showManualTokenDialog(Context context) {
        EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setHint("y0_AgAAAAB...");
        // Pre-fill with current token if any
        String currentToken = Settings.VOT_OAUTH_TOKEN.get();
        if (currentToken != null && !currentToken.isEmpty()) {
            editText.setText(currentToken);
            editText.setSelection(currentToken.length());
        }

        CustomDialog.create(
                context,
                str("morphe_vot_oauth_enter_token_title"),
                null,
                editText,
                null,
                () -> {
                    String token = editText.getText().toString().trim();
                    if (token.isEmpty()) {
                        return;
                    }
                    onTokenObtained(token, 0);
                },
                () -> { /* Cancel — do nothing */ },
                null,
                null,
                true
        ).first.show();
    }

    //endregion

    //region WebView OAuth flow ---------------------------------------------------------

    /**
     * Opens the fullscreen WebView dialog for the Yandex OAuth flow.
     */
    private void openWebViewAuth(Context context) {
        try {
            VotAuthWebViewDialog dialog = new VotAuthWebViewDialog(context,
                    new VotAuthWebViewDialog.OnTokenReceivedListener() {
                        @Override
                        public void onTokenReceived(@NonNull String token, long expiresIn) {
                            onTokenObtained(token, expiresIn);
                        }

                        @Override
                        public void onCancelled() {
                            Logger.printDebug(() -> "VotOAuthPreference: WebView auth cancelled");
                        }
                    });
            dialog.show();
        } catch (Exception e) {
            Logger.printException(() -> "VotOAuthPreference: failed to open WebView", e);
            Utils.showToastLong(str("morphe_vot_oauth_no_network"));
        }
    }

    //endregion

    //region Token handling -------------------------------------------------------------

    /**
     * Called when a token is obtained (from WebView or manual entry).
     * Validates the token, loads profile info, saves it, and updates UI.
     */
    private void onTokenObtained(@NonNull String token, long expiresIn) {
        Context context = getContext();

        Utils.runOnBackgroundThread(() -> {
            // Validate the token first
            if (!VotApiClient.isValidOAuthToken(token)) {
                Utils.runOnMainThread(() -> {
                    Utils.showToastLong(str("morphe_vot_oauth_invalid_token"));
                });
                return;
            }

            // Save the token
            Settings.VOT_OAUTH_TOKEN.save(token);

            // Load profile info
            String displayName = fetchDisplayName(token);

            Utils.runOnMainThread(() -> {
                cachedDisplayName = displayName;
                updateUI();
            });
        });
    }

    /**
     * Signs out: clears the token and cached profile, resets validation cache.
     */
    private void signOut() {
        Settings.VOT_OAUTH_TOKEN.save("");
        VotApiClient.clearTokenValidationCache();
        cachedDisplayName = null;
        updateUI();
    }

    //endregion

    //region Profile loading ------------------------------------------------------------

    /**
     * Loads the user's display name from Yandex Passport API asynchronously.
     * Updates the UI when done.
     */
    private void loadProfileAsync() {
        String token = Settings.VOT_OAUTH_TOKEN.get();
        if (token == null || token.isEmpty()) return;

        Utils.runOnBackgroundThread(() -> {
            String displayName = fetchDisplayName(token);
            if (displayName != null) {
                Utils.runOnMainThread(() -> {
                    cachedDisplayName = displayName;
                    updateUI();
                });
            }
        });
    }

    /**
     * Fetches the display name for a Yandex OAuth token.
     *
     * @param token valid OAuth token
     * @return display name, or {@code null} on failure
     */
    @Nullable
    private static String fetchDisplayName(@NonNull String token) {
        try {
            String url = "https://login.yandex.ru/info?format=json";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "OAuth " + token);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                int code = conn.getResponseCode();
                if (code != 200) {
                    Logger.printDebug(() -> "VotOAuthPreference: profile fetch returned HTTP " + code);
                    return null;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String displayName = json.optString("display_name", null);
                if (displayName == null || displayName.isEmpty()) {
                    // Fallback: try other fields
                    displayName = json.optString("real_name", null);
                    if (displayName == null || displayName.isEmpty()) {
                        displayName = json.optString("login", null);
                    }
                }
                final String finalDisplayName = displayName;
                Logger.printDebug(() -> "VotOAuthPreference: fetched display_name=" + finalDisplayName);
                return finalDisplayName;

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printException(() -> "VotOAuthPreference: profile fetch failed", e);
            return null;
        }
    }

    //endregion
}
