/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Facts and interpretation stay separate so the renderer can label them honestly.
 */
public record StoryAiResult(
        boolean hasVerifiedBg,
        String oneLineHook,
        String background,
        String lyricStory,
        String keyMetaphor,
        List<String> moodTags,
        String sourceLabel
) {
    /** Only the finished narrative may be displayed; it must fit a card without clipping. */
    public static final int MAX_STORY_WORDS = 120;
    public static final int MAX_HOOK_CHARS = 30;
    private static final String KEY_TS = "ts";

    public StoryAiResult {
        lyricStory = limitStoryWords(lyricStory);
        background = normalizeOrNull(background);
        keyMetaphor = normalizeOrNull(keyMetaphor);
        oneLineHook = limitHook(oneLineHook);
    }

    /** One short sentence, never longer than the card's quote slot. */
    public static String limitHook(@androidx.annotation.Nullable String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= MAX_HOOK_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_HOOK_CHARS).trim();
    }

    /** Trims at a word boundary so the visible text is never cut half-way through a word. */
    public static String limitStoryWords(@androidx.annotation.Nullable String text) {
        return limitWords(text, MAX_STORY_WORDS);
    }

    public static String limitWords(@androidx.annotation.Nullable String text, int maxWords) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] words = trimmed.split("\\s+");
        if (words.length <= maxWords) {
            return trimmed;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /** Internal fields are bounded by the prompt, never cut by code; keep the complete text. */
    @androidx.annotation.Nullable
    public static String normalizeOrNull(@androidx.annotation.Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)
                || "none".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_TS, System.currentTimeMillis());
            obj.put("has_verified_bg", hasVerifiedBg);
            obj.put("one_line_hook", oneLineHook == null ? "" : oneLineHook);
            obj.put("background", background == null ? JSONObject.NULL : background);
            obj.put("lyric_story", lyricStory == null ? "" : lyricStory);
            obj.put("key_metaphor", keyMetaphor == null ? JSONObject.NULL : keyMetaphor);
            JSONArray tags = new JSONArray();
            if (moodTags != null) {
                for (String tag : moodTags) {
                    tags.put(tag);
                }
            }
            obj.put("mood_tags", tags);
            obj.put("source_label", sourceLabel == null ? "" : sourceLabel);
            return obj.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    @androidx.annotation.Nullable
    public static StoryAiResult fromJson(@androidx.annotation.Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            List<String> tags = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("mood_tags");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String tag = arr.optString(i, "");
                    if (!tag.isEmpty()) {
                        tags.add(tag);
                    }
                }
            }
            return new StoryAiResult(
                    obj.optBoolean("has_verified_bg", false),
                    obj.optString("one_line_hook", ""),
                    obj.isNull("background") ? null : obj.optString("background", ""),
                    obj.optString("lyric_story", ""),
                    obj.isNull("key_metaphor") ? null : obj.optString("key_metaphor", ""),
                    tags,
                    obj.optString("source_label", ""));
        } catch (Exception ex) {
            return null;
        }
    }
}
