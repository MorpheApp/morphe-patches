/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4208
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/**
 * Separable box blur of opaque ARGB pixels. Three passes approximate a gaussian blur.
 * Meant for tiny buffers, where it is cheaper than any GPU blur available on the minimum SDK.
 */
final class BoxBlur {

    private static final int PASSES = 3;

    private BoxBlur() {
    }

    /**
     * @param temp Scratch buffer of the same size as {@code src}.
     * @param dst  Can be the same array as {@code src}.
     */
    static void blur(int[] src, int[] temp, int[] dst, int width, int height,
                     int radiusX, int radiusY) {
        int[] input = src;
        for (int pass = 0; pass < PASSES; pass++) {
            blurHorizontal(input, temp, width, height, radiusX);
            blurVertical(temp, dst, width, height, radiusY);
            input = dst;
        }
    }

    private static void blurHorizontal(int[] src, int[] dst, int width, int height, int radius) {
        final int div = 2 * radius + 1;
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            final int first = src[row];
            int rSum = ((first >> 16) & 0xFF) * (radius + 1);
            int gSum = ((first >> 8) & 0xFF) * (radius + 1);
            int bSum = (first & 0xFF) * (radius + 1);

            for (int i = 1; i <= radius; i++) {
                int p = src[row + Math.min(i, width - 1)];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int x = 0; x < width; x++) {
                dst[row + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);

                int pIn = src[row + Math.min(x + radius + 1, width - 1)];
                int pOut = src[row + Math.max(x - radius, 0)];
                rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bSum += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int width, int height, int radius) {
        final int div = 2 * radius + 1;
        for (int x = 0; x < width; x++) {
            final int first = src[x];
            int rSum = ((first >> 16) & 0xFF) * (radius + 1);
            int gSum = ((first >> 8) & 0xFF) * (radius + 1);
            int bSum = (first & 0xFF) * (radius + 1);

            for (int i = 1; i <= radius; i++) {
                int p = src[Math.min(i, height - 1) * width + x];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int y = 0; y < height; y++) {
                dst[y * width + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);

                int pIn = src[Math.min(y + radius + 1, height - 1) * width + x];
                int pOut = src[Math.max(y - radius, 0) * width + x];
                rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bSum += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }
}
