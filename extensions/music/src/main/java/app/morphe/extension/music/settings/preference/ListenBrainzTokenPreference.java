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
import android.content.Intent;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.music.patches.scrobbling.listenbrainz.ListenBrainz;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"unused", "deprecation"})
public class ListenBrainzTokenPreference extends Preference {

    private static final int STATUS_COLOR_ERROR = 0xFFE53935;
    private static final int STATUS_COLOR_SUCCESS = 0xFF43A047;

    public ListenBrainzTokenPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public ListenBrainzTokenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ListenBrainzTokenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ListenBrainzTokenPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        setSummary(str(isLoggedIn()
                ? "morphe_music_listenbrainz_token_summary_logged_in"
                : "morphe_music_scrobbling_summary_logged_out"
        ));
    }

    private static boolean isLoggedIn() {
        return !Settings.LISTENBRAINZ_USER_TOKEN.get().isBlank();
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();
        final boolean loggedIn = isLoggedIn();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_listenbrainz_token_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(Utils.getAppForegroundColor());
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        EditText tokenInput = createThemedEditText(context);
        tokenInput.setHint(str("morphe_music_listenbrainz_token_dialog_hint"));
        if (loggedIn) {
            String currentToken = Settings.LISTENBRAINZ_USER_TOKEN.get();
            tokenInput.setText(currentToken);
            tokenInput.setSelection(currentToken.length());
        }
        content.addView(tokenInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Utils.getAppForegroundColor());
        status.setVisibility(View.GONE);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_listenbrainz_token_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String token = tokenInput.getText().toString().trim();
                    if (token.isEmpty()) {
                        Settings.LISTENBRAINZ_USER_TOKEN.resetToDefault();
                        updateSummary();
                        Utils.showToastShort(str("morphe_music_listenbrainz_token_toast_cleared"));
                    } else {
                        Settings.LISTENBRAINZ_USER_TOKEN.save(token);
                        updateSummary();
                        Utils.showToastShort(str("morphe_music_listenbrainz_token_toast_saved"));
                        Utils.runOnBackgroundThread(() -> {
                            try {
                                ListenBrainz.TokenValidation validation = ListenBrainz.validateToken(token);
                                if (!validation.valid) {
                                    Utils.showToastLong(str("morphe_music_listenbrainz_token_toast_invalid_warning"));
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                },
                null,
                loggedIn ? str("morphe_music_scrobbling_log_out") : null,
                loggedIn ? () -> {
                    Settings.LISTENBRAINZ_USER_TOKEN.resetToDefault();
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_scrobbling_logged_out_toast"));
                } : null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        Button getBtn = CustomDialog.createButton(context, null,
                str("morphe_music_listenbrainz_token_dialog_get_token"),
                () -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://listenbrainz.org/profile/"));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception ex) {
                        Logger.printException(() -> "ListenBrainzTokenPreference failed to open browser", ex);
                    }
                },
                false, false);

        Button verifyBtn = CustomDialog.createButton(context, null,
                str("morphe_music_listenbrainz_token_dialog_verify"),
                () -> {
                    String token = tokenInput.getText().toString().trim();
                    if (token.isEmpty()) {
                        showStatus(status, str("morphe_music_listenbrainz_token_status_empty"), STATUS_COLOR_ERROR);
                        return;
                    }
                    showStatus(status, str("morphe_music_listenbrainz_token_status_validating"), Utils.getAppForegroundColor());
                    Utils.runOnBackgroundThread(() -> {
                        try {
                            ListenBrainz.TokenValidation validation = ListenBrainz.validateToken(token);
                            Utils.runOnMainThread(() -> {
                                if (validation.valid) {
                                    showStatus(status,
                                            str("morphe_music_listenbrainz_token_status_valid", validation.userName),
                                            STATUS_COLOR_SUCCESS);
                                } else {
                                    String reason = validation.message != null
                                            ? validation.message
                                            : str("morphe_music_listenbrainz_token_status_invalid_unknown");
                                    showStatus(status,
                                            str("morphe_music_listenbrainz_token_status_invalid", reason),
                                            STATUS_COLOR_ERROR);
                                }
                            });
                        } catch (Exception ex) {
                            Utils.runOnMainThread(() ->
                                    showStatus(status,
                                            str("morphe_music_listenbrainz_token_status_failed", ex.getMessage()),
                                            STATUS_COLOR_ERROR));
                        }
                    });
                },
                false, false);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionRowParams.topMargin = Dim.dp12;
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, Dim.dp36, 1.0f);
        leftParams.rightMargin = Dim.dp4;
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, Dim.dp36, 1.0f);
        rightParams.leftMargin = Dim.dp4;
        actionRow.addView(getBtn, leftParams);
        actionRow.addView(verifyBtn, rightParams);
        content.addView(actionRow, actionRowParams);

        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Dim.dp12;
        content.addView(status, statusParams);

        // CustomDialog layout order: [title, buttonContainer]. Insert custom content before the buttons.
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
