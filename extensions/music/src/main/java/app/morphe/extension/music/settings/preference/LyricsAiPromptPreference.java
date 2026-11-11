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
import android.preference.SwitchPreference;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.derive.ProcessingProviders;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiPromptPreference extends SwitchPreference {

    private boolean dialogShowing = false;
    private boolean settingFromCode = false;

    public LyricsAiPromptPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public LyricsAiPromptPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LyricsAiPromptPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LyricsAiPromptPreference(Context context) {
        super(context);
    }

    private BooleanSetting switchSetting() {
        String key = getKey();
        if (key == null) return null;
        if (key.equals("morphe_music_lyrics_use_ai_section")) {
            return Settings.LYRICS_USE_AI_SECTION;
        }
        return null;
    }

    private StringSetting promptSetting() {
        String key = getKey();
        if (key == null) return null;
        if (key.equals("morphe_music_lyrics_use_ai_section")) {
            return Settings.LYRICS_AI_SECTION_PROMPT;
        }
        return null;
    }

    private void setCheckedSilently(boolean checked) {
        settingFromCode = true;
        setChecked(checked);
        settingFromCode = false;
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        if (settingFromCode || promptSetting() == null) {
            return super.callChangeListener(newValue);
        }
        if (dialogShowing) {
            return false;
        }
        BooleanSetting setting = switchSetting();
        if (Boolean.TRUE.equals(newValue) && (setting == null || !setting.get())) {
            dialogShowing = true;
            setCheckedSilently(false);
            showDialog();
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

        StringSetting promptSetting = promptSetting();
        BooleanSetting switchSetting = switchSetting();
        if (promptSetting == null || switchSetting == null) {
            dialogShowing = false;
            return;
        }

        LinearLayout content = LyricsProviderConfigDialog.createContent(context);

        EditText tokenInput = LyricsProviderConfigDialog.addInput(content,
                "morphe_music_lyrics_ai_config_api_token_label",
                Settings.LYRICS_AI_API_TOKEN.get(), true);
        final EditText[] urlBox = new EditText[1];
        EditText modelInput = LyricsProviderConfigDialog.addModelInput(content,
                Settings.LYRICS_AI_MODEL.get(),
                () -> urlBox[0] == null ? Collections.emptyList()
                        : OpenAIClient.fetchModels(urlBox[0].getText().toString().trim(),
                                tokenInput.getText().toString().trim()));
        EditText extraHeadersInput = LyricsProviderConfigDialog.addInput(content,
                "morphe_music_lyrics_ai_config_extra_headers_label",
                Settings.LYRICS_AI_EXTRA_HEADERS.get(), false);
        LyricsProviderConfigDialog.makeMultiLine(extraHeadersInput, 2, 5);
        LyricsProviderConfigDialog.setHint(extraHeadersInput,
                "morphe_music_lyrics_ai_config_extra_headers_hint");
        EditText extraBodyInput = LyricsProviderConfigDialog.addInput(content,
                "morphe_music_lyrics_ai_config_extra_body_label",
                Settings.LYRICS_AI_EXTRA_BODY.get(), false);
        LyricsProviderConfigDialog.makeMultiLine(extraBodyInput, 2, 5);
        LyricsProviderConfigDialog.setHint(extraBodyInput,
                "morphe_music_lyrics_ai_config_extra_body_hint");
        urlBox[0] = LyricsProviderConfigDialog.addBaseUrlInput(content,
                Settings.LYRICS_AI_BASE_URL.get(), preset -> {
                    modelInput.setText(preset.model());
                });
        final EditText urlInput = urlBox[0];
        LyricsProviderConfigDialog.addTestButton(context, content, () -> {
            final String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                return;
            }
            LyricsProviderConfigDialog.test(activity, () -> OpenAIClient.validateConfig(url,
                    tokenInput.getText().toString().trim(),
                    modelInput.getText().toString().trim()) ? List.of("ok") : null,
                    ProcessingProviders.OPENAI_COMPATIBLE_LABEL);
        });
        final LyricsProviderConfigDialog.PromptField promptField = LyricsProviderConfigDialog.addPromptSection(content,
                promptSetting, "morphe_music_lyrics_ai_config_prompt_label");

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                ProcessingProviders.OPENAI_COMPATIBLE_LABEL,
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    final String extraBody = extraBodyInput.getText().toString().trim();
                    final String extraHeaders = extraHeadersInput.getText().toString().trim();
                    final String baseUrl = urlInput.getText().toString().trim();
                    if (!OpenAIClient.isValidExtraBody(extraBody)
                            || !OpenAIClient.isValidExtraHeaders(extraHeaders)) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid"));
                        return;
                    }
                    if (baseUrl.isEmpty()
                            || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                        return;
                    }
                    Settings.LYRICS_AI_EXTRA_BODY.save(extraBody);
                    Settings.LYRICS_AI_EXTRA_HEADERS.save(extraHeaders);
                    Settings.LYRICS_AI_BASE_URL.save(baseUrl);
                    Settings.LYRICS_AI_API_TOKEN.save(tokenInput.getText().toString().trim());
                    Settings.LYRICS_AI_MODEL.save(modelInput.getText().toString().trim());
                    promptField.save();
                    enableAfterValidation(switchSetting);
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_BODY.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_HEADERS.resetToDefault();
                    promptField.reset();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_reset"));
                },
                false
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        mainLayout.addView(scroll, mainLayout.getChildCount() - 1, scrollParams);

        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            setCheckedSilently(switchSetting.get());
        });

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the AI section dialog", ex);
            dialogShowing = false;
        }
    }

    /**
     * <p>An AI feature pointed at an endpoint that rejects requests produces nothing at
     * all, and it fails silently at playback time when nothing says why. Refusing here
     * turns that into something the user can act on.
     */
    private void enableAfterValidation(BooleanSetting switchSetting) {
        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
        VALIDATION.execute(() -> {
            final boolean valid = OpenAIClient.validateConfig(
                    Settings.LYRICS_AI_BASE_URL.get(),
                    Settings.LYRICS_AI_API_TOKEN.get(),
                    Settings.LYRICS_AI_MODEL.get());
            Utils.runOnMainThread(() -> {
                if (!valid) {
                    setCheckedSilently(switchSetting.get());
                    Utils.showToastShort(str("morphe_music_lyrics_ai_prompt_toast_endpoint_invalid"));
                    return;
                }
                switchSetting.save(true);
                setCheckedSilently(true);
            });
        });
    }

    private static final ExecutorService VALIDATION = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "lyrics-ai-validate");
                thread.setDaemon(true);
                return thread;
            });
}
