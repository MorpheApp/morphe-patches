/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * Builds crisp vector silhouettes for fetching, outro, and dynamic gap indicators in
 * {@link PlayerLiveLyricsView}.
 */
final class PlayerLiveLyricsArtwork {

    private PlayerLiveLyricsArtwork() {
    }

    static void buildSingleNotePathInto(Path target, float widthPx, float heightPx) {
        target.reset();
        final float rx = widthPx * 0.25f;
        final float ry = heightPx * 0.13f;
        final float headCx = widthPx * 0.33f;
        final float headCy = heightPx * 0.78f;
        final float stemW = Math.max(1.2f, heightPx * 0.11f);
        final float stemRight = headCx + rx * 0.94f;
        final float stemLeft = stemRight - stemW;
        final float topY = heightPx * 0.08f;

        target.addOval(new RectF(headCx - rx, headCy - ry, headCx + rx, headCy + ry),
                Path.Direction.CW);
        target.addRect(stemLeft, topY, stemRight, headCy, Path.Direction.CW);

        // Flowing eighth-note flag anchored cleanly to the stem top.
        target.moveTo(stemLeft, topY);
        target.cubicTo(stemRight + widthPx * 0.26f, heightPx * 0.16f,
                widthPx * 0.98f, heightPx * 0.30f,
                widthPx * 0.86f, heightPx * 0.50f);
        target.cubicTo(widthPx * 0.84f, heightPx * 0.36f,
                stemRight + widthPx * 0.14f, heightPx * 0.28f,
                stemRight, topY + heightPx * 0.17f);
        target.close();
    }

    static void buildDoubleNotePathInto(Path target, float sizePx) {
        target.reset();
        final float headW = sizePx * 0.34f;
        final float headH = sizePx * 0.24f;
        final float stemW = Math.max(1.2f, sizePx * 0.11f);
        final float beamH = Math.max(1.6f, sizePx * 0.17f);

        final float leftCx = sizePx * 0.20f;
        final float leftCy = sizePx * 0.80f;
        final float rightCx = sizePx * 0.78f;
        final float rightCy = sizePx * 0.68f;

        target.addOval(new RectF(leftCx - headW * 0.5f, leftCy - headH * 0.5f,
                leftCx + headW * 0.5f, leftCy + headH * 0.5f), Path.Direction.CW);
        target.addOval(new RectF(rightCx - headW * 0.5f, rightCy - headH * 0.5f,
                rightCx + headW * 0.5f, rightCy + headH * 0.5f), Path.Direction.CW);

        final float leftStemX = leftCx + headW * 0.34f - stemW;
        final float rightStemX = rightCx + headW * 0.34f - stemW;
        final float leftTopY = sizePx * 0.16f;
        final float rightTopY = sizePx * 0.04f;

        target.addRect(leftStemX, leftTopY, leftStemX + stemW, leftCy, Path.Direction.CW);
        target.addRect(rightStemX, rightTopY, rightStemX + stemW, rightCy, Path.Direction.CW);

        target.moveTo(leftStemX, leftTopY);
        target.lineTo(rightStemX + stemW, rightTopY);
        target.lineTo(rightStemX + stemW, rightTopY + beamH);
        target.lineTo(leftStemX, leftTopY + beamH);
        target.close();
    }
}
