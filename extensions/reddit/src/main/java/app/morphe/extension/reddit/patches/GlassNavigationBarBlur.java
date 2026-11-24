/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.WeakHashMap;
import kotlin.jvm.functions.Function1;

import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.GlassStyle;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class GlassNavigationBarBlur {

    // Upstream Morphe found that a blur render effect redrawn every frame can crash the app on Android 12/13.
    private static final int MIN_SDK = 34;
    private static final float MAX_BLUR_DP = 32f;
    private static final float EDGE_DP = 1f;

    private static final Path CLIP_PATH = new Path();
    private static final WeakHashMap<View, Boolean> DECOR_VIEWS = new WeakHashMap<>();

    static ContentRecorder current;
    static ContentRecorder recording;

    private static float barLeft;
    private static float barTop;
    private static float barW;
    private static float barH;

    private static RenderNode blurNode;
    private static float cachedBlurRadius = -1f;

    private static Paint edgePaint;
    private static LinearGradient edgeShader;
    private static float cachedEdgeTop = -1f;
    private static float cachedEdgeHeight = -1f;
    private static boolean cachedEdgeDark;

    private GlassNavigationBarBlur() {}

    static boolean glassOn() {
        try {
            return GlassNavigationBarPatch.GLASS_ENABLED;
        } catch (Throwable ex) {
            Logger.printException(() -> "glassOn failure", ex);
            return false;
        }
    }

    private static int getBlurPercent() {
        final int val = Settings.GLASS_NAVIGATION_BAR_BLUR.get();
        return (val < 0 || val > 100) ? 60 : val;
    }

    static float blurRadiusPx() {
        try {
            return getBlurPercent() / 100f * MAX_BLUR_DP * GlassNavigationBarPatch.getDensity();
        } catch (Throwable ex) {
            Logger.printException(() -> "blurRadiusPx failure", ex);
            return 0f;
        }
    }

    static boolean blurActive() {
        try {
            return Build.VERSION.SDK_INT >= MIN_SDK
                    && glassOn()
                    && Settings.GLASS_NAVIGATION_BAR_STYLE.get() == GlassStyle.FROSTED
                    && blurRadiusPx() > 0f;
        } catch (Throwable ex) {
            Logger.printException(() -> "blurActive failure", ex);
            return false;
        }
    }

    public static Object contentModifier(Object modifier) {
        try {
            if (Build.VERSION.SDK_INT < MIN_SDK || !glassOn()) {
                return modifier;
            }
            return drawWithContent(modifier, new ContentRecorder());
        } catch (Throwable ex) {
            Logger.printException(() -> "contentModifier failure", ex);
            return modifier;
        }
    }

    private static final class ContentRecorder implements Function1<Object, Object> {
        final RenderNode node = new RenderNode("bin0culars_glass_content");
        boolean holdsBar;
        int width;
        int height;

        @Override
        public Object invoke(Object scope) {
            boolean drawn = false;
            try {
                if (!blurActive()) {
                    drawn = true;
                    ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_drawContent();
                    return null;
                }
                Object cc = composeCanvas(scope);
                Canvas real = nativeCanvas(cc);
                if (real == null || !real.isHardwareAccelerated()) {
                    drawn = true;
                    ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_drawContent();
                    return null;
                }
                long size = ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_getSize();
                float widthFloat = Float.intBitsToFloat((int) (size >>> 32));
                float heightFloat = Float.intBitsToFloat((int) size);
                int w = (int) Math.ceil(widthFloat);
                int h = (int) Math.ceil(heightFloat);
                if (w <= 0 || h <= 0) {
                    drawn = true;
                    ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_drawContent();
                    return null;
                }

                node.setPosition(0, 0, w, h);
                RecordingCanvas rec = node.beginRecording(w, h);
                ContentRecorder previous = recording;
                recording = this;
                // Swap Compose's native canvas with the RenderNode's RecordingCanvas to capture content drawing.
                setNativeCanvas(cc, rec);
                try {
                    drawn = true;
                    ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_drawContent();
                } finally {
                    setNativeCanvas(cc, real);
                    node.endRecording();
                    recording = previous;
                }
                real.drawRenderNode(node);
                if (!holdsBar) {
                    current = this;
                    width = w;
                    height = h;
                }
            } catch (Throwable ignored) {
                if (!drawn) {
                    try {
                        ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_drawContent();
                    } catch (Throwable ignored2) {
                    }
                }
            }
            return null;
        }
    }

    static void drawBar(Object scope, float left, float top, float cw, float ch, float radius) {
        try {
            if (recording != null) {
                recording.holdsBar = true;
                if (current == recording) {
                    current = null;
                }
                return;
            }
            if (!blurActive() || current == null) {
                return;
            }
            Canvas real = nativeCanvas(composeCanvas(scope));
            if (real == null || !real.isHardwareAccelerated()) {
                return;
            }
            long size = ((GlassNavigationBarPatch.ContentDrawScope) scope).patch_getSize();
            float bh = Float.intBitsToFloat((int) size);

            barLeft = left;
            barTop = (current.height - bh) + top;
            barW = cw;
            barH = ch;

            recordBlur();

            int m = (int) Math.ceil(blurRadiusPx());
            real.save();
            try {
                CLIP_PATH.reset();
                CLIP_PATH.addRoundRect(left, top, left + cw, top + ch, radius, radius, Path.Direction.CW);
                real.clipPath(CLIP_PATH);
                real.translate(left - m, top - m);
                real.drawRenderNode(blurNode);
            } finally {
                real.restore();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int pageArgb() {
        int c = (int) (GlassNavigationBarPatch.lastPageColor >>> 32);
        if (c == 0) {
            return GlassNavigationBarPatch.isDarkTheme ? 0xFF000000 : 0xFFFFFFFF;
        }
        return c | 0xFF000000;
    }

    private static void recordBlur() {
        try {
            if (current == null) {
                return;
            }
            float r = blurRadiusPx();
            if (r <= 0f) {
                return;
            }
            int m = (int) Math.ceil(r);
            int w = (int) Math.ceil(barW) + 2 * m;
            int h = (int) Math.ceil(barH) + 2 * m;
            if (w <= 0 || h <= 0) {
                return;
            }
            if (blurNode == null) {
                blurNode = new RenderNode("bin0culars_glass_blur");
            }
            blurNode.setPosition(0, 0, w, h);
            if (cachedBlurRadius != r) {
                ColorMatrix boost = new ColorMatrix();
                boost.setSaturation(1.4f);
                RenderEffect effect = RenderEffect.createChainEffect(
                        RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP),
                        RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(boost)));
                blurNode.setRenderEffect(effect);
                cachedBlurRadius = r;
            }
            RecordingCanvas rec = blurNode.beginRecording(w, h);
            try {
                rec.drawColor(pageArgb());
                rec.translate(-(barLeft - m), -(barTop - m));
                rec.drawRenderNode(current.node);
            } finally {
                blurNode.endRecording();
            }
        } catch (Throwable ignored) {
        }
    }

    static void drawEdge(Object scope, float left, float top, float cw, float ch, float radius) {
        try {
            if (!blurActive() || recording != null) {
                return;
            }
            Canvas real = nativeCanvas(composeCanvas(scope));
            if (real == null) {
                return;
            }
            float density = GlassNavigationBarPatch.getDensity();
            float strokeWidth = EDGE_DP * density;
            if (edgePaint == null) {
                edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                edgePaint.setStyle(Paint.Style.STROKE);
            }
            edgePaint.setStrokeWidth(strokeWidth);

            boolean isDark = GlassNavigationBarPatch.glassIsDark();
            if (edgeShader == null || cachedEdgeTop != top || cachedEdgeHeight != ch || cachedEdgeDark != isDark) {
                int colorStart = isDark ? 0x40FFFFFF : 0xB3FFFFFF;
                int colorEnd = isDark ? 0x0DFFFFFF : 0x14000000;
                edgeShader = new LinearGradient(0f, top, 0f, top + ch, colorStart, colorEnd, Shader.TileMode.CLAMP);
                edgePaint.setShader(edgeShader);
                cachedEdgeTop = top;
                cachedEdgeHeight = ch;
                cachedEdgeDark = isDark;
            }

            float halfStroke = strokeWidth / 2f;
            float rLeft = left + halfStroke;
            float rTop = top + halfStroke;
            float rRight = left + cw - halfStroke;
            float rBottom = top + ch - halfStroke;
            float rRadius = Math.max(0f, radius - halfStroke);
            real.drawRoundRect(rLeft, rTop, rRight, rBottom, rRadius, rRadius, edgePaint);
        } catch (Throwable ignored) {
        }
    }

    public static void onActivityResumed(Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) {
                return;
            }
            View decorView = activity.getWindow().peekDecorView();
            if (decorView == null) {
                decorView = activity.getWindow().getDecorView();
            }
            if (decorView == null || DECOR_VIEWS.containsKey(decorView)) {
                return;
            }
            DECOR_VIEWS.put(decorView, Boolean.TRUE);
            decorView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        if (blurActive() && current != null && barW > 0) {
                            recordBlur();
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "onActivityResumed failure", ex);
        }
    }

    private static Object composeCanvas(Object scope) {
        return null;
    }

    private static Canvas nativeCanvas(Object composeCanvas) {
        return null;
    }

    private static void setNativeCanvas(Object composeCanvas, Canvas canvas) {}

    @SuppressWarnings("rawtypes")
    private static Object drawWithContent(Object modifier, Function1 block) {
        return modifier;
    }
}
