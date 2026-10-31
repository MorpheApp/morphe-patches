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
import android.text.InputType;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.morphe.extension.music.patches.lyrics.derive.AiProviders;
import app.morphe.extension.music.patches.lyrics.derive.DeepLClient;
import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * The dialog behind a translation or romanization provider's name. Translation and romanization
 * share one configuration per service, so both lists open the same settings.
 */
final class LyricsProviderConfigDialog {

    private static final ExecutorService VALIDATION = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "lyrics-provider-validate");
                thread.setDaemon(true);
                return thread;
            });

    private LyricsProviderConfigDialog() {
    }

    /**
     * @param prompt the prompt the service is sent, or null for a service that does not read one.
     */
    static void show(Activity context, String provider, @Nullable StringSetting prompt,
                     Runnable onSaved) {
        final Dialog dialog;
        try {
            dialog = switch (provider) {
                case AiProviders.OPENAI_COMPATIBLE -> compatibleDialog(context, prompt, onSaved);
                case AiProviders.DEEPL -> deepLDialog(context, onSaved);
                default -> null;
            };
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the provider settings dialog", ex);
            return;
        }
        if (dialog == null) {
            return;
        }
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the provider settings dialog", ex);
        }
    }

    /** Calls back with whether the provider can answer with the stored settings. */
    static void validate(String provider, Consumer<Boolean> callback) {
        try {
            VALIDATION.execute(() -> {
                boolean valid = false;
                try {
                    switch (provider) {
                        case AiProviders.OPENAI_COMPATIBLE -> valid = OpenAIClient.validateConfig(
                                Settings.LYRICS_AI_BASE_URL.get(),
                                Settings.LYRICS_AI_API_TOKEN.get(),
                                Settings.LYRICS_AI_MODEL.get());
                        case AiProviders.DEEPL -> {
                            final String key = Settings.LYRICS_DEEPL_API_KEY.get();
                            valid = !key.isEmpty() && DeepLClient.translate(
                                    Collections.singletonList("hello"), "EN", key) != null;
                        }
                        default -> {
                        }
                    }
                } catch (Exception ex) {
                    Logger.printDebug(() -> "provider validation failed: " + provider, ex);
                }
                final boolean result = valid;
                Utils.runOnMainThread(() -> callback.accept(result));
            });
        } catch (Exception ex) {
            Logger.printException(() -> "provider validation failure: " + provider, ex);
            Utils.runOnMainThread(() -> callback.accept(false));
        }
    }

    private static Dialog compatibleDialog(Activity context, @Nullable StringSetting prompt,
                                           Runnable onSaved) {
        LinearLayout content = createContent(context);
        // The URL row is added last so the preset it triggers can fill the boxes above.
        EditText tokenInput = addInput(content,
                "morphe_music_lyrics_ai_config_api_token_label",
                Settings.LYRICS_AI_API_TOKEN.get(), true);
        final EditText[] urlBox = new EditText[1];
        EditText modelInput = addModelInput(content, Settings.LYRICS_AI_MODEL.get(),
                () -> urlBox[0] == null ? Collections.emptyList()
                        : OpenAIClient.fetchModels(urlBox[0].getText().toString().trim(),
                                tokenInput.getText().toString().trim()));
        EditText extraHeadersInput = addInput(content,
                "morphe_music_lyrics_ai_config_extra_headers_label",
                Settings.LYRICS_AI_EXTRA_HEADERS.get(), false);
        makeMultiLine(extraHeadersInput, 2, 5);
        setHint(extraHeadersInput, "morphe_music_lyrics_ai_config_extra_headers_hint");
        EditText extraBodyInput = addInput(content,
                "morphe_music_lyrics_ai_config_extra_body_label",
                Settings.LYRICS_AI_EXTRA_BODY.get(), false);
        makeMultiLine(extraBodyInput, 2, 5);
        setHint(extraBodyInput, "morphe_music_lyrics_ai_config_extra_body_hint");
        urlBox[0] = addBaseUrlInput(content, Settings.LYRICS_AI_BASE_URL.get(), preset -> {
            modelInput.setText(preset.model());
        });
        final EditText urlInput = urlBox[0];
        addTestButton(context, content, () -> {
            final String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                return;
            }
            test(context, () -> OpenAIClient.validateConfig(url,
                    tokenInput.getText().toString().trim(),
                    modelInput.getText().toString().trim()) ? List.of("ok") : null,
                    AiProviders.OPENAI_COMPATIBLE_LABEL);
        });
        final PromptField promptField = addPromptSection(content, prompt);

        return createDialog(context,
                AiProviders.OPENAI_COMPATIBLE_LABEL,
                content, onSaved,
                () -> {
                    final String extraBody = extraBodyInput.getText().toString().trim();
                    Settings.LYRICS_AI_EXTRA_BODY.save(extraBody);
                    Settings.LYRICS_AI_EXTRA_HEADERS.save(
                            extraHeadersInput.getText().toString().trim());
                    Settings.LYRICS_AI_BASE_URL.save(urlInput.getText().toString().trim());
                    Settings.LYRICS_AI_API_TOKEN.save(tokenInput.getText().toString().trim());
                    Settings.LYRICS_AI_MODEL.save(modelInput.getText().toString().trim());
                    promptField.save();
                },
                () -> {
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_BODY.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_HEADERS.resetToDefault();
                    promptField.reset();
                },
                () -> !OpenAIClient.isValidExtraBody(
                        extraBodyInput.getText().toString().trim()),
                () -> !OpenAIClient.isValidExtraHeaders(
                        extraHeadersInput.getText().toString().trim()),
                () -> {
                    final String baseUrl = urlInput.getText().toString().trim();
                    return !baseUrl.isEmpty()
                            && !baseUrl.startsWith("http://")
                            && !baseUrl.startsWith("https://");
                });
    }

    private static final String DEEPL_DASHBOARD_URL = "https://www.deepl.com/en/pro-account";

    private static Dialog deepLDialog(Activity context, Runnable onSaved) {
        LinearLayout content = createContent(context);
        EditText keyInput = addInput(content,
                "morphe_music_lyrics_ai_config_deepl_key_label",
                Settings.LYRICS_DEEPL_API_KEY.get(), true);

        final Button getKeyButton = CustomDialog.createButton(context, null,
                str("morphe_music_token_dialog_get_token"), () -> {
                    try {
                        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DEEPL_DASHBOARD_URL));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "DeepL dashboard button failure", ex);
                    }
                }, false, false);
        final LinearLayout.LayoutParams getKeyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        getKeyParams.topMargin = Dim.dp12;
        content.addView(getKeyButton, getKeyParams);

        return createDialog(context,
                AiProviders.DEEPL_LABEL,
                content, onSaved,
                () -> Settings.LYRICS_DEEPL_API_KEY.save(keyInput.getText().toString().trim()),
                () -> Settings.LYRICS_DEEPL_API_KEY.resetToDefault(),
                () -> keyInput.getText().toString().trim().isEmpty());
    }

    /** The prompt box and the setting behind it; a no-op pair when the service takes no prompt. */
    private static final class PromptField {
        @Nullable
        private final StringSetting setting;
        @Nullable
        private final EditText input;

        PromptField(@Nullable StringSetting setting, @Nullable EditText input) {
            this.setting = setting;
            this.input = input;
        }

        void save() {
            if (setting == null || input == null) {
                return;
            }
            final String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                setting.resetToDefault();
            } else {
                setting.save(text);
            }
            input.setText(setting.get());
        }

        void reset() {
            if (setting == null) {
                return;
            }
            setting.resetToDefault();
            if (input != null) {
                input.setText(setting.get());
            }
        }
    }

    /** The prompt box for a list whose service reads one; null for both members otherwise. */
    private static PromptField addPromptSection(LinearLayout content,
                                                @Nullable StringSetting prompt) {
        if (prompt == null) {
            return new PromptField(null, null);
        }
        final EditText input = addInput(content,
                "morphe_music_lyrics_ai_config_prompt_label", prompt.get(), false);
        makeMultiLine(input, 4, 8);
        return new PromptField(prompt, input);
    }

    interface Validator {
        boolean invalid();
    }

    static Dialog createDialog(Activity context, String title, LinearLayout content,
                               @Nullable Runnable onSaved, Runnable save, Runnable reset,
                               @Nullable Validator... validators) {
        final Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                context,
                title,
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    if (validators != null) {
                        for (Validator validator : validators) {
                            if (validator.invalid()) {
                                Utils.showToastShort(str(
                                        "morphe_music_lyrics_ai_config_status_invalid"));
                                return;
                            }
                        }
                    }
                    save.run();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_saved"));
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    reset.run();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_reset"));
                },
                true
        );

        final Dialog dialog = pair.first;
        final LinearLayout mainLayout = pair.second;
        final android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1, scrollParams);
        return dialog;
    }

    static LinearLayout createContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    private static void test(Activity context, Supplier<List<String>> request, String label) {
        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
        Utils.runOnBackgroundThread(() -> {
            List<String> result;
            try {
                result = request.get();
            } catch (Exception ex) {
                Logger.printDebug(() -> label + " validation failed", ex);
                result = null;
            }
            final List<String> response = result;
            Utils.runOnMainThread(() -> {
                if (response != null && !response.isEmpty()) {
                    Utils.showToastShort(str(
                            "morphe_music_lyrics_ai_config_status_connected",
                            label, response.get(0)));
                } else {
                    Utils.showToastShort(str(
                            "morphe_music_lyrics_ai_config_status_failed", label));
                }
            });
        });
    }

    /** The endpoint box and its preset button, added at the top of the form. */
    private static EditText addBaseUrlInput(LinearLayout section, String value,
                                           Consumer<AiProviders.EndpointPreset> onPresetPicked) {
        final Context context = section.getContext();
        EditText input = ThemedInput.create(context);
        input.setText(value);
        input.setHint(str("morphe_music_lyrics_ai_config_base_url_hint"));
        Button presets = Glyph.DOWN.create(context);
        presets.setContentDescription(str("morphe_music_lyrics_ai_config_base_url_presets"));
        presets.setOnClickListener(v -> {
            final List<AiProviders.EndpointPreset> items =
                    new ArrayList<>(AiProviders.ENDPOINT_PRESETS);
            items.sort(Comparator.comparing(AiProviders.EndpointPreset::label,
                    String.CASE_INSENSITIVE_ORDER));
            final String[] labels = items.stream()
                    .map(AiProviders.EndpointPreset::label)
                    .toArray(String[]::new);
            CustomDialogListPreference.createListDialog(context,
                    str("morphe_music_lyrics_ai_config_base_url_presets_title"),
                    labels, labels, null, index -> {
                        final AiProviders.EndpointPreset preset = items.get(index);
                        input.setText(preset.baseUrl());
                        input.setSelection(input.getText().length());
                        onPresetPicked.accept(preset);
                    }).show();
        });

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        LinearLayout.LayoutParams buttonParams = Glyph.DOWN.params();
        buttonParams.leftMargin = Dim.dp8;
        row.addView(presets, buttonParams);
        section.addView(row, 0);
        section.addView(createLabel(context, "morphe_music_lyrics_ai_config_base_url_label"), 0);
        return input;
    }

    static TextView createLabel(Context context, String textKey) {
        TextView label = new TextView(context);
        label.setText(str(textKey));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp8;
        params.bottomMargin = Dim.dp4;
        label.setLayoutParams(params);
        return label;
    }

    /** A triangle reads as play for the action button and as a menu for the two drop-downs. */
    private enum Glyph {
        /** Filled, laid out along the line height so it reads like the app's own play icon. */
        PLAY('▶', 11),
        /** A small open arrow in the line's upper half, where a drop-down arrow sits. */
        DOWN('▾', 13);

        /** Circles, so they read as icon buttons rather than as one-letter labels. */
        private static final int SIZE_DP = 36;

        final char character;
        final int sizeSp;

        Glyph(char character, int sizeSp) {
            this.character = character;
            this.sizeSp = sizeSp;
        }

        Button create(Context context) {
            Button button = new Button(context);
            button.setText(String.valueOf(character));
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            button.setTextColor(ThemeUtils.getAppForegroundColor());
            button.setBackground(CustomDialog.createRoundedBackground(SIZE_DP / 2,
                    ThemeUtils.getEditTextBackground()));
            button.setPadding(0, 0, 0, 0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            return button;
        }

        LinearLayout.LayoutParams params() {
            return new LinearLayout.LayoutParams(Dim.dp36, Dim.dp36);
        }
    }

    static EditText addInput(LinearLayout section, String labelKey, String value, boolean secret) {
        Context context = section.getContext();
        section.addView(createLabel(context, labelKey));
        EditText input = ThemedInput.create(context);
        if (secret) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        input.setText(value);
        section.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    static void makeMultiLine(EditText input, int minLines, int maxLines) {
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setMinLines(minLines);
        input.setMaxLines(maxLines);
        input.setVerticalScrollBarEnabled(true);
    }

    /** A hint defaults to a fixed grey the dark theme leaves unreadable, so it follows the app. */
    static void setHint(EditText input, String hintKey) {
        input.setHint(str(hintKey));
        input.setHintTextColor((0x99 << 24) | (ThemeUtils.getAppForegroundColor() & 0x00FFFFFF));
    }

    /** A model name input with a button that lists the models the provider offers. */
    static EditText addModelInput(LinearLayout section, String value,
                                  Supplier<List<String>> fetchModels) {
        final Context context = section.getContext();
        section.addView(createLabel(context, "morphe_music_lyrics_ai_config_model_label"));

        EditText input = ThemedInput.create(context);
        input.setText(value);
        Button fetch = Glyph.DOWN.create(context);
        fetch.setContentDescription(str("morphe_music_lyrics_ai_config_fetch_models"));
        fetch.setOnClickListener(v -> {
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetching_models"));
            Utils.runOnBackgroundThread(() -> {
                List<String> models;
                try {
                    models = fetchModels.get();
                } catch (Exception ex) {
                    models = null;
                }
                final List<String> fetched = models;
                Utils.runOnMainThread(() -> showModelPicker(context, fetched, input));
            });
        });

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        LinearLayout.LayoutParams buttonParams = Glyph.DOWN.params();
        buttonParams.leftMargin = Dim.dp8;
        row.addView(fetch, buttonParams);
        section.addView(row);
        return input;
    }

    private static void showModelPicker(Context context, @Nullable List<String> models, EditText target) {
        if (models == null || models.isEmpty()) {
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetch_models_failed"));
            return;
        }
        String[] items = models.toArray(new String[0]);
        CustomDialogListPreference.createListDialog(context,
                str("morphe_music_lyrics_ai_config_fetch_models_success"), items, items,
                target.getText().toString().trim(), index -> {
                    target.setText(items[index]);
                    target.setSelection(items[index].length());
                }).show();
    }

    private static void addTestButton(Context context, LinearLayout section, Runnable onTest) {
        Button testButton = Glyph.PLAY.create(context);
        testButton.setContentDescription(str("morphe_music_lyrics_ai_config_test_connection"));
        testButton.setOnClickListener(v -> onTest.run());
        LinearLayout.LayoutParams params = Glyph.PLAY.params();
        params.topMargin = Dim.dp8;
        params.bottomMargin = Dim.dp4;
        section.addView(testButton, params);
    }
}
