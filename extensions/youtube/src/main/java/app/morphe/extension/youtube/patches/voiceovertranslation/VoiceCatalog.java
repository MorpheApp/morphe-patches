/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Static catalog of all Edge TTS voices known to this patch. Used both to populate the
 * voice picker UI and to resolve a voice id for a given language at TTS dispatch time.
 *
 * <p>Multilingual voices are also added to languages other than their native one, so a
 * user with an exotic caption language still has fallback options.
 */
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
            String code = countryCode.toUpperCase();
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

    // First voice for each language is used for 'app language' TTS voice type.
    private static final Voice[] ALL_VOICES = {
            new Voice(true, "af-ZA-WillemNeural"),
            new Voice(true, "am-ET-AmehaNeural"),
            new Voice(true, "ar-AE-HamdanNeural"),
            new Voice(true, "ar-BH-AliNeural"),
            new Voice(true, "ar-DZ-IsmaelNeural"),
            new Voice(true, "ar-EG-ShakirNeural"),
            new Voice(true, "ar-IQ-BasselNeural"),
            new Voice(true, "ar-JO-TaimNeural"),
            new Voice(true, "ar-KW-FahedNeural"),
            new Voice(true, "ar-LB-RamiNeural"),
            new Voice(true, "ar-LY-OmarNeural"),
            new Voice(true, "ar-MA-JamalNeural"),
            new Voice(true, "ar-OM-AbdullahNeural"),
            new Voice(true, "ar-QA-MoazNeural"),
            new Voice(true, "ar-SA-HamedNeural"),
            new Voice(true, "ar-SY-LaithNeural"),
            new Voice(true, "ar-TN-HediNeural"),
            new Voice(true, "ar-YE-SalehNeural"),
            new Voice(true, "az-AZ-BabekNeural"),
            new Voice(true, "bg-BG-BorislavNeural"),
            new Voice(true, "bn-BD-PradeepNeural"),
            new Voice(true, "bn-IN-BashkarNeural"),
            new Voice(true, "bs-BA-GoranNeural"),
            new Voice(true, "ca-ES-EnricNeural"),
            new Voice(true, "cs-CZ-AntoninNeural"),
            new Voice(true, "cy-GB-AledNeural"),
            new Voice(true, "da-DK-JeppeNeural"),
            new Voice(true, "de-AT-JonasNeural"),
            new Voice(true, "de-CH-JanNeural"),
            new Voice(true, "de-DE-ConradNeural"),
            new Voice(true, "de-DE-FlorianMultilingualNeural"),
            new Voice(true, "de-DE-KillianNeural"),
            new Voice(true, "el-GR-NestorasNeural"),
            new Voice(true, "en-AU-WilliamMultilingualNeural"),
            new Voice(true, "en-CA-LiamNeural"),
            new Voice(true, "en-GB-RyanNeural"),
            new Voice(true, "en-GB-ThomasNeural"),
            new Voice(true, "en-HK-SamNeural"),
            new Voice(true, "en-IE-ConnorNeural"),
            new Voice(true, "en-IN-PrabhatNeural"),
            // Voice sounds almost identical to Abeo below
            // new Voice(true, "en-KE-ChilembaNeural"),
            new Voice(true, "en-NG-AbeoNeural"),
            new Voice(true, "en-NZ-MitchellNeural"),
            new Voice(true, "en-PH-JamesNeural"),
            new Voice(true, "en-SG-WayneNeural"),
            new Voice(true, "en-TZ-ElimuNeural"),
            new Voice(true, "en-US-ChristopherNeural"),
            new Voice(true, "en-US-AndrewMultilingualNeural"),
            // new Voice(true, "en-US-AndrewNeural"),
            new Voice(true, "en-US-BrianMultilingualNeural"),
            //new Voice(true, "en-US-BrianNeural"),
            new Voice(true, "en-US-EricNeural"),
            new Voice(true, "en-US-GuyNeural"),
            new Voice(true, "en-US-RogerNeural"),
            new Voice(true, "en-US-SteffanNeural"),
            new Voice(true, "en-ZA-LukeNeural"),
            new Voice(true, "es-AR-TomasNeural"),
            new Voice(true, "es-BO-MarceloNeural"),
            new Voice(true, "es-CL-LorenzoNeural"),
            new Voice(true, "es-CO-GonzaloNeural"),
            new Voice(true, "es-CR-JuanNeural"),
            new Voice(true, "es-CU-ManuelNeural"),
            new Voice(true, "es-DO-EmilioNeural"),
            new Voice(true, "es-EC-LuisNeural"),
            new Voice(true, "es-ES-AlvaroNeural"),
            new Voice(true, "es-GQ-JavierNeural"),
            new Voice(true, "es-GT-AndresNeural"),
            new Voice(true, "es-HN-CarlosNeural"),
            new Voice(true, "es-MX-JorgeNeural"),
            new Voice(true, "es-NI-FedericoNeural"),
            new Voice(true, "es-PA-RobertoNeural"),
            new Voice(true, "es-PE-AlexNeural"),
            new Voice(true, "es-PR-VictorNeural"),
            new Voice(true, "es-PY-MarioNeural"),
            new Voice(true, "es-SV-RodrigoNeural"),
            new Voice(true, "es-US-AlonsoNeural"),
            new Voice(true, "es-UY-MateoNeural"),
            new Voice(true, "es-VE-SebastianNeural"),
            new Voice(true, "et-EE-KertNeural"),
            new Voice(true, "fa-IR-FaridNeural"),
            new Voice(true, "fi-FI-HarriNeural"),
            new Voice(true, "fil-PH-AngeloNeural"),
            new Voice(true, "fr-BE-GerardNeural"),
            new Voice(true, "fr-CA-AntoineNeural"),
            new Voice(true, "fr-CA-JeanNeural"),
            new Voice(true, "fr-CA-ThierryNeural"),
            new Voice(true, "fr-CH-FabriceNeural"),
            new Voice(true, "fr-FR-HenriNeural"),
            new Voice(true, "fr-FR-RemyMultilingualNeural"),
            new Voice(true, "ga-IE-ColmNeural"),
            new Voice(true, "gl-ES-RoiNeural"),
            new Voice(true, "gu-IN-NiranjanNeural"),
            new Voice(true, "he-IL-AvriNeural"),
            new Voice(true, "hi-IN-MadhurNeural"),
            new Voice(true, "hr-HR-SreckoNeural"),
            new Voice(true, "hu-HU-TamasNeural"),
            new Voice(true, "id-ID-ArdiNeural"),
            new Voice(true, "is-IS-GunnarNeural"),
            new Voice(true, "it-IT-DiegoNeural"),
            new Voice(true, "it-IT-GiuseppeMultilingualNeural"),
            new Voice(true, "iu-Cans-CA-TaqqiqNeural"),
            new Voice(true, "iu-Latn-CA-TaqqiqNeural"),
            new Voice(true, "ja-JP-KeitaNeural"),
            new Voice(true, "jv-ID-DimasNeural"),
            new Voice(true, "ka-GE-GiorgiNeural"),
            new Voice(true, "kk-KZ-DauletNeural"),
            new Voice(true, "km-KH-PisethNeural"),
            new Voice(true, "kn-IN-GaganNeural"),
            new Voice(true, "ko-KR-HyunsuMultilingualNeural"),
            new Voice(true, "ko-KR-InJoonNeural"),
            new Voice(true, "lo-LA-ChanthavongNeural"),
            new Voice(true, "lt-LT-LeonasNeural"),
            new Voice(true, "lv-LV-NilsNeural"),
            new Voice(true, "mk-MK-AleksandarNeural"),
            new Voice(true, "ml-IN-MidhunNeural"),
            new Voice(true, "mn-MN-BataaNeural"),
            new Voice(true, "mr-IN-ManoharNeural"),
            new Voice(true, "ms-MY-OsmanNeural"),
            new Voice(true, "mt-MT-JosephNeural"),
            new Voice(true, "my-MM-ThihaNeural"),
            new Voice(true, "nb-NO-FinnNeural"),
            new Voice(true, "ne-NP-SagarNeural"),
            new Voice(true, "nl-BE-ArnaudNeural"),
            new Voice(true, "nl-NL-MaartenNeural"),
            new Voice(true, "pl-PL-MarekNeural"),
            new Voice(true, "ps-AF-GulNawazNeural"),
            new Voice(true, "pt-BR-AntonioNeural"),
            new Voice(true, "pt-PT-DuarteNeural"),
            new Voice(true, "ro-RO-EmilNeural"),
            new Voice(true, "ru-RU-DmitryNeural"),
            new Voice(true, "si-LK-SameeraNeural"),
            new Voice(true, "sk-SK-LukasNeural"),
            new Voice(true, "sl-SI-RokNeural"),
            new Voice(true, "so-SO-MuuseNeural"),
            new Voice(true, "sq-AL-IlirNeural"),
            new Voice(true, "sr-RS-NicholasNeural"),
            new Voice(true, "su-ID-JajangNeural"),
            new Voice(true, "sv-SE-MattiasNeural"),
            new Voice(true, "sw-KE-RafikiNeural"),
            new Voice(true, "sw-TZ-DaudiNeural"),
            new Voice(true, "ta-IN-ValluvarNeural"),
            new Voice(true, "ta-LK-KumarNeural"),
            new Voice(true, "ta-MY-SuryaNeural"),
            new Voice(true, "ta-SG-AnbuNeural"),
            new Voice(true, "te-IN-MohanNeural"),
            new Voice(true, "th-TH-NiwatNeural"),
            new Voice(true, "tr-TR-AhmetNeural"),
            new Voice(true, "uk-UA-OstapNeural"),
            new Voice(true, "ur-IN-SalmanNeural"),
            new Voice(true, "ur-PK-AsadNeural"),
            new Voice(true, "uz-UZ-SardorNeural"),
            new Voice(true, "vi-VN-NamMinhNeural"),
            new Voice(true, "zh-CN-YunjianNeural"),
            new Voice(true, "zh-CN-YunxiaNeural"),
            new Voice(true, "zh-CN-YunxiNeural"),
            new Voice(true, "zh-CN-YunyangNeural"),
            new Voice(true, "zh-HK-WanLungNeural"),
            new Voice(true, "zh-TW-YunJheNeural"),
            new Voice(true, "zu-ZA-ThembaNeural"),

            new Voice(false, "af-ZA-AdriNeural"),
            new Voice(false, "am-ET-MekdesNeural"),
            new Voice(false, "ar-AE-FatimaNeural"),
            new Voice(false, "ar-BH-LailaNeural"),
            new Voice(false, "ar-DZ-AminaNeural"),
            new Voice(false, "ar-EG-SalmaNeural"),
            new Voice(false, "ar-IQ-RanaNeural"),
            new Voice(false, "ar-JO-SanaNeural"),
            new Voice(false, "ar-KW-NouraNeural"),
            new Voice(false, "ar-LB-LaylaNeural"),
            new Voice(false, "ar-LY-ImanNeural"),
            new Voice(false, "ar-MA-MounaNeural"),
            new Voice(false, "ar-OM-AyshaNeural"),
            new Voice(false, "ar-QA-AmalNeural"),
            new Voice(false, "ar-SA-ZariyahNeural"),
            new Voice(false, "ar-SY-AmanyNeural"),
            new Voice(false, "ar-TN-ReemNeural"),
            new Voice(false, "ar-YE-MaryamNeural"),
            new Voice(false, "az-AZ-BanuNeural"),
            new Voice(false, "bg-BG-KalinaNeural"),
            new Voice(false, "bn-BD-NabanitaNeural"),
            new Voice(false, "bn-IN-TanishaaNeural"),
            new Voice(false, "bs-BA-VesnaNeural"),
            new Voice(false, "ca-ES-JoanaNeural"),
            new Voice(false, "cs-CZ-VlastaNeural"),
            new Voice(false, "cy-GB-NiaNeural"),
            new Voice(false, "da-DK-ChristelNeural"),
            new Voice(false, "de-AT-IngridNeural"),
            new Voice(false, "de-CH-LeniNeural"),
            new Voice(false, "de-DE-AmalaNeural"),
            new Voice(false, "de-DE-KatjaNeural"),
            new Voice(false, "de-DE-SeraphinaMultilingualNeural"),
            new Voice(false, "el-GR-AthinaNeural"),
            new Voice(false, "en-AU-NatashaNeural"),
            new Voice(false, "en-CA-ClaraNeural"),
            new Voice(false, "en-GB-LibbyNeural"),
            new Voice(false, "en-GB-MaisieNeural"),
            new Voice(false, "en-GB-SoniaNeural"),
            new Voice(false, "en-HK-YanNeural"),
            new Voice(false, "en-IE-EmilyNeural"),
            new Voice(false, "en-IN-NeerjaExpressiveNeural"),
            // new Voice(false, "en-IN-NeerjaNeural"),
            new Voice(false, "en-KE-AsiliaNeural"),
            new Voice(false, "en-NG-EzinneNeural"),
            new Voice(false, "en-NZ-MollyNeural"),
            new Voice(false, "en-PH-RosaNeural"),
            new Voice(false, "en-SG-LunaNeural"),
            new Voice(false, "en-TZ-ImaniNeural"),
            new Voice(false, "en-US-AnaNeural"),
            new Voice(false, "en-US-AriaNeural"),
            new Voice(false, "en-US-AvaMultilingualNeural"),
            //new Voice(false, "en-US-AvaNeural"),
            new Voice(false, "en-US-EmmaMultilingualNeural"),
            // new Voice(false, "en-US-EmmaNeural"),
            new Voice(false, "en-US-JennyNeural"),
            new Voice(false, "en-US-MichelleNeural"),
            new Voice(false, "en-ZA-LeahNeural"),
            new Voice(false, "es-AR-ElenaNeural"),
            new Voice(false, "es-BO-SofiaNeural"),
            new Voice(false, "es-CL-CatalinaNeural"),
            new Voice(false, "es-CO-SalomeNeural"),
            new Voice(false, "es-CR-MariaNeural"),
            new Voice(false, "es-CU-BelkysNeural"),
            new Voice(false, "es-DO-RamonaNeural"),
            new Voice(false, "es-EC-AndreaNeural"),
            new Voice(false, "es-ES-ElviraNeural"),
            new Voice(false, "es-ES-XimenaNeural"),
            new Voice(false, "es-GQ-TeresaNeural"),
            new Voice(false, "es-GT-MartaNeural"),
            new Voice(false, "es-HN-KarlaNeural"),
            new Voice(false, "es-MX-DaliaNeural"),
            new Voice(false, "es-NI-YolandaNeural"),
            new Voice(false, "es-PA-MargaritaNeural"),
            new Voice(false, "es-PE-CamilaNeural"),
            new Voice(false, "es-PR-KarinaNeural"),
            new Voice(false, "es-PY-TaniaNeural"),
            new Voice(false, "es-SV-LorenaNeural"),
            new Voice(false, "es-US-PalomaNeural"),
            new Voice(false, "es-UY-ValentinaNeural"),
            new Voice(false, "es-VE-PaolaNeural"),
            new Voice(false, "et-EE-AnuNeural"),
            new Voice(false, "fa-IR-DilaraNeural"),
            new Voice(false, "fi-FI-NooraNeural"),
            new Voice(false, "fil-PH-BlessicaNeural"),
            new Voice(false, "fr-BE-CharlineNeural"),
            new Voice(false, "fr-CA-SylvieNeural"),
            new Voice(false, "fr-CH-ArianeNeural"),
            new Voice(false, "fr-FR-DeniseNeural"),
            new Voice(false, "fr-FR-EloiseNeural"),
            new Voice(false, "fr-FR-VivienneMultilingualNeural"),
            new Voice(false, "ga-IE-OrlaNeural"),
            new Voice(false, "gl-ES-SabelaNeural"),
            new Voice(false, "gu-IN-DhwaniNeural"),
            new Voice(false, "he-IL-HilaNeural"),
            new Voice(false, "hi-IN-SwaraNeural"),
            new Voice(false, "hr-HR-GabrijelaNeural"),
            new Voice(false, "hu-HU-NoemiNeural"),
            new Voice(false, "id-ID-GadisNeural"),
            new Voice(false, "is-IS-GudrunNeural"),
            new Voice(false, "it-IT-ElsaNeural"),
            new Voice(false, "it-IT-IsabellaNeural"),
            new Voice(false, "iu-Cans-CA-SiqiniqNeural"),
            new Voice(false, "iu-Latn-CA-SiqiniqNeural"),
            new Voice(false, "ja-JP-NanamiNeural"),
            new Voice(false, "jv-ID-SitiNeural"),
            new Voice(false, "ka-GE-EkaNeural"),
            new Voice(false, "kk-KZ-AigulNeural"),
            new Voice(false, "km-KH-SreymomNeural"),
            new Voice(false, "kn-IN-SapnaNeural"),
            new Voice(false, "ko-KR-SunHiNeural"),
            new Voice(false, "lo-LA-KeomanyNeural"),
            new Voice(false, "lt-LT-OnaNeural"),
            new Voice(false, "lv-LV-EveritaNeural"),
            new Voice(false, "mk-MK-MarijaNeural"),
            new Voice(false, "ml-IN-SobhanaNeural"),
            new Voice(false, "mn-MN-YesuiNeural"),
            new Voice(false, "mr-IN-AarohiNeural"),
            new Voice(false, "ms-MY-YasminNeural"),
            new Voice(false, "mt-MT-GraceNeural"),
            new Voice(false, "my-MM-NilarNeural"),
            new Voice(false, "nb-NO-PernilleNeural"),
            new Voice(false, "ne-NP-HemkalaNeural"),
            new Voice(false, "nl-BE-DenaNeural"),
            new Voice(false, "nl-NL-ColetteNeural"),
            new Voice(false, "nl-NL-FennaNeural"),
            new Voice(false, "pl-PL-ZofiaNeural"),
            new Voice(false, "ps-AF-LatifaNeural"),
            new Voice(false, "pt-BR-FranciscaNeural"),
            new Voice(false, "pt-BR-ThalitaMultilingualNeural"),
            new Voice(false, "pt-PT-RaquelNeural"),
            new Voice(false, "ro-RO-AlinaNeural"),
            new Voice(false, "ru-RU-SvetlanaNeural"),
            new Voice(false, "si-LK-ThiliniNeural"),
            new Voice(false, "sk-SK-ViktoriaNeural"),
            new Voice(false, "sl-SI-PetraNeural"),
            new Voice(false, "so-SO-UbaxNeural"),
            new Voice(false, "sq-AL-AnilaNeural"),
            new Voice(false, "sr-RS-SophieNeural"),
            new Voice(false, "su-ID-TutiNeural"),
            new Voice(false, "sv-SE-SofieNeural"),
            new Voice(false, "sw-KE-ZuriNeural"),
            new Voice(false, "sw-TZ-RehemaNeural"),
            new Voice(false, "ta-IN-PallaviNeural"),
            new Voice(false, "ta-LK-SaranyaNeural"),
            new Voice(false, "ta-MY-KaniNeural"),
            new Voice(false, "ta-SG-VenbaNeural"),
            new Voice(false, "te-IN-ShrutiNeural"),
            new Voice(false, "th-TH-PremwadeeNeural"),
            new Voice(false, "tr-TR-EmelNeural"),
            new Voice(false, "uk-UA-PolinaNeural"),
            new Voice(false, "ur-IN-GulNeural"),
            new Voice(false, "ur-PK-UzmaNeural"),
            new Voice(false, "uz-UZ-MadinaNeural"),
            new Voice(false, "vi-VN-HoaiMyNeural"),
            new Voice(false, "zh-CN-liaoning-XiaobeiNeural"),
            new Voice(false, "zh-CN-shaanxi-XiaoniNeural"),
            new Voice(false, "zh-CN-XiaoxiaoNeural"),
            new Voice(false, "zh-CN-XiaoyiNeural"),
            new Voice(false, "zh-HK-HiuGaaiNeural"),
            new Voice(false, "zh-HK-HiuMaanNeural"),
            new Voice(false, "zh-TW-HsiaoChenNeural"),
            new Voice(false, "zh-TW-HsiaoYuNeural"),
            new Voice(false, "zu-ZA-ThandoNeural")
    };

    private static final Map<String, Voice> VOICES_BY_ID;
    private static final Map<String, List<Voice>> VOICES_BY_LANG;

    static {
        VOICES_BY_ID = new HashMap<>(2 * ALL_VOICES.length);
        VOICES_BY_LANG = new HashMap<>(100, 0.5f);

        List<Voice> multilingualVoices = new ArrayList<>();

        for (Voice v : ALL_VOICES) {
            VOICES_BY_ID.put(v.id, v);
            VOICES_BY_LANG.computeIfAbsent(v.languageTag, k -> new ArrayList<>()).add(v);
            if (v.isMultilingual) {
                multilingualVoices.add(v);
            }
        }

        for (Map.Entry<String, List<Voice>> entry : VOICES_BY_LANG.entrySet()) {
            String lang = entry.getKey();
            List<Voice> voices = entry.getValue();
            for (Voice mv : multilingualVoices) {
                if (!mv.languageTag.equals(lang)) {
                    voices.add(mv);
                }
            }
        }
    }

    private VoiceCatalog() {}

    @Nullable
    static Voice getVoice(String id) {
        return VOICES_BY_ID.get(id);
    }

    /**
     * @param lang BCP 47 (pt-BR, en-US) or ISO 639 (pt, en)
     */
    @Nullable
    static List<Voice> getVoicesForLang(String lang) {
        return VOICES_BY_LANG.get(getIso639(lang));
    }

    /**
     * @param lang BCP 47 (pt-BR, en-US) or ISO 639 (pt, en)
     * @return ISO 639 language.
     */
    public static String getIso639(String lang) {
        final int separatorIndex = lang.indexOf('-');
        if (separatorIndex > 0) {
            lang = lang.substring(0, separatorIndex);
        }
        return lang;
    }

    /**
     * @param lang ISO-639 (pt, en) or BCP 47 language (pt-BR, en-US).
     */
    @Nullable
    static String resolve(String lang, @Nullable String preferredVoiceId) {
        lang = getIso639(lang);

        List<Voice> voices = VOICES_BY_LANG.get(lang);
        if (voices == null || voices.isEmpty()) {
            voices = Objects.requireNonNull(VOICES_BY_LANG.get("en"));
        }
        if (preferredVoiceId != null) {
            Voice preferred = VOICES_BY_ID.get(preferredVoiceId);
            if (preferred != null) {
                for (Voice v : voices) {
                    if (v.id.equals(preferredVoiceId)) return v.id;
                }
            }
        }
        return voices.get(0).id;
    }
}