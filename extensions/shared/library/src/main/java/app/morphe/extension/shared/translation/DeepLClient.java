/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3877
 * https://github.com/MorpheApp/morphe-patches/pull/4313
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.translation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Machine translation with DeepL, using an API key of the user.
 */
public final class DeepLClient {

    private static final String API_FREE_URL = "https://api-free.deepl.com/v2/translate";
    private static final String API_PRO_URL = "https://api.deepl.com/v2/translate";

    /** Keys of the free plan end with this, and only work with {@link #API_FREE_URL}. */
    private static final String FREE_API_KEY_SUFFIX = ":fx";

    private DeepLClient() {
    }

    /**
     * Translates lines. Always call off the main thread.
     *
     * @param targetLanguage Language code such as {@code uk} or {@code zh-TW}.
     * @return Translated lines, in the order they were given.
     * @throws TextTranslator.TranslationHttpException If DeepL answers with an error.
     */
    public static List<String> translate(List<String> lines, String targetLanguage, String apiKey)
            throws Exception {
        JSONArray translations = request(lines, targetLanguage, apiKey);
        List<String> result = new ArrayList<>(translations.length());
        for (int i = 0, length = translations.length(); i < length; i++) {
            result.add(translations.getJSONObject(i).optString("text", ""));
        }
        return result;
    }

    /**
     * Translates a single text and reports the language it was written in.
     * Always call off the main thread.
     *
     * @param targetLanguage Language code such as {@code uk} or {@code zh-TW}.
     * @throws TextTranslator.TranslationHttpException If DeepL answers with an error.
     */
    public static TextTranslator.Translation translateText(String text, String targetLanguage,
                                                           String apiKey) throws Exception {
        JSONObject translation = request(List.of(text), targetLanguage, apiKey).getJSONObject(0);
        // An upper case code such as EN, without the region.
        String detected = translation.optString("detected_source_language");
        return new TextTranslator.Translation(translation.getString("text"),
                detected.isEmpty() ? null : detected);
    }

    private static JSONArray request(List<String> texts, String targetLanguage, String apiKey)
            throws Exception {
        JSONArray textArray = new JSONArray();
        for (String text : texts) {
            textArray.put(text);
        }
        JSONObject body = new JSONObject();
        body.put("text", textArray);
        body.put("target_lang", toDeepLLanguage(targetLanguage));

        String url = apiKey.endsWith(FREE_API_KEY_SUFFIX) ? API_FREE_URL : API_PRO_URL;
        String response = TextTranslator.post(url, "application/json; charset=utf-8",
                body.toString(), "Authorization", "DeepL-Auth-Key " + apiKey, "DeepL");
        return new JSONObject(response).getJSONArray("translations");
    }

    /**
     * @return The DeepL target language. DeepL refuses the codes without a variant for English,
     *         Portuguese and Chinese, and uses its own codes for the variants.
     */
    private static String toDeepLLanguage(String language) {
        String clean = language.trim().toUpperCase(Locale.ROOT);
        if (clean.equals("EN") || clean.startsWith("EN-")) {
            return "EN-US";
        }
        switch (clean) {
            case "PT", "PT-BR" -> {
                return "PT-BR";
            }
            case "PT-PT" -> {
                return "PT-PT";
            }
            case "ZH", "ZH-CN", "ZH-HANS", "ZH-SG" -> {
                return "ZH-HANS";
            }
            case "ZH-TW", "ZH-HK", "ZH-HANT" -> {
                return "ZH-HANT";
            }
            case "IN", "ID" -> {
                return "ID";
            }
            case "IW", "HE" -> {
                return "HE";
            }
            case "JI", "YI" -> {
                return "YI";
            }
            case "NO" -> {
                return "NB";
            }
        }
        // Other languages use the plain two-letter code, such as JA, KO or DE.
        int hyphen = clean.indexOf('-');
        return hyphen > 0 ? clean.substring(0, hyphen) : clean;
    }
}
