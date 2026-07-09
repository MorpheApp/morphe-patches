/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.music.patches.scrobbling.lastfm.LastFM;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"unused", "deprecation"})
public class LastFMTokenPreference extends Preference {

    private static final int STATUS_COLOR_ERROR = 0xFFE53935;
    private static final int STATUS_COLOR_SUCCESS = 0xFF43A047;

    public LastFMTokenPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LastFMTokenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LastFMTokenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LastFMTokenPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        if (isLoggedIn()) {
            setSummary(str("morphe_music_lastfm_token_summary_logged_in", Settings.LASTFM_USERNAME.get()));
        } else {
            setSummary(str("morphe_music_scrobbling_summary_logged_out"));
        }
    }

    private static boolean isLoggedIn() {
        return !Settings.LASTFM_SESSION_KEY.get().isBlank() && !Settings.LASTFM_USERNAME.get().isBlank();
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();
        boolean loggedIn = isLoggedIn();
        String currentUsername = Settings.LASTFM_USERNAME.get();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_lastfm_token_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(Utils.getAppForegroundColor());
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        EditText usernameInput = createThemedEditText(context);
        usernameInput.setHint(str("morphe_music_lastfm_token_dialog_username_hint"));
        if (!currentUsername.isEmpty()) {
            usernameInput.setText(currentUsername);
            usernameInput.setSelection(currentUsername.length());
        }
        LinearLayout.LayoutParams usernameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        content.addView(usernameInput, usernameParams);

        EditText passwordInput = createThemedEditText(context);
        passwordInput.setHint(str("morphe_music_lastfm_token_dialog_password_hint"));
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        passwordParams.topMargin = Dim.dp8;
        content.addView(passwordInput, passwordParams);

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Utils.getAppForegroundColor());
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Dim.dp12;

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lastfm_token_title"),
                null,
                null,
                null,
                null,
                () -> {},
                loggedIn ? str("morphe_music_scrobbling_log_out") : null,
                loggedIn ? () -> {
                    Settings.LASTFM_SESSION_KEY.resetToDefault();
                    Settings.LASTFM_USERNAME.resetToDefault();
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_scrobbling_logged_out_toast"));
                } : null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        Button loginBtn = CustomDialog.createButton(context, null,
                str("morphe_music_lastfm_token_dialog_login"),
                () -> {
                    String username = usernameInput.getText().toString().trim();
                    String password = passwordInput.getText().toString();
                    if (username.isEmpty() || password.isEmpty()) {
                        showStatus(status, str("morphe_music_lastfm_token_status_empty"),
                                STATUS_COLOR_ERROR);
                        return;
                    }
                    showStatus(status, str("morphe_music_lastfm_token_status_logging_in"),
                            Utils.getAppForegroundColor());
                    Utils.runOnBackgroundThread(() -> {
                        try {
                            LastFM.Session session = LastFM.getMobileSession(username, password);
                            Utils.runOnMainThread(() -> {
                                if (session.key != null) {
                                    showStatus(status, str("morphe_music_lastfm_token_status_success"),
                                            STATUS_COLOR_SUCCESS);
                                    Settings.LASTFM_SESSION_KEY.save(session.key);
                                    Settings.LASTFM_USERNAME.save(session.name);
                                    updateSummary();
                                    Utils.showToastShort(str("morphe_music_lastfm_token_toast_saved"));
                                } else {
                                    showStatus(status, str("morphe_music_lastfm_token_status_no_session"),
                                            STATUS_COLOR_ERROR);
                                }
                            });
                        } catch (Exception ex) {
                            Utils.runOnMainThread(() -> showStatus(status,
                                    str("morphe_music_lastfm_token_status_failed",
                                            ex.getMessage()), STATUS_COLOR_ERROR));
                        }
                    });
                },
                false, false);

        LinearLayout.LayoutParams loginParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        loginParams.topMargin = Dim.dp12;
        content.addView(loginBtn, loginParams);

        content.addView(status, statusParams);

        // CustomDialog layout order: [title, content, buttonContainer]. Insert before the buttons.
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    private static void showStatus(TextView status, CharSequence text, int color) {
        status.setText(text);
        status.setTextColor(color);
        status.setVisibility(View.VISIBLE);
    }

    private static EditText createThemedEditText(Context context) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setTextColor(Utils.getAppForegroundColor());
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(Utils.getEditTextBackground());
        editText.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);
        return editText;
    }
}
