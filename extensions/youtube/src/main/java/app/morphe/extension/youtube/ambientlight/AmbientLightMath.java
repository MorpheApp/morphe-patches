/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4576
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.ambientlight;

import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * Pixel work for the ambient light. The picture is small ARGB int arrays. The light is
 * 3 floats per pixel in linear light, because 8 bit values and gamma blending make a smooth
 * glow blotchy and muddy.
 */
final class AmbientLightMath {
    private AmbientLightMath() {
    }

    private static final float SATURATION = 1.25f;
    /** Blur radius next to the picture, as a divisor of the width of the light. */
    private static final int SHARP_BLUR_WIDTH_DIVISOR = 32;
    /** Blur radius far from the picture, as a divisor of the width of the light. */
    private static final int SOFT_BLUR_WIDTH_DIVISOR = 10;
    /** Distance at which only the soft light is left, in picture heights. */
    private static final float SOFT_DISTANCE = 0.9f;
    private static final int BLUR_PASSES = 3;
    /** Share of the page color at the bottom of the screen. */
    private static final float BOTTOM_PAGE_COLOR = 0.25f;
    private static final float[] LINEAR = new float[256];
    // Reused between frames. The light is only computed on one thread.
    private static float[] nearRows = new float[0];
    private static float[] farRows = new float[0];
    /** Cells of the light this far inside the picture are never shown. */
    private static final float HIDDEN_MARGIN = 2f;
    private static final short HALF_ONE = 0x3C00;
    private static final int SMALLEST_NORMAL_HALF_BITS = 0x38800000;
    private static final int HALF_MANTISSA_SHIFT = 13;
    private static final int HALF_ROUNDING = 1 << (HALF_MANTISSA_SHIFT - 1);
    private static final int HALF_EXPONENT_OFFSET = 112 << 10;

    /** The level a converted capture has, for each level of a capture that is not converted. */
    private static final float[] CONVERTED = new float[256];
    /** The level without the conversion, for each level of a converted capture. */
    private static final int[] NOT_CONVERTED = new int[256];
    /** A conversion changes the mean level by this share of what is expected, more or less. */
    private static final float MIN_CONVERSION_SHARE = 0.5f;
    private static final float MAX_CONVERSION_SHARE = 1.6f;
    /** Mean channel difference (out of 255) that the conversion may leave unexplained. */
    private static final float MAX_CONVERSION_RESIDUAL = 3f;
    /** Share of the difference between two captures that the conversion may leave unexplained. */
    private static final float MAX_CONVERSION_RESIDUAL_SHARE = 0.3f;
    private static int[] referenceChannel = new int[0];
    private static int[] pictureChannel = new int[0];

    static {
        for (int i = 0; i < LINEAR.length; i++) {
            final float value = i / 255f;
            LINEAR[i] = value * value;
            // The BT.709 curve of the video to linear light, and that to the sRGB curve.
            final double light = value < 0.081 ? value / 4.5 : Math.pow((value + 0.099) / 1.099, 1 / 0.45);
            CONVERTED[i] = (float) (255 * (light <= 0.0031308 ? 12.92 * light : 1.055 * Math.pow(light, 1 / 2.4) - 0.055));
        }
        for (int level = 0, plain = 0; level < NOT_CONVERTED.length; level++) {
            while (plain < 255 && CONVERTED[plain + 1] <= level) plain++;
            NOT_CONVERTED[level] = plain < 255 && CONVERTED[plain + 1] - level < level - CONVERTED[plain] ? plain + 1 : plain;
        }
    }

    /** Neutral colors within this luma of the page color are the page background. */
    private static final int PAGE_LUMA_TOLERANCE = 6;
    /** Neutral colors this far from the page color are not a raised surface. */
    private static final int MAX_RAISED_LUMA_DIFFERENCE = 56;
    private static final int MAX_RAISED_ALPHA = 96;
    /** White text stays readable if the light is at most this bright. */
    private static final float DARK_PAGE_MAX_BRIGHTNESS = 0.5f;
    /** Black text stays readable if the light is at least this bright. */
    private static final float LIGHT_PAGE_MIN_BRIGHTNESS = 0.6f;
    /** A few bright spots must not darken the whole page. */
    private static final int READABILITY_PERCENTILE = 85;
    private static final int BRIGHTNESS_BINS = 64;
    private static final int MAX_NEUTRAL_CHROMA = 12;
    private static final int MIN_OPAQUE_ALPHA = 0xF0;
    private static final int BAR_MAX_LUMA = 18;
    /** Share of a line that can be bright in a bar, for noise and logos. */
    private static final int BAR_NOISE_PERCENT = 4;
    private static final int MAX_BAR_ASYMMETRY_PERCENT = 3;

