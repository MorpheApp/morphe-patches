/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.SurfaceTexture;
import android.hardware.DataSpace;
import android.media.Image;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Draws videos with an effect shader.
 * <p>
 * YouTube and YouTube Music can draw videos with their own OpenGL renderer instead of showing
 * the decoder output directly. Its passthrough shader is replaced with one that holds the code of
 * the selected effect. All effects are user changeable code, and the default ones are in
 * {@link VideoEffectPresets}.
 */
@SuppressWarnings("unused")
public final class VideoEffectsPatch {

    /**
     * Animations use the time in seconds. It wraps so that it keeps its precision in the shader.
     */
    private static final long TIME_WRAP_MILLISECONDS = 600_000;

    /**
     * Name of the only video view type that the OpenGL renderer draws on.
     */
    private static final String SURFACE_VIEW_TYPE = "SURFACE";

    /**
     * The vertex shader of YouTube, plus the untransformed coordinates and the texture
     * transform for the fragment shader. It still works with the original fragment shader.
     * <p>
     * The transform is passed as varyings and not as a shared uniform, because a uniform
     * used by both shaders must have the same precision, and highp may not exist in the
     * fragment shader.
     */
    private static final String VERTEX_SHADER = """
            attribute vec4 vPosition;
            attribute vec4 vTexCoord;
            uniform mat4 texTransMat;
            varying vec2 texCoordVar;
            varying vec2 vUv;
            varying vec2 vTexOrigin;
            varying vec2 vTexAxisX;
            varying vec2 vTexAxisY;
            void main() {
                gl_Position = vPosition;
                texCoordVar = (texTransMat * vTexCoord).xy;
                vUv = vTexCoord.xy;
                vTexOrigin = (texTransMat * vec4(0.0, 0.0, 0.0, 1.0)).xy;
                vTexAxisX = (texTransMat * vec4(1.0, 0.0, 0.0, 0.0)).xy;
                vTexAxisY = (texTransMat * vec4(0.0, 1.0, 0.0, 0.0)).xy;
            }
            """;

    private static final String FRAGMENT_SHADER_FUNCTIONS = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES texture;
            uniform int uEffect;
            uniform float uTime;
            uniform vec2 uResolution;
            // 0 for SDR, 1 for PQ and 2 for HLG.
            uniform int uVideoTransfer;
            uniform int uHdrOutput;
            varying vec2 texCoordVar;
            varying vec2 vUv;
            varying vec2 vTexOrigin;
            varying vec2 vTexAxisX;
            varying vec2 vTexAxisY;

            // Effects are made for SDR colors. HDR colors up to the brightness of SDR white use the
            // values up to HDR_WHITE, and brighter colors are compressed into the values above it.
            const float HDR_WHITE = 0.85;
            const float HDR_WHITE_NITS = 203.0;
            // log(10000.0 / HDR_WHITE_NITS)
            const float HDR_LOG_RANGE = 3.8971;

            // Brightest HDR color that the effect used for the pixel.
            float hdrBrightest = 0.0;

