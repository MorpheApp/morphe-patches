/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.derive.QuickLRCClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * The key for the service that transcribes or time-aligns the lyrics of the current track. It
 * lives next to the AI endpoint settings because both are keys for a service the lyrics are sent
 * to, and shares nothing with them but that.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsTranscribeKeyPreference extends Preference {

    private static final String DASHBOARD_URL = "https://quicklrc.com/dashboard";

    @Nullable
    private Dialog activeDialog;

    public LyricsTranscribeKeyPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsTranscribeKeyPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsTranscribeKeyPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsTranscribeKeyPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    @SuppressWarnings("ConstantValue")
    private void updateSummary() {
        final String key = Settings.LYRICS_TRANSCRIBE_KEY.get();
        setSummary(key == null || key.trim().isEmpty()
                ? str("morphe_music_lyrics_transcribe_key_summary")
                : str("morphe_music_lyrics_transcribe_key_set_summary"));
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    @SuppressWarnings("ConstantValue")
    private void showDialog() {
        final Context context = getContext();
        if (!(context instanceof Activity activity)
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activeDialog != null && activeDialog.isShowing()) {
            return;
        }

        final String stored = Settings.LYRICS_TRANSCRIBE_KEY.get();
        final boolean configured = stored != null && !stored.trim().isEmpty();

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        final TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_lyrics_transcribe_key_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(ThemeUtils.getAppForegroundColor());
        final LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        final EditText keyInput = ThemedInput.create(context);
        keyInput.setHint("qlrc_...");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (configured) {
            keyInput.setText(stored);
            keyInput.setSelection(stored.length());
        }
        content.addView(keyInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final Button getKeyButton = CustomDialog.createButton(context, null,
                str("morphe_music_token_dialog_get_token"),
                () -> {
                    try {
                        final Intent intent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse(DASHBOARD_URL));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "QuickLRC dashboard button failure", ex);
                    }
                },
                false, false);
        final LinearLayout.LayoutParams getKeyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        getKeyParams.topMargin = Dim.dp12;
        content.addView(getKeyButton, getKeyParams);

        final Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_transcribe_key_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    final String key = keyInput.getText().toString().trim();
                    if (key.isEmpty()) {
                        // Saving nothing clears it, which is also what the reset button does.
                        Settings.LYRICS_TRANSCRIBE_KEY.resetToDefault();
                        updateSummary();
                        Utils.showToastShort(str("morphe_music_lyrics_transcribe_key_cleared"));
                        return;
                    }
                    // Checked before storing: a key that the service refuses is worth learning
                    // about now rather than at the next track, and the check costs no credit.
                    QuickLRCClient.checkKey(key, result -> {
                        if (result == QuickLRCClient.KeyCheck.REJECTED) {
                            Utils.showToastShort(
                                    str("morphe_music_lyrics_transcribe_key_rejected"));
                            return;
                        }
                        Settings.LYRICS_TRANSCRIBE_KEY.save(key);
                        updateSummary();
                        Utils.showToastShort(str("morphe_music_lyrics_transcribe_key_saved"));
                    });
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    Settings.LYRICS_TRANSCRIBE_KEY.resetToDefault();
                    keyInput.setText("");
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_lyrics_transcribe_key_cleared"));
                },
                false
        );

        final Dialog dialog = dialogPair.first;
        final LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == d) {
                activeDialog = null;
            }
        });
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            activeDialog = null;
            Logger.printDebug(() -> "Transcribe key dialog could not be shown", ex);
        }
    }
}