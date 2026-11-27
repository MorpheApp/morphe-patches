/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.music.settings.Settings;

public record CoverStyleConfig(
        String style,
        int cornerRadiusDp,
        int shadowDp,
        int borderWidthDp,
        String frameColor,
        String accentColor,
        String textColor,
        String discColor,
        String labelColor,
        float spinSpeed,
        boolean showTrackInfo,
        int titleSizeSp,
        int artistSizeSp,
        String filmTone,
        int perforationCount,
        int perforationRadiusDp,
        String paperColor,
        String shellColor,
        float tiltDegrees
) {

    public static final String DEFAULT_STYLE = "plain";
    public static final List<String> STYLES = List.of(
            DEFAULT_STYLE, "film", "vinyl", "polaroid", "cassette", "cd", "poster", "framed",
            "phonograph", "ipod", "fisheye", "recordbox", "emboss", "projector");

    public static final Map<String, Integer> PALETTE = createPalette();

    private static Map<String, Integer> createPalette() {
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("charcoal", 0xFF1A1A1E);
        palette.put("ivory", 0xFFFFF6E3);
        palette.put("crimson", 0xFFC6424E);
        palette.put("amber", 0xFFE8A33D);
        palette.put("teal", 0xFF3E8E8A);
        palette.put("violet", 0xFF8A6BC6);
        palette.put("slate", 0xFF4A5568);
        palette.put("sand", 0xFFD8C3A5);
        palette.put("rose", 0xFFD9738C);
        palette.put("mint", 0xFF74C69D);
        palette.put("sky", 0xFF6BA8E8);
        return palette;
    }

    public static int color(String token, int fallback) {
        if (token == null) {
            return fallback;
        }
        Integer color = PALETTE.get(token.toLowerCase(Locale.ROOT));
        return color == null ? fallback : color;
    }

    public static CoverStyleConfig defaults(String style) {
        String safe = STYLES.contains(style) ? style : DEFAULT_STYLE;
        return new CoverStyleConfig(
                safe,
                6, 6, safe.equals("framed") || safe.equals("poster") ? 8 : 0,
                "charcoal", "ivory", "ivory",
                safe.equals("vinyl") || safe.equals("cd") || safe.equals("phonograph") ? "auto" : "slate",
                safe.equals("vinyl") ? "crimson" : "ivory",
                switch (safe) {
                    case "vinyl" -> 0.6f;
                    case "cd" -> 0.8f;
                    case "cassette" -> 0.4f;
                    case "phonograph" -> 0.5f;
                    default -> 0f;
                },
                !safe.equals("cassette"),
                18, 13,
                "charcoal", 8, 2, "ivory", "charcoal",
                safe.equals("polaroid") ? -2f : 0f
        );
    }

    public CoverStyleConfig withStyle(String newStyle) {
        CoverStyleConfig base = defaults(newStyle);
        return new CoverStyleConfig(
                STYLES.contains(newStyle) ? newStyle : DEFAULT_STYLE,
                cornerRadiusDp, shadowDp, borderWidthDp,
                frameColor, accentColor, textColor, discColor, labelColor, spinSpeed,
                showTrackInfo, titleSizeSp, artistSizeSp, filmTone,
                perforationCount, perforationRadiusDp, paperColor, shellColor, tiltDegrees);
    }

    public static CoverStyleConfig fromJson(String json) {
        CoverStyleConfig d = defaults(DEFAULT_STYLE);
        if (json == null || json.trim().isEmpty()) {
            return d;
        }
        try {
            JSONObject obj = new JSONObject(json);
            String style = obj.optString("style", d.style);
            CoverStyleConfig base = defaults(style);
            return new CoverStyleConfig(
                    STYLES.contains(style) ? style : DEFAULT_STYLE,
                    clamp(obj.optInt("cornerRadiusDp", base.cornerRadiusDp), 0, 24),
                    clamp(obj.optInt("shadowDp", base.shadowDp), 0, 16),
                    clamp(obj.optInt("borderWidthDp", base.borderWidthDp), 0, 12),
                    token(obj.optString("frameColor", base.frameColor), base.frameColor),
                    token(obj.optString("accentColor", base.accentColor), base.accentColor),
                    token(obj.optString("textColor", base.textColor), base.textColor),
                    token(obj.optString("discColor", base.discColor), base.discColor),
                    token(obj.optString("labelColor", base.labelColor), base.labelColor),
                    clamp((float) obj.optDouble("spinSpeed", base.spinSpeed), 0f, 2f),
                    obj.optBoolean("showTrackInfo", base.showTrackInfo),
                    clamp(obj.optInt("titleSizeSp", base.titleSizeSp), 12, 24),
                    clamp(obj.optInt("artistSizeSp", base.artistSizeSp), 10, 18),
                    token(obj.optString("filmTone", base.filmTone), base.filmTone),
                    clamp(obj.optInt("perforationCount", base.perforationCount), 4, 12),
                    clamp(obj.optInt("perforationRadiusDp", base.perforationRadiusDp), 1, 4),
                    token(obj.optString("paperColor", base.paperColor), base.paperColor),
                    token(obj.optString("shellColor", base.shellColor), base.shellColor),
                    clamp((float) obj.optDouble("tiltDegrees", base.tiltDegrees), -8f, 8f));
        } catch (JSONException ignored) {
            return d;
        }
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("style", style);
            obj.put("cornerRadiusDp", cornerRadiusDp);
            obj.put("shadowDp", shadowDp);
            obj.put("borderWidthDp", borderWidthDp);
            obj.put("frameColor", frameColor);
            obj.put("accentColor", accentColor);
            obj.put("textColor", textColor);
            obj.put("discColor", discColor);
            obj.put("labelColor", labelColor);
            obj.put("spinSpeed", spinSpeed);
            obj.put("showTrackInfo", showTrackInfo);
            obj.put("titleSizeSp", titleSizeSp);
            obj.put("artistSizeSp", artistSizeSp);
            obj.put("filmTone", filmTone);
            obj.put("perforationCount", perforationCount);
            obj.put("perforationRadiusDp", perforationRadiusDp);
            obj.put("paperColor", paperColor);
            obj.put("shellColor", shellColor);
            obj.put("tiltDegrees", tiltDegrees);
            return obj.toString();
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    public static CoverStyleConfig load(String style) {
        String raw = Settings.LYRICS_COVER_STYLE_PARAMS.get();
        if (raw == null || raw.trim().isEmpty()) {
            return defaults(style);
        }
        try {
            JSONObject obj = new JSONObject(raw);
            String entry = obj.optString(style, "");
            if (!entry.isEmpty()) {
                return fromJson(entry).withStyle(style);
            }
        } catch (JSONException ignored) {
        }
        return defaults(style);
    }

    public static void save(String style, CoverStyleConfig config) {
        Map<String, CoverStyleConfig> all = loadAll();
        all.put(STYLES.contains(style) ? style : DEFAULT_STYLE, config.withStyle(style));
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, CoverStyleConfig> entry : all.entrySet()) {
            try {
                obj.put(entry.getKey(), new JSONObject(entry.getValue().toJson()));
            } catch (JSONException ignored) {
            }
        }
        Settings.LYRICS_COVER_STYLE_PARAMS.save(obj.toString());
    }

    private static Map<String, CoverStyleConfig> loadAll() {
        Map<String, CoverStyleConfig> all = new LinkedHashMap<>();
        String raw = Settings.LYRICS_COVER_STYLE_PARAMS.get();
        if (raw == null || raw.trim().isEmpty()) {
            return all;
        }
        try {
            JSONObject obj = new JSONObject(raw);
            for (String style : STYLES) {
                String entry = obj.optString(style, "");
                if (!entry.isEmpty()) {
                    all.put(style, fromJson(entry).withStyle(style));
                }
            }
        } catch (JSONException ignored) {
        }
        return all;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String token(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.equals("auto")) {
            return v;
        }
        return PALETTE.containsKey(v) ? v : fallback;
    }

    /** Default value for {@code morphe_music_lyrics_cover_style_params}: all cover presets' defaults as one JSON object. */
    public static final String DEFAULT_PARAMS_JSON;

    static {
        JSONObject root = new JSONObject();
        for (String style : STYLES) {
            try {
                root.put(style, new JSONObject(defaults(style).toJson()));
            } catch (JSONException ignored) {
            }
        }
        DEFAULT_PARAMS_JSON = root.toString();
    }
}
