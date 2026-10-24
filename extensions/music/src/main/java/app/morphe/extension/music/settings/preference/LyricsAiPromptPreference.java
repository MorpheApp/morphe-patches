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
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
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
        switch (key) {
            case "morphe_music_lyrics_use_ai_section":
                return Settings.LYRICS_USE_AI_SECTION;
            default:
                return null;
        }
    }

    private StringSetting promptSetting() {
        String key = getKey();
        if (key == null) return null;
        switch (key) {
            case "morphe_music_lyrics_use_ai_section":
                return Settings.LYRICS_AI_SECTION_PROMPT;
            default:
                return null;
        }
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

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        EditText promptInput = CustomDialog.createEditText(context);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptInput.setSingleLine(false);
        promptInput.setMinLines(4);
        promptInput.setMaxLines(8);
        promptInput.setVerticalScrollBarEnabled(true);
        promptInput.setHint(str("morphe_music_lyrics_ai_prompt_dialog_title"));
        String currentPrompt = promptSetting.get();
        if (!currentPrompt.isEmpty()) {
            promptInput.setText(currentPrompt);
            promptInput.setSelection(currentPrompt.length());
        }
        content.addView(promptInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView caption = new TextView(context);
        caption.setText(str("morphe_music_lyrics_ai_prompt_dialog_hint"));
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setTextColor(ThemeUtils.getAppForegroundColor() & 0xAAFFFFFF);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = Dim.dp8;
        content.addView(caption, captionParams);

        LinearLayout.LayoutParams reasoningParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        reasoningParams.topMargin = Dim.dp16;
        content.addView(createReasoningRow(context), reasoningParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_ai_prompt_dialog_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String prompt = promptInput.getText().toString().trim();
                    if (prompt.isEmpty()) {
                        promptSetting.resetToDefault();
                        prompt = promptSetting.get();
                    }
                    // Saved before anything is asked of the network, so a feature that cannot run
                    // yet does not cost the user the prompt they just wrote.
                    promptSetting.save(prompt);
                    enableAfterValidation(switchSetting);
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    promptSetting.resetToDefault();
                    promptInput.setText(promptSetting.get());
                    Utils.showToastShort(str("morphe_music_lyrics_ai_prompt_toast_reset"));
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
     * all, and it fails silently at playback time where nothing says why. Refusing here
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
                Utils.showToastShort(str("morphe_music_lyrics_ai_prompt_toast_saved"));
            });
        });
    }

    private static final ExecutorService VALIDATION = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "lyrics-ai-validate");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * The reasoning effort sent with each request. Which levels exist belongs to the endpoint, so the
     * row stays hidden until it has asked: an endpoint that names no levels takes no effort from us,
     * and showing a fixed list would offer choices the model cannot honour.
     */
    private View createReasoningRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(CustomDialog.createRoundedBackground(Dim.dp8,
                ThemeUtils.getAppBackgroundColor() & 0x33FFFFFF));

        TextView label = new TextView(context);
        label.setText("Reasoning");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(ThemeUtils.getAppForegroundColor() & 0xAAFFFFFF);
        row.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);
        row.setVisibility(View.GONE);

        final String stored = Settings.LYRICS_AI_REASONING_EFFORT.get();
        value.setText(display(stored));
        MODEL_QUERY.execute(() -> {
            final String[] efforts = OpenAIClient.fetchReasoningEfforts(
                    Settings.LYRICS_AI_BASE_URL.get(), Settings.LYRICS_AI_API_TOKEN.get(),
                    Settings.LYRICS_AI_MODEL.get());
            Utils.runOnMainThread(() -> {
                if (efforts.length == 0) {
                    return;
                }
                row.setVisibility(View.VISIBLE);
                final CharSequence[] labels = new CharSequence[efforts.length];
                for (int i = 0; i < efforts.length; i++) {
                    labels[i] = display(efforts[i]);
                }
                row.setOnClickListener(v -> CustomDialogListPreference.createListDialog(context,
                                "Reasoning effort", labels, efforts,
                                Settings.LYRICS_AI_REASONING_EFFORT.get(), index -> {
                                    final String chosen = efforts[index];
                                    Settings.LYRICS_AI_REASONING_EFFORT.save(chosen);
                                    value.setText(display(chosen));
                                })
                        .show());
            });
        });
        return row;
    }

    private static String display(String effort) {
        if (effort == null || effort.isEmpty()) {
            return "Default";
        }
        return Character.toUpperCase(effort.charAt(0)) + effort.substring(1);
    }

    private static final ExecutorService MODEL_QUERY = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "lyrics-ai-models");
                thread.setDaemon(true);
                return thread;
            });
}