/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.patches.CaptionTracks;

import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;

/** Reuse the translation catalog without maintaining a second language list. */
public final class DualCaptionLanguagePreference extends CustomDialogListPreference {
    public DualCaptionLanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setDependency("morphe_translate_caption_blocks");
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String[] codes = CaptionTracks.languageCodes();
        Collator collator = Collator.getInstance(locale);
        Arrays.sort(
                codes,
                (a, b) ->
                        collator.compare(
                                Locale.forLanguageTag(a).getDisplayName(locale),
                                Locale.forLanguageTag(b).getDisplayName(locale)));
        String[] values = new String[codes.length + 1];
        CharSequence[] labels = new CharSequence[values.length];
        values[0] = "off";
        labels[0] = StringRef.str("morphe_preferred_caption_language_entry_off");
        for (int i = 0; i < codes.length; i++) {
            values[i + 1] = codes[i];
            labels[i + 1] = Locale.forLanguageTag(codes[i]).getDisplayName(locale);
        }
        setEntries(labels);
        setEntryValues(values);
    }
}
