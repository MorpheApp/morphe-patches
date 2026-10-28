/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3877
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.settings.Settings;

/**
 * The translation and romanization providers a user can pick, and the one place a request is
 * sent to the AI model the picked provider stands for.
 */
public final class AiProviders {

    public static final String GOOGLE = "google";
    public static final String LOCAL = "local";
    public static final String GEMINI = "gemini";
    public static final String OPENAI_COMPATIBLE = "openai_compatible";
    public static final String DEEPL = "deepl";

    /** What a model answered, and the model that answered it. */
    record Result(List<String> lines, String model) {
    }

    private AiProviders() {
    }

    public static boolean isAi(@Nullable String provider) {
        return GEMINI.equals(provider) || OPENAI_COMPATIBLE.equals(provider);
    }

    /**
     * Sends the prompt to the model of an AI provider. Null when the provider is not an AI one,
     * has no key yet, or gave no usable answer, so the caller falls back to Google.
     */
    @Nullable
    static Result mapLines(String provider, String prompt, List<String> lines) {
        String model;
        List<String> mapped;
        switch (provider) {
            case GEMINI -> {
                String apiKey = Settings.LYRICS_GEMINI_API_KEY.get();
                if (apiKey.isEmpty()) return null;
                model = Settings.LYRICS_GEMINI_MODEL.get();
                mapped = GeminiClient.mapLines(apiKey, model, prompt, lines);
            }
            case OPENAI_COMPATIBLE -> {
                model = Settings.LYRICS_AI_MODEL.get();
                mapped = OpenAIClient.mapLines(Settings.LYRICS_AI_BASE_URL.get(),
                        Settings.LYRICS_AI_API_TOKEN.get(), model, prompt, lines);
            }
            default -> {
                return null;
            }
        }
        return mapped == null ? null : new Result(mapped, model);
    }
}
