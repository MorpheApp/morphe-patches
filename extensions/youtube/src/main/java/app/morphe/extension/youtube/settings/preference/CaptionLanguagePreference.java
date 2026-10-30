/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.patches.CaptionTracks;
import app.morphe.extension.youtube.settings.Settings;

/** One localized translation catalogue for preferred and secondary caption languages. */
public final class CaptionLanguagePreference extends CustomDialogListPreference {
    private final boolean primary;

    public CaptionLanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        primary = "morphe_preferred_caption_language".equals(getKey());
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String selected = primary ? Settings.PREFERRED_CAPTION_LANGUAGE.get()
                : Settings.SECONDARY_CAPTION_LANGUAGE.get();
        String[] codes = CaptionTracks.languageCodes(selected);
        Collator collator = Collator.getInstance(locale);
        Arrays.sort(codes, (a, b) -> collator.compare(
                CaptionTracks.languageName(a, locale), CaptionTracks.languageName(b, locale)));
        int offset = primary ? 2 : 1;
        String[] values = new String[codes.length + offset];
        CharSequence[] labels = new CharSequence[values.length];
        values[0] = "off";
        labels[0] = StringRef.str("morphe_preferred_caption_language_entry_off");
        if (primary) {
            values[1] = "default";
            labels[1] = StringRef.str("morphe_language_DEFAULT");
        }
        for (int i = 0; i < codes.length; i++) {
            values[i + offset] = codes[i];
            labels[i + offset] = CaptionTracks.languageName(codes[i], locale);
        }
        setEntries(labels);
        setEntryValues(values);
    }

    @Override
    public CharSequence getSummary() {
        CharSequence selected = getEntry();
        return (selected == null ? "" : selected + "\n") + StringRef.str(primary
                ? "morphe_preferred_caption_language_summary" : "morphe_translate_caption_blocks_summary");
    }
}
