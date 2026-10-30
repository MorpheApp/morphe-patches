/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3877
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.settings.Settings;

/**
 * Translation and romanization provider ids, and the AI request path they share. Provider
 * settings are comma separated priority lists; a leading {@code -} disables an entry.
 */
public final class AiProviders {

    /** Translation and romanization already included in the lyrics source. */
    public static final String EMBEDDED = "embedded";
    public static final String GOOGLE = "google";
    public static final String LOCAL = "local";
    /** The Rime word tables, the user's own lists included. Named "Rime Wordlist" in the settings. */
    public static final String RIME = "rime";
    public static final String OPENAI_COMPATIBLE = "openai_compatible";
    public static final String DEEPL = "deepl";

    /** Brand names, kept out of the string resources so no translation can restate them. */
    public static final String EMBEDDED_LABEL = "Embedded";
    public static final String GOOGLE_LABEL = "Google Translate";
    public static final String OPENAI_COMPATIBLE_LABEL = "OpenAI Compatible";
    public static final String DEEPL_LABEL = "DeepL";

    public static final List<String> TRANSLATION_ORDER =
            List.of(EMBEDDED, GOOGLE, OPENAI_COMPATIBLE, DEEPL);
    public static final List<String> ROMANIZATION_ORDER =
            List.of(EMBEDDED, GOOGLE, LOCAL, RIME, OPENAI_COMPATIBLE);

    public static final String DEFAULT_TRANSLATION_ORDER =
            "embedded,google,-openai_compatible,-deepl";
    public static final String DEFAULT_ROMANIZATION_ORDER =
            "embedded,google,-local,-rime,-openai_compatible";
    /** Endpoints offered by name, in the order they are listed. */
    public static final List<EndpointPreset> ENDPOINT_PRESETS = List.of(
            EndpointPreset.of("OpenAI", "https://api.openai.com/v1"),
            EndpointPreset.of("Anthropic Claude", "https://api.anthropic.com/v1"),
            // Its compatibility layer lists no models and wants the "models/" form.
            EndpointPreset.of("Gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai",
                    "models/gemini-2.5-flash"),
            EndpointPreset.of("OpenRouter", "https://openrouter.ai/api/v1"),
            EndpointPreset.of("DeepSeek", "https://api.deepseek.com/v1"),
            EndpointPreset.of("Groq", "https://api.groq.com/openai/v1"),
            EndpointPreset.of("Mistral", "https://api.mistral.ai/v1"),
            EndpointPreset.of("xAI Grok", "https://api.x.ai/v1"),
            EndpointPreset.of("Together AI", "https://api.together.xyz/v1"),
            EndpointPreset.of("Qwen (DashScope)",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            EndpointPreset.of("Z.AI (GLM)", "https://open.bigmodel.cn/api/paas/v4"),
            EndpointPreset.of("Moonshot (Kimi)", "https://api.moonshot.cn/v1"),
            EndpointPreset.of("Pollinations (free)", "https://text.pollinations.ai/openai"));

    private AiProviders() {
    }

    /** Parse the stored list; a provider missing from it is enabled by default. */
    public static List<ProviderEntry> parseOrder(@Nullable String stored, List<String> known) {
        List<ProviderEntry> entries = new ArrayList<>(known.size());
        Set<String> seen = new HashSet<>(known.size());

        if (stored != null) {
            for (String raw : stored.split(",")) {
                String token = raw.trim();
                if (token.isEmpty()) {
                    continue;
                }
                boolean enabled = true;
                if (token.startsWith("-")) {
                    enabled = false;
                    token = token.substring(1).trim();
                }
                if (token.isEmpty() || !known.contains(token) || !seen.add(token)) {
                    continue;
                }
                entries.add(new ProviderEntry(token, enabled));
            }
        }

        for (String id : known) {
            if (!seen.contains(id)) {
                // Embedded readings used to be unconditional, so keep them ahead of older lists.
                if (EMBEDDED.equals(id)) {
                    entries.add(0, new ProviderEntry(id, true));
                } else if (RIME.equals(id)) {
                    // Rime Wordlist fetches word lists; leave it off for stored lists that predate
                    // it rather than downloading data an upgrade did not ask for.
                    entries.add(afterLocal(entries), new ProviderEntry(id, false));
                } else {
                    entries.add(new ProviderEntry(id, true));
                }
            }
        }
        return entries;
    }

    /** Where a provider the stored list never named belongs: just after the local dictionaries. */
    private static int afterLocal(List<ProviderEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (LOCAL.equals(entries.get(i).id())) {
                return i + 1;
            }
        }
        return entries.size();
    }

    public static List<String> enabledProviders(@Nullable String stored, List<String> known) {
        List<String> enabled = new ArrayList<>();
        for (ProviderEntry entry : parseOrder(stored, known)) {
            if (entry.enabled()) {
                enabled.add(entry.id());
            }
        }
        return enabled;
    }

    public static String serializeOrder(List<ProviderEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (ProviderEntry entry : entries) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (!entry.enabled()) {
                sb.append('-');
            }
            sb.append(entry.id());
        }
        return sb.toString();
    }

    /** Rewrites one provider's flag, returning {@code stored} untouched when it already matches. */
    public static String withProviderEnabled(@Nullable String stored, List<String> known,
                                             String id, boolean enabled) {
        final List<ProviderEntry> entries = parseOrder(stored, known);
        boolean changed = false;
        final List<ProviderEntry> updated = new ArrayList<>(entries.size());
        for (ProviderEntry entry : entries) {
            if (entry.id().equals(id) && entry.enabled() != enabled) {
                updated.add(new ProviderEntry(id, enabled));
                changed = true;
            } else {
                updated.add(entry);
            }
        }
        return changed ? serializeOrder(updated) : stored;
    }

    private static String currentId(String id) {
        return switch (id) {
            // An early build named the entry after the endpoint it talks to.
            case "openai", "openrouter" -> OPENAI_COMPATIBLE;
            default -> id;
        };
    }


    public static boolean isAi(@Nullable String provider) {
        return OPENAI_COMPATIBLE.equals(provider);
    }

    /** Null when the provider cannot answer, so the caller can try the next one. */
    @Nullable
    static Result mapLines(String provider, String prompt, List<String> lines) {
        if (!OPENAI_COMPATIBLE.equals(provider)) {
            return null;
        }
        final String model = Settings.LYRICS_AI_MODEL.get();
        final List<String> mapped = OpenAIClient.mapLines(Settings.LYRICS_AI_BASE_URL.get(),
                Settings.LYRICS_AI_API_TOKEN.get(), model, prompt, lines);
        return mapped == null ? null : new Result(mapped, model);
    }

    public record ProviderEntry(String id, boolean enabled) {
    }

    /** A service offered by name; the URL is stored as the client will call it. */
    public record EndpointPreset(String label, String baseUrl, String model) {
        public static EndpointPreset of(String label, String baseUrl) {
            return new EndpointPreset(label, OpenAIClient.chatUrl(baseUrl), "");
        }

        public static EndpointPreset of(String label, String baseUrl, String model) {
            return new EndpointPreset(label, OpenAIClient.chatUrl(baseUrl), model);
        }
    }

    record Result(List<String> lines, String model) {
    }
}
