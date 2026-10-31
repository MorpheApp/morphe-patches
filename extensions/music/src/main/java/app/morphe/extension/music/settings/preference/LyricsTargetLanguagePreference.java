/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
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
        final String labelKey = isTranslation()
                ? "morphe_music_lyrics_translation_target_language"
                : "morphe_music_lyrics_romanization_target_language";
        final String name = currentLanguageName();
        setSummary(str(labelKey, name == null ? "" : name));
    }

    /**
     * Opens the language picker for the side named by {@code translation}. {@code onPicked} runs
     * after the new language is saved.
     */
    public static void showLanguageDialog(Context context, boolean translation,
            @Nullable Runnable onPicked) {
        final CharSequence[] entries = buildEntries(translation);
        final CharSequence[] values = buildValues(translation);
        if (entries == null || values == null || entries.length != values.length) {
            return;
        }
        final String title = str(translation
                ? "morphe_music_lyrics_translation_language_title"
                : "morphe_music_lyrics_romanize_language_title");
        final String selected = languageSetting(translation).get();
        CustomDialogListPreference.createListDialog(context, title, entries, values, selected,
                index -> {
                    final String picked = values[index].toString();
                    if (picked.equals(selected)) {
                        return;
                    }
                    languageSetting(translation).save(picked);
                    if (onPicked != null) {
                        onPicked.run();
                    }
                }).show();
    }

    /**
     * The display name of the stored language: the entry matching the stored value, or "App
     * language" for Automatic. Null leaves the summary with nothing but the label.
     */
    @Nullable
    private String currentLanguageName() {
        final CharSequence[] entries;
        final CharSequence[] values;
        try {
            entries = buildEntries();
            values = buildValues();
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
    private CharSequence[] buildEntries() {
        return buildEntries(isTranslation());
    }

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
        final List<CharSequence> merged = new ArrayList<>(base.length);
        for (CharSequence entry : base) {
            merged.add(entry);
        }
        final List<String> added = addedLanguages(values);
        for (String code : added) {
            final String label = RimeDictStore.customLabel(code);
            merged.add((label == null ? code : label) + " (Rime)");
        }
        return merged.toArray(new CharSequence[0]);
    }

    @Nullable
    private CharSequence[] buildValues() {
        return buildValues(isTranslation());
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
        final List<CharSequence> merged = new ArrayList<>(base.length);
        for (CharSequence entry : base) {
            merged.add(entry);
        }
        for (String code : addedLanguages(base)) {
            merged.add(code.toUpperCase(Locale.ROOT));
        }
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