    /**
     * Area average, because readbacks are scaled with few samples and sparkle between frames.
     */
    static void downsample(int[] source, int sourceWidth, int[] bounds,
                           int[] target, int targetWidth, int targetHeight) {
        final int regionWidth = bounds[2] - bounds[0];
        final int regionHeight = bounds[3] - bounds[1];
        for (int y = 0; y < targetHeight; y++) {
            final int top = bounds[1] + y * regionHeight / targetHeight;
            final int bottom = Math.max(top + 1, bounds[1] + (y + 1) * regionHeight / targetHeight);
            for (int x = 0; x < targetWidth; x++) {
                final int left = bounds[0] + x * regionWidth / targetWidth;
                final int right = Math.max(left + 1, bounds[0] + (x + 1) * regionWidth / targetWidth);
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int row = top; row < bottom; row++) {
                    for (int column = left; column < right; column++) {
                        final int color = source[row * sourceWidth + column];
                        red += (color >> 16) & 0xFF;
                        green += (color >> 8) & 0xFF;
                        blue += color & 0xFF;
                    }
                }
                final int count = (bottom - top) * (right - left);
                target[y * targetWidth + x] = 0xFF000000 | (red / count) << 16
                        | (green / count) << 8 | (blue / count);
            }
        }
    }

    /**
     * Finds the part of a frame that is not black.
     *
     * @return Left, top, right and bottom of it, or null for an all dark frame.
     */
    @Nullable
    static int[] pictureBounds(int[] pixels, int width, int height) {
        int top = 0;
        while (top < height && isDarkLine(pixels, top * width, 1, width)) top++;
        if (top == height) return null;

        int bottom = height;
        while (isDarkLine(pixels, (bottom - 1) * width, 1, width)) bottom--;
        int left = 0;
        while (isDarkLine(pixels, left + top * width, width, bottom - top)) left++;
        int right = width;
        while (isDarkLine(pixels, right - 1 + top * width, width, bottom - top)) right--;
        return new int[]{left, top, right, bottom};
    }

    /**
     * Black bars are the same on both sides. A bar on only one side, as with subtitles in the
     * other bar, counts as no bars, so that nothing is ever painted over the subtitles.
     */
    static int[] withoutOneSidedBars(int[] bounds, int width, int height) {
        final int left = bounds[0];
        final int top = bounds[1];
        final int right = bounds[2];
        final int bottom = bounds[3];
        final int verticalSlack = Math.max(1, height * MAX_BAR_ASYMMETRY_PERCENT / 100);
        final int horizontalSlack = Math.max(1, width * MAX_BAR_ASYMMETRY_PERCENT / 100);
        final boolean vertical = Math.abs(top - (height - bottom)) <= verticalSlack && top >= verticalSlack;
        final boolean horizontal = Math.abs(left - (width - right)) <= horizontalSlack && left >= horizontalSlack;
        return new int[]{horizontal ? left : 0, vertical ? top : 0,
                horizontal ? right : width, vertical ? bottom : height};
    }

    private static boolean isDarkLine(int[] pixels, int offset, int step, int count) {
        final int allowedBright = count * BAR_NOISE_PERCENT / 100;
        int bright = 0;
        for (int i = 0; i < count; i++) {
            if (luma(pixels[offset + i * step]) > BAR_MAX_LUMA && ++bright > allowedBright) return false;
        }
        return true;
    }

    /**
     * @return The mean absolute difference per color channel.
     */
    static int difference(int[] first, int[] second) {
        long total = 0;
        for (int i = 0; i < first.length; i++) {
            final int a = first[i];
            final int b = second[i];
            total += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                    + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                    + Math.abs((a & 0xFF) - (b & 0xFF));
        }
        return (int) (total / (3L * first.length));
    }

    /**
     * Some devices convert the BT.709 transfer curve of the video to sRGB in only some of the
     * captures, about every second one on a reported device. A converted capture is brighter,
     * mostly in the dark parts, and the light flickers if it follows both kinds.
     * The channels are compared sorted, so things that moved in the picture do not count.
     *
     * @return If {@code picture} is {@code reference} with the conversion.
     */
    static boolean isConverted(int[] reference, int[] picture) {
        final int length = reference.length;
        long referenceSum = 0;
        long pictureSum = 0;
        float convertedSum = 0f;
        for (int i = 0; i < length; i++) {
            final int a = reference[i];
            final int b = picture[i];
            final int red = (a >> 16) & 0xFF;
            final int green = (a >> 8) & 0xFF;
            final int blue = a & 0xFF;
            referenceSum += red + green + blue;
            convertedSum += CONVERTED[red] + CONVERTED[green] + CONVERTED[blue];
            pictureSum += ((b >> 16) & 0xFF) + ((b >> 8) & 0xFF) + (b & 0xFF);
        }
        final float expected = (convertedSum - referenceSum) / (3f * length);
        final float change = (pictureSum - referenceSum) / (3f * length);
        // Almost every capture stops here, so the sorting below is rare.
        if (expected < 1f || change < MIN_CONVERSION_SHARE * expected || change > MAX_CONVERSION_SHARE * expected) {
            return false;
        }

        if (referenceChannel.length != length) {
            referenceChannel = new int[length];
            pictureChannel = new int[length];
        }
        float difference = 0f;
        float residual = 0f;
        for (int shift = 16; shift >= 0; shift -= 8) {
            for (int i = 0; i < length; i++) {
                referenceChannel[i] = (reference[i] >> shift) & 0xFF;
                pictureChannel[i] = (picture[i] >> shift) & 0xFF;
            }
            Arrays.sort(referenceChannel);
            Arrays.sort(pictureChannel);
            for (int i = 0; i < length; i++) {
                difference += Math.abs(pictureChannel[i] - referenceChannel[i]);
                residual += Math.abs(pictureChannel[i] - CONVERTED[referenceChannel[i]]);
            }
        }
        residual /= 3f * length;
        difference /= 3f * length;
        return residual <= MAX_CONVERSION_RESIDUAL && residual <= MAX_CONVERSION_RESIDUAL_SHARE * difference;
    }

    /**
     * Takes the conversion out of a capture again, see {@link #isConverted}.
     */
    static void undoConversion(int[] picture) {
        for (int i = 0; i < picture.length; i++) {
            final int color = picture[i];
            picture[i] = 0xFF000000 | NOT_CONVERTED[(color >> 16) & 0xFF] << 16
                    | NOT_CONVERTED[(color >> 8) & 0xFF] << 8 | NOT_CONVERTED[color & 0xFF];
        }
    }

    /**
     * Moves {@code current} towards {@code target}.
     *
     * @param keep Share of the current color to keep, from 0 to 256.
     */
    static void blend(int[] current, int[] target, int keep) {
        final int take = 256 - keep;
        for (int i = 0; i < current.length; i++) {
            final int a = current[i];
            final int b = target[i];
            final int red = (((a >> 16) & 0xFF) * keep + ((b >> 16) & 0xFF) * take + 128) >> 8;
            final int green = (((a >> 8) & 0xFF) * keep + ((b >> 8) & 0xFF) * take + 128) >> 8;
            final int blue = ((a & 0xFF) * keep + (b & 0xFF) * take + 128) >> 8;
            current[i] = 0xFF000000 | red << 16 | green << 8 | blue;
        }
    }

    /**
     * @param linear 3 floats for each pixel.
     */
    static void toLinear(int[] pixels, float[] linear) {
        for (int i = 0; i < pixels.length; i++) {
            final int color = pixels[i];
            linear[i * 3] = LINEAR[(color >> 16) & 0xFF];
            linear[i * 3 + 1] = LINEAR[(color >> 8) & 0xFF];
            linear[i * 3 + 2] = LINEAR[color & 0xFF];
        }
    }

    /**
     * Paints the light of the picture over the window. A point takes the color of the nearest
     * point of the picture, and turns from {@code near} to the column averages in {@code far}
     * over one picture height.
     *
     * @param video  left, top, right and bottom of the picture, in window coordinates.
     * @param out    3 floats for each pixel.
     */
    static void render(float[] near, int nearWidth, int nearHeight, float[] far, int farWidth, int farHeight,
                       int[] video, int windowWidth, int windowHeight, float[] out, int outWidth, int outHeight) {
        final float videoWidth = Math.max(1, video[2] - video[0]);
        final float videoHeight = Math.max(1, video[3] - video[1]);
        final float cellWidth = windowWidth / (float) outWidth;
        final float cellHeight = windowHeight / (float) outHeight;

        float[] columnDistance = new float[outWidth];
        int[] nearLeft = new int[outWidth];
        float[] nearWeight = new float[outWidth];
        int[] farLeft = new int[outWidth];
        float[] farWeight = new float[outWidth];
        for (int column = 0; column < outWidth; column++) {
            final float x = (column + 0.5f) * cellWidth;
            final float u = clamp((x - video[0]) / videoWidth);
            columnDistance[column] = Math.max(Math.max(video[0] - x, x - video[2]), 0f);
            final float nearX = u * (nearWidth - 1);
            nearLeft[column] = Math.min((int) nearX, nearWidth - 2);
            nearWeight[column] = nearX - nearLeft[column];
            final float farX = u * (farWidth - 1);
            farLeft[column] = Math.min((int) farX, farWidth - 2);
            farWeight[column] = farX - farLeft[column];
        }
        nearRows = interpolateColumns(near, nearWidth, nearHeight, nearLeft, nearWeight, outWidth, nearRows);
        farRows = interpolateColumns(far, farWidth, farHeight, farLeft, farWeight, outWidth, farRows);

        final int rowLength = outWidth * 3;
        int index = 0;
        for (int row = 0; row < outHeight; row++) {
            final float y = (row + 0.5f) * cellHeight;
            final float v = clamp((y - video[1]) / videoHeight);
            final float verticalDistance = Math.max(Math.max(video[1] - y, y - video[3]), 0f);
            final float nearY = v * (nearHeight - 1);
            final int nearTop = Math.min((int) nearY, nearHeight - 2);
            final float nearRowWeight = nearY - nearTop;
            final float farY = v * (farHeight - 1);
            final int farTop = Math.min((int) farY, farHeight - 2);
            final float farRowWeight = farY - farTop;

            int nearIndex = nearTop * rowLength;
            int farIndex = farTop * rowLength;
            for (int column = 0; column < outWidth; column++) {
                final float softness = clamp(Math.max(verticalDistance, columnDistance[column]) / videoHeight);
                for (int channel = 0; channel < 3; channel++) {
                    float value = 0f;
                    if (softness < 1f) {
                        final float upper = nearRows[nearIndex + channel];
                        value = upper + (nearRows[nearIndex + rowLength + channel] - upper) * nearRowWeight;
                    }
                    if (softness > 0f) {
                        final float upper = farRows[farIndex + channel];
                        value += (upper + (farRows[farIndex + rowLength + channel] - upper) * farRowWeight - value)
                                * softness;
                    }
                    out[index++] = value;
                }
                nearIndex += 3;
                farIndex += 3;
            }
        }
    }

    /**
     * @return Every row of {@code pixels} interpolated at the output columns, in {@code buffer} if it fits.
     */
    private static float[] interpolateColumns(float[] pixels, int width, int height, int[] left, float[] weight,
                                              int outWidth, float[] buffer) {
        final int length = height * outWidth * 3;
        float[] rows = buffer.length == length ? buffer : new float[length];
        int index = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < outWidth; column++) {
                final int position = (row * width + left[column]) * 3;
                final float columnWeight = weight[column];
                for (int channel = 0; channel < 3; channel++) {
                    final float a = pixels[position + channel];
                    rows[index++] = a + (pixels[position + 3 + channel] - a) * columnWeight;
                }
            }
        }
        return rows;
    }

    /**
     * Finishes the rendered light. It keeps the shapes of the picture next to the picture and
     * melts into a soft glow farther away, with a slight fall off towards the page color.
     *
     * @param soft    Working copy, as long as {@code light}.
     * @param scratch Working copy, as long as {@code light}.
     * @param picture Left, top, right and bottom of the picture, in light pixels.
     * @param pageColor Page color in linear light, 3 floats.
     */
    static void soften(float[] light, float[] soft, float[] scratch, int width, int height,
                       float[] picture, float[] pageColor) {
        final float softDistance = Math.max(1f, (picture[3] - picture[1]) * SOFT_DISTANCE);
        final int sharpRadius = Math.max(1, width / SHARP_BLUR_WIDTH_DIVISOR);
        final int softRadius = Math.max(2, width / SOFT_BLUR_WIDTH_DIVISOR);
        System.arraycopy(light, 0, soft, 0, light.length);
        // Only the soft light is used this far below the picture.
        final int sharpRows = Math.min(height, (int) Math.ceil(picture[3] + softDistance) + BLUR_PASSES * sharpRadius + 1);
        blur(light, scratch, width, sharpRows, sharpRadius, sharpRows);
        // Every row is the same from one picture height below the picture on.
        final int uniformFrom = Math.min(height, (int) Math.ceil(picture[3] + (picture[3] - picture[1])) + 1);
        blur(soft, scratch, width, height, softRadius, uniformFrom);

        final int hiddenFrom = hiddenFrom(picture);
        final int hiddenTo = hiddenTo(picture, width);
        final float rows = Math.max(1f, height - picture[3]);
        final float redPage = pageColor[0];
        final float greenPage = pageColor[1];
        final float bluePage = pageColor[2];
        int index = 0;
        for (int y = 0; y < height; y++) {
            final boolean hiddenRow = hiddenFrom < hiddenTo && isHiddenRow(y, picture);
            final float centerY = y + 0.5f;
            final float verticalDistance = Math.max(Math.max(picture[1] - centerY, centerY - picture[3]), 0f);
            final float t = clamp((centerY - picture[3]) / rows);
            final float pageWeight = t * t * (3f - 2f * t) * BOTTOM_PAGE_COLOR;
            for (int x = 0; x < width; x++) {
                if (hiddenRow && x == hiddenFrom) {
                    index += (hiddenTo - hiddenFrom) * 3;
                    x = hiddenTo - 1;
                    continue;
                }
                final float centerX = x + 0.5f;
                final float distance = Math.max(verticalDistance,
                        Math.max(Math.max(picture[0] - centerX, centerX - picture[2]), 0f));
                final float blend = clamp(distance / softDistance);
                final float softWeight = blend * blend * (3f - 2f * blend);
                final float red = light[index] + (soft[index] - light[index]) * softWeight;
                final float green = light[index + 1] + (soft[index + 1] - light[index + 1]) * softWeight;
                final float blue = light[index + 2] + (soft[index + 2] - light[index + 2]) * softWeight;
                final float gray = 0.2126f * red + 0.7152f * green + 0.0722f * blue;
                final float saturatedRed = Math.max(0f, gray + (red - gray) * SATURATION);
                final float saturatedGreen = Math.max(0f, gray + (green - gray) * SATURATION);
                final float saturatedBlue = Math.max(0f, gray + (blue - gray) * SATURATION);
                light[index] = saturatedRed + (redPage - saturatedRed) * pageWeight;
                light[index + 1] = saturatedGreen + (greenPage - saturatedGreen) * pageWeight;
                light[index + 2] = saturatedBlue + (bluePage - saturatedBlue) * pageWeight;
                index += 3;
            }
        }
    }

    /**
     * @param uniformFrom The first row from which all rows are the same, which stays true
     *                    (a little further down) through every pass.
     */
    private static void blur(float[] pixels, float[] scratch, int width, int height, int radius, int uniformFrom) {
        int uniform = uniformFrom;
        for (int pass = 0; pass < BLUR_PASSES; pass++) {
            int outputRows = Math.min(height, uniform + 1);
            boxBlur(pixels, scratch, width, height, radius, true, outputRows);
            copyLastRow(scratch, width, height, outputRows);
            uniform += radius;
            outputRows = Math.min(height, uniform + 1);
            boxBlur(scratch, pixels, width, height, radius, false, outputRows);
            copyLastRow(pixels, width, height, outputRows);
        }
    }

    private static void copyLastRow(float[] pixels, int width, int height, int outputRows) {
        final int rowLength = width * 3;
        final int source = (outputRows - 1) * rowLength;
        for (int row = outputRows; row < height; row++) {
            System.arraycopy(pixels, source, pixels, row * rowLength, rowLength);
        }
    }

    /**
     * @param outputRows Only these rows are written.
     */
    private static void boxBlur(float[] in, float[] out, int width, int height, int radius, boolean horizontal,
                                int outputRows) {
        final int lines = horizontal ? outputRows : width;
        final int length = horizontal ? width : height;
        final int outputLength = horizontal ? width : outputRows;
        final int step = horizontal ? 3 : 3 * width;
        final int lineStep = horizontal ? 3 * width : 3;
        final float scale = 1f / (2 * radius + 1);
        for (int line = 0; line < lines; line++) {
            final int base = line * lineStep;
            float red = 0f;
            float green = 0f;
            float blue = 0f;
            for (int i = -radius; i <= radius; i++) {
                final int position = base + Math.max(0, Math.min(length - 1, i)) * step;
                red += in[position];
                green += in[position + 1];
                blue += in[position + 2];
            }
            for (int i = 0; i < outputLength; i++) {
                final int position = base + i * step;
                out[position] = red * scale;
                out[position + 1] = green * scale;
                out[position + 2] = blue * scale;
                final int entering = base + (i + radius + 1 < length ? i + radius + 1 : length - 1) * step;
                final int leaving = base + (i > radius ? i - radius : 0) * step;
                red += in[entering] - in[leaving];
                green += in[entering + 1] - in[leaving + 1];
                blue += in[entering + 2] - in[leaving + 2];
            }
        }
    }

    /**
     * @return The first column of the cells behind the picture, too far in for scaling up the light to reach them.
     */
    private static int hiddenFrom(float[] picture) {
        return Math.max(0, (int) Math.ceil(picture[0] + HIDDEN_MARGIN));
    }

    private static int hiddenTo(float[] picture, int width) {
        return Math.min(width, (int) Math.ceil(picture[2] - HIDDEN_MARGIN));
    }

    private static boolean isHiddenRow(int y, float[] picture) {
        return y >= picture[1] + HIDDEN_MARGIN && y < picture[3] - HIDDEN_MARGIN;
    }

    /**
     * Writes the square root of the light into {@code encoded}, for the cells that can be seen.
     * That is what an extended sRGB bitmap expects.
     *
     * @return The largest change of any of those cells from {@code presented}.
     */
    static float encodeVisible(float[] light, float[] encoded, float[] presented, int width, int height, float[] picture) {
        final int hiddenFrom = hiddenFrom(picture);
        final int hiddenTo = hiddenTo(picture, width);
        float largestChange = 0f;
        int index = 0;
        for (int y = 0; y < height; y++) {
            final boolean hiddenRow = hiddenFrom < hiddenTo && isHiddenRow(y, picture);
            for (int x = 0; x < width; x++) {
                if (hiddenRow && x == hiddenFrom) {
                    index += (hiddenTo - hiddenFrom) * 3;
                    x = hiddenTo - 1;
                    continue;
                }
                for (int channel = 0; channel < 3; channel++) {
                    final float value = (float) Math.sqrt(Math.min(1f, light[index]));
                    encoded[index] = value;
                    largestChange = Math.max(largestChange, Math.abs(value - presented[index]));
                    index++;
                }
            }
        }
        return largestChange;
    }

    /**
     * Writes the visible cells as half floats. Alpha is left alone: {@link #fillAlpha} sets it once.
     */
    static void toHalfFloats(float[] encoded, short[] halfFloats, int width, int height, float[] picture) {
        final int hiddenFrom = hiddenFrom(picture);
        final int hiddenTo = hiddenTo(picture, width);
        int source = 0;
        int target = 0;
        for (int y = 0; y < height; y++) {
            final boolean hiddenRow = hiddenFrom < hiddenTo && isHiddenRow(y, picture);
            for (int x = 0; x < width; x++) {
                if (hiddenRow && x == hiddenFrom) {
                    source += (hiddenTo - hiddenFrom) * 3;
                    target += (hiddenTo - hiddenFrom) * 4;
                    x = hiddenTo - 1;
                    continue;
                }
                halfFloats[target] = encodeHalf(encoded[source]);
                halfFloats[target + 1] = encodeHalf(encoded[source + 1]);
                halfFloats[target + 2] = encodeHalf(encoded[source + 2]);
                source += 3;
                target += 4;
            }
        }
    }

    static void fillAlpha(short[] halfFloats) {
        for (int i = 3; i < halfFloats.length; i += 4) halfFloats[i] = HALF_ONE;
    }

    /**
     * @param value From 0 to 1.
     */
    static short encodeHalf(float value) {
        final int bits = Float.floatToRawIntBits(value);
        if (bits < SMALLEST_NORMAL_HALF_BITS) return 0;
        return (short) (((bits + HALF_ROUNDING) >> HALF_MANTISSA_SHIFT) - HALF_EXPONENT_OFFSET);
    }

    /**
     * How much of the page color has to cover the light so that the text of the page stays
     * readable. It is zero unless the light is too bright for white text on a dark page,
     * or too dark for black text on a light page.
     *
     * @param firstRow The first row of {@code light} that is below the picture.
     * @return The opacity of the cover, from 0 to 1.
     */
    static float readabilityCover(float[] light, int width, int height, int firstRow, boolean darkPage) {
        int[] bins = new int[BRIGHTNESS_BINS];
        int count = 0;
        for (int i = Math.max(0, firstRow) * width; i < width * height; i++) {
            final float gray = 0.2126f * light[i * 3] + 0.7152f * light[i * 3 + 1] + 0.0722f * light[i * 3 + 2];
            bins[Math.min(BRIGHTNESS_BINS - 1, (int) ((float) Math.sqrt(gray) * BRIGHTNESS_BINS))]++;
            count++;
        }
        if (count == 0) return 0f;

        final int target = (darkPage ? READABILITY_PERCENTILE : 100 - READABILITY_PERCENTILE) * count / 100;
        int bin = 0;
        for (int seen = bins[0]; seen <= target && bin < BRIGHTNESS_BINS - 1; seen += bins[bin]) bin++;
        final float brightness = (bin + 0.5f) / BRIGHTNESS_BINS;

        return darkPage
                ? Math.max(0f, 1f - DARK_PAGE_MAX_BRIGHTNESS / brightness)
                : Math.max(0f, 1f - (1f - LIGHT_PAGE_MIN_BRIGHTNESS) / (1f - brightness));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static int luma(int color) {
        return (((color >> 16) & 0xFF) * 299 + ((color >> 8) & 0xFF) * 587
                + (color & 0xFF) * 114) / 1000;
    }

    private static boolean isOpaqueNeutral(int color) {
        if ((color >>> 24) < MIN_OPAQUE_ALPHA) return false;
        final int red = (color >> 16) & 0xFF;
        final int green = (color >> 8) & 0xFF;
        final int blue = color & 0xFF;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue))
                <= MAX_NEUTRAL_CHROMA;
    }

    /**
     * What a background color becomes on the see-through watch page. The page color turns
     * transparent, a slightly raised surface such as a card turns into a translucent veil,
     * and everything else keeps its color.
     */
    static int seeThroughColor(int color, int pageColor) {
        if (!isOpaqueNeutral(color)) return color;

        final int difference = luma(color) - luma(pageColor);
        final boolean darkPage = luma(pageColor) < 128;
        // Pure black on a dark grey page and pure white on an off-white page are page too.
        if (Math.abs(difference) <= PAGE_LUMA_TOLERANCE || darkPage == (difference < 0)) return 0;
        if (Math.abs(difference) > MAX_RAISED_LUMA_DIFFERENCE) return color;

        final int alpha = Math.min(MAX_RAISED_ALPHA, Math.abs(difference) * 2 + 8);
        return alpha << 24 | (darkPage ? 0xFFFFFF : 0x000000);
    }
}
