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

        EditText baseUrlInput = ThemedInput.create(context);
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
        EditText apiTokenInput = ThemedInput.create(context);
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
        EditText modelInput = ThemedInput.create(context);
        modelInput.setHint(str("morphe_music_lyrics_ai_config_model_hint"));
        String currentModel = Settings.LYRICS_AI_MODEL.get();
        if (!currentModel.isEmpty()) {
            modelInput.setText(currentModel);
            modelInput.setSelection(currentModel.length());
        }
        content.addView(modelInput, modelParams);

        LinearLayout.LayoutParams extraBodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        extraBodyParams.topMargin = Dim.dp8;
        EditText extraBodyInput = ThemedInput.create(context);
        extraBodyInput.setSingleLine(false);
        extraBodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        extraBodyInput.setMinLines(2);
        extraBodyInput.setMaxLines(5);
        extraBodyInput.setVerticalScrollBarEnabled(true);
        extraBodyInput.setHint(str("morphe_music_lyrics_ai_extra_body_hint"));
        String currentExtraBody = Settings.LYRICS_AI_EXTRA_BODY.get();
        if (!currentExtraBody.isEmpty()) {
            extraBodyInput.setText(currentExtraBody);
            extraBodyInput.setSelection(currentExtraBody.length());
        }
        content.addView(extraBodyInput, extraBodyParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_use_ai_title"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        Runnable resetAction = () -> {
            Settings.LYRICS_AI_BASE_URL.resetToDefault();
            Settings.LYRICS_AI_API_TOKEN.resetToDefault();
            Settings.LYRICS_AI_MODEL.resetToDefault();
            Settings.LYRICS_AI_EXTRA_BODY.resetToDefault();
            baseUrlInput.setText(Settings.LYRICS_AI_BASE_URL.get());
            apiTokenInput.setText("");
            modelInput.setText(Settings.LYRICS_AI_MODEL.get());
            extraBodyInput.setText("");
            updateSummary();
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_reset"));
        };

        Runnable saveAction = () -> {
            String baseUrl = baseUrlInput.getText().toString().trim();
            String apiToken = apiTokenInput.getText().toString().trim();
            String model = modelInput.getText().toString().trim();
            String extraBody = extraBodyInput.getText().toString().trim();

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
            // Nothing is sent until this parses, so a body that will not is refused here, not saved.
            if (!OpenAIClient.isValidExtraBody(extraBody)) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_extra_body_invalid"));
                return;
            }
            // Written before the endpoint is asked, so one that only takes what this body makes answers yes.
            Settings.LYRICS_AI_EXTRA_BODY.save(extraBody);

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
                    dialog.dismiss();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_saved"));
                });
            });
        };

        // Both buttons are built here instead of handed to create() because the save action can
        // refuse what it was given, and the input has to survive that: create() dismisses on OK
        // unconditionally, so a refusal would close the dialog and drop everything typed into it.
        mainLayout.removeViewAt(mainLayout.getChildCount() - 1);
        Button saveButton = CustomDialog.createButton(context, dialog,
                str("morphe_settings_save"), saveAction, true, false);
        Button resetButton = CustomDialog.createButton(context, dialog,
                str("morphe_settings_reset"), resetAction, false, false);
        mainLayout.addView(CustomDialog.createButtonRow(context, resetButton, saveButton));

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the AI settings dialog", ex);
        }
    }
}
