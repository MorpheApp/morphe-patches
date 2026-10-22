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
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.preference.Preference;
import android.preference.PreferenceManager;
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

import java.util.List;
import java.util.function.Supplier;

import app.morphe.extension.music.patches.lyrics.derive.AiProviders;
import app.morphe.extension.music.patches.lyrics.derive.GeminiClient;
import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.PinyinDictStore;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Picks the translation and romanization providers, and holds the keys and models of the ones
 * that need them. Only the sections of the picked providers are shown.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsAiConfigPreference extends Preference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String[] TRANSLATION_PROVIDERS = {
            AiProviders.GOOGLE,
            AiProviders.GEMINI,
            AiProviders.OPENROUTER,
            AiProviders.OPENAI,
            AiProviders.OPENAI_COMPATIBLE,
            AiProviders.DEEPL
    };

    private static final String[] ROMANIZATION_PROVIDERS = {
            AiProviders.GOOGLE,
            AiProviders.LOCAL,
            AiProviders.GEMINI,
            AiProviders.OPENROUTER,
            AiProviders.OPENAI,
            AiProviders.OPENAI_COMPATIBLE
    };

    private static final String TRANSLATION_LABEL_PREFIX = "morphe_music_lyrics_translation_provider_";
    private static final String ROMANIZATION_LABEL_PREFIX = "morphe_music_lyrics_romanization_provider_";

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
        setSummary(str("morphe_music_lyrics_ai_config_summary",
                str(TRANSLATION_LABEL_PREFIX + Settings.LYRICS_TRANSLATION_PROVIDER.get()),
                str(ROMANIZATION_LABEL_PREFIX + Settings.LYRICS_ROMANIZATION_PROVIDER.get())));
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        preferenceManager.getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    /** The providers can also be picked from their own list preferences. */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (Settings.LYRICS_TRANSLATION_PROVIDER.key.equals(key)
                || Settings.LYRICS_ROMANIZATION_PROVIDER.key.equals(key)) {
            updateSummary();
        }
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

        final String[] selectedTranslation = {Settings.LYRICS_TRANSLATION_PROVIDER.get()};
        final String[] selectedRomanization = {Settings.LYRICS_ROMANIZATION_PROVIDER.get()};
        content.addView(createLabel(context, "morphe_music_lyrics_ai_config_translation_provider_label"));
        Button translationButton = createButton(context, str(TRANSLATION_LABEL_PREFIX + selectedTranslation[0]));
        content.addView(translationButton);
        content.addView(createLabel(context, "morphe_music_lyrics_ai_config_romanization_provider_label"));
        Button romanizationButton = createButton(context, str(ROMANIZATION_LABEL_PREFIX + selectedRomanization[0]));
        content.addView(romanizationButton);

        // Gemini.
        LinearLayout geminiSection = createSection(context, "morphe_music_lyrics_ai_config_gemini_section");
        EditText geminiKeyInput = addInput(geminiSection, "morphe_music_lyrics_ai_config_api_key_label",
                Settings.LYRICS_GEMINI_API_KEY.get(), true);
        EditText geminiModelInput = addModelInput(geminiSection, Settings.LYRICS_GEMINI_MODEL.get(),
                () -> GeminiClient.fetchModels(geminiKeyInput.getText().toString().trim()));
        content.addView(geminiSection);

        // OpenRouter.
        LinearLayout openRouterSection = createSection(context, "morphe_music_lyrics_ai_config_openrouter_section");
        EditText openRouterKeyInput = addInput(openRouterSection, "morphe_music_lyrics_ai_config_api_key_label",
                Settings.LYRICS_OPENROUTER_API_KEY.get(), true);
        EditText openRouterModelInput = addModelInput(openRouterSection, Settings.LYRICS_OPENROUTER_MODEL.get(),
                () -> OpenAIClient.fetchModels(AiProviders.OPENROUTER_URL,
                        openRouterKeyInput.getText().toString().trim()));
        content.addView(openRouterSection);

        // OpenAI.
        LinearLayout openAiSection = createSection(context, "morphe_music_lyrics_ai_config_openai_section");
        EditText openAiKeyInput = addInput(openAiSection, "morphe_music_lyrics_ai_config_api_key_label",
                Settings.LYRICS_OPENAI_API_KEY.get(), true);
        EditText openAiModelInput = addModelInput(openAiSection, Settings.LYRICS_OPENAI_MODEL.get(),
                () -> OpenAIClient.fetchModels(AiProviders.OPENAI_URL,
                        openAiKeyInput.getText().toString().trim()));
        content.addView(openAiSection);

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
        EditText promptInput = addInput(compatibleSection, "morphe_music_lyrics_ai_config_prompt_label",
                Settings.LYRICS_AI_TRANSLATION_PROMPT.get(), false);
        makeMultiLine(promptInput, 3, 6);
        EditText promptRomajiInput = addInput(compatibleSection, "morphe_music_lyrics_ai_config_prompt_romaji_label",
                Settings.LYRICS_AI_ROMANIZATION_PROMPT.get(), false);
        makeMultiLine(promptRomajiInput, 3, 6);
        EditText extraBodyInput = addInput(compatibleSection, "morphe_music_lyrics_ai_config_extra_body_label",
                Settings.LYRICS_AI_EXTRA_BODY.get(), false);
        makeMultiLine(extraBodyInput, 2, 5);
        content.addView(compatibleSection);

        // DeepL.
        LinearLayout deepLSection = createSection(context, "morphe_music_lyrics_ai_config_deepl_section");
        EditText deepLKeyInput = addInput(deepLSection, "morphe_music_lyrics_ai_config_deepl_key_label",
                Settings.LYRICS_DEEPL_API_KEY.get(), true);
        content.addView(deepLSection);

        Runnable updateVisibility = () -> {
            setShown(geminiSection, AiProviders.GEMINI, selectedTranslation[0], selectedRomanization[0]);
            setShown(openRouterSection, AiProviders.OPENROUTER, selectedTranslation[0], selectedRomanization[0]);
            setShown(openAiSection, AiProviders.OPENAI, selectedTranslation[0], selectedRomanization[0]);
            setShown(compatibleSection, AiProviders.OPENAI_COMPATIBLE, selectedTranslation[0], selectedRomanization[0]);
            setShown(deepLSection, AiProviders.DEEPL, selectedTranslation[0], selectedRomanization[0]);
        };
        updateVisibility.run();

        translationButton.setOnClickListener(v -> showProviderPicker(context,
                "morphe_music_lyrics_ai_config_translation_provider_label",
                TRANSLATION_PROVIDERS, TRANSLATION_LABEL_PREFIX, selectedTranslation[0], provider -> {
                    selectedTranslation[0] = provider;
                    translationButton.setText(str(TRANSLATION_LABEL_PREFIX + provider));
                    updateVisibility.run();
                }));
        romanizationButton.setOnClickListener(v -> showProviderPicker(context,
                "morphe_music_lyrics_ai_config_romanization_provider_label",
                ROMANIZATION_PROVIDERS, ROMANIZATION_LABEL_PREFIX, selectedRomanization[0], provider -> {
                    selectedRomanization[0] = provider;
                    romanizationButton.setText(str(ROMANIZATION_LABEL_PREFIX + provider));
                    updateVisibility.run();
                }));

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_ai_config_dialog_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String extraBody = extraBodyInput.getText().toString().trim();
                    // Nothing is sent until this parses, so a body that will not is refused here, not saved.
                    if (!OpenAIClient.isValidExtraBody(extraBody)) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_extra_body_invalid"));
                        return;
                    }
                    String baseUrl = compatibleUrlInput.getText().toString().trim();
                    if (isUsed(AiProviders.OPENAI_COMPATIBLE, selectedTranslation[0], selectedRomanization[0])
                            && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid_url"));
                        return;
                    }
                    Settings.LYRICS_AI_EXTRA_BODY.save(extraBody);
                    Settings.LYRICS_TRANSLATION_PROVIDER.save(selectedTranslation[0]);
                    Settings.LYRICS_ROMANIZATION_PROVIDER.save(selectedRomanization[0]);
                    Settings.LYRICS_GEMINI_API_KEY.save(geminiKeyInput.getText().toString().trim());
                    Settings.LYRICS_GEMINI_MODEL.save(geminiModelInput.getText().toString().trim());
                    Settings.LYRICS_OPENROUTER_API_KEY.save(openRouterKeyInput.getText().toString().trim());
                    Settings.LYRICS_OPENROUTER_MODEL.save(openRouterModelInput.getText().toString().trim());
                    Settings.LYRICS_OPENAI_API_KEY.save(openAiKeyInput.getText().toString().trim());
                    Settings.LYRICS_OPENAI_MODEL.save(openAiModelInput.getText().toString().trim());
                    Settings.LYRICS_AI_BASE_URL.save(baseUrl);
                    Settings.LYRICS_AI_API_TOKEN.save(compatibleTokenInput.getText().toString().trim());
                    Settings.LYRICS_AI_MODEL.save(compatibleModelInput.getText().toString().trim());
                    Settings.LYRICS_AI_TRANSLATION_PROMPT.save(promptInput.getText().toString().trim());
                    Settings.LYRICS_AI_ROMANIZATION_PROMPT.save(promptRomajiInput.getText().toString().trim());
                    Settings.LYRICS_DEEPL_API_KEY.save(deepLKeyInput.getText().toString().trim());

                    if (AiProviders.LOCAL.equals(selectedRomanization[0])) {
                        if (!JapaneseDictStore.isReady()) JapaneseDictStore.ensure(null);
                        if (!PinyinDictStore.isReady()) PinyinDictStore.ensure(null);
                    }

                    updateSummary();
                    Utils.showToastShort(str("morphe_music_lyrics_ai_config_toast_saved"));
                },
                null,
                str("morphe_settings_reset"),
                () -> {
                    Settings.LYRICS_TRANSLATION_PROVIDER.resetToDefault();
                    Settings.LYRICS_ROMANIZATION_PROVIDER.resetToDefault();
                    Settings.LYRICS_GEMINI_API_KEY.resetToDefault();
                    Settings.LYRICS_GEMINI_MODEL.resetToDefault();
                    Settings.LYRICS_OPENROUTER_API_KEY.resetToDefault();
                    Settings.LYRICS_OPENROUTER_MODEL.resetToDefault();
                    Settings.LYRICS_OPENAI_API_KEY.resetToDefault();
                    Settings.LYRICS_OPENAI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_BASE_URL.resetToDefault();
                    Settings.LYRICS_AI_API_TOKEN.resetToDefault();
                    Settings.LYRICS_AI_MODEL.resetToDefault();
                    Settings.LYRICS_AI_TRANSLATION_PROMPT.resetToDefault();
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

    private static boolean isUsed(String provider, String translation, String romanization) {
        return provider.equals(translation) || provider.equals(romanization);
    }

    private static void setShown(View section, String provider, String translation, String romanization) {
        section.setVisibility(isUsed(provider, translation, romanization) ? View.VISIBLE : View.GONE);
    }

    private interface OnProviderSelected {
        void onSelected(String provider);
    }

    private static void showProviderPicker(Context context, String titleKey, String[] providers,
                                           String labelPrefix, String selected, OnProviderSelected onSelected) {
        String[] labels = new String[providers.length];
        for (int i = 0; i < providers.length; i++) {
            labels[i] = str(labelPrefix + providers[i]);
        }
        CustomDialogListPreference.createListDialog(context, str(titleKey), labels, providers,
                selected, index -> onSelected.onSelected(providers[index])).show();
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
}
