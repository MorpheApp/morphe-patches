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
import android.preference.SwitchPreference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiConfigPreference extends SwitchPreference {

    private boolean dialogShowing = false;
    private volatile boolean saveInProgress = false;
    private boolean settingFromCode = false;

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
        setSummary(Settings.LYRICS_USE_AI_TRANSLATION.get()
                ? str("morphe_music_lyrics_ai_config_status_configured")
                : str("morphe_music_lyrics_ai_config_status_not_configured"));
    }

    private void setCheckedSilently(boolean checked) {
        settingFromCode = true;
        setChecked(checked);
        settingFromCode = false;
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        if (settingFromCode) {
            return super.callChangeListener(newValue);
        }
        if (dialogShowing) {
            return false;
        }
        boolean turningOn = Boolean.TRUE.equals(newValue);
        if (turningOn && !Settings.LYRICS_USE_AI_TRANSLATION.get()) {
            String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
            String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
            String model = Settings.LYRICS_AI_MODEL.get();
            dialogShowing = true;
            setCheckedSilently(false);
            setSummary(str("morphe_music_lyrics_ai_config_status_validating"));
            Utils.runOnBackgroundThread(() -> {
                boolean valid = validateConfig(baseUrl, apiToken, model);
                Utils.runOnMainThread(() -> {
                    if (!valid) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_invalid"));
                    }
                    showDialog();
                });
            });
            return false;
        }
        return super.callChangeListener(newValue);
    }

    private void showDialog() {
        Context context = getContext();
        if (!(context instanceof Activity activity) || activity.isFinishing()
                || activity.isDestroyed()) {
            dialogShowing = false;
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

        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        promptParams.topMargin = Dim.dp8;
        EditText promptInput = createThemedEditText(context);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptInput.setSingleLine(false);
        promptInput.setMinLines(3);
        promptInput.setMaxLines(6);
        promptInput.setVerticalScrollBarEnabled(true);
        String currentPrompt = Settings.LYRICS_AI_PROMPT.get();
        if (!currentPrompt.isEmpty()) {
            promptInput.setText(currentPrompt);
            promptInput.setSelection(currentPrompt.length());
        }
        content.addView(promptInput, promptParams);

        TextView promptCaption = new TextView(context);
        promptCaption.setText(str("morphe_music_lyrics_ai_config_prompt_hint"));
        promptCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        promptCaption.setTextColor(ThemeUtils.getAppForegroundColor() & 0xAAFFFFFF);
        LinearLayout.LayoutParams promptCaptionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        promptCaptionParams.topMargin = Dim.dp8;
        content.addView(promptCaption, promptCaptionParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_ai_config_dialog_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    saveInProgress = true;
                    String baseUrl = baseUrlInput.getText().toString().trim();
                    String apiToken = apiTokenInput.getText().toString().trim();
                    String model = modelInput.getText().toString().trim();

                    if (baseUrl.isEmpty()) {
                        saveInProgress = false;
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_empty_url"));
                        return;
                    }
                    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                        saveInProgress = false;
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                        return;
                    }
                    if (model.isEmpty()) {
                        saveInProgress = false;
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_empty_model"));
                        return;
                    }

                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
                    Utils.runOnBackgroundThread(() -> {
                        boolean valid = validateConfig(baseUrl, apiToken, model);
                        Utils.runOnMainThread(() -> {
                            if (!valid) {
                                saveInProgress = false;
                                setCheckedSilently(false);
                                setSummary(str("morphe_music_lyrics_ai_config_status_not_configured"));
                                Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_invalid"));
                                return;
                            }
                            Settings.LYRICS_AI_BASE_URL.save(baseUrl);
                            Settings.LYRICS_AI_API_TOKEN.save(apiToken);
                            Settings.LYRICS_AI_MODEL.save(model);
                            Settings.LYRICS_AI_PROMPT.save(
                                    promptInput.getText().toString().trim());
                            Settings.LYRICS_USE_AI_TRANSLATION.save(true);
                            saveInProgress = false;
                            setCheckedSilently(true);
                            setSummary(str("morphe_music_lyrics_ai_config_status_configured"));
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
                    Settings.LYRICS_AI_PROMPT.resetToDefault();
                    baseUrlInput.setText(Settings.LYRICS_AI_BASE_URL.get());
                    apiTokenInput.setText("");
                    modelInput.setText(Settings.LYRICS_AI_MODEL.get());
                    promptInput.setText(Settings.LYRICS_AI_PROMPT.get());
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

        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            if (saveInProgress) return;
            boolean saved = Settings.LYRICS_USE_AI_TRANSLATION.get();
            setCheckedSilently(saved);
            setSummary(saved
                    ? str("morphe_music_lyrics_ai_config_status_configured")
                    : str("morphe_music_lyrics_ai_config_status_not_configured"));
        });

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ignored) {
            dialogShowing = false;
        }
    }

    private static boolean validateConfig(String baseUrl, String apiToken, String model) {
        java.net.HttpURLConnection conn = null;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("model", model);
            org.json.JSONArray messages = new org.json.JSONArray();
            messages.put(new org.json.JSONObject()
                    .put("role", "user")
                    .put("content", "hi"));
            body.put("messages", messages);
            body.put("max_tokens", 1);

            String requestBody = body.toString();

            conn = (java.net.HttpURLConnection) new java.net.URL(baseUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (!apiToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            }
            conn.setDoOutput(true);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            return conn.getResponseCode() == 200;
        } catch (Exception ex) {
            Logger.printDebug(() -> "testApiEndpoint failure", ex);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
