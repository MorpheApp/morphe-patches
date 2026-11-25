/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches.glassmenus;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * The window of the activity behind a menu, blurred by the menu itself, for while the system does not blur behind
 * the window of the menu, such as with the battery saver on. The views are recorded into a blurred render node each
 * time the menu or the activity is drawn. The node only refers to the drawings of the views, so it is fast.
 * Videos and other windows are not part of it.
 * <p>
 * Android 14 and later only: on Android 12 and 13 a blur render effect that is redrawn can crash the app.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
final class AppBackdropDrawable extends Drawable {

    /**
     * How long the dim of the window takes to fade in.
     */
    private static final long DIM_FADE_MS = 200;

    private final View menu;
    private final RenderNode node = new RenderNode("GlassMenuBackdrop");
    private final ViewTreeObserver.OnDrawListener appDrawListener;
    private final int[] location = new int[2];
    private final int[] drawnLocation = new int[2];
    private final int[] appLocation = new int[2];

    /**
     * The window of the activity while it is drawn, otherwise null.
     */
    @Nullable
    private View app;
    private long shownTime;

    AppBackdropDrawable(View menu, float blurRadius) {
        this.menu = menu;
        node.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP));
        appDrawListener = menu::postInvalidateOnAnimation;
    }

    /**
     * @return If the blurred app is drawn.
     */
    boolean isShown() {
        return app != null;
    }

    /**
     * Stays hidden if the menu has no window of an activity behind it.
     */
    void setShown(boolean shown) {
        if (shown == isShown()) return;

        if (app != null) {
            app.getViewTreeObserver().removeOnDrawListener(appDrawListener);
            app = null;
            return;
        }

        app = windowBehind(menu);
        if (app != null) {
            app.getViewTreeObserver().addOnDrawListener(appDrawListener);
            shownTime = SystemClock.uptimeMillis();
        }
    }

    @Nullable
    private static View windowBehind(View menu) {
        Context context = menu.getContext();
        while (context instanceof ContextWrapper wrapper) {
            if (context instanceof Activity activity) {
                View decor = activity.getWindow().peekDecorView();
                // A menu in the window of the activity would draw itself into its own background.
                return decor != menu.getRootView() ? decor : null;
            }
            context = wrapper.getBaseContext();
        }
        return null;
    }

    /**
     * A view that only moves, such as a bottom sheet that slides in, is not drawn again, but what is behind it changes.
     */
    void invalidateIfMoved() {
        if (app == null) return;
        menu.getLocationOnScreen(location);
        if (location[0] != drawnLocation[0] || location[1] != drawnLocation[1]) {
            menu.invalidate();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (app == null || !app.isAttachedToWindow() || !canvas.isHardwareAccelerated()) return;

        Rect bounds = getBounds();
        menu.getLocationOnScreen(drawnLocation);
        app.getLocationOnScreen(appLocation);
        node.setPosition(bounds);
        RecordingCanvas recording = node.beginRecording();
        try {
            // Without a color below, blurred text would be mostly clear and the sharp text would show.
            recording.drawColor(ThemeUtils.getAppBackgroundColor());
            recording.translate(appLocation[0] - drawnLocation[0] - bounds.left,
                    appLocation[1] - drawnLocation[1] - bounds.top);
            drawWithoutVideos(recording, app);
        } finally {
            node.endRecording();
        }

        final int saveCount = canvas.save();
        canvas.clipRect(bounds);
        canvas.drawRenderNode(node);
        // The system blur includes the dim behind the window.
        canvas.drawColor(Color.argb(windowDimAmount(), 0f, 0f, 0f));
        canvas.restoreToCount(saveCount);
    }

    /**
     * Draws a view without the {@link SurfaceView}s in it. A surface view moves its surface to where it is drawn,
     * so the video would be moved if it was also drawn here.
     */
    private static void drawWithoutVideos(Canvas canvas, View view) {
        if (!(view instanceof ViewGroup group) || !holdsVideo(group)) {
            view.draw(canvas);
            return;
        }

        Drawable background = group.getBackground();
        if (background != null) background.draw(canvas);
        canvas.translate(-group.getScrollX(), -group.getScrollY());
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            final float alpha = child.getAlpha();
            if (child instanceof SurfaceView || child.getVisibility() != View.VISIBLE || alpha == 0) continue;

            final int saveCount = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            if (group.getClipChildren()) canvas.clipRect(0, 0, child.getWidth(), child.getHeight());
            if (alpha < 1) canvas.saveLayerAlpha(null, Math.round(255 * alpha));
            drawWithoutVideos(canvas, child);
            canvas.restoreToCount(saveCount);
        }
    }

    private static boolean holdsVideo(ViewGroup group) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child instanceof SurfaceView || (child instanceof ViewGroup childGroup && holdsVideo(childGroup))) {
                return true;
            }
        }
        return false;
    }

    private float windowDimAmount() {
        if (!(menu.getRootView().getLayoutParams() instanceof WindowManager.LayoutParams params)
                || (params.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0) {
            return 0;
        }
        final float fade = Math.min(1, (SystemClock.uptimeMillis() - shownTime) / (float) DIM_FADE_MS);
        if (fade < 1) menu.postInvalidateOnAnimation();
        return params.dimAmount * fade;
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Deprecated
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
