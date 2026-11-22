/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

/**
 * The video effects that are there by default. Users can change, rename or delete them,
 * and they are examples of effect code.
 */
public final class VideoEffectPresets {

    private VideoEffectPresets() {
    }

    /**
     * The default effects, in the text format of
     * {@link VideoEffectsPatch#encodeCustomEffects(java.util.List)}. The effects that improve
     * the picture are first, then the fun effects sorted by name.
     */
    public static final String DEFAULT_CUSTOM_EFFECTS = """
            @Enhance (low)
            // Sharpens the picture and adds a little color and contrast.

            // Raises the saturation of dull colors more than of colors that are already saturated.
            vec3 myVivid(vec3 c) {
                float saturation = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                return adjustSaturation(c, 1.15 + 0.5 * (1.0 - smoothstep(0.0, 0.5, saturation)));
            }

            vec3 customEffect(vec2 uv) {
                // 1.0 is the strength of Enhance (high).
                float strength = 0.33;
                // Unsharp mask with the 4 neighbor pixels.
                vec2 px = 1.0 / uResolution;
                vec3 c = pick(uv);
                vec3 blur = (pick(uv + vec2(px.x, 0.0)) + pick(uv - vec2(px.x, 0.0))
                        + pick(uv + vec2(0.0, px.y)) + pick(uv - vec2(0.0, px.y))) * 0.25;
                c = clamp(c + (c - blur) * 0.4 * strength, 0.0, 1.0);
                c = mix(c, myVivid(c), strength);
                return contrast(c, 0.25 * strength);
            }
            @Enhance (medium)
            // Sharpens the picture and adds color and contrast.

            // Raises the saturation of dull colors more than of colors that are already saturated.
            vec3 myVivid(vec3 c) {
                float saturation = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                return adjustSaturation(c, 1.15 + 0.5 * (1.0 - smoothstep(0.0, 0.5, saturation)));
            }

            vec3 customEffect(vec2 uv) {
                // 1.0 is the strength of Enhance (high).
                float strength = 0.67;
                // Unsharp mask with the 4 neighbor pixels.
                vec2 px = 1.0 / uResolution;
                vec3 c = pick(uv);
                vec3 blur = (pick(uv + vec2(px.x, 0.0)) + pick(uv - vec2(px.x, 0.0))
                        + pick(uv + vec2(0.0, px.y)) + pick(uv - vec2(0.0, px.y))) * 0.25;
                c = clamp(c + (c - blur) * 0.4 * strength, 0.0, 1.0);
                c = mix(c, myVivid(c), strength);
                return contrast(c, 0.25 * strength);
            }
            @Enhance (high)
            // Sharpens the picture and adds more color and contrast.

            // Raises the saturation of dull colors more than of colors that are already saturated.
            vec3 myVivid(vec3 c) {
                float saturation = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                return adjustSaturation(c, 1.15 + 0.5 * (1.0 - smoothstep(0.0, 0.5, saturation)));
            }

            vec3 customEffect(vec2 uv) {
                float strength = 1.0;
                // Unsharp mask with the 4 neighbor pixels.
                vec2 px = 1.0 / uResolution;
                vec3 c = pick(uv);
                vec3 blur = (pick(uv + vec2(px.x, 0.0)) + pick(uv - vec2(px.x, 0.0))
                        + pick(uv + vec2(0.0, px.y)) + pick(uv - vec2(0.0, px.y))) * 0.25;
                c = clamp(c + (c - blur) * 0.4 * strength, 0.0, 1.0);
                c = mix(c, myVivid(c), strength);
                return contrast(c, 0.25 * strength);
            }
            @Vivid colors
            // Raises the saturation of dull colors more than of colors that are already saturated.
            vec3 customEffect(vec2 uv) {
                vec3 c = pick(uv);
                float saturation = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                return adjustSaturation(c, 1.15 + 0.5 * (1.0 - smoothstep(0.0, 0.5, saturation)));
            }
            @More contrast
            // S curve for each color channel, that darkens the dark parts and brightens the bright parts.
            vec3 customEffect(vec2 uv) {
                return contrast(pick(uv), 0.55);
            }
            @Sharpen
            // Unsharp mask with the 4 neighbor pixels.
            vec3 customEffect(vec2 uv) {
                float amount = 0.7;
                vec2 px = 1.0 / uResolution;
                vec3 c = pick(uv);
                vec3 blur = (pick(uv + vec2(px.x, 0.0)) + pick(uv - vec2(px.x, 0.0))
                        + pick(uv + vec2(0.0, px.y)) + pick(uv - vec2(0.0, px.y))) * 0.25;
                return clamp(c + (c - blur) * amount, 0.0, 1.0);
            }
            @Brighten dark scenes
            // Brightens the dark parts with a gamma curve and keeps the bright parts the same.
            // No channel is made brighter than full, so the colors don't change.
            vec3 customEffect(vec2 uv) {
                vec3 c = pick(uv);
                float l = max(luma(c), 0.001);
                float brightened = mix(pow(l, 0.6), l, smoothstep(0.2, 0.7, l));
                float brightest = max(c.r, max(c.g, c.b));
                return c * min(min(brightened / l, 1.0 / max(brightest, 0.001)), 4.0);
            }
            @Warm colors (less blue light)
            // Less blue and a little less green.
            vec3 customEffect(vec2 uv) {
                return pick(uv) * vec3(1.0, 0.86, 0.66);
            }
            @Color blind assist (deuteranopia)
            // Moves the colors that a viewer without green cones can't tell apart into colors that they can see.
            // The color blindness is simulated with the matrix of Machado, Oliveira and Fernandes (2009),
            // and the difference to the original is added to the color channels that the viewer can see.
            vec3 customEffect(vec2 uv) {
                vec3 c = pick(uv);
                vec3 simulated = vec3(
                    dot(c, vec3(0.367322, 0.860646, -0.227968)),
                    dot(c, vec3(0.280085, 0.672501, 0.047413)),
                    dot(c, vec3(-0.011820, 0.042940, 0.968881)));
                vec3 error = c - simulated;
                return clamp(c + vec3(0.0, 0.7 * error.r + error.g, 0.7 * error.r + error.b), 0.0, 1.0);
            }
            @Color blind assist (protanopia)
            // Moves the colors that a viewer without red cones can't tell apart into colors that they can see.
            // The color blindness is simulated with the matrix of Machado, Oliveira and Fernandes (2009),
            // and the difference to the original is added to the color channels that the viewer can see.
            vec3 customEffect(vec2 uv) {
                vec3 c = pick(uv);
                vec3 simulated = vec3(
                    dot(c, vec3(0.152286, 1.052583, -0.204868)),
                    dot(c, vec3(0.114503, 0.786281, 0.099216)),
                    dot(c, vec3(-0.003882, -0.048116, 1.051998)));
                vec3 error = c - simulated;
                return clamp(c + vec3(0.0, 0.7 * error.r + error.g, 0.7 * error.r + error.b), 0.0, 1.0);
            }
            @Color blind assist (tritanopia)
            // Moves the colors that a viewer without blue cones can't tell apart into colors that they can see.
            // The color blindness is simulated with the matrix of Machado, Oliveira and Fernandes (2009),
            // and the difference to the original is added to the color channels that the viewer can see.
            vec3 customEffect(vec2 uv) {
                vec3 c = pick(uv);
                vec3 simulated = vec3(
                    dot(c, vec3(1.255528, -0.076749, -0.178779)),
                    dot(c, vec3(-0.078411, 0.930809, 0.147602)),
                    dot(c, vec3(0.004733, 0.691367, 0.303900)));
                vec3 error = c - simulated;
                return clamp(c + vec3(error.r + 0.7 * error.b, error.g + 0.7 * error.b, 0.0), 0.0, 1.0);
            }
            @Black and white
            // Only the brightness of the colors.
            vec3 customEffect(vec2 uv) {
                return vec3(luma(pick(uv)));
            }
            @Alien colors
            // The red, green and blue channels swapped around.
            vec3 customEffect(vec2 uv) {
                return adjustSaturation(pick(uv).brg, 1.3);
            }
            @ASCII art
            // Green text characters on black, picked by the brightness.
            // 5x5 bitmaps of the characters, from dark to bright. Bits 0 to 14 hold the top 3 rows,
            // and bits 0 to 9 of the second number the bottom 2 rows, starting at the top left.
            vec2 myGlyph(float level) {
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

            vec3 customEffect(vec2 uv) {
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
                vec2 bitmap = myGlyph(floor(l * 9.999));
                float bits = row < 3.0 ? bitmap.x : bitmap.y;
                float index = (row < 3.0 ? row : row - 3.0) * 5.0 + local.x;
                float on = mod(floor(bits / exp2(index)), 2.0);
                return vec3(0.2, 1.0, 0.35) * on * (0.55 + 0.45 * l);
            }
            @Comic book
            // Ink outlines, flat colors and halftone dots in the shadows.
            vec3 customEffect(vec2 uv) {
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
            @CRT TV
            // A curved tube TV screen with scanlines and red, green and blue phosphor stripes.
            vec3 customEffect(vec2 uv) {
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
            @Fisheye
            // Magnifies the middle and squeezes the sides, like a wide angle lens.
            vec3 customEffect(vec2 uv) {
                vec2 p = centered(uv);
                float radius = 0.45 * length(vec2(uResolution.x / max(uResolution.y, 1.0), 1.0));
                float r = length(p) / radius;
                if (r < 1.0) p *= mix(0.45, 1.0, r * r);
                return pick(uncentered(p));
            }
            @Inverted colors
            vec3 customEffect(vec2 uv) {
                return 1.0 - pick(uv);
            }
            @Kaleidoscope
            // Mirrored slices of the middle of the video that slowly turn around.
            vec3 customEffect(vec2 uv) {
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
            @Mirror image
            vec3 customEffect(vec2 uv) {
                return pick(vec2(1.0 - uv.x, uv.y));
            }
            @Neon edges
            // Glowing outlines in colors that move across the video.
            vec3 customEffect(vec2 uv) {
                float edge = smoothstep(0.08, 0.5, edges(uv, 1.5));
                return hue(uv.x * 0.5 + uv.y * 0.5 + uTime * 0.15) * edge * 1.4;
            }
            @Night vision
            // Green and grainy, with scanlines.
            vec3 customEffect(vec2 uv) {
                float l = pow(luma(pick(uv)), 0.65) * 1.3;
                float noise = (rand(uv * 900.0 + floor(uTime * 30.0)) - 0.5) * 0.18;
                float scanline = 0.88 + 0.12 * sin(gl_FragCoord.y * 2.0);
                vec3 c = vec3(0.12, 1.0, 0.22) * (l + noise) * scanline;
                return clamp(c * vignette(uv, 2.6), 0.0, 1.0);
            }
            @Old film
            // Sepia with film grain and flicker.
            vec3 customEffect(vec2 uv) {
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
            @Pencil sketch
            // Pencil lines on paper, with hatching in the dark parts that is redrawn a few times per second.
            vec3 customEffect(vec2 uv) {
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
            @Pop art
            // Strong colors with only a few levels each.
            vec3 customEffect(vec2 uv) {
                vec3 c = adjustSaturation(pick(uv), 1.8);
                return floor(c * 3.0 + 0.5) / 3.0;
            }
            @Puzzle
            // The picture cut into 4x4 pieces that fly to mixed up places and back again.
            vec3 customEffect(vec2 uv) {
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
            @Rainbow
            // The colors keep turning around the color wheel.
            vec3 customEffect(vec2 uv) {
                return clamp(hueRotate(adjustSaturation(pick(uv), 1.4), uTime * 1.2), 0.0, 1.0);
            }
            @Retro handheld
            // 160 pixels wide with 4 shades of green, like an old handheld game console.
            vec3 customEffect(vec2 uv) {
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
            @Retro PC
            // 320 pixels wide with the 32 colors of the DawnBringer 32 pixel art palette, and ordered dithering.
            // Ordered dithering thresholds from 0 to 15/16 in a 4x4 pattern.
            float myBayer2(vec2 a) {
                a = mod(floor(a), 2.0);
                return fract(dot(a, vec2(0.5, a.y * 0.75)));
            }

            float myBayer4(vec2 a) {
                return myBayer2(0.5 * a) * 0.25 + myBayer2(a);
            }

            // Keeps the palette color that is closest to c. The colors are from 0 to 255.
            void myCloserColor(vec3 c, vec3 color, inout float best, inout vec3 result) {
                vec3 diff = c - color / 255.0;
                float d = dot(diff * diff, vec3(2.0, 4.0, 3.0));
                if (d < best) {
                    best = d;
                    result = color / 255.0;
                }
            }

            vec3 customEffect(vec2 uv) {
                float pixel = max(1.0, floor(uResolution.x / 320.0));
                vec2 cell = floor(uv * uResolution / pixel);
                vec3 c = adjustSaturation(pick((cell + 0.5) * pixel / uResolution), 1.2);
                c += (myBayer4(cell) - 0.47) * 0.2;
                float best = 100.0;
                vec3 result = vec3(0.0);
                myCloserColor(c, vec3(0.0, 0.0, 0.0), best, result);
                myCloserColor(c, vec3(34.0, 32.0, 52.0), best, result);
                myCloserColor(c, vec3(69.0, 40.0, 60.0), best, result);
                myCloserColor(c, vec3(102.0, 57.0, 49.0), best, result);
                myCloserColor(c, vec3(143.0, 86.0, 59.0), best, result);
                myCloserColor(c, vec3(223.0, 113.0, 38.0), best, result);
                myCloserColor(c, vec3(217.0, 160.0, 102.0), best, result);
                myCloserColor(c, vec3(238.0, 195.0, 154.0), best, result);
                myCloserColor(c, vec3(251.0, 242.0, 54.0), best, result);
                myCloserColor(c, vec3(153.0, 229.0, 80.0), best, result);
                myCloserColor(c, vec3(106.0, 190.0, 48.0), best, result);
                myCloserColor(c, vec3(55.0, 148.0, 110.0), best, result);
                myCloserColor(c, vec3(75.0, 105.0, 47.0), best, result);
                myCloserColor(c, vec3(82.0, 75.0, 36.0), best, result);
                myCloserColor(c, vec3(50.0, 60.0, 57.0), best, result);
                myCloserColor(c, vec3(63.0, 63.0, 116.0), best, result);
                myCloserColor(c, vec3(48.0, 96.0, 130.0), best, result);
                myCloserColor(c, vec3(91.0, 110.0, 225.0), best, result);
                myCloserColor(c, vec3(99.0, 155.0, 255.0), best, result);
                myCloserColor(c, vec3(95.0, 205.0, 228.0), best, result);
                myCloserColor(c, vec3(203.0, 219.0, 252.0), best, result);
                myCloserColor(c, vec3(255.0, 255.0, 255.0), best, result);
                myCloserColor(c, vec3(155.0, 173.0, 183.0), best, result);
                myCloserColor(c, vec3(132.0, 126.0, 135.0), best, result);
                myCloserColor(c, vec3(105.0, 106.0, 106.0), best, result);
                myCloserColor(c, vec3(89.0, 86.0, 82.0), best, result);
                myCloserColor(c, vec3(118.0, 66.0, 138.0), best, result);
                myCloserColor(c, vec3(172.0, 50.0, 50.0), best, result);
                myCloserColor(c, vec3(217.0, 87.0, 99.0), best, result);
                myCloserColor(c, vec3(215.0, 123.0, 186.0), best, result);
                myCloserColor(c, vec3(143.0, 151.0, 74.0), best, result);
                myCloserColor(c, vec3(138.0, 111.0, 48.0), best, result);
                return result;
            }
            @Thermal camera
            // Dark is cold purple, bright is hot yellow and white.
            vec3 customEffect(vec2 uv) {
                float t = clamp(luma(pick(uv)), 0.0, 1.0) * 5.0;
                if (t < 1.0) return mix(vec3(0.0, 0.0, 0.1), vec3(0.25, 0.0, 0.6), t);
                if (t < 2.0) return mix(vec3(0.25, 0.0, 0.6), vec3(0.9, 0.0, 0.45), t - 1.0);
                if (t < 3.0) return mix(vec3(0.9, 0.0, 0.45), vec3(1.0, 0.45, 0.0), t - 2.0);
                if (t < 4.0) return mix(vec3(1.0, 0.45, 0.0), vec3(1.0, 0.95, 0.2), t - 3.0);
                return mix(vec3(1.0, 0.95, 0.2), vec3(1.0), t - 4.0);
            }
            @Tilt-shift (miniature)
            // Blurs the top and bottom and adds color, so the video looks like a miniature model.
            vec3 customEffect(vec2 uv) {
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
            @Tiny planet
            // The bottom of the picture in the middle and the top all around, like a little planet.
            vec3 customEffect(vec2 uv) {
                vec2 p = centered(uv);
                float angle = atan(p.x, -p.y) / (2.0 * PI) + 0.5 + uTime * 0.02;
                // Two mirrored copies around the circle, so there is no seam.
                return pick(vec2(abs(fract(angle) * 2.0 - 1.0), length(p) * 2.0));
            }
            @Underwater
            // Blue green colors and a wavy picture.
            vec3 customEffect(vec2 uv) {
                vec2 wave = vec2(sin(uv.y * 22.0 + uTime * 2.0), cos(uv.x * 18.0 + uTime * 1.6)) * 0.006;
                return clamp(pick(uv + wave) * vec3(0.7, 0.92, 1.1), 0.0, 1.0);
            }
            @VHS tape
            // Shaky lines, color fringes, noise and scanlines, like an old video tape.
            vec3 customEffect(vec2 uv) {
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
            @Video wall
            // The video on a wall of 3x3 screens.
            vec3 customEffect(vec2 uv) {
                vec2 tile = fract(uv * 3.0);
                vec2 border = 12.0 / uResolution;
                vec2 inside = step(border, tile) * step(border, 1.0 - tile);
                return pick(tile) * inside.x * inside.y;
            }""";
}
