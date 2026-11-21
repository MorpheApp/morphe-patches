/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.opengl.GLES20;
import android.os.SystemClock;

import androidx.annotation.Nullable;

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
        ALIEN("adjustSaturation(pick(uv).brg, 1.3)"),
        ASCII_ART("asciiArt(uv)"),
        COMIC_BOOK("comicBook(uv)"),
        CRT_TV("crtTv(uv)"),
        FISHEYE("fisheye(uv)"),
        GLITCH("glitch(uv)"),
        INVERT("1.0 - pick(uv)"),
        KALEIDOSCOPE("kaleidoscope(uv)"),
        MIRROR_IMAGE("pick(vec2(1.0 - uv.x, uv.y))"),
        NEON_EDGES("neonEdges(uv)"),
        NIGHT_VISION("nightVision(uv)"),
        OLD_FILM("oldFilm(uv)"),
        PENCIL_SKETCH("pencilSketch(uv)"),
        POP_ART("popArt(uv)"),
        PUZZLE("puzzle(uv)"),
        RAINBOW("clamp(hueRotate(adjustSaturation(pick(uv), 1.4), uTime * 1.2), 0.0, 1.0)"),
        RETRO_HANDHELD("retroHandheld(uv)"),
        RETRO_PC("retroPc(uv)"),
        RIPPLE("ripple(uv)"),
        SHATTERED_GLASS("shatteredGlass(uv)"),
        SLICES("slices(uv)"),
        SWIRL("swirl(uv)"),
        THERMAL("thermal(luma(pick(uv)))"),
        TILT_SHIFT("tiltShift(uv)"),
        TINY_PLANET("tinyPlanet(uv)"),
        UNDERWATER("underwater(uv)"),
        VHS("vhs(uv)"),
        VIDEO_WALL("videoWall(uv)");

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

            // Ordered dithering thresholds from 0 to 15/16 in a 4x4 pattern.
            float bayer2(vec2 a) {
                a = mod(floor(a), 2.0);
                return fract(dot(a, vec2(0.5, a.y * 0.75)));
            }

            float bayer4(vec2 a) {
                return bayer2(0.5 * a) * 0.25 + bayer2(a);
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

            vec3 thermal(float t) {
                t = clamp(t, 0.0, 1.0) * 5.0;
                if (t < 1.0) return mix(vec3(0.0, 0.0, 0.1), vec3(0.25, 0.0, 0.6), t);
                if (t < 2.0) return mix(vec3(0.25, 0.0, 0.6), vec3(0.9, 0.0, 0.45), t - 1.0);
                if (t < 3.0) return mix(vec3(0.9, 0.0, 0.45), vec3(1.0, 0.45, 0.0), t - 2.0);
                if (t < 4.0) return mix(vec3(1.0, 0.45, 0.0), vec3(1.0, 0.95, 0.2), t - 3.0);
                return mix(vec3(1.0, 0.95, 0.2), vec3(1.0), t - 4.0);
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

            vec3 oldFilm(vec2 uv) {
                vec3 c = pick(uv);
                vec3 sepia = vec3(
                    dot(c, vec3(0.393, 0.769, 0.189)),
                    dot(c, vec3(0.349, 0.686, 0.168)),
                    dot(c, vec3(0.272, 0.534, 0.131)));
                float frame = floor(uTime * 18.0);
                float grain = (rand(uv * 1000.0 + frame) - 0.5) * 0.12;
                float flicker = 0.94 + 0.06 * rand(vec2(frame, 7.0));
                return clamp((sepia + grain) * flicker * vignette(uv, 1.8), 0.0, 1.0);
            }

            vec3 nightVision(vec2 uv) {
                float l = pow(luma(pick(uv)), 0.65) * 1.3;
                float noise = (rand(uv * 900.0 + floor(uTime * 30.0)) - 0.5) * 0.18;
                float scanline = 0.88 + 0.12 * sin(gl_FragCoord.y * 2.0);
                vec3 c = vec3(0.12, 1.0, 0.22) * (l + noise) * scanline;
                return clamp(c * vignette(uv, 2.6), 0.0, 1.0);
            }

            vec3 vhs(vec2 uv) {
                float frame = floor(uTime * 30.0);
                float row = floor(uv.y * 240.0);
                float shift = (rand(vec2(row, frame)) - 0.5) * 0.002;
                float r = pick(uv + vec2(shift + 0.004, 0.0)).r;
                float g = pick(uv + vec2(shift, 0.0)).g;
                float b = pick(uv + vec2(shift - 0.004, 0.0)).b;
                vec3 c = adjustSaturation(vec3(r, g, b), 0.8);
                c += (rand(uv * 700.0 + frame) - 0.5) * 0.08;
                c *= 0.92 + 0.08 * step(0.5, fract(gl_FragCoord.y * 0.25));
                return clamp(c, 0.0, 1.0);
            }

            vec3 popArt(vec2 uv) {
                vec3 c = adjustSaturation(pick(uv), 1.8);
                return floor(c * 3.0 + 0.5) / 3.0;
            }

            vec3 retroHandheld(vec2 uv) {
                float pixel = max(1.0, floor(uResolution.x / 160.0));
                vec2 cell = floor(uv * uResolution / pixel);
                vec2 cellUv = (cell + 0.5) * pixel / uResolution;
                float l = luma(pick(cellUv));
                vec2 dither = mod(cell, 2.0);
                float threshold = dither.x * 0.5 + dither.y * 0.25 - 0.375;
                float level = clamp(floor(l * 3.999 + threshold), 0.0, 3.0);
                if (level < 0.5) return vec3(0.06, 0.22, 0.06);
                if (level < 1.5) return vec3(0.19, 0.38, 0.19);
                if (level < 2.5) return vec3(0.55, 0.67, 0.06);
                return vec3(0.61, 0.74, 0.06);
            }

            // Keeps the palette color that is closest to c. The colors are from 0 to 255.
            void closerColor(vec3 c, vec3 color, inout float best, inout vec3 result) {
                vec3 diff = c - color / 255.0;
                float d = dot(diff * diff, vec3(2.0, 4.0, 3.0));
                if (d < best) {
                    best = d;
                    result = color / 255.0;
                }
            }

            // 320 pixels wide with the 32 colors of the DawnBringer 32 pixel art palette, and ordered dithering.
            vec3 retroPc(vec2 uv) {
                float pixel = max(1.0, floor(uResolution.x / 320.0));
                vec2 cell = floor(uv * uResolution / pixel);
                vec3 c = adjustSaturation(pick((cell + 0.5) * pixel / uResolution), 1.2);
                c += (bayer4(cell) - 0.47) * 0.2;
                float best = 100.0;
                vec3 result = vec3(0.0);
                closerColor(c, vec3(0.0, 0.0, 0.0), best, result);
                closerColor(c, vec3(34.0, 32.0, 52.0), best, result);
                closerColor(c, vec3(69.0, 40.0, 60.0), best, result);
                closerColor(c, vec3(102.0, 57.0, 49.0), best, result);
                closerColor(c, vec3(143.0, 86.0, 59.0), best, result);
                closerColor(c, vec3(223.0, 113.0, 38.0), best, result);
                closerColor(c, vec3(217.0, 160.0, 102.0), best, result);
                closerColor(c, vec3(238.0, 195.0, 154.0), best, result);
                closerColor(c, vec3(251.0, 242.0, 54.0), best, result);
                closerColor(c, vec3(153.0, 229.0, 80.0), best, result);
                closerColor(c, vec3(106.0, 190.0, 48.0), best, result);
                closerColor(c, vec3(55.0, 148.0, 110.0), best, result);
                closerColor(c, vec3(75.0, 105.0, 47.0), best, result);
                closerColor(c, vec3(82.0, 75.0, 36.0), best, result);
                closerColor(c, vec3(50.0, 60.0, 57.0), best, result);
                closerColor(c, vec3(63.0, 63.0, 116.0), best, result);
                closerColor(c, vec3(48.0, 96.0, 130.0), best, result);
                closerColor(c, vec3(91.0, 110.0, 225.0), best, result);
                closerColor(c, vec3(99.0, 155.0, 255.0), best, result);
                closerColor(c, vec3(95.0, 205.0, 228.0), best, result);
                closerColor(c, vec3(203.0, 219.0, 252.0), best, result);
                closerColor(c, vec3(255.0, 255.0, 255.0), best, result);
                closerColor(c, vec3(155.0, 173.0, 183.0), best, result);
                closerColor(c, vec3(132.0, 126.0, 135.0), best, result);
                closerColor(c, vec3(105.0, 106.0, 106.0), best, result);
                closerColor(c, vec3(89.0, 86.0, 82.0), best, result);
                closerColor(c, vec3(118.0, 66.0, 138.0), best, result);
                closerColor(c, vec3(172.0, 50.0, 50.0), best, result);
                closerColor(c, vec3(217.0, 87.0, 99.0), best, result);
                closerColor(c, vec3(215.0, 123.0, 186.0), best, result);
                closerColor(c, vec3(143.0, 151.0, 74.0), best, result);
                closerColor(c, vec3(138.0, 111.0, 48.0), best, result);
                return result;
            }

            // 5x5 bitmaps of the characters, from dark to bright. Bits 0 to 14 hold the top 3 rows,
            // and bits 0 to 9 of the second number the bottom 2 rows, starting at the top left.
            vec2 glyph(float level) {
                if (level < 0.5) return vec2(0.0, 0.0);         // space
                if (level < 1.5) return vec2(0.0, 128.0);       // .
                if (level < 2.5) return vec2(128.0, 4.0);       // :
                if (level < 3.5) return vec2(14336.0, 0.0);     // -
                if (level < 4.5) return vec2(14464.0, 4.0);     // +
                if (level < 5.5) return vec2(448.0, 14.0);      // =
                if (level < 6.5) return vec2(4565.0, 686.0);    // *
                if (level < 7.5) return vec2(4467.0, 826.0);    // %
                if (level < 8.5) return vec2(11242.0, 351.0);   // #
                return vec2(30254.0, 973.0);                    // @
            }

            vec3 asciiArt(vec2 uv) {
                // Each character cell is 6x7 dots with the 5x5 character in it.
                float dotSize = max(1.0, floor(uResolution.x / 480.0));
                vec2 cellSize = vec2(6.0, 7.0) * dotSize;
                vec2 p = vec2(uv.x, 1.0 - uv.y) * uResolution;
                vec2 cell = floor(p / cellSize);
                vec2 local = floor(mod(p, cellSize) / dotSize);
                vec2 center = (cell + 0.5) * cellSize / uResolution;
                float l = clamp((luma(pick(vec2(center.x, 1.0 - center.y))) - 0.04) * 1.2, 0.0, 1.0);
                float row = local.y - 1.0;
                if (local.x > 4.0 || row < 0.0 || row > 4.0) return vec3(0.0);
                vec2 bitmap = glyph(floor(l * 9.999));
                float bits = row < 3.0 ? bitmap.x : bitmap.y;
                float index = (row < 3.0 ? row : row - 3.0) * 5.0 + local.x;
                float on = mod(floor(bits / exp2(index)), 2.0);
                return vec3(0.2, 1.0, 0.35) * on * (0.55 + 0.45 * l);
            }

            vec3 neonEdges(vec2 uv) {
                float edge = smoothstep(0.08, 0.5, edges(uv, 1.5));
                return hue(uv.x * 0.5 + uv.y * 0.5 + uTime * 0.15) * edge * 1.4;
            }

            // Ink outlines, flat colors and halftone dots in the shadows.
            vec3 comicBook(vec2 uv) {
                vec3 c = adjustSaturation(pick(uv), 1.5);
                float l = luma(c);
                // 5 brightness levels with soft steps, each as bright as the top of its range.
                float steps = l * 5.0;
                float level = (floor(steps) + smoothstep(0.8, 1.0, fract(steps)) + 1.0) / 5.0;
                vec3 flatColor = clamp(c * level / max(l, 0.04), 0.0, 1.0);
                // Halftone dots only in the dark parts.
                float dotSize = max(3.0, floor(uResolution.x / 140.0));
                vec2 p = uv * uResolution / dotSize;
                vec2 grid = fract(vec2(p.x + p.y, p.y - p.x) * 0.7071) - 0.5;
                float radius = 0.45 * smoothstep(0.6, 1.0, 1.0 - l);
                float halftone = 1.0 - smoothstep(radius - 0.08, radius + 0.08, length(grid));
                flatColor *= 1.0 - 0.35 * halftone;
                float ink = smoothstep(0.3, 0.6, edges(uv, 2.0));
                return mix(flatColor, vec3(0.03), ink);
            }

            // Pencil lines on paper, with hatching in the dark parts that is redrawn a few times per second.
            vec3 pencilSketch(vec2 uv) {
                float l = luma(pick(uv));
                float line = smoothstep(0.1, 0.45, edges(uv, 1.0));
                vec2 p = uv * uResolution;
                float spacing = max(4.0, floor(uResolution.x / 150.0));
                float frame = floor(uTime * 8.0);
                float wobble = 1.5 * sin(p.y * 0.05 + frame);
                float offset = rand(vec2(frame, 3.0)) * spacing;
                float d1 = abs(fract((p.x + p.y + wobble + offset) / spacing) - 0.5) * 2.0;
                float d2 = abs(fract((p.x - p.y - wobble + offset) / spacing) - 0.5) * 2.0;
                float hatch1 = (1.0 - smoothstep(0.15, 0.4, d1)) * smoothstep(0.6, 0.3, l);
                float hatch2 = (1.0 - smoothstep(0.15, 0.4, d2)) * smoothstep(0.35, 0.1, l);
                float grain = (rand(floor(p)) - 0.5) * 0.06;
                float value = 1.0 - 0.15 * (1.0 - l) - 0.85 * line - 0.4 * hatch1 - 0.4 * hatch2 + grain;
                return mix(vec3(0.22, 0.22, 0.25), vec3(0.97, 0.95, 0.89), clamp(value, 0.0, 1.0));
            }

            vec3 underwater(vec2 uv) {
                vec2 wave = vec2(sin(uv.y * 22.0 + uTime * 2.0), cos(uv.x * 18.0 + uTime * 1.6)) * 0.006;
                return clamp(pick(uv + wave) * vec3(0.7, 0.92, 1.1), 0.0, 1.0);
            }

            vec3 kaleidoscope(vec2 uv) {
                float aspect = uResolution.x / max(uResolution.y, 1.0);
                vec2 p = uv - 0.5;
                p.x *= aspect;
                float radius = length(p);
                float segment = PI / 3.0;
                float angle = mod(atan(p.y, p.x) + uTime * 0.2, segment);
                angle = abs(angle - segment * 0.5);
                p = radius * vec2(cos(angle), sin(angle));
                p.x /= aspect;
                vec2 mirrored = 1.0 - abs(1.0 - mod(p + 0.5, 2.0));
                return pick(mirrored);
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

            // Magnifies the middle and squeezes the sides, like a wide angle lens.
            vec3 fisheye(vec2 uv) {
                vec2 p = centered(uv);
                float radius = 0.45 * length(vec2(uResolution.x / max(uResolution.y, 1.0), 1.0));
                float r = length(p) / radius;
                if (r < 1.0) p *= mix(0.45, 1.0, r * r);
                return pick(uncentered(p));
            }

            // The bottom of the picture in the middle and the top all around, like a little planet.
            vec3 tinyPlanet(vec2 uv) {
                vec2 p = centered(uv);
                float angle = atan(p.x, -p.y) / (2.0 * PI) + 0.5 + uTime * 0.02;
                // Two mirrored copies around the circle, so there is no seam.
                return pick(vec2(abs(fract(angle) * 2.0 - 1.0), length(p) * 2.0));
            }

            // A whirlpool in the middle that slowly turns one way and then the other.
            vec3 swirl(vec2 uv) {
                vec2 p = centered(uv);
                float t = max(0.0, 1.0 - length(p) / 0.6);
                return pick(uncentered(rotate(p, t * t * 3.0 * sin(uTime * 0.5))));
            }

            // Round waves that move out from the middle.
            vec3 ripple(vec2 uv) {
                vec2 p = centered(uv);
                float r = max(length(p), 0.0001);
                p += p / r * sin(r * 50.0 - uTime * 4.0) * 0.006 * smoothstep(0.0, 0.1, r);
                return pick(uncentered(p));
            }

            // The picture broken into shards that are each moved and turned a little.
            vec3 shatteredGlass(vec2 uv) {
                vec2 p = centered(uv) * 5.0;
                vec2 base = floor(p);
                float best = 10.0;
                float secondBest = 10.0;
                vec2 bestId = vec2(0.0);
                vec2 bestSeed = vec2(0.0);
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec2 id = base + vec2(float(x), float(y));
                        vec2 seed = id + vec2(rand(id), rand(id + 17.31));
                        float d = length(p - seed);
                        if (d < best) {
                            secondBest = best;
                            best = d;
                            bestId = id;
                            bestSeed = seed;
                        } else if (d < secondBest) {
                            secondBest = d;
                        }
                    }
                }
                vec2 q = rotate(p - bestSeed, (rand(bestId + 3.7) - 0.5) * 0.12);
                vec2 shift = (vec2(rand(bestId + 5.1), rand(bestId + 9.3)) - 0.5) * 0.15;
                vec3 c = pick(uncentered((bestSeed + q + shift) / 5.0));
                // Thin light lines where the glass is cracked.
                float crack = 1.0 - smoothstep(0.0, 0.04, secondBest - best);
                return mix(c, vec3(0.9), crack * 0.5);
            }

            // Horizontal strips that slide left and right at different times.
            vec3 slices(vec2 uv) {
                float strip = floor(uv.y * 12.0);
                float shift = 0.08 * sin(uTime * 1.5 + rand(vec2(strip, 1.0)) * 2.0 * PI);
                return pick(vec2(fract(uv.x + shift), uv.y));
            }

            // Blocks of the picture that jump to the wrong place now and then.
            vec3 glitch(vec2 uv) {
                float tick = floor(uTime * 12.0);
                float band = floor(uv.y * 24.0);
                float block = floor(uv.x * 6.0);
                vec2 offset = vec2(0.0);
                bool burst = rand(vec2(floor(uTime * 3.0), 7.0)) > 0.75;
                if (burst && rand(vec2(band * 7.0 + block, tick)) > 0.85) {
                    offset = (vec2(rand(vec2(band, tick + 1.0)), rand(vec2(block, tick + 2.0))) - 0.5) * vec2(0.3, 0.1);
                }
                if (rand(vec2(band * 3.1, tick)) > 0.97) {
                    offset.x += (rand(vec2(band, tick + 5.0)) - 0.5) * 0.05;
                }
                return pick(fract(uv + offset));
            }

            // The video on a wall of 3x3 screens.
            vec3 videoWall(vec2 uv) {
                vec2 tile = fract(uv * 3.0);
                vec2 border = 12.0 / uResolution;
                vec2 inside = step(border, tile) * step(border, 1.0 - tile);
                return pick(tile) * inside.x * inside.y;
            }

            // The picture cut into 4x4 pieces that fly to mixed up places and back again.
            vec3 puzzle(vec2 uv) {
                float cycle = floor(uTime / 10.0);
                float phase = fract(uTime / 10.0);
                // Piece j goes to place j * k + offset (mod 16), which is a different place for every
                // piece when k is odd.
                float k = 3.0 + 2.0 * floor(rand(vec2(cycle, 1.0)) * 6.0);
                float offset = floor(rand(vec2(cycle, 2.0)) * 16.0);
                vec3 result = vec3(0.05);
                bool found = false;
                for (int i = 0; i < 16; i++) {
                    float j = float(i);
                    float delay = rand(vec2(j, cycle)) * 0.08;
                    float scatter = smoothstep(0.2 + delay, 0.3 + delay, phase)
                            - smoothstep(0.65 + delay, 0.75 + delay, phase);
                    float away = mod(j * k + offset, 16.0);
                    vec2 home = vec2(mod(j, 4.0), floor(j / 4.0));
                    vec2 pos = mix(home, vec2(mod(away, 4.0), floor(away / 4.0)), scatter) / 4.0;
                    vec2 local = (uv - pos) * 4.0;
                    if (local.x >= 0.0 && local.x < 1.0 && local.y >= 0.0 && local.y < 1.0) {
                        // A moving piece is drawn on top of the pieces that don't move.
                        bool moving = scatter > 0.0 && scatter < 1.0;
                        if (!found || moving) {
                            float edge = min(min(local.x, 1.0 - local.x), min(local.y, 1.0 - local.y));
                            float outline = mix(1.0, smoothstep(0.0, 0.02, edge), min(scatter * 4.0, 1.0));
                            result = pick((home + local) / 4.0) * outline;
                            found = true;
                        }
                    }
                }
                return result;
            }

            // A curved tube TV screen with scanlines and red, green and blue phosphor stripes.
            vec3 crtTv(vec2 uv) {
                vec2 cc = uv - 0.5;
                vec2 curved = uv + cc * dot(cc, cc) * 0.35;
                if (curved.x < 0.0 || curved.x > 1.0 || curved.y < 0.0 || curved.y > 1.0) return vec3(0.0);
                vec3 c = pick(curved);
                float lineSize = max(2.0, floor(uResolution.y / 240.0));
                float scanline = 0.7 + 0.3 * abs(sin(curved.y * uResolution.y * PI / lineSize));
                float column = mod(gl_FragCoord.x, 3.0);
                vec3 mask = column < 1.0 ? vec3(1.0, 0.7, 0.7) : (column < 2.0 ? vec3(0.7, 1.0, 0.7) : vec3(0.7, 0.7, 1.0));
                vec2 edge = smoothstep(0.0, 0.03, curved) * smoothstep(0.0, 0.03, 1.0 - curved);
                return clamp(c * scanline * mask * 1.35 * edge.x * edge.y * vignette(uv, 0.8), 0.0, 1.0);
            }

            // Blurs the top and bottom and adds color, so the video looks like a miniature model.
            vec3 tiltShift(vec2 uv) {
                float blur = smoothstep(0.12, 0.45, abs(uv.y - 0.5)) * 0.012;
                float aspect = uResolution.x / max(uResolution.y, 1.0);
                vec3 sum = vec3(0.0);
                for (int i = 0; i < 16; i++) {
                    float fi = float(i);
                    float r = sqrt((fi + 0.5) / 16.0) * blur;
                    float a = fi * 2.39996;
                    sum += pick(uv + vec2(cos(a) / aspect, sin(a)) * r);
                }
                return contrast(adjustSaturation(sum / 16.0, 1.4), 0.2);
            }

            """;

    private static final String FRAGMENT_SHADER = createFragmentShader();

    /**
     * The program that the uniform locations belong to. Only used on the render thread.
     */
    private static int lastProgram;
    private static int effectLocation = -1;
    private static int timeLocation = -1;
    private static int resolutionLocation = -1;
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

    /**
     * Adds the main method that draws the effect of the uEffect uniform.
     */
    private static String createFragmentShader() {
        StringBuilder shader = new StringBuilder(FRAGMENT_SHADER_FUNCTIONS);
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
        return isEnabled() ? FRAGMENT_SHADER : original;
    }

    /**
     * Injection point.
     * Called on the render thread for every frame, right after the video shader program is put in use.
     */
    public static void onUseProgram(int program) {
        try {
            if (program != lastProgram) {
                lastProgram = program;
                effectLocation = GLES20.glGetUniformLocation(program, "uEffect");
                timeLocation = GLES20.glGetUniformLocation(program, "uTime");
                resolutionLocation = GLES20.glGetUniformLocation(program, "uResolution");
            }

            // Program with the original shader.
            if (effectLocation < 0) return;

            lastEffectVideoId = currentVideoId;

            GLES20.glUniform1i(effectLocation, SharedYouTubeSettings.VIDEO_EFFECT.get().ordinal());
            GLES20.glUniform1f(timeLocation, (SystemClock.elapsedRealtime() % TIME_WRAP_MILLISECONDS) / 1000f);
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
            GLES20.glUniform2f(resolutionLocation, viewport[2], viewport[3]);
        } catch (Exception ex) {
            Logger.printException(() -> "onUseProgram failure", ex);
        }
    }
}
