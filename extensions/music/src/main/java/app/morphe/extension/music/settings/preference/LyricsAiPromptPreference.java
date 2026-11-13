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
import android.util.AttributeSet;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;

@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiPromptPreference extends BooleanSettingPreference {

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

        Dialog dialog = LyricsProviderConfigDialog.compatibleDialog(activity, promptSetting, null,
                () -> enableAfterValidation(switchSetting), null);
        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            setCheckedSilently(switchSetting.get());
        });

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the AI prompt dialog", ex);
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
