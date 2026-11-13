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
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.romanizers.CldrTransformsStore;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RubyGenerator;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

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
    private static final String DEFAULT = "DEFAULT";
    private static final String NONE = "NONE";
    private static final String JAPANESE_DICTIONARY = "SudachiDict";

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
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
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
        final String labelKey = isTranslation()
                ? "morphe_music_lyrics_translation_target_language"
                : "morphe_music_lyrics_romanization_target_language";
        final String name = currentLanguageName();
        final String summary = str(labelKey, name == null ? "" : name);
        final String rubyProvider = isTranslation() ? null : rubyProviderLabel();
        setSummary(rubyProvider == null ? summary
                : str("morphe_music_lyrics_with_ruby_provider", summary, rubyProvider));
    }

    /**
     * The provider name that reads the ruby target, or null when it should not be
     * shown: NONE, DEFAULT (Automatic), or same language as romaji. A language the user
     * added a Rime table for keeps its own name, since no shipped provider reads it.
     */
    @Nullable
    public static String rubyProviderLabel() {
        final String romanize = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        final String ruby = Settings.LYRICS_RUBY_LANGUAGE.get();
        if (NONE.equalsIgnoreCase(ruby) || DEFAULT.equalsIgnoreCase(ruby) || romanize.equals(ruby)) {
            return null;
        }
        if (isAddedByUser(ruby)) {
            return RimeDictStore.customLabel(ruby);
        }
        if (RubyGenerator.isJapaneseRubyLanguage(ruby)) {
            return JapaneseDictStore.isReady() ? JAPANESE_DICTIONARY : null;
        }
        return null;
    }

    /**
     * Opens the language picker for the side named by {@code translation}. {@code onPicked} runs
     * after the new language is saved.
     */
    public static void showLanguageDialog(Context context, boolean translation,
            @Nullable Runnable onPicked) {
        showLanguageDialog(context, translation, false, onPicked);
    }

    /** @param ruby Whether to show the Ruby languages, a part of the romanization list. */
    private static void showLanguageDialog(Context context, boolean translation, boolean ruby,
            @Nullable Runnable onPicked) {
        final CharSequence[] allEntries = buildEntries(translation);
        final CharSequence[] allValues = buildValues(translation);
        if (allEntries == null || allValues == null || allEntries.length != allValues.length) {
            return;
        }
        final List<CharSequence> entryList = new ArrayList<>(allEntries.length);
        final List<CharSequence> valueList = new ArrayList<>(allValues.length);
        for (int i = 0; i < allValues.length; i++) {
            if (!ruby || isRubyLanguage(allValues[i].toString())
                    || isAddedByUser(allValues[i].toString())) {
                entryList.add(allEntries[i]);
                valueList.add(allValues[i]);
            }
        }
        final CharSequence[] values = valueList.toArray(new CharSequence[0]);

        final StringSetting setting = ruby ? Settings.LYRICS_RUBY_LANGUAGE : languageSetting(translation);
        final String title = str(ruby
                ? "morphe_music_lyrics_ruby_language_title"
                : translation
                  ? "morphe_music_lyrics_translation_language_title"
                  : "morphe_music_lyrics_romanize_language_title");
        final boolean offersRuby = !translation && !ruby;
        final String selected = setting.get();

        final Dialog[] dialog = new Dialog[1];
        dialog[0] = CustomDialogListPreference.createListDialog(context, title,
                entryList.toArray(new CharSequence[0]), values, selected,
                index -> {
                    final String picked = values[index].toString();
                    if (picked.equals(selected)) {
                        return;
                    }
                    if (!translation && NONE.equalsIgnoreCase(picked)) {
                        // The romanization and the ruby cannot both be off.
                        final StringSetting other = ruby
                                ? Settings.LYRICS_ROMANIZE_LANGUAGE
                                : Settings.LYRICS_RUBY_LANGUAGE;
                        if (NONE.equalsIgnoreCase(other.get())) {
                            other.save(DEFAULT);
                        }
                    }
                    setting.save(picked);
                    if (onPicked != null) {
                        onPicked.run();
                    }
                },
                offersRuby ? str("morphe_music_lyrics_ruby_language_title") : null,
                offersRuby ? () -> {
                    dialog[0].dismiss();
                    showLanguageDialog(context, false, true, onPicked);
                } : null);
        dialog[0].show();
    }

    /** The languages the shipped dictionaries give a ruby for. */
    private static boolean isRubyLanguage(String value) {
        return DEFAULT.equalsIgnoreCase(value) || NONE.equalsIgnoreCase(value)
                || RubyGenerator.isJapaneseRubyLanguage(value);
    }

    private static boolean isAddedByUser(String value) {
        for (String code : RimeDictStore.customLanguages()) {
            if (code.equalsIgnoreCase(value) && !RimeDictStore.isReserved(code)) {
                return true;
            }
        }
        return false;
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
        if (translation || base.length != values.length) {
            return base;
        }
        final List<CharSequence> merged = new ArrayList<>(base.length + 1);
        for (int i = 0; i < base.length; i++) {
            if (!CldrTransformsStore.isReady()
                    && SYRIAC.equals(values[i].toString())) {
                continue;
            }
            final String value = values[i].toString();
            if (RubyGenerator.isJapaneseRubyLanguage(value) && JapaneseDictStore.isReady()) {
                merged.add(base[i] + " (" + JAPANESE_DICTIONARY + ")");
            } else {
                merged.add(base[i]);
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

    private static CharSequence[] buildValues(boolean translation) {
        final CharSequence[] base;
        if (translation) {
            base = ResourceUtils.getStringArray("morphe_language_entry_values");
        } else {
            base = ResourceUtils.getStringArray(KEY_ROMANIZE_LANGUAGE + "_entry_values");
        }
        if (translation) {
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
        merged.add(NONE);
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
