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
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * The endpoint every AI feature talks to. It is one shared setting rather than a switch of its
 * own, so the features next to it stay the only switches a user has to reason about.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiConfigPreference extends Preference {

    public LyricsAiConfigPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsAiConfigPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        String model = Settings.LYRICS_AI_MODEL.get();
        setSummary(model.isEmpty()
                ? null
                : str("morphe_music_lyrics_ai_config_summary_configured", model));
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();
        if (!(context instanceof Activity activity) || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_lyrics_ai_config_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        EditText baseUrlInput = createThemedEditText(context);
        baseUrlInput.setHint(str("morphe_music_lyrics_ai_config_base_url_hint"));
        String currentBaseUrl = Settings.LYRICS_AI_BASE_URL.get();
        if (!currentBaseUrl.isEmpty()) {
            baseUrlInput.setText(currentBaseUrl);
            baseUrlInput.setSelection(currentBaseUrl.length());
        }
        content.addView(baseUrlInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tokenParams.topMargin = Dim.dp8;
        EditText apiTokenInput = createThemedEditText(context);
        apiTokenInput.setHint(str("morphe_music_lyrics_ai_config_api_token_hint"));
        apiTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String currentToken = Settings.LYRICS_AI_API_TOKEN.get();
        if (!currentToken.isEmpty()) {
            apiTokenInput.setText(currentToken);
            apiTokenInput.setSelection(currentToken.length());
        }
        content.addView(apiTokenInput, tokenParams);

        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        modelParams.topMargin = Dim.dp8;
        EditText modelInput = createThemedEditText(context);
        modelInput.setHint(str("morphe_music_lyrics_ai_config_model_hint"));
        String currentModel = Settings.LYRICS_AI_MODEL.get();
        if (!currentModel.isEmpty()) {
            modelInput.setText(currentModel);
            modelInput.setSelection(currentModel.length());
        }
        content.addView(modelInput, modelParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_use_ai_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String baseUrl = baseUrlInput.getText().toString().trim();
                    String apiToken = apiTokenInput.getText().toString().trim();
                    String model = modelInput.getText().toString().trim();

                    if (baseUrl.isEmpty()) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_empty_url"));
                        return;
                    }
                    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                        return;
                    }
                    if (model.isEmpty()) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_empty_model"));
                        return;
                    }

                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
                    Utils.runOnBackgroundThread(() -> {
                        boolean valid = OpenAIClient.validateConfig(baseUrl, apiToken, model);
                        Utils.runOnMainThread(() -> {
                            if (!valid) {
                                Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_invalid"));
                                return;
                            }
                            Settings.LYRICS_AI_BASE_URL.save(baseUrl);
                            Settings.LYRICS_AI_API_TOKEN.save(apiToken);
                            Settings.LYRICS_AI_MODEL.save(model);
                            updateSummary();
                            Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_saved"));
                        });
                    });
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    baseUrlInput.setText(Settings.LYRICS_AI_BASE_URL.get());
                    apiTokenInput.setText("");
                    modelInput.setText(Settings.LYRICS_AI_MODEL.get());
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_reset"));
                },
                false
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the AI settings dialog", ex);
        }
    }

    private static EditText createThemedEditText(Context context) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setTextColor(ThemeUtils.getAppForegroundColor());
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        editText.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);
        return editText;
    }
}