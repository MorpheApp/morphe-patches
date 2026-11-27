/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.patches.lyrics.derive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Hidden tuning variables for lyric visuals (spring, glow, gradient stops, scale).
 *
 * <p>Stored as a single JSON string in {@code Settings.LYRICS_TUNING_PARAMS}. All numeric
 * parameters default to the values previously hardcoded in the rendering code, so leaving the
 * preference at its default is byte-for-byte identical to the old behaviour. Values are clamped
 * here so a malformed entry can never crash a draw pass.
 */
public record LyricsTuningConfig(
        LyricsTuningConfig.Spring spring,
        LyricsTuningConfig.Glow glow,
        LyricsTuningConfig.Gradient gradient,
        LyricsTuningConfig.Scale scale
) {

    public static final String DEFAULT_PARAMS_JSON;

    static {
        try {
            DEFAULT_PARAMS_JSON = new JSONObject()
                    .put("spring", Spring.defaults().toJson())
                    .put("glow", Glow.defaults().toJson())
                    .put("gradient", Gradient.defaults().toJson())
                    .put("scale", Scale.defaults().toJson())
                    .toString();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private static volatile LyricsTuningConfig cached = defaults();
    private static volatile long revision = 0L;

    public static LyricsTuningConfig get() {
        return cached;
    }

    public static long revision() {
        return revision;
    }

    public static LyricsTuningConfig defaults() {
        return new LyricsTuningConfig(Spring.defaults(), Glow.defaults(),
                Gradient.defaults(), Scale.defaults());
    }

    public static void reload(String json) {
        cached = fromJson(json);
        revision++;
    }

    public static LyricsTuningConfig fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return defaults();
        }
        try {
            JSONObject root = new JSONObject(json.trim());
            return new LyricsTuningConfig(
                    Spring.fromJson(root.optJSONObject("spring")),
                    Glow.fromJson(root.optJSONObject("glow")),
                    Gradient.fromJson(root.optJSONObject("gradient")),
                    Scale.fromJson(root.optJSONObject("scale")));
        } catch (JSONException ignored) {
            return defaults();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- Spring
    public record Spring(
            float zetaDefault, float zetaTight, float zetaWide,
            float relaxZeta, float relaxBoost, float relaxMaxZeta,
            float maxGapMs, float settleRatio,
            float riseX1, float riseX2, float riseX3, float riseX4,
            int maxStaggerSteps, float relaxUpwardFactor
    ) {
        public static Spring defaults() {
            return new Spring(0.78f, 0.85f, 0.73f, 0.80f, 0.05f, 0.95f,
                    2500f, 1.5f, 0.41f, 0f, 0.12f, 0.99f, 12, 0.5f);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("zetaDefault", zetaDefault).put("zetaTight", zetaTight)
                    .put("zetaWide", zetaWide).put("relaxZeta", relaxZeta)
                    .put("relaxBoost", relaxBoost).put("relaxMaxZeta", relaxMaxZeta)
                    .put("maxGapMs", maxGapMs).put("settleRatio", settleRatio)
                    .put("riseX1", riseX1).put("riseX2", riseX2)
                    .put("riseX3", riseX3).put("riseX4", riseX4)
                    .put("maxStaggerSteps", maxStaggerSteps)
                    .put("relaxUpwardFactor", relaxUpwardFactor);
        }

        static Spring fromJson(JSONObject o) {
            Spring d = defaults();
            if (o == null) return d;
            return new Spring(
                    clamp((float) o.optDouble("zetaDefault", d.zetaDefault), 0.01f, 0.99f),
                    clamp((float) o.optDouble("zetaTight", d.zetaTight), 0.01f, 0.99f),
                    clamp((float) o.optDouble("zetaWide", d.zetaWide), 0.01f, 0.99f),
                    clamp((float) o.optDouble("relaxZeta", d.relaxZeta), 0.01f, 0.99f),
                    clamp((float) o.optDouble("relaxBoost", d.relaxBoost), 0f, 0.2f),
                    clamp((float) o.optDouble("relaxMaxZeta", d.relaxMaxZeta), 0.01f, 0.99f),
                    clamp((float) o.optDouble("maxGapMs", d.maxGapMs), 100f, 10000f),
                    clamp((float) o.optDouble("settleRatio", d.settleRatio), 0.2f, 4f),
                    clamp((float) o.optDouble("riseX1", d.riseX1), 0f, 1f),
                    clamp((float) o.optDouble("riseX2", d.riseX2), 0f, 1f),
                    clamp((float) o.optDouble("riseX3", d.riseX3), 0f, 1f),
                    clamp((float) o.optDouble("riseX4", d.riseX4), 0f, 1f),
                    clamp(o.optInt("maxStaggerSteps", d.maxStaggerSteps), 1, 32),
                    clamp((float) o.optDouble("relaxUpwardFactor", d.relaxUpwardFactor), 0.1f, 1f));
        }
    }

    // ---------------------------------------------------------------- Glow
    public record Glow(
            float blurRadiusEm, float elevationEm, float extraPaddingEm,
            float emphasisLiftEm, float emphasisRiseEm, float emphasisShiftEm,
            float emphasisScaleGain, float glowPeakOpacity, float glowTailOpacity,
            float glowReleaseOpacity, float glowFinalOpacity,
            float omegaColor, float omegaSimple,
            float finishXMs, float finishAlphaMs,
            float maskRasterScale, float glowRasterScale,
            float maxMaskSidePx, long prewarmBudgetNs,
            float charShadowIntensityMax
    ) {
        public static Glow defaults() {
            return new Glow(0.10f, 0.05f, 0.40f, 0.05f, 0.035f, 0.05f, 0.1f,
                    0.8f, 0.2f, 0.7f, 0.3f, 14.0f, 11.5f, 180f, 240f,
                    1.25f, 0.75f, 2048f, 1_500_000L, 0.45f);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("blurRadiusEm", blurRadiusEm).put("elevationEm", elevationEm)
                    .put("extraPaddingEm", extraPaddingEm)
                    .put("emphasisLiftEm", emphasisLiftEm).put("emphasisRiseEm", emphasisRiseEm)
                    .put("emphasisShiftEm", emphasisShiftEm).put("emphasisScaleGain", emphasisScaleGain)
                    .put("glowPeakOpacity", glowPeakOpacity).put("glowTailOpacity", glowTailOpacity)
                    .put("glowReleaseOpacity", glowReleaseOpacity).put("glowFinalOpacity", glowFinalOpacity)
                    .put("omegaColor", omegaColor).put("omegaSimple", omegaSimple)
                    .put("finishXMs", finishXMs).put("finishAlphaMs", finishAlphaMs)
                    .put("maskRasterScale", maskRasterScale).put("glowRasterScale", glowRasterScale)
                    .put("maxMaskSidePx", maxMaskSidePx).put("prewarmBudgetNs", prewarmBudgetNs)
                    .put("charShadowIntensityMax", charShadowIntensityMax);
        }

        static Glow fromJson(JSONObject o) {
            Glow d = defaults();
            if (o == null) return d;
            return new Glow(
                    clamp((float) o.optDouble("blurRadiusEm", d.blurRadiusEm), 0f, 0.5f),
                    clamp((float) o.optDouble("elevationEm", d.elevationEm), 0f, 0.2f),
                    clamp((float) o.optDouble("extraPaddingEm", d.extraPaddingEm), 0f, 1f),
                    clamp((float) o.optDouble("emphasisLiftEm", d.emphasisLiftEm), 0f, 0.3f),
                    clamp((float) o.optDouble("emphasisRiseEm", d.emphasisRiseEm), 0f, 0.3f),
                    clamp((float) o.optDouble("emphasisShiftEm", d.emphasisShiftEm), 0f, 0.3f),
                    clamp((float) o.optDouble("emphasisScaleGain", d.emphasisScaleGain), 0f, 0.5f),
                    clamp((float) o.optDouble("glowPeakOpacity", d.glowPeakOpacity), 0f, 1f),
                    clamp((float) o.optDouble("glowTailOpacity", d.glowTailOpacity), 0f, 1f),
                    clamp((float) o.optDouble("glowReleaseOpacity", d.glowReleaseOpacity), 0f, 1f),
                    clamp((float) o.optDouble("glowFinalOpacity", d.glowFinalOpacity), 0f, 1f),
                    clamp((float) o.optDouble("omegaColor", d.omegaColor), 1f, 50f),
                    clamp((float) o.optDouble("omegaSimple", d.omegaSimple), 1f, 50f),
                    clamp((float) o.optDouble("finishXMs", d.finishXMs), 40f, 2000f),
                    clamp((float) o.optDouble("finishAlphaMs", d.finishAlphaMs), 40f, 2000f),
                    clamp((float) o.optDouble("maskRasterScale", d.maskRasterScale), 0.5f, 2f),
                    clamp((float) o.optDouble("glowRasterScale", d.glowRasterScale), 0.5f, 2f),
                    clamp((float) o.optDouble("maxMaskSidePx", d.maxMaskSidePx), 512f, 4096f),
                    clamp(o.optLong("prewarmBudgetNs", d.prewarmBudgetNs), 100_000L, 10_000_000L),
                    clamp((float) o.optDouble("charShadowIntensityMax", d.charShadowIntensityMax), 0f, 1f));
        }
    }

    // --------------------------------------------------------------- Gradient
    public record Gradient(float[] stops, float wipeWidthEm, float featherDp, float featherEm,
                           float romanSubWidthRatio) {
        public static Gradient defaults() {
            return new Gradient(new float[]{0f, 0.1f, 0.9f, 1f}, 1f, 6f, 0.30f, 0.7f);
        }

        JSONObject toJson() throws JSONException {
            JSONArray arr = new JSONArray();
            for (float s : stops) arr.put(s);
            return new JSONObject()
                    .put("stops", arr).put("wipeWidthEm", wipeWidthEm)
                    .put("featherDp", featherDp).put("featherEm", featherEm)
                    .put("romanSubWidthRatio", romanSubWidthRatio);
        }

        static Gradient fromJson(JSONObject o) {
            Gradient d = defaults();
            if (o == null) return d;
            float[] stops = d.stops;
            JSONArray arr = o.optJSONArray("stops");
            if (arr != null && arr.length() >= 2) {
                int n = Math.min(arr.length(), 6);
                float[] tmp = new float[n];
                float prev = 0f;
                boolean ok = true;
                for (int i = 0; i < n; i++) {
                    float v = clamp((float) arr.optDouble(i, Double.NaN), 0f, 1f);
                    if (Float.isNaN(v)) { ok = false; break; }
                    if (i > 0 && v < prev + 0.01f) { ok = false; break; }
                    tmp[i] = v;
                    prev = v;
                }
                if (ok) stops = tmp;
            }
            if (stops[0] != 0f) stops[0] = 0f;
            if (stops[stops.length - 1] != 1f) stops[stops.length - 1] = 1f;
            return new Gradient(
                    stops,
                    clamp((float) o.optDouble("wipeWidthEm", d.wipeWidthEm), 0.2f, 3f),
                    clamp((float) o.optDouble("featherDp", d.featherDp), 1f, 32f),
                    clamp((float) o.optDouble("featherEm", d.featherEm), 0.05f, 1f),
                    clamp((float) o.optDouble("romanSubWidthRatio", d.romanSubWidthRatio), 0.3f, 1f));
        }
    }

    // ----------------------------------------------------------------- Scale
    public record Scale(
            float activeLine, float inactiveLine, float inactiveAlpha, float browseAlpha,
            float[] spotlightAlphas, float[] spotlightScales,
            float secondaryClosed, float regionClosed, float regionSlide,
            float emphasisScaleGain, float coverZoom,
            float gapEnterScale, float gapPeakScale, float gapBreathAmp,
            float gapDotRadiusDp
    ) {
        public static Scale defaults() {
            return new Scale(1.0f, 0.93f, 0.35f, 0.60f,
                    new float[]{0.35f, 0.28f, 0.20f, 0.14f, 0.10f},
                    new float[]{0.97f, 0.97f, 0.93f, 0.93f, 0.90f, 0.85f},
                    0.80f, 0.80f, 0.35f, 0.1f, 1.06f, 0.58f, 1.18f, 0.10f, 5.0f);
        }

        JSONObject toJson() throws JSONException {
            JSONArray a = new JSONArray();
            for (float v : spotlightAlphas) a.put(v);
            JSONArray b = new JSONArray();
            for (float v : spotlightScales) b.put(v);
            return new JSONObject()
                    .put("activeLine", activeLine).put("inactiveLine", inactiveLine)
                    .put("inactiveAlpha", inactiveAlpha).put("browseAlpha", browseAlpha)
                    .put("spotlightAlphas", a).put("spotlightScales", b)
                    .put("secondaryClosed", secondaryClosed).put("regionClosed", regionClosed)
                    .put("regionSlide", regionSlide).put("emphasisScaleGain", emphasisScaleGain)
                    .put("coverZoom", coverZoom).put("gapEnterScale", gapEnterScale)
                    .put("gapPeakScale", gapPeakScale).put("gapBreathAmp", gapBreathAmp)
                    .put("gapDotRadiusDp", gapDotRadiusDp);
        }

        static Scale fromJson(JSONObject o) {
            Scale d = defaults();
            if (o == null) return d;
            float[] alphas = d.spotlightAlphas;
            JSONArray a = o.optJSONArray("spotlightAlphas");
            if (a != null && a.length() >= 4) {
                int n = Math.min(a.length(), 6);
                float[] tmp = new float[n];
                boolean ok = true;
                for (int i = 0; i < n; i++) {
                    float v = clamp((float) a.optDouble(i, Double.NaN), 0f, 1f);
                    if (Float.isNaN(v)) { ok = false; break; }
                    tmp[i] = v;
                }
                if (ok) alphas = tmp;
            }
            float[] scales = d.spotlightScales;
            JSONArray b = o.optJSONArray("spotlightScales");
            if (b != null && b.length() >= 4) {
                int n = Math.min(b.length(), 6);
                float[] tmp = new float[n];
                boolean ok = true;
                for (int i = 0; i < n; i++) {
                    float v = clamp((float) b.optDouble(i, Double.NaN), 0.5f, 1.3f);
                    if (Float.isNaN(v)) { ok = false; break; }
                    tmp[i] = v;
                }
                if (ok) scales = tmp;
            }
            return new Scale(
                    clamp((float) o.optDouble("activeLine", d.activeLine), 0.5f, 1.3f),
                    clamp((float) o.optDouble("inactiveLine", d.inactiveLine), 0.5f, 1.3f),
                    clamp((float) o.optDouble("inactiveAlpha", d.inactiveAlpha), 0f, 1f),
                    clamp((float) o.optDouble("browseAlpha", d.browseAlpha), 0f, 1f),
                    alphas, scales,
                    clamp((float) o.optDouble("secondaryClosed", d.secondaryClosed), 0.5f, 1.3f),
                    clamp((float) o.optDouble("regionClosed", d.regionClosed), 0.5f, 1.3f),
                    clamp((float) o.optDouble("regionSlide", d.regionSlide), 0f, 1f),
                    clamp((float) o.optDouble("emphasisScaleGain", d.emphasisScaleGain), 0f, 0.5f),
                    clamp((float) o.optDouble("coverZoom", d.coverZoom), 1f, 1.3f),
                    clamp((float) o.optDouble("gapEnterScale", d.gapEnterScale), 0.3f, 1f),
                    clamp((float) o.optDouble("gapPeakScale", d.gapPeakScale), 1f, 1.5f),
                    clamp((float) o.optDouble("gapBreathAmp", d.gapBreathAmp), 0f, 0.3f),
                    clamp((float) o.optDouble("gapDotRadiusDp", d.gapDotRadiusDp), 2f, 12f));
        }
    }
}
