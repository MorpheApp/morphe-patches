/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.romanizers.BopomofoDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.CldrTransformsStore;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * The target language row under a translation or romanization service list. The language is
 * shown as one line under the providers it belongs to rather than as a row of its own, so the
 * list reads as the whole service: the providers, and the language they read into.
 *
 * <p>Tap opens the same language list the old standalone row did. The romanization list also
 * carries the languages the user added a Rime word table for, which is why it is rebuilt here
 * rather than fixed by the array.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsTargetLanguagePreference extends Preference {

    private static final String KEY_TRANSLATION_LANGUAGE = "morphe_music_lyrics_translation_language";
    private static final String KEY_ROMANIZE_LANGUAGE = "morphe_music_lyrics_romanize_language";
    private static final String KEY_RUBY_LANGUAGE = "morphe_music_lyrics_ruby_language";

    private static final String SYRIAC = "SYC";

    public LyricsTargetLanguagePreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsTargetLanguagePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsTargetLanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsTargetLanguagePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Nothing is stored here; the tap is what opens the list.
        setPersistent(false);
        setSelectable(true);
    }

    @Override
    protected void onAttachedToHierarchy(android.preference.PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        refreshSummary();
    }

    /** The setting the tap writes, chosen by the key the row was declared with. */
    private StringSetting setting() {
        return languageSetting(isTranslation());
    }

    private static StringSetting languageSetting(boolean translation) {
        return translation ? Settings.LYRICS_TRANSLATION_LANGUAGE
                : Settings.LYRICS_ROMANIZE_LANGUAGE;
    }

    private boolean isTranslation() {
        return KEY_TRANSLATION_LANGUAGE.equals(getKey());
    }

    /** "Translation target language: App language", or the romanization counterpart. */
    private void refreshSummary() {
        final boolean translation = isTranslation();
        final String labelKey = translation
                ? "morphe_music_lyrics_translation_target_language"
                : "morphe_music_lyrics_romanization_target_language";
        if (translation) {
            final String name = currentLanguageName();
            setSummary(str(labelKey, name == null ? "" : name));
        } else {
            final String name1 = currentLanguageName();
            final String suffix = rubyDisplaySuffix();
            setSummary(str(labelKey, name1 == null ? "" : name1)
                    + (suffix != null ? suffix : ""));
        }
    }

    /**
     * The provider name that reads the ruby target, or null when it should not be
     * shown: NONE, DEFAULT (Automatic), or same language as romaji. A language the user
     * added a Rime table for keeps its own name, since no shipped provider reads it.
     */
    @Nullable
    public static String rubyProviderLabel() {
        final String v1 = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        final String v2 = Settings.LYRICS_RUBY_LANGUAGE.get();
        if ("NONE".equalsIgnoreCase(v2) || "DEFAULT".equalsIgnoreCase(v2) || v1.equals(v2)) {
            return null;
        }
        // A language the user added a Rime table for keeps its own name.
        for (String code : RimeDictStore.customLanguages()) {
            if (code.equalsIgnoreCase(v2) && !RimeDictStore.isReserved(code)) {
                return RimeDictStore.customLabel(code);
            }
        }
        // A shipped language reads from a named dictionary, so the provider is shown.
        if ("ja".equalsIgnoreCase(v2) || "ja-Hira".equalsIgnoreCase(v2) || "ja-Kana".equalsIgnoreCase(v2)) {
            return JapaneseDictStore.isReady() ? "SudachiDict" : null;
        }
        if ("BOPOMOFO".equalsIgnoreCase(v2)) {
            return BopomofoDictStore.isReady() ? "McBopomofo" : null;
        }
        return null;
    }

    /**
     * A suffix like " (with SudachiDict)" for the ruby target, built from
     * {@link #rubyProviderLabel()}, or null when there is no provider to show.
     */
    @Nullable
    public static String rubyDisplaySuffix() {
        final String label = rubyProviderLabel();
        return label != null ? " (with " + label + ")" : null;
    }

    @Nullable
    private static String rubyName(String value) {
        try {
            final CharSequence[] entries = buildEntries(false);
            final CharSequence[] values = buildValues(false);
            return entryFor(values, entries, value);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read ruby language name", ex);
            return null;
        }
    }

    @Nullable
    private String currentRomaji2LanguageName() {
        final String value = Settings.LYRICS_RUBY_LANGUAGE.get();
        if ("NONE".equalsIgnoreCase(value)) {
            return null;
        }
        return rubyName(value);
    }

    /**
     * Opens the language picker for the side named by {@code translation}. {@code onPicked} runs
     * after the new language is saved.
     */
    public static void showLanguageDialog(Context context, boolean translation,
            @Nullable Runnable onPicked) {
        showLanguageDialogFor(context, translation, onPicked, false);
    }

    private static void showLanguageDialogFor(Context context, boolean translation,
            @Nullable Runnable onPicked, boolean isRomaji2) {
        final CharSequence[] entries = isRomaji2
                ? buildRubyEntries()
                : buildEntries(translation);
        final CharSequence[] values = isRomaji2
                ? buildRubyValues()
                : buildValues(translation);
        if (entries == null || values == null || entries.length != values.length) {
            return;
        }
        final StringSetting setting;
        final String title;
        final CharSequence neutralText;
        if (translation) {
            title = str("morphe_music_lyrics_translation_language_title");
            setting = Settings.LYRICS_TRANSLATION_LANGUAGE;
            neutralText = null;
        } else if (isRomaji2) {
            title = str("morphe_music_lyrics_ruby_language_title");
            setting = Settings.LYRICS_RUBY_LANGUAGE;
            neutralText = null;
        } else {
            title = str("morphe_music_lyrics_romanize_language_title");
            setting = Settings.LYRICS_ROMANIZE_LANGUAGE;
            neutralText = str("morphe_music_lyrics_ruby_language_title");
        }
        final String selected = setting.get();

        final Dialog[] dlg = new Dialog[1];
        final Runnable roma1NeutralClick = () -> {
            dlg[0].dismiss();
            showLanguageDialogFor(context, translation, onPicked, true);
        };
        dlg[0] = createLanguageListDialog(context, title, entries, values, selected,
                index -> {
                    final String picked = values[index].toString();
                    if (picked.equals(selected)) {
                        return;
                    }
                    if (!translation && "NONE".equalsIgnoreCase(picked)) {
                        final StringSetting other = isRomaji2
                                ? Settings.LYRICS_ROMANIZE_LANGUAGE
                                : Settings.LYRICS_RUBY_LANGUAGE;
                        if ("NONE".equalsIgnoreCase(other.get())) {
                            other.save("DEFAULT");
                        }
                    }
                    setting.save(picked);
                    if (onPicked != null) {
                        onPicked.run();
                    }
                },
                neutralText,
                neutralText != null ? roma1NeutralClick : null);
        dlg[0].show();
    }

    /**
     * A list dialog that also carries a Neutral button, which the shared
     * {@link CustomDialogListPreference#createListDialog} does not support.
     * Same geometry and adapter; the Neutral button opens the ruby language picker.
     */
    private static Dialog createLanguageListDialog(Context context, String title,
            CharSequence[] entries, CharSequence[] entryValues,
            @Nullable String selectedValue, IntConsumer onSelected,
            @Nullable CharSequence neutralText, @Nullable Runnable neutralClick) {
        ListView listView = new ListView(context);
        listView.setId(android.R.id.list);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setDividerHeight(0);

        CustomDialogListPreference.ListPreferenceArrayAdapter adapter =
                new CustomDialogListPreference.ListPreferenceArrayAdapter(
                        context, entries, entryValues, selectedValue);
        listView.setAdapter(adapter);

        if (selectedValue != null) {
            for (int i = 0, length = entryValues.length; i < length; i++) {
                if (selectedValue.equals(entryValues[i].toString())) {
                    listView.setItemChecked(i, true);
                    listView.setSelection(i);
                    break;
                }
            }
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context, title, null, null, null, null,
                () -> {}, // Cancel button action.
                neutralText, neutralClick, true);

        Dialog dialog = dialogPair.first;

        LinearLayout mainLayout = dialogPair.second;
        LinearLayout.LayoutParams listViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mainLayout.addView(listView, mainLayout.getChildCount() - 1, listViewParams);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            onSelected.accept(position);
            dialog.dismiss();
        });

        return dialog;
    }

    /**
     * The display name of the stored language: the entry matching the stored value, or "App
     * language" for Automatic. Null leaves the summary with nothing but the label.
     */
    @Nullable
    private String currentLanguageName() {
        final boolean translation = isTranslation();
        final CharSequence[] entries;
        final CharSequence[] values;
        try {
            entries = buildEntries(translation);
            values = buildValues(translation);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the language list", ex);
            return null;
        }
        return entryFor(values, entries, setting().get());
    }

    /**
     * The label of the row a stored value names, or null when the list no longer has one. A value
     * whose row is gone has to read as absent rather than as a blank summary, which is what the
     * caller falls back from.
     */
    @Nullable
    private static String entryFor(@Nullable CharSequence[] values, @Nullable CharSequence[] entries,
            @Nullable String value) {
        if (value == null || values == null || entries == null
                || values.length != entries.length) {
            return null;
        }
        for (int i = 0; i < values.length; i++) {
            if (value.equalsIgnoreCase(values[i].toString())) {
                return entries[i].toString();
            }
        }
        return null;
    }

    /**
     * The list to show, which also names the summary: the shipped array plus, for
     * romanization, one row per language the user added a Rime word table for.
     */
    @Nullable
    private static CharSequence[] buildEntries(boolean translation) {
        final CharSequence[] base;
        if (translation) {
            base = ResourceUtils.getStringArray("morphe_language_entries");
        } else {
            base = ResourceUtils.getStringArray(KEY_ROMANIZE_LANGUAGE + "_entries");
        }
        final CharSequence[] values = translation
                ? ResourceUtils.getStringArray("morphe_language_entry_values")
                : ResourceUtils.getStringArray(KEY_ROMANIZE_LANGUAGE + "_entry_values");
        if (translation || base == null || values == null || base.length != values.length) {
            return base;
        }
        final List<CharSequence> merged = new ArrayList<>(base.length + 1);
        for (int i = 0; i < base.length; i++) {
            if (!CldrTransformsStore.isReady()
                    && SYRIAC.equals(values[i].toString())) {
                continue;
            }
            String entry = base[i].toString();
            String value = values[i].toString();
            if ("ja".equalsIgnoreCase(value) || "ja-Hira".equalsIgnoreCase(value) || "ja-Kana".equalsIgnoreCase(value)) {
                if (JapaneseDictStore.isReady()) {
                    entry += " (SudachiDict)";
                }
            } else if ("BOPOMOFO".equalsIgnoreCase(value)) {
                if (BopomofoDictStore.isReady()) {
                    entry += " (McBopomofo)";
                }
            }
            merged.add(entry);
        }
        final List<String> added = addedLanguages(values);
        for (String code : added) {
            final String label = RimeDictStore.customLabel(code);
            merged.add((label == null ? code : label) + " (Rime)");
        }
        merged.add(str("morphe_music_lyrics_romanize_language_none"));
        return merged.toArray(new CharSequence[0]);
    }

    @Nullable
    private static CharSequence[] buildValues(boolean translation) {
        final CharSequence[] base;
        if (translation) {
            base = ResourceUtils.getStringArray("morphe_language_entry_values");
        } else {
            base = ResourceUtils.getStringArray(KEY_ROMANIZE_LANGUAGE + "_entry_values");
        }
        if (translation || base == null) {
            return base;
        }
        final List<CharSequence> merged = new ArrayList<>(base.length + 1);
        for (CharSequence entry : base) {
            if (!CldrTransformsStore.isReady()
                    && SYRIAC.equals(entry.toString())) {
                continue;
            }
            merged.add(entry);
        }
        for (String code : addedLanguages(base)) {
            merged.add(code.toUpperCase(Locale.ROOT));
        }
        merged.add("NONE");
        return merged.toArray(new CharSequence[0]);
    }

    /** The ruby language list: shipped dictionaries plus Rime custom languages. */
    @Nullable
    private static CharSequence[] buildRubyEntries() {
        final CharSequence[] base = ResourceUtils.getStringArray(
                KEY_ROMANIZE_LANGUAGE + "_entries");
        final CharSequence[] values = ResourceUtils.getStringArray(
                KEY_ROMANIZE_LANGUAGE + "_entry_values");
        if (base == null || values == null || base.length != values.length) {
            return null;
        }
        final List<CharSequence> merged = new ArrayList<>(base.length + 1);
        for (int i = 0; i < base.length; i++) {
            String value = values[i].toString();
            if ("DEFAULT".equalsIgnoreCase(value)
                    || "ja".equalsIgnoreCase(value)
                    || "ja-Hira".equalsIgnoreCase(value)
                    || "ja-Kana".equalsIgnoreCase(value)
                    || "BOPOMOFO".equalsIgnoreCase(value)) {
                String entry = base[i].toString();
                if ("ja".equalsIgnoreCase(value) || "ja-Hira".equalsIgnoreCase(value)
                        || "ja-Kana".equalsIgnoreCase(value)) {
                    if (JapaneseDictStore.isReady()) {
                        entry += " (SudachiDict)";
                    }
                } else if ("BOPOMOFO".equalsIgnoreCase(value)) {
                    if (BopomofoDictStore.isReady()) {
                        entry += " (McBopomofo)";
                    }
                }
                merged.add(entry);
            }
        }
        final List<String> added = addedLanguages(values);
        for (String code : added) {
            final String label = RimeDictStore.customLabel(code);
            merged.add((label == null ? code : label) + " (Rime)");
        }
        merged.add(str("morphe_music_lyrics_romanize_language_none"));
        return merged.toArray(new CharSequence[0]);
    }

    @Nullable
    private static CharSequence[] buildRubyValues() {
        final CharSequence[] base = ResourceUtils.getStringArray(
                KEY_ROMANIZE_LANGUAGE + "_entry_values");
        if (base == null) {
            return null;
        }
        final List<CharSequence> merged = new ArrayList<>(base.length + 1);
        for (CharSequence value : base) {
            String v = value.toString();
            if ("DEFAULT".equalsIgnoreCase(v)
                    || "ja".equalsIgnoreCase(v)
                    || "ja-Hira".equalsIgnoreCase(v)
                    || "ja-Kana".equalsIgnoreCase(v)
                    || "BOPOMOFO".equalsIgnoreCase(v)) {
                merged.add(value);
            }
        }
        for (String code : addedLanguages(base)) {
            merged.add(code.toUpperCase(Locale.ROOT));
        }
        merged.add("NONE");
        return merged.toArray(new CharSequence[0]);
    }



    /** A language the user added a Rime word table for, unless this build already reads it. */
    private static List<String> addedLanguages(CharSequence[] shipped) {
        final List<String> added = new ArrayList<>();
        for (String code : RimeDictStore.customLanguages()) {
            if (RimeDictStore.isReserved(code)) {
                continue;
            }
            boolean listed = false;
            for (CharSequence value : shipped) {
                if (value.toString().equalsIgnoreCase(code)) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                added.add(code);
            }
        }
        return added;
    }

    @Override
    protected void onClick() {
        if (!isEnabled()) {
            return;
        }
        try {
            showLanguageDialog();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the language dialog", ex);
        }
    }

    private void showLanguageDialog() {
        showLanguageDialog(getContext(), isTranslation(), this::refreshSummary);
    }
}