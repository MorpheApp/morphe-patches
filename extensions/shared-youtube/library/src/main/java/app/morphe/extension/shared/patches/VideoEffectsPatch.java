/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Draws videos with an effect shader.
 * <p>
 * YouTube and YouTube Music can draw videos with their own OpenGL renderer instead of showing
 * the decoder output directly. Its passthrough shader is replaced with one that holds all effects,
 * and the effect is picked with a uniform for every frame, so it can be changed while a video plays.
 */
@SuppressWarnings("unused")
public final class VideoEffectsPatch {

    /**
     * Each effect holds the shader expression that draws it, from the picture coordinates {@code uv}.
     * The order is the effect number in the fragment shader.
     */
    public enum VideoEffect {
        NONE("pick(uv)"),
        ENHANCE_LOW("enhance(uv, 0.33)"),
        ENHANCE_MEDIUM("enhance(uv, 0.67)"),
        ENHANCE_HIGH("enhance(uv, 1.0)"),
        VIVID_COLORS("vivid(pick(uv))"),
        MORE_CONTRAST("contrast(pick(uv), 0.55)"),
        SHARPEN("sharpen(uv, 0.7)"),
        BRIGHTEN_SHADOWS("brightenShadows(pick(uv))"),
        WARM_COLORS("pick(uv) * vec3(1.0, 0.86, 0.66)"),
        COLOR_BLIND_DEUTERANOPIA("daltonize(pick(uv), 1)"),
        COLOR_BLIND_PROTANOPIA("daltonize(pick(uv), 2)"),
        COLOR_BLIND_TRITANOPIA("daltonize(pick(uv), 3)"),
        BLACK_AND_WHITE("vec3(luma(pick(uv)))"),
        CUSTOM("customEffect(uv)");

        private final String shaderExpression;

        VideoEffect(String shaderExpression) {
            this.shaderExpression = shaderExpression;
        }
    }

    /**
     * Animations use the time in seconds. It wraps so that it keeps its precision in the shader.
     */
    private static final long TIME_WRAP_MILLISECONDS = 600_000;

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
            varying vec2 texCoordVar;
            varying vec2 vUv;
            varying vec2 vTexOrigin;
            varying vec2 vTexAxisX;
            varying vec2 vTexAxisY;

            const float PI = 3.14159265;

            // Color at a point of the picture, where (0, 0) is the bottom left and (1, 1) the top right.
            vec3 pick(vec2 uv) {
                uv = clamp(uv, 0.0, 1.0);
                return texture2D(texture, vTexOrigin + uv.x * vTexAxisX + uv.y * vTexAxisY).rgb;
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

            // Raises the saturation of dull colors more than of colors that are already saturated.
            vec3 vivid(vec3 c) {
                float saturation = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                return adjustSaturation(c, 1.15 + 0.5 * (1.0 - smoothstep(0.0, 0.5, saturation)));
            }

            // Unsharp mask with the 4 neighbor pixels.
            vec3 sharpen(vec2 uv, float amount) {
                vec2 px = 1.0 / uResolution;
                vec3 c = pick(uv);
                vec3 blur = (pick(uv + vec2(px.x, 0.0)) + pick(uv - vec2(px.x, 0.0))
                        + pick(uv + vec2(0.0, px.y)) + pick(uv - vec2(0.0, px.y))) * 0.25;
                return clamp(c + (c - blur) * amount, 0.0, 1.0);
            }

            // Brightens the dark parts with a gamma curve and keeps the bright parts the same.
            // No channel is made brighter than full, so the colors don't change.
            vec3 brightenShadows(vec3 c) {
                float l = max(luma(c), 0.001);
                float brightened = mix(pow(l, 0.6), l, smoothstep(0.2, 0.7, l));
                float brightest = max(c.r, max(c.g, c.b));
                return c * min(min(brightened / l, 1.0 / max(brightest, 0.001)), 4.0);
            }

            // Sharpens and adds color and contrast. A strength of 1 is the full effect.
            vec3 enhance(vec2 uv, float strength) {
                vec3 c = sharpen(uv, 0.4 * strength);
                c = mix(c, vivid(c), strength);
                return contrast(c, 0.25 * strength);
            }

            // Moves the colors that a color blind viewer can't tell apart into colors that they can see.
            // The color blindness is simulated with the matrices of Machado, Oliveira and Fernandes (2009),
            // and the difference to the original is added to the color channels that the viewer can see.
            vec3 daltonize(vec3 c, int type) {
                vec3 simulated;
                if (type == 1) {
                    simulated = vec3(
                        dot(c, vec3(0.367322, 0.860646, -0.227968)),
                        dot(c, vec3(0.280085, 0.672501, 0.047413)),
                        dot(c, vec3(-0.011820, 0.042940, 0.968881)));
                } else if (type == 2) {
                    simulated = vec3(
                        dot(c, vec3(0.152286, 1.052583, -0.204868)),
                        dot(c, vec3(0.114503, 0.786281, 0.099216)),
                        dot(c, vec3(-0.003882, -0.048116, 1.051998)));
                } else {
                    simulated = vec3(
                        dot(c, vec3(1.255528, -0.076749, -0.178779)),
                        dot(c, vec3(-0.078411, 0.930809, 0.147602)),
                        dot(c, vec3(0.004733, 0.691367, 0.303900)));
                }
                vec3 error = c - simulated;
                vec3 correction = type == 3
                    ? vec3(error.r + 0.7 * error.b, error.g + 0.7 * error.b, 0.0)
                    : vec3(0.0, 0.7 * error.r + error.g, 0.7 * error.r + error.b);
                return clamp(c + correction, 0.0, 1.0);
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
     * Comment at the start of the code of a new custom effect. It tells an AI chatbot how to write
     * an effect. It is not saved, so it does not fill the settings.
     */
    private static final String CUSTOM_EFFECT_INSTRUCTIONS = """
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
            // - Answer with only the code.
            """;

    /**
     * Custom effect code that shows the video as it is. Used when no custom effect is selected,
     * or the selected one has an error.
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
     * Start of a line with the name of a custom effect in the saved setting, and of the
     * entry values of custom effects in the effect lists. It is never valid in shader code.
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
    private static int textureTransformLocation = -1;
    private static final int[] viewport = new int[4];

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
            if (value.length() > 0) value.append('\n');
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

    @Nullable
    public static CustomEffect findCustomEffect(String name) {
        for (CustomEffect effect : getCustomEffects()) {
            if (effect.name.equals(name)) return effect;
        }
        return null;
    }

    /**
     * @return The code of the selected custom effect,
     *         or code that shows the video as it is if no custom effect is selected.
     */
    private static String getSelectedCustomEffectCode() {
        if (SharedYouTubeSettings.VIDEO_EFFECT.get() == VideoEffect.CUSTOM) {
            CustomEffect effect = findCustomEffect(SharedYouTubeSettings.VIDEO_EFFECT_CUSTOM_NAME.get());
            if (effect != null) return effect.code;
        }
        return CUSTOM_EFFECT_DEFAULT_CODE;
    }

    /**
     * @return The entry value of the selected effect in the effect lists. The value of an
     *         effect is its enum name, and of a custom effect its name after {@link #CUSTOM_EFFECT_NAME_PREFIX}.
     */
    public static String getSelectedEffectEntryValue() {
        VideoEffect effect = SharedYouTubeSettings.VIDEO_EFFECT.get();
        return effect == VideoEffect.CUSTOM
                ? CUSTOM_EFFECT_NAME_PREFIX + SharedYouTubeSettings.VIDEO_EFFECT_CUSTOM_NAME.get()
                : effect.name();
    }

    /**
     * @param entryValue A value of {@link #getSelectedEffectEntryValue()}.
     * @return The effect of the entry value. The name of a custom effect is saved.
     */
    public static VideoEffect saveCustomEffectNameOfEntryValue(String entryValue) {
        if (entryValue.startsWith(CUSTOM_EFFECT_NAME_PREFIX)) {
            SharedYouTubeSettings.VIDEO_EFFECT_CUSTOM_NAME.save(
                    entryValue.substring(CUSTOM_EFFECT_NAME_PREFIX.length()));
            return VideoEffect.CUSTOM;
        }
        return VideoEffect.valueOf(entryValue);
    }

    /**
     * Adds the custom effect code and the main method that draws the effect of the uEffect uniform.
     */
    private static String createFragmentShader(String customEffectCode) {
        StringBuilder shader = new StringBuilder(FRAGMENT_SHADER_FUNCTIONS);
        shader.append(customEffectCode).append('\n');
        shader.append("void main() {\n");
        shader.append("    vec2 uv = vUv;\n");
        shader.append("    vec3 c;\n");
        VideoEffect[] effects = VideoEffect.values();
        for (int i = 1; i < effects.length; i++) {
            shader.append(i == 1 ? "    if" : "    } else if")
                    .append(" (uEffect == ").append(i).append(") {\n")
                    .append("        c = ").append(effects[i].shaderExpression).append(";\n");
        }
        shader.append("    } else {\n");
        shader.append("        c = ").append(VideoEffect.NONE.shaderExpression).append(";\n");
        shader.append("    }\n");
        shader.append("    gl_FragColor = vec4(c, 1.0);\n");
        shader.append("}\n");
        return shader.toString();
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
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
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
        return SharedYouTubeSettings.VIDEO_EFFECT.get() != VideoEffect.NONE;
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
        return original || isEnabled();
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

        if (appProgramHasEffects && SharedYouTubeSettings.VIDEO_EFFECT.get() == VideoEffect.CUSTOM) {
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
            textureTransformLocation = GLES20.glGetUniformLocation(program, "texTransMat");
        }

        // Program with the original shader.
        if (effectLocation < 0) return;

        lastEffectVideoId = currentVideoId;

        GLES20.glUniform1i(effectLocation, SharedYouTubeSettings.VIDEO_EFFECT.get().ordinal());
        GLES20.glUniform1f(timeLocation, (SystemClock.elapsedRealtime() % TIME_WRAP_MILLISECONDS) / 1000f);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        GLES20.glUniform2f(resolutionLocation, viewport[2], viewport[3]);
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
