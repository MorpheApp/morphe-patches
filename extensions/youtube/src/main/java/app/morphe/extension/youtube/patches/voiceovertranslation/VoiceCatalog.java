/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1685
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Atomic snapshots of downloaded or cached Edge TTS voices. */
final class VoiceCatalog {

    public static final class Voice implements Comparable<Voice> {
        private static final String MULTILINGUAL_NEURAL_SUFFIX = "MultilingualNeural";
        private static final String EXPRESSIVE_SUFFIX = "Expressive";
        private static final String NEURAL_SUFFIX = "Neural";

        public final String id;
        /**
         * The BCP-47 primary language subtag, e.g. {@code "en"} from {@code "en-US-GuyNeural"}.
         */
        public final String languageTag;
        public final String countryTag;
        public final String flag;
        public final boolean isMale;
        public final String shortName;
        public final String dialogDisplayName;
        public final boolean isMultilingual;

        Voice(boolean isMale, String id) {
            final int dash1 = id.indexOf('-');
            int dash2 = id.indexOf('-', dash1 + 1);
            this.id = id;
            this.isMale = isMale;
            this.languageTag = id.substring(0, dash1);

            String country = id.substring(dash1 + 1, dash2);
            if (country.length() > 2) {
                int nextDash = id.indexOf('-', dash2 + 1);
                if (nextDash != -1) {
                    String part = id.substring(dash2 + 1, nextDash);
                    if (part.length() == 2) {
                        country = part;
                        dash2 = nextDash;
                    }
                }
            }
            this.countryTag = country;
            this.flag = countryToFlag(country);

            this.isMultilingual = id.contains(MULTILINGUAL_NEURAL_SUFFIX);
            this.shortName = id.substring(dash2 + 1)
                    .replace(MULTILINGUAL_NEURAL_SUFFIX, "")
                    .replace(EXPRESSIVE_SUFFIX, "")
                    .replace(NEURAL_SUFFIX, "");
            this.dialogDisplayName = flag + " " + shortName;
        }

        private static String countryToFlag(String countryCode) {
            if (countryCode == null || countryCode.length() != 2) return "";
            String code = countryCode.toUpperCase(Locale.ROOT);
            final int firstChar = code.charAt(0) - 'A' + 0x1F1E6;
            final int secondChar = code.charAt(1) - 'A' + 0x1F1E6;
            return new String(Character.toChars(firstChar)) + new String(Character.toChars(secondChar));
        }

        @Override
        public int compareTo(@NonNull Voice other) {
            if (this.isMale != other.isMale) return this.isMale ? -1 : 1;

            Locale deviceLocale = Locale.getDefault();
            String userLang = deviceLocale.getLanguage();
            String userCountry = deviceLocale.getCountry();

            final boolean thisLangMatch = this.languageTag.equalsIgnoreCase(userLang);
            final boolean otherLangMatch = other.languageTag.equalsIgnoreCase(userLang);
            if (thisLangMatch != otherLangMatch) return thisLangMatch ? -1 : 1;

            if (!userCountry.isEmpty()) {
                final boolean thisCountryMatch = this.countryTag.equalsIgnoreCase(userCountry);
                final boolean otherCountryMatch = other.countryTag.equalsIgnoreCase(userCountry);
                if (thisCountryMatch != otherCountryMatch) return thisCountryMatch ? -1 : 1;
            }

            final int countryComp = this.countryTag.compareToIgnoreCase(other.countryTag);
            if (countryComp != 0) return countryComp;
            return this.shortName.compareToIgnoreCase(other.shortName);
        }

        @NonNull
        @Override
        public String toString() {
            return "Voice{" +
                    "id='" + id + '\'' +
                    ", languageTag='" + languageTag + '\'' +
                    ", countryTag='" + countryTag + '\'' +
                    ", flag='" + flag + '\'' +
                    ", isMale=" + isMale +
                    ", shortName='" + shortName + '\'' +
                    ", dialogDisplayName='" + dialogDisplayName + '\'' +
                    ", isMultilingual=" + isMultilingual +
                    '}';
        }
    }

    // Readers on UI, synthesis, and prefetch threads all see one complete snapshot.
    private static volatile Snapshot current = Snapshot.of(Collections.emptyList());

    /** Replace the complete catalog atomically; never publish a partially parsed response. */
    static void setAvailableVoices(List<Voice> voices) {
        current = Snapshot.of(voices);
    }

    private VoiceCatalog() {}

    @Nullable
    static Voice getVoice(String id) {
        return current.byId.get(id);
    }

    /** Return native-language and multilingual voices for a BCP 47 or ISO 639 tag. */
    @Nullable
    static List<Voice> getVoicesForLang(String lang) {
        return current.byLanguage.get(getIso639(lang));
    }

    public static String getIso639(String lang) {
        final int separatorIndex = lang.indexOf('-');
        return separatorIndex > 0 ? lang.substring(0, separatorIndex) : lang;
    }

    /** Keep the saved selection if still available; otherwise fall back to the language default. */
    @Nullable
    static String resolve(String lang, @Nullable String preferredVoiceId) {
        Snapshot snapshot = current;
        List<Voice> voices = snapshot.byLanguage.get(getIso639(lang));
        if (voices == null || voices.isEmpty()) voices = snapshot.byLanguage.get("en");
        if (voices == null || voices.isEmpty()) return null;

        if (preferredVoiceId != null) {
            for (Voice voice : voices) {
                if (voice.id.equals(preferredVoiceId)) return voice.id;
            }
        }
        return voices.get(0).id;
    }

    private static final class Snapshot {
        final Map<String, Voice> byId;
        final Map<String, List<Voice>> byLanguage;

        private Snapshot(Map<String, Voice> byId, Map<String, List<Voice>> byLanguage) {
            this.byId = Collections.unmodifiableMap(byId);
            this.byLanguage = Collections.unmodifiableMap(byLanguage);
        }

        static Snapshot of(List<Voice> voices) {
            Map<String, Voice> byId = new LinkedHashMap<>();
            Map<String, List<Voice>> byLanguage = new HashMap<>();
            List<Voice> multilingual = new ArrayList<>();

            for (Voice voice : voices) {
                if (byId.putIfAbsent(voice.id, voice) != null) continue;
                byLanguage.computeIfAbsent(voice.languageTag, key -> new ArrayList<>()).add(voice);
                if (voice.isMultilingual) multilingual.add(voice);
            }
            for (Map.Entry<String, List<Voice>> entry : byLanguage.entrySet()) {
                List<Voice> candidates = entry.getValue();
                for (Voice voice : multilingual) {
                    if (!voice.languageTag.equals(entry.getKey())) candidates.add(voice);
                }
                entry.setValue(Collections.unmodifiableList(candidates));
            }
            return new Snapshot(byId, byLanguage);
        }
    }
}