            vec3 hdrVideoToNits(vec3 c) {
                c = clamp(c, 0.0, 1.0);
                if (uVideoTransfer == 2) {
                    vec3 light = mix(c * c / 3.0, (exp((c - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(0.5, c));
                    float y = dot(light, vec3(0.2627, 0.6780, 0.0593));
                    return 1000.0 * pow(max(y, 0.000001), 0.2) * light;
                }
                vec3 p = pow(c, vec3(1.0 / 78.84375));
                return 10000.0 * pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(6.2773946));
            }

            vec3 hdrNitsToEffect(vec3 nits) {
                vec3 x = nits / HDR_WHITE_NITS;
                return mix(HDR_WHITE * pow(x, vec3(1.0 / 2.2)),
                        HDR_WHITE + (1.0 - HDR_WHITE) * log(max(x, 1.0)) / HDR_LOG_RANGE, step(1.0, x));
            }

            vec3 hdrEffectToNits(vec3 c) {
                return HDR_WHITE_NITS * mix(pow(c / HDR_WHITE, vec3(2.2)),
                        exp(max(c - HDR_WHITE, 0.0) * HDR_LOG_RANGE / (1.0 - HDR_WHITE)), step(HDR_WHITE, c));
            }

            vec3 hdrEffectToOutput(vec3 c) {
                // Effects can make any part of the picture white, which must not become a bright HDR highlight.
                c = clamp(c, 0.0, max(hdrBrightest, HDR_WHITE));
                if (uHdrOutput == 1) {
                    vec3 p = pow(hdrEffectToNits(c) / 10000.0, vec3(0.1593017578125));
                    return pow((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p), vec3(78.84375));
                }
                // BT.2020 to BT.709 colors.
                vec3 light = mat3(
                        1.6605, -0.1246, -0.0182,
                        -0.5876, 1.1329, -0.1006,
                        -0.0728, -0.0083, 1.1187) * pow(c, vec3(2.2));
                return pow(clamp(light, 0.0, 1.0), vec3(1.0 / 2.2));
            }

            const float PI = 3.14159265;

            // Color at a point of the picture, where (0, 0) is the bottom left and (1, 1) the top right.
            vec3 pick(vec2 uv) {
                uv = clamp(uv, 0.0, 1.0);
                vec3 c = texture2D(texture, vTexOrigin + uv.x * vTexAxisX + uv.y * vTexAxisY).rgb;
                if (uVideoTransfer == 0) return c;
                c = hdrNitsToEffect(hdrVideoToNits(c));
                hdrBrightest = max(hdrBrightest, max(c.r, max(c.g, c.b)));
                return c;
            }

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            float rand(vec2 co) {
                return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
            }

            float vignette(vec2 uv, float strength) {
                vec2 d = uv - 0.5;
                return clamp(1.0 - dot(d, d) * strength, 0.0, 1.0);
            }

            vec3 adjustSaturation(vec3 c, float amount) {
                return clamp(mix(vec3(luma(c)), c, amount), 0.0, 1.0);
            }

            vec3 hueRotate(vec3 c, float angle) {
                const vec3 k = vec3(0.57735);
                float cosAngle = cos(angle);
                return c * cosAngle + cross(k, c) * sin(angle) + k * dot(k, c) * (1.0 - cosAngle);
            }

            vec3 hue(float h) {
                return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
            }

            // Strength of the edges around a point, with a Sobel filter of the brightness.
            float edges(vec2 uv, float width) {
                vec2 px = width / uResolution;
                float tl = luma(pick(uv + vec2(-px.x, px.y)));
                float t = luma(pick(uv + vec2(0.0, px.y)));
                float tr = luma(pick(uv + vec2(px.x, px.y)));
                float l = luma(pick(uv + vec2(-px.x, 0.0)));
                float r = luma(pick(uv + vec2(px.x, 0.0)));
                float bl = luma(pick(uv + vec2(-px.x, -px.y)));
                float b = luma(pick(uv + vec2(0.0, -px.y)));
                float br = luma(pick(uv + vec2(px.x, -px.y)));
                float gx = tr + 2.0 * r + br - tl - 2.0 * l - bl;
                float gy = tl + 2.0 * t + tr - bl - 2.0 * b - br;
                return length(vec2(gx, gy));
            }

            // S curve for each color channel, that darkens the dark parts and brightens the bright parts.
            vec3 contrast(vec3 c, float amount) {
                return mix(c, c * c * (3.0 - 2.0 * c), amount);
            }

            // Point with the center of the picture at (0, 0) and the same scale on both axes.
            vec2 centered(vec2 uv) {
                return (uv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0);
            }

            vec2 uncentered(vec2 p) {
                return p / vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) + 0.5;
            }

            vec2 rotate(vec2 p, float angle) {
                float c = cos(angle);
                float s = sin(angle);
                return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
            }

            """;

    /**
     * Pasted text can be cut off at this length, so longer code arrives incomplete.
     */
    public static final int PASTED_TEXT_LENGTH_LIMIT = 20_000;

    /**
     * Comment at the start of the code of a new custom effect. It tells an AI chatbot how to write
     * an effect. It is not saved, so it does not fill the settings.
     */
    private static final String CUSTOM_EFFECT_INSTRUCTIONS = String.format(Locale.ROOT, """
            // CUSTOM VIDEO EFFECT
            // Copy all of this text to an AI chatbot, and describe the effect you want after it. Then replace all of this text with the code the AI gives you.
            //
            // Instructions for the AI:
            // - Write GLSL ES 1.00 (OpenGL ES 2.0) fragment shader code for a video effect.
            // - Write the function vec3 customEffect(vec2 uv) that returns the RGB color (0.0 to 1.0) of the pixel at uv. uv is (0.0, 0.0) at the bottom left of the video and (1.0, 1.0) at the top right.
            // - Available: vec3 pick(vec2 uv) is the video color at uv. float uTime is the time in seconds, and goes back to 0.0 every 10 minutes. vec2 uResolution is the size in pixels. PI. float luma(vec3 color). float rand(vec2 seed) is from 0.0 to 1.0. vec2 rotate(vec2 p, float angle). vec3 adjustSaturation(vec3 color, float amount). vec3 hueRotate(vec3 color, float angle). vec3 hue(float h) is the fully saturated color at h (0.0 to 1.0) around the color wheel. vec3 contrast(vec3 color, float amount) is an S curve. float vignette(vec2 uv, float strength) is 1.0 in the middle and less toward the corners. float edges(vec2 uv, float width) is the strength of the edges at uv. vec2 centered(vec2 uv) is the point with the center of the video at (0.0, 0.0) and square pixels, and vec2 uncentered(vec2 p) changes it back to uv.
            // - Don't write #version, #extension, precision, uniforms, varyings, main() or texture2D.
            // - Start the names of other functions and global constants with "my".
            // - Write floats with a decimal point (1.0, not 1). Loops must have constant bounds.
            // - Keep the code, comments included, shorter than %d characters. Longer text can be cut off when it is pasted.
            // - Answer with only the code.
            """, PASTED_TEXT_LENGTH_LIMIT);

    /**
     * Custom effect code that shows the video as it is. Used when no effect is selected,
     * or the selected one is missing or has an error.
     */
    private static final String CUSTOM_EFFECT_DEFAULT_CODE = """
            vec3 customEffect(vec2 uv) {
                return pick(uv);
            }""";

    /**
     * Code of a new custom effect.
     */
    public static final String CUSTOM_EFFECT_TEMPLATE =
            CUSTOM_EFFECT_INSTRUCTIONS + "\n" + CUSTOM_EFFECT_DEFAULT_CODE;

    /**
     * Start of a line with the name of an effect in the saved setting.
     * It is never valid in shader code.
     */
    public static final String CUSTOM_EFFECT_NAME_PREFIX = "@";

    /**
     * A custom effect, with code that has the function {@code vec3 customEffect(vec2 uv)}.
     */
    public static final class CustomEffect {
        public final String name;
        public final String code;

        public CustomEffect(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    /**
     * The saved custom effects and the setting value they were read from.
     */
    @Nullable
    private static volatile String cachedCustomEffectsValue;
    private static volatile List<CustomEffect> cachedCustomEffects = List.of();

    /**
     * Line of the fragment shader where the custom effect code starts.
     */
    private static final int CUSTOM_EFFECT_FIRST_LINE = countLines(FRAGMENT_SHADER_FUNCTIONS) + 1;

    /**
     * Line number in compiler messages, such as "ERROR: 0:123: ...".
     */
    private static final Pattern COMPILER_LINE_NUMBER = Pattern.compile("\\b0:(\\d+):");

    /**
     * Custom effect code of the last fragment shader, and the shader made from it.
     * The shader shows the video as it is if the code has an error.
     */
    @Nullable
    private static volatile String cachedCustomEffectCode;
    @Nullable
    private static volatile String cachedFragmentShader;

    /**
     * The program that the app made, and if it was made with the effect shaders.
     * Only used on the render thread, as are all program fields.
     */
    private static int appProgram;
    private static boolean appProgramHasEffects;

    /**
     * Program made with newer custom effect code than the program of the app, or 0 for none.
     * The app keeps its program while the player exists, so a program with the new code is used
     * instead. The program of the app is not linked again, because some GPU drivers, such as
     * the one of the emulator, keep drawing with the old shader.
     */
    private static int customProgram;
    @Nullable
    private static EGLContext customProgramContext;

    /**
     * Custom effect code of the program in use.
     */
    @Nullable
    private static String programCustomEffectCode;

    /**
     * The program in use, and its uniform locations.
     */
    private static int lastProgram;
    private static int effectLocation = -1;
    private static int timeLocation = -1;
    private static int resolutionLocation = -1;
    private static int videoTransferLocation = -1;
    private static int hdrOutputLocation = -1;
    private static int textureTransformLocation = -1;
    private static final int[] viewport = new int[4];

    /**
     * Values of the uVideoTransfer uniform.
     */
    private static final int VIDEO_TRANSFER_SDR = 0;
    private static final int VIDEO_TRANSFER_PQ = 1;
    private static final int VIDEO_TRANSFER_HLG = 2;

    private static final int EGL_GL_COLORSPACE = 0x309D;
    private static final int EGL_GL_COLORSPACE_BT2020_PQ = 0x3340;
    private static final int HDR_COLOR_BITS = 10;
    private static final int EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL = 0x3360;
    private static final int EGL_CTA861_3_MAX_FRAME_AVERAGE_LEVEL = 0x3361;
    private static final int EGL_METADATA_SCALING = 50_000;
    private static final int EGL_DONT_CARE = -1;

    /**
     * Offsets in {@link MediaFormat#KEY_HDR_STATIC_INFO}.
     */
    private static final int HDR_STATIC_INFO_SIZE = 25;
    private static final int HDR_STATIC_INFO_MASTERING_LIGHT_OFFSET = 17;
    private static final int HDR_STATIC_INFO_MAX_LIGHT_OFFSET = 21;
    private static final int HDR_STATIC_INFO_MAX_AVERAGE_LIGHT_OFFSET = 23;

    /**
     * Peak brightness of HLG videos that are converted to PQ.
     */
    private static final int HLG_MAX_LIGHT_NITS = 1000;

    /**
     * Brightest light that PQ can have. Larger values in the metadata of a video are not valid.
     */
    private static final int PQ_MAX_LIGHT_NITS = 10_000;

    /**
     * Last output format of the video decoder.
     */
    @Nullable
    private static volatile MediaFormat videoFormat;

    /**
     * Brightness of the video in nits, or 0 if not known.
     */
    private static volatile int videoMaxLight;
    private static volatile int videoMaxAverageLight;

    /**
     * Transfer function of the video frame that is drawn.
     */
    private static int videoTransfer = VIDEO_TRANSFER_SDR;

    /**
     * If frames are drawn on an HDR surface. HLG videos are also drawn as PQ,
     * because fewer devices can draw HLG.
     */
    private static boolean hdrOutput;

    /**
     * If an HDR surface can be made. Null if not checked yet.
     */
    @Nullable
    private static Boolean hdrOutputSupported;

    /**
     * App class that holds the EGL objects of a video renderer.
     */
    public interface EglHelper {
        // Methods added during patching.
        EGLConfig patch_getEglConfig();
        void patch_setEglSurface(EGLSurface eglSurface);
        Surface patch_getSurface();
    }

    /**
     * App class that draws the video frames.
     */
    public interface VideoRenderer {
        // Methods added during patching.
        EglHelper patch_getEglHelper();
    }

    /**
     * The video that is playing.
     */
    @Nullable
    private static volatile String currentVideoId;

    /**
     * The last video drawn with the effect shader.
     */
    @Nullable
    private static volatile String lastEffectVideoId;

    /**
     * If the app was last told to draw with the OpenGL renderer.
     */
    private static volatile boolean glVideoRendererUsed;

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static int countLines(String text) {
        int lines = 0;
        for (int i = 0, length = text.length(); i < length; i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /**
     * Reads the custom effects from the saved setting value, which is the text of
     * {@link #encodeCustomEffects(List)}, or that text compressed.
     */
    public static List<CustomEffect> decodeCustomEffects(String value) {
        List<CustomEffect> effects = new ArrayList<>();
        String text;
        try {
            text = value.isEmpty() || value.startsWith(CUSTOM_EFFECT_NAME_PREFIX)
                    ? value
                    : decompress(value);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the custom video effects: " + value, ex);
            return effects;
        }

        String name = null;
        StringBuilder code = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith(CUSTOM_EFFECT_NAME_PREFIX)) {
                if (name != null) effects.add(new CustomEffect(name, code.toString().trim()));
                name = line.substring(CUSTOM_EFFECT_NAME_PREFIX.length()).trim();
                code.setLength(0);
            } else if (name != null) {
                code.append(line).append('\n');
            } else if (!line.trim().isEmpty()) {
                Logger.printException(() -> "Custom video effects text does not start with a name: " + line);
            }
        }
        if (name != null) effects.add(new CustomEffect(name, code.toString().trim()));
        return effects;
    }

    /**
     * Each effect is a line with {@link #CUSTOM_EFFECT_NAME_PREFIX} and the name, then the lines
     * of its code. The text is compressed if that is shorter, so exported settings stay small.
     *
     * @return The setting value that {@link #decodeCustomEffects(String)} reads.
     */
    public static String encodeCustomEffects(List<CustomEffect> effects) {
        StringBuilder value = new StringBuilder();
        for (CustomEffect effect : effects) {
            if (Utils.isNotEmpty(value)) value.append('\n');
            value.append(CUSTOM_EFFECT_NAME_PREFIX).append(effect.name);
            for (String line : effect.code.trim().split("\n", -1)) {
                value.append('\n');
                // Shader code ignores the space, and the line is not read as a name.
                if (line.startsWith(CUSTOM_EFFECT_NAME_PREFIX)) value.append(' ');
                value.append(line);
            }
        }

        String text = value.toString();
        String compressed = compress(text);
        return compressed.length() < text.length() ? compressed : text;
    }

    /**
     * Deflate and URL safe Base64, which has no characters that the settings export escapes.
     * Base64 never has {@link #CUSTOM_EFFECT_NAME_PREFIX}, so compressed text can be told apart.
     */
    private static String compress(String text) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(text.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer));
            }
            return Base64.encodeToString(output.toByteArray(),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } finally {
            deflater.end();
        }
    }

    private static String decompress(String value) throws DataFormatException {
        byte[] compressed = Base64.decode(value, Base64.URL_SAFE);
        Inflater inflater = new Inflater(true);
        try {
            // Without a header, the inflater needs one more byte after the data.
            inflater.setInput(Arrays.copyOf(compressed, compressed.length + 1));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int length = inflater.inflate(buffer);
                if (length == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new DataFormatException("Compressed custom video effects are cut off");
                }
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        } finally {
            inflater.end();
        }
    }

    /**
     * @return The code without the instructions comment of {@link #CUSTOM_EFFECT_TEMPLATE}.
     */
    public static String removeCustomEffectInstructions(String code) {
        String trimmed = code.trim();
        String instructions = CUSTOM_EFFECT_INSTRUCTIONS.trim();
        return trimmed.startsWith(instructions)
                ? trimmed.substring(instructions.length()).trim()
                : trimmed;
    }

    /**
     * @return The saved custom effects.
     */
    public static List<CustomEffect> getCustomEffects() {
        String value = SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.get();
        //noinspection StringEquality
        if (value != cachedCustomEffectsValue) {
            cachedCustomEffects = Collections.unmodifiableList(decodeCustomEffects(value));
            cachedCustomEffectsValue = value;
        }
        return cachedCustomEffects;
    }

    /**
     * @return Off and the names of the effects, for the effect lists.
     */
    public static CharSequence[] getEffectEntries() {
        List<CustomEffect> effects = getCustomEffects();
        CharSequence[] entries = new CharSequence[effects.size() + 1];
        entries[0] = str("morphe_video_effect_entry_none");
        for (int i = 0, size = effects.size(); i < size; i++) {
            entries[i + 1] = effects.get(i).name;
        }
        return entries;
    }

    /**
     * @return The values of {@link #getEffectEntries()}, which are the values of
     *         {@link SharedYouTubeSettings#VIDEO_EFFECT}.
     */
    public static CharSequence[] getEffectEntryValues() {
        List<CustomEffect> effects = getCustomEffects();
        CharSequence[] values = new CharSequence[effects.size() + 1];
        values[0] = "";
        for (int i = 0, size = effects.size(); i < size; i++) {
            values[i + 1] = effects.get(i).name;
        }
        return values;
    }

    @Nullable
    public static CustomEffect findCustomEffect(String name) {
        for (CustomEffect effect : getCustomEffects()) {
            if (effect.name.equals(name)) return effect;
        }
        return null;
    }

    /**
     * @return The code of the selected effect,
     *         or code that shows the video as it is if no effect is selected.
     */
    private static String getSelectedCustomEffectCode() {
        String name = SharedYouTubeSettings.VIDEO_EFFECT.get();
        if (!name.isEmpty()) {
            CustomEffect effect = findCustomEffect(name);
            if (effect != null) return effect.code;
        }
        return CUSTOM_EFFECT_DEFAULT_CODE;
    }

    /**
     * Adds the custom effect code and the main method, which draws the effect if the uEffect
     * uniform is 1. With 0 it shows the video as it is, so the effect can be turned off while
     * the app keeps the program.
     */
    private static String createFragmentShader(String customEffectCode) {
        return FRAGMENT_SHADER_FUNCTIONS + customEffectCode + "\n" + """
                void main() {
                    vec3 c = uEffect == 1 ? customEffect(vUv) : pick(vUv);
                    if (uVideoTransfer != 0) c = hdrEffectToOutput(c);
                    gl_FragColor = vec4(c, 1.0);
                }
                """;
    }

    /**
     * @return The fragment shader with the custom effect code, or with code that shows the video as it is
     *         if the code has an error.
     */
    private static String getEffectFragmentShader() {
        String code = getSelectedCustomEffectCode();
        String shader = cachedFragmentShader;
        if (shader != null && code.equals(cachedCustomEffectCode)) {
            return shader;
        }

        shader = createFragmentShader(code);
        //noinspection StringEquality
        if (code != CUSTOM_EFFECT_DEFAULT_CODE) {
            String error;
            try {
                error = findShaderError(VERTEX_SHADER, shader);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not check the custom video effect", ex);
                error = ex.toString();
            }
            if (error != null) {
                String message = error;
                Logger.printInfo(() -> "Custom video effect has an error: " + message);
                shader = createFragmentShader(CUSTOM_EFFECT_DEFAULT_CODE);
            }
        }

        cachedCustomEffectCode = code;
        cachedFragmentShader = shader;
        return shader;
    }

    /**
     * Code that is cut off while it is pasted ends inside a block or a comment, and then the
     * compiler errors are about the code that comes after it, which does not help.
     *
     * @return If the code has a block or comment that is never closed.
     */
    public static boolean isCustomEffectCodeCutOff(String code) {
        int openBlocks = 0;
        for (int i = 0, length = code.length(); i < length; i++) {
            if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                if (end < 0) break;
                i = end;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                if (end < 0) return true;
                i = end + 1;
            } else {
                char c = code.charAt(i);
                if (c == '{') openBlocks++;
                else if (c == '}') openBlocks--;
            }
        }
        return openBlocks > 0;
    }

    /**
     * Compiles the custom effect code with the other effects, to find errors before it is used.
     *
     * @return The compiler errors, with the line numbers of the custom code,
     *         or null if the code has no error.
     */
    @Nullable
    public static String findCustomEffectError(String customEffectCode) {
        String error = findShaderError(VERTEX_SHADER, createFragmentShader(customEffectCode));
        if (error == null) return null;

        Matcher matcher = COMPILER_LINE_NUMBER.matcher(error);
        // StringBuffer, because appendReplacement with a StringBuilder needs Android 14.
        //noinspection StringBufferMayBeStringBuilder
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            //noinspection ConstantConditions
            int line = Integer.parseInt(matcher.group(1)) - CUSTOM_EFFECT_FIRST_LINE + 1;
            matcher.appendReplacement(result, line > 0 ? "line " + line + ":" : "$0");
        }
        matcher.appendTail(result);
        return result.toString().trim();
    }

    /**
     * Compiles and links a shader program in a small temporary OpenGL context.
     * The context of the app is never used, because a shader that fails to compile in it
     * can make the next shaders of the app fail too.
     *
     * @return The compiler or linker errors, or null if there is no error.
     */
    @Nullable
    private static String findShaderError(String vertexShader, String fragmentShader) {
        if (EGL14.eglGetCurrentContext().equals(EGL14.EGL_NO_CONTEXT)) {
            return findShaderErrorInNewContext(vertexShader, fragmentShader);
        }

        // The thread has a context of the app. Check on another thread with its own context.
        String[] error = new String[1];
        RuntimeException[] failure = new RuntimeException[1];
        Thread thread = new Thread(() -> {
            try {
                error[0] = findShaderErrorInNewContext(vertexShader, fragmentShader);
            } catch (RuntimeException ex) {
                failure[0] = ex;
            }
        }, "VideoEffectsShaderCheck");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the shader", ex);
        }
        if (failure[0] != null) throw failure[0];
        return error[0];
    }

    /**
     * Must be called on a thread without an OpenGL context.
     */
    @Nullable
    private static String findShaderErrorInNewContext(String vertexShader, String fragmentShader) {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        int vertex = 0;
        int fragment = 0;
        int program = 0;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("eglInitialize failed: " + EGL14.eglGetError());
            }
            int[] configAttributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0)
                    || configCount[0] == 0) {
                throw new IllegalStateException("eglChooseConfig failed: " + EGL14.eglGetError());
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (context.equals(EGL14.EGL_NO_CONTEXT) || surface.equals(EGL14.EGL_NO_SURFACE)
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("Could not make an OpenGL context: " + EGL14.eglGetError());
            }

            vertex = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            String error = compileShader(vertex, vertexShader);
            if (error != null) return error;

            fragment = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            error = compileShader(fragment, fragmentShader);
            if (error != null) return error;

            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            return status[0] == GLES20.GL_TRUE ? null : GLES20.glGetProgramInfoLog(program);
        } finally {
            if (program != 0) GLES20.glDeleteProgram(program);
            if (fragment != 0) GLES20.glDeleteShader(fragment);
            if (vertex != 0) GLES20.glDeleteShader(vertex);
            if (!display.equals(EGL14.EGL_NO_DISPLAY)) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (!surface.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, surface);
                if (!context.equals(EGL14.EGL_NO_CONTEXT)) EGL14.eglDestroyContext(display, context);
                // No eglTerminate, because the display is shared with the contexts of the app.
            }
        }
    }

    /**
     * @return The compiler errors, or null if there is no error.
     */
    @Nullable
    private static String compileShader(int shader, String source) {
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        return status[0] == GLES20.GL_TRUE ? null : GLES20.glGetShaderInfoLog(shader);
    }

    /**
     * The renderer is chosen when a video loads, so a video that started without an effect
     * needs to load again before an effect can show.
     *
     * @return If the current video is drawn with the effect shader.
     */
    public static boolean isCurrentVideoDrawnWithEffects() {
        String videoId = currentVideoId;
        return videoId != null && videoId.equals(lastEffectVideoId);
    }

    private static boolean isEnabled() {
        return !SharedYouTubeSettings.VIDEO_EFFECT.get().isEmpty();
    }

    /**
     * Injection point.
     */
    public static void setVideoId(String videoId) {
        currentVideoId = videoId;
    }

    /**
     * Injection point.
     * Turns on the OpenGL renderer that YouTube can draw videos with.
     */
    public static boolean useGlVideoRenderer(boolean original) {
        final boolean useRenderer = original || isEnabled();
        glVideoRendererUsed = useRenderer;
        if (!useRenderer) {
            // The same video can open again without the renderer.
            lastEffectVideoId = null;
        }
        return useRenderer;
    }

    /**
     * Injection point.
     * Server configs can draw videos without DRM on a secure surface view.
     * Videos with DRM still get a secure surface view, as the app picks it before this check.
     */
    public static boolean useSecureSurfaceView(boolean original) {
        return original && !isEnabled();
    }

    /**
     * Injection point.
     * A/B tests can draw videos on a GL or dual surface view.
     *
     * @param viewType GL or dual surface view type.
     */
    public static <T extends Enum<T>> T getVideoViewType(T viewType) {
        try {
            if (isEnabled()) {
                return Enum.valueOf(viewType.getDeclaringClass(), SURFACE_VIEW_TYPE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getVideoViewType failure", ex);
        }
        return viewType;
    }

    /**
     * Injection point.
     */
    public static String getVertexShader(String original) {
        return isEnabled() ? VERTEX_SHADER : original;
    }

    /**
     * Injection point.
     */
    public static String getFragmentShader(String original) {
        try {
            return isEnabled() ? getEffectFragmentShader() : original;
        } catch (Exception ex) {
            Logger.printException(() -> "getFragmentShader failure", ex);
            return original;
        }
    }

    /**
     * Injection point.
     * Replaces the call that puts the video shader program in use, on the render thread for every frame.
     */
    public static void useProgram(int program) {
        int programToUse = program;
        try {
            programToUse = findProgramToUse(program);
        } catch (Exception ex) {
            Logger.printException(() -> "findProgramToUse failure", ex);
        }

        GLES20.glUseProgram(programToUse);

        try {
            setUniforms(programToUse);
        } catch (Exception ex) {
            Logger.printException(() -> "setUniforms failure", ex);
        }
    }

    /**
     * @return The program of the app, or a program made with newer custom effect code.
     */
    private static int findProgramToUse(int program) {
        if (program != appProgram) {
            appProgram = program;
            appProgramHasEffects = GLES20.glGetUniformLocation(program, "uEffect") >= 0;
            // Belongs to the old program, and maybe to a context that no longer exists.
            deleteCustomProgram();
            // The program was made from the last shader that getFragmentShader() returned.
            programCustomEffectCode = cachedCustomEffectCode;
        }

        if (appProgramHasEffects && isEnabled()) {
            String code = getSelectedCustomEffectCode();
            //noinspection StringEquality
            if (code != programCustomEffectCode && !code.equals(programCustomEffectCode)) {
                // Not again for every frame if the program cannot be made.
                programCustomEffectCode = code;
                int newProgram = createProgram(program, getEffectFragmentShader());
                if (newProgram != 0) {
                    deleteCustomProgram();
                    customProgram = newProgram;
                    customProgramContext = EGL14.eglGetCurrentContext();
                    Logger.printDebug(() -> "Video shader program changed to the new custom effect code");
                }
            }
        }

        return customProgram != 0 ? customProgram : program;
    }

    private static void setUniforms(int program) {
        if (program != lastProgram) {
            lastProgram = program;
            effectLocation = GLES20.glGetUniformLocation(program, "uEffect");
            timeLocation = GLES20.glGetUniformLocation(program, "uTime");
            resolutionLocation = GLES20.glGetUniformLocation(program, "uResolution");
            videoTransferLocation = GLES20.glGetUniformLocation(program, "uVideoTransfer");
            hdrOutputLocation = GLES20.glGetUniformLocation(program, "uHdrOutput");
            textureTransformLocation = GLES20.glGetUniformLocation(program, "texTransMat");
        }

        // Program with the original shader.
        if (effectLocation < 0) return;

        // The last frames of a renderer can be drawn after the app stops using it.
        if (glVideoRendererUsed) {
            lastEffectVideoId = currentVideoId;
        }

        GLES20.glUniform1i(effectLocation, isEnabled() ? 1 : 0);
        GLES20.glUniform1f(timeLocation, (SystemClock.elapsedRealtime() % TIME_WRAP_MILLISECONDS) / 1000f);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        GLES20.glUniform2f(resolutionLocation, viewport[2], viewport[3]);
        GLES20.glUniform1i(videoTransferLocation, videoTransfer);
        GLES20.glUniform1i(hdrOutputLocation, hdrOutput ? 1 : 0);
    }

    /**
     * Injection point.
     * Sets the texture transform, which the app does for every frame with the location it found
     * in its own program.
     */
    public static void setTextureTransform(int location, int count, boolean transpose,
                                           float[] value, int offset) {
        GLES20.glUniformMatrix4fv(customProgram != 0 && lastProgram == customProgram
                ? textureTransformLocation
                : location, count, transpose, value, offset);
    }

    private static boolean isHdrOutputSupported(EGLDisplay display) {
        Boolean supported = hdrOutputSupported;
        if (supported == null) {
            String extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
            supported = extensions != null && extensions.contains("EGL_EXT_gl_colorspace_bt2020_pq");
            hdrOutputSupported = supported;
            boolean log = supported;
            Logger.printDebug(() -> "HDR video output supported: " + log);
        }
        return supported;
    }

    /**
     * Injection point.
     * Replaces the call that chooses the 8 bit config of the video renderer.
     */
    public static boolean chooseEglConfig(EGLDisplay display, int[] attributes, int attributesOffset,
                                          EGLConfig[] configs, int configsOffset, int configSize,
                                          int[] configCount, int configCountOffset) {
        try {
            if (isEnabled() && isHdrOutputSupported(display)) {
                EGLConfig config = findHdrConfig(display, attributes, attributesOffset);
                if (config != null) {
                    configs[configsOffset] = config;
                    configCount[configCountOffset] = 1;
                    return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "chooseEglConfig failure", ex);
        }
        return EGL14.eglChooseConfig(display, attributes, attributesOffset,
                configs, configsOffset, configSize, configCount, configCountOffset);
    }

    @Nullable
    private static EGLConfig findHdrConfig(EGLDisplay display, int[] attributes, int attributesOffset) {
        int[] hdrAttributes = Arrays.copyOfRange(attributes, attributesOffset, attributes.length);
        for (int i = 0; hdrAttributes[i] != EGL14.EGL_NONE; i += 2) {
            if (isColorSize(hdrAttributes[i])) hdrAttributes[i + 1] = HDR_COLOR_BITS;
        }

        EGLConfig[] configs = new EGLConfig[64];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, hdrAttributes, 0, configs, 0, configs.length, count, 0)) {
            return null;
        }

        // The sizes are minimums, so this also finds floating point configs.
        int[] size = new int[1];
        for (int i = 0; i < count[0]; i++) {
            EGL14.eglGetConfigAttrib(display, configs[i], EGL14.EGL_RED_SIZE, size, 0);
            if (size[0] == HDR_COLOR_BITS) return configs[i];
        }
        return null;
    }

    private static boolean isColorSize(int attribute) {
        return attribute == EGL14.EGL_RED_SIZE
                || attribute == EGL14.EGL_GREEN_SIZE
                || attribute == EGL14.EGL_BLUE_SIZE;
    }

    private static boolean isEffectShaderUsed() {
        return isEnabled() || appProgramHasEffects;
    }

    /**
     * Injection point.
     */
    public static void setVideoDataSpace(Image image) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isEffectShaderUsed()) {
                setVideoTransfer(image.getDataSpace());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoDataSpace failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setVideoDataSpace(SurfaceTexture texture) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isEffectShaderUsed()) {
                setVideoTransfer(texture.getDataSpace());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoDataSpace failure", ex);
        }
    }

    /**
     * Injection point.
     * Called for each frame, shortly before the decoder releases it to the video renderer.
     */
    public static void setVideoFormat(@Nullable MediaFormat format) {
        try {
            if (format == null || format == videoFormat || !isEffectShaderUsed()) return;
            videoFormat = format;

            int maxLight = 0;
            int maxAverageLight = 0;
            ByteBuffer info = format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO);
            if (info != null && info.remaining() >= HDR_STATIC_INFO_SIZE) {
                info = info.slice().order(ByteOrder.LITTLE_ENDIAN);
                maxLight = info.getShort(HDR_STATIC_INFO_MAX_LIGHT_OFFSET) & 0xFFFF;
                if (maxLight == 0) {
                    maxLight = info.getShort(HDR_STATIC_INFO_MASTERING_LIGHT_OFFSET) & 0xFFFF;
                }
                maxAverageLight = info.getShort(HDR_STATIC_INFO_MAX_AVERAGE_LIGHT_OFFSET) & 0xFFFF;
            }
            videoMaxLight = Math.min(maxLight, PQ_MAX_LIGHT_NITS);
            videoMaxAverageLight = Math.min(maxAverageLight, PQ_MAX_LIGHT_NITS);
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoFormat failure", ex);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static void setVideoTransfer(int dataSpace) {
        int dataSpaceTransfer = DataSpace.getTransfer(dataSpace);
        int transfer = dataSpaceTransfer == DataSpace.TRANSFER_ST2084
                ? VIDEO_TRANSFER_PQ
                : dataSpaceTransfer == DataSpace.TRANSFER_HLG
                ? VIDEO_TRANSFER_HLG
                : VIDEO_TRANSFER_SDR;
        if (transfer != videoTransfer) {
            videoTransfer = transfer;
            Logger.printDebug(() -> "Video data space changed to: 0x" + Integer.toHexString(dataSpace));
        }
    }

    /**
     * Injection point.
     * Called on the render thread before each frame is drawn, after {@link #setVideoDataSpace}.
     */
    public static void updateOutputColorSpace(VideoRenderer renderer) {
        try {
            if (!isEffectShaderUsed()) return;

            EGLDisplay display = EGL14.eglGetCurrentDisplay();
            EGLSurface surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
            if (display.equals(EGL14.EGL_NO_DISPLAY) || surface.equals(EGL14.EGL_NO_SURFACE)) return;

            if (!isHdrOutputSupported(display)) {
                hdrOutput = false;
                return;
            }

            boolean useHdr = videoTransfer != VIDEO_TRANSFER_SDR;
            // The app makes a new surface, without a color space, if the size or the place of the player changes.
            hdrOutput = isHdrSurface(display, surface);
            if (useHdr != hdrOutput) {
                hdrOutput = replaceSurface(renderer.patch_getEglHelper(), display, surface, useHdr);
            }
            if (hdrOutput) {
                setSurfaceBrightness(display);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateOutputColorSpace failure", ex);
        }
    }

    private static boolean isHdrSurface(EGLDisplay display, EGLSurface surface) {
        int[] colorSpace = new int[1];
        return EGL14.eglQuerySurface(display, surface, EGL_GL_COLORSPACE, colorSpace, 0)
                && colorSpace[0] == EGL_GL_COLORSPACE_BT2020_PQ;
    }

    /**
     * Sets the brightness of the video, which displays use to choose how bright HDR colors are shown.
     */
    private static void setSurfaceBrightness(EGLDisplay display) {
        int maxLight = videoMaxLight;
        int maxAverageLight = videoMaxAverageLight;
        if (maxLight == 0 && videoTransfer == VIDEO_TRANSFER_HLG) {
            maxLight = HLG_MAX_LIGHT_NITS;
        }

        EGLSurface surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        // The levels are only used if both are set.
        EGL14.eglSurfaceAttrib(display, surface, EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL,
                maxLight == 0 ? EGL_DONT_CARE : maxLight * EGL_METADATA_SCALING);
        EGL14.eglSurfaceAttrib(display, surface, EGL_CTA861_3_MAX_FRAME_AVERAGE_LEVEL,
                maxLight == 0 ? EGL_DONT_CARE : maxAverageLight * EGL_METADATA_SCALING);
    }

    /**
     * The color space of a surface can only be set when it is made.
     *
     * @return If the new surface is an HDR surface.
     */
    private static boolean replaceSurface(EglHelper helper, EGLDisplay display,
                                          EGLSurface oldSurface, boolean useHdr) {
        EGLContext context = EGL14.eglGetCurrentContext();
        EGLConfig config = helper.patch_getEglConfig();
        Surface window = helper.patch_getSurface();

        // A window can have only one EGL surface.
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, oldSurface);

        EGLSurface surface = createSurface(display, config, window, useHdr);
        if (useHdr && (surface.equals(EGL14.EGL_NO_SURFACE) || !isHdrSurface(display, surface))) {
            int error = EGL14.eglGetError();
            Logger.printInfo(() -> "Could not make an HDR surface: " + error);
            hdrOutputSupported = false;
            useHdr = false;
            if (!surface.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, surface);
            surface = createSurface(display, config, window, false);
        }

        helper.patch_setEglSurface(surface);
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent failed: " + EGL14.eglGetError());
        }
        boolean log = useHdr;
        Logger.printDebug(() -> "Video surface replaced, HDR: " + log);
        return useHdr;
    }

    private static EGLSurface createSurface(EGLDisplay display, EGLConfig config, Surface window, boolean useHdr) {
        int[] attributes = useHdr
                ? new int[]{EGL_GL_COLORSPACE, EGL_GL_COLORSPACE_BT2020_PQ, EGL14.EGL_NONE}
                : new int[]{EGL14.EGL_NONE};
        return EGL14.eglCreateWindowSurface(display, config, window, attributes, 0);
    }

    /**
     * Makes a program like the one of the app, with another fragment shader.
     * The vertex attributes get the locations that the app found in its program.
     *
     * @return The program, or 0 if it could not be made.
     */
    private static int createProgram(int appProgram, String fragmentShader) {
        int vertex = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        int fragment = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        int program = GLES20.glCreateProgram();
        try {
            String error = compileShader(vertex, VERTEX_SHADER);
            if (error == null) error = compileShader(fragment, fragmentShader);
            if (error == null) {
                GLES20.glAttachShader(program, vertex);
                GLES20.glAttachShader(program, fragment);

                int[] attributeCount = new int[1];
                GLES20.glGetProgramiv(appProgram, GLES20.GL_ACTIVE_ATTRIBUTES, attributeCount, 0);
                int[] size = new int[1];
                int[] type = new int[1];
                for (int i = 0; i < attributeCount[0]; i++) {
                    String name = GLES20.glGetActiveAttrib(appProgram, i, size, 0, type, 0);
                    int location = GLES20.glGetAttribLocation(appProgram, name);
                    if (location >= 0) GLES20.glBindAttribLocation(program, location, name);
                }

                GLES20.glLinkProgram(program);
                int[] status = new int[1];
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
                if (status[0] == GLES20.GL_TRUE) {
                    return program;
                }
                error = GLES20.glGetProgramInfoLog(program);
            }

            String message = error;
            Logger.printException(() -> "Could not make the video shader program: " + message);
            GLES20.glDeleteProgram(program);
            return 0;
        } finally {
            // Deleted with the program.
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
        }
    }

    private static void deleteCustomProgram() {
        if (customProgram == 0) return;

        // A program of another context can have the same number as an unrelated program of this one.
        if (EGL14.eglGetCurrentContext().equals(customProgramContext)) {
            GLES20.glDeleteProgram(customProgram);
        }
        customProgram = 0;
        customProgramContext = null;
        // A new program can get the same number.
        lastProgram = 0;
    }
}
