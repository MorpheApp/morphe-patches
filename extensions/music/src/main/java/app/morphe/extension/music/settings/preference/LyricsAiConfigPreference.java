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
import android.graphics.Typeface;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import app.morphe.extension.music.patches.lyrics.derive.AiProviders;
import app.morphe.extension.music.patches.lyrics.derive.DeepLClient;
import app.morphe.extension.music.patches.lyrics.derive.GeminiClient;
import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Holds the API keys, models, custom endpoints, and prompt templates for all AI and cloud providers.
 * Translation and Romanization provider selection happens in their respective settings sections.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiConfigPreference extends Preference {

    private static final String TRANSLATION_LABEL_PREFIX = "morphe_music_lyrics_translation_provider_";

    private boolean dialogShowing = false;

    public LyricsAiConfigPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateSummary();
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateSummary();
    }

    public LyricsAiConfigPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateSummary();
    }

    public LyricsAiConfigPreference(Context context) {
        super(context);
        updateSummary();
    }

    private void updateSummary() {
        setSummary(str("morphe_music_lyrics_ai_config_summary"));
    }


    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        if (dialogShowing) return;
        Context context = getContext();
        if (!(context instanceof Activity activity) || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }
        dialogShowing = true;

        ScrollView scrollView = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_lyrics_ai_config_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        String[] endpointFilter = {"all"};
        content.addView(createLabel(context, "morphe_music_lyrics_ai_config_endpoint_label"));
        Button endpointFilterButton = createButton(context, str("morphe_music_lyrics_ai_config_show_all"));
        content.addView(endpointFilterButton);

        // Gemini.
        LinearLayout geminiSection = createSection(context, "morphe_music_lyrics_ai_config_gemini_section");
        EditText geminiKeyInput = addInput(geminiSection, "morphe_music_lyrics_ai_config_api_key_label",
                Settings.LYRICS_GEMINI_API_KEY.get(), true);
        EditText geminiModelInput = addModelInput(geminiSection, Settings.LYRICS_GEMINI_MODEL.get(),
                () -> GeminiClient.fetchModels(geminiKeyInput.getText().toString().trim()));
        addTestButton(geminiSection, () -> {
            String key = geminiKeyInput.getText().toString().trim();
            String model = geminiModelInput.getText().toString().trim();
            if (key.isEmpty()) {
                Utils.showToastShort("Enter Gemini API key first");
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
            Utils.runOnBackgroundThread(() -> {
                List<String> res = GeminiClient.mapLines(key, model, "Translate to Japanese:\n1. hello", Collections.singletonList("hello"));
                Utils.runOnMainThread(() -> {
                    if (res != null && !res.isEmpty()) {
                        Utils.showToastShort("Gemini: Connected! (" + res.get(0) + ")");
                    } else {
                        Utils.showToastShort("Gemini: Connection failed. Check API key and model.");
                    }
                });
            });
        });
        content.addView(geminiSection);

        // A custom OpenAI-compatible endpoint.
        LinearLayout compatibleSection = createSection(context, "morphe_music_lyrics_ai_config_openai_compat_section");
        EditText compatibleUrlInput = addInput(compatibleSection, "morphe_music_lyrics_ai_config_base_url_label",
                Settings.LYRICS_AI_BASE_URL.get(), false);
        compatibleUrlInput.setHint(str("morphe_music_lyrics_ai_config_base_url_hint"));
        EditText compatibleTokenInput = addInput(compatibleSection, "morphe_music_lyrics_ai_config_api_token_label",
                Settings.LYRICS_AI_API_TOKEN.get(), true);
        EditText compatibleModelInput = addModelInput(compatibleSection, Settings.LYRICS_AI_MODEL.get(),
                () -> OpenAIClient.fetchModels(compatibleUrlInput.getText().toString().trim(),
                        compatibleTokenInput.getText().toString().trim()));
        EditText extraBodyInput = addInput(compatibleSection, "morphe_music_lyrics_ai_config_extra_body_label",
                Settings.LYRICS_AI_EXTRA_BODY.get(), false);
        makeMultiLine(extraBodyInput, 2, 5);
        addTestButton(compatibleSection, () -> {
            String url = compatibleUrlInput.getText().toString().trim();
            String token = compatibleTokenInput.getText().toString().trim();
            String model = compatibleModelInput.getText().toString().trim();
            if (url.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
            Utils.runOnBackgroundThread(() -> {
                boolean ok = OpenAIClient.validateConfig(url, token, model);
                Utils.runOnMainThread(() -> {
                    if (ok) {
                        Utils.showToastShort("OpenAI-Compatible: Connected successfully!");
                    } else {
                        Utils.showToastShort("OpenAI-Compatible: Connection failed. Check URL, token, and model.");
                    }
                });
            });
        });
        content.addView(compatibleSection);

        // DeepL.
        LinearLayout deepLSection = createSection(context, "morphe_music_lyrics_ai_config_deepl_section");
        EditText deepLKeyInput = addInput(deepLSection, "morphe_music_lyrics_ai_config_deepl_key_label",
                Settings.LYRICS_DEEPL_API_KEY.get(), true);
        addTestButton(deepLSection, () -> {
            String key = deepLKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                Utils.showToastShort("Enter DeepL authentication key first");
                return;
            }
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
            Utils.runOnBackgroundThread(() -> {
                List<String> res = DeepLClient.translate(Collections.singletonList("hello"), "JA", key);
                Utils.runOnMainThread(() -> {
                    if (res != null && !res.isEmpty()) {
                        Utils.showToastShort("DeepL: Connected! (" + res.get(0) + ")");
                    } else {
                        Utils.showToastShort("DeepL: Connection failed. Check authentication key.");
                    }
                });
            });
        });
        content.addView(deepLSection);

        // AI Prompt Templates.
        LinearLayout promptSection = createSection(context, "morphe_music_lyrics_ai_prompt_dialog_title");
        EditText promptInput = addInput(promptSection, "morphe_music_lyrics_ai_config_prompt_label",
                Settings.LYRICS_AI_TRANSLATION_PROMPT.get(), false);
        makeMultiLine(promptInput, 3, 6);
        EditText promptRomajiInput = addInput(promptSection, "morphe_music_lyrics_ai_config_prompt_romaji_label",
                Settings.LYRICS_AI_ROMANIZATION_PROMPT.get(), false);
        makeMultiLine(promptRomajiInput, 3, 6);
        EditText promptSectionInput = addInput(promptSection, "morphe_music_lyrics_ai_config_prompt_section_label",
                Settings.LYRICS_AI_SECTION_PROMPT.get(), false);
        makeMultiLine(promptSectionInput, 3, 6);
        content.addView(promptSection);

        Runnable updateVisibility = () -> {
            boolean showAll = "all".equals(endpointFilter[0]);
            geminiSection.setVisibility(showAll || AiProviders.GEMINI.equals(endpointFilter[0]) ? View.VISIBLE : View.GONE);
            compatibleSection.setVisibility(showAll || AiProviders.OPENAI_COMPATIBLE.equals(endpointFilter[0]) ? View.VISIBLE : View.GONE);
            deepLSection.setVisibility(showAll || AiProviders.DEEPL.equals(endpointFilter[0]) ? View.VISIBLE : View.GONE);
            promptSection.setVisibility(showAll || "prompts".equals(endpointFilter[0]) ? View.VISIBLE : View.GONE);
        };
        updateVisibility.run();

        endpointFilterButton.setOnClickListener(v -> {
            String[] filterValues = {
                    "all",
                    AiProviders.GEMINI,
                    AiProviders.OPENAI_COMPATIBLE,
                    AiProviders.DEEPL,
                    "prompts"
            };
            String[] filterLabels = {
                    str("morphe_music_lyrics_ai_config_show_all"),
                    str(TRANSLATION_LABEL_PREFIX + AiProviders.GEMINI),
                    str(TRANSLATION_LABEL_PREFIX + AiProviders.OPENAI_COMPATIBLE),
                    str(TRANSLATION_LABEL_PREFIX + AiProviders.DEEPL),
                    str("morphe_music_lyrics_ai_prompt_dialog_title")
            };
            CustomDialogListPreference.createListDialog(context,
                    str("morphe_music_lyrics_ai_config_endpoint_label"),
                    filterLabels, filterValues, endpointFilter[0], index -> {
                        endpointFilter[0] = filterValues[index];
                        endpointFilterButton.setText(filterLabels[index]);
                        updateVisibility.run();
                    }).show();
        });

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_ai_config_dialog_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String extraBody = extraBodyInput.getText().toString().trim();
                    if (!OpenAIClient.isValidExtraBody(extraBody)) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_extra_body_invalid"));
                        return;
                    }
                    String baseUrl = compatibleUrlInput.getText().toString().trim();
                    if (!baseUrl.isEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                        return;
                    }
                    Settings.LYRICS_AI_EXTRA_BODY.save(extraBody);
                    Settings.LYRICS_GEMINI_API_KEY.save(geminiKeyInput.getText().toString().trim());
                    Settings.LYRICS_GEMINI_MODEL.save(geminiModelInput.getText().toString().trim());
                    Settings.LYRICS_AI_BASE_URL.save(baseUrl);
                    Settings.LYRICS_AI_API_TOKEN.save(compatibleTokenInput.getText().toString().trim());
                    Settings.LYRICS_AI_MODEL.save(compatibleModelInput.getText().toString().trim());
                    Settings.LYRICS_AI_TRANSLATION_PROMPT.save(promptInput.getText().toString().trim());
                    Settings.LYRICS_AI_ROMANIZATION_PROMPT.save(promptRomajiInput.getText().toString().trim());
                    Settings.LYRICS_AI_SECTION_PROMPT.save(promptSectionInput.getText().toString().trim());
                    Settings.LYRICS_DEEPL_API_KEY.save(deepLKeyInput.getText().toString().trim());

                    updateSummary();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_saved"));
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    Settings.LYRICS_GEMINI_API_KEY.resetToDefault();
                    Settings.LYRICS_GEMINI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_TRANSLATION_PROMPT.resetToDefault();
                    Settings.LYRICS_AI_ROMANIZATION_PROMPT.resetToDefault();
                    Settings.LYRICS_AI_SECTION_PROMPT.resetToDefault();
                    Settings.LYRICS_AI_EXTRA_BODY.resetToDefault();
                    Settings.LYRICS_DEEPL_API_KEY.resetToDefault();
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_reset"));
                },
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1, scrollParams);

        dialog.setOnDismissListener(d -> dialogShowing = false);

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            dialogShowing = false;
            Logger.printException(() -> "Could not show the AI settings dialog", ex);
        }
    }

    private static LinearLayout createSection(Context context, String titleKey) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(context);
        header.setText(str(titleKey));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp16;
        section.addView(header, params);
        return section;
    }

    private static TextView createLabel(Context context, String textKey) {
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

    private static Button createButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(ThemeUtils.getAppForegroundColor());
        button.setBackground(CustomDialog.createRoundedBackground(10, ThemeUtils.getEditTextBackground()));
        button.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        return button;
    }

    private static EditText addInput(LinearLayout section, String labelKey, String value, boolean secret) {
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

    private static void makeMultiLine(EditText input, int minLines, int maxLines) {
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setMinLines(minLines);
        input.setMaxLines(maxLines);
        input.setVerticalScrollBarEnabled(true);
    }

    /** A model name input with a button that lists the models the provider offers. */
    private static EditText addModelInput(LinearLayout section, String value, Supplier<List<String>> fetchModels) {
        Context context = section.getContext();
        section.addView(createLabel(context, "morphe_music_lyrics_ai_config_model_label"));

        EditText input = ThemedInput.create(context);
        input.setText(value);
        Button fetch = createButton(context, str("morphe_music_lyrics_ai_config_fetch_models"));
        fetch.setOnClickListener(v -> {
            Utils.showToastShort(str("morphe_music_lyrics_ai_config_fetching_models"));
            Utils.runOnBackgroundThread(() -> {
                List<String> models = fetchModels.get();
                Utils.runOnMainThread(() -> showModelPicker(context, models, input));
            });
        });

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private static void addTestButton(LinearLayout section, Runnable onTest) {
        Context context = section.getContext();
        Button testButton = createButton(context, str("morphe_music_lyrics_ai_config_test_connection"));
        testButton.setOnClickListener(v -> onTest.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp8;
        params.bottomMargin = Dim.dp4;
        section.addView(testButton, params);
    }
}
