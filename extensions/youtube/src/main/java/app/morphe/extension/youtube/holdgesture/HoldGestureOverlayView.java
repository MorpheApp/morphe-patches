/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Draws the speed pill, the lock target and the lock badge over the player.
 * Takes no touches. The controller tells it what to show.
 */
final class HoldGestureOverlayView extends View {
    private static final int SURFACE = 0xCC101218;
    private static final int LABEL_SURFACE = 0x8C000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFFF4E45;
    private static final int PALE_CYAN = 0xFF9BDCF5;
    private static final float BORDER_ALPHA = 0.18f;

    /** Player height the sizes are designed for. A lower player shrinks everything. */
    private static final float DESIGN_HEIGHT_DP = 200;
    private static final float MIN_SCALE = 0.8f;

    private static final float PILL_HEIGHT = 30;
    private static final float PILL_EXPANDED_WIDTH = 190;
    private static final float PILL_EXPANDED_HEIGHT = 58;
    private static final float RULER_HALF_WIDTH = 79;
    private static final float RULER_TICK_SPACING = 13;
    private static final float LOCK_RADIUS = 21;
    private static final float LOCK_RADIUS_ARMED = 24;
    private static final float LOCK_ICON_SIZE = 18;
    private static final float LABEL_HEIGHT = 20;
    private static final float BADGE_HEIGHT = 26;
    /** A finger needs more room than the badge is tall. */
    private static final float BADGE_TOUCH_HEIGHT = 44;
    /** Free room to keep between the badge and a player control. */
    private static final float BADGE_CLEARANCE = 6;
    /** Larger views are layouts, backgrounds and the video, which the badge may cover. */
    private static final float WIDEST_CONTROL = 0.6f;
    private static final float TALLEST_CONTROL = 0.4f;
    /** Draws texts such as the video title itself, also when it has children. */
    private static final String LITHO_HOST_CLASS = "com.facebook.litho.ComponentHost";

    private static final long ARROW_PULSE_MS = 900;
    /** The ruler folds away when the speed has not changed for this long, to uncover the video. */
    private static final long RULER_IDLE_MS = 1200;
    private static final long RIPPLE_MS = 450;
    private static final long THROTTLE_PULSE_MS = 520;
    private static final int THROTTLE_GLOW_STRIPS = 24;

    private final float density;
    private float scale = 1;
    private boolean fullscreen;
    private float topInset;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF badgeBounds = new RectF();

    private final Spring pillShown = new Spring(380, 0.7f);
    private final Spring pillExpanded = new Spring(300, 0.85f);
    private final Spring speedBump = new Spring(600, 0.45f);
    private final Spring rulerPosition = new Spring(420, 0.9f);
    private final Spring accent = new Spring(200, 1);
    private final Spring lockShown = new Spring(380, 0.7f);
    private final Spring lockArmed = new Spring(500, 0.6f);
    private final Spring badgeShown = new Spring(300, 1);
    /** Where the badge is, as a distance from the top center of the player. */
    private final Spring badgeShiftX = new Spring(300, 0.9f);
    private final Spring badgeShiftY = new Spring(300, 0.9f);
    private final Spring throttleGlow = new Spring(380, 1);
    private final Spring coasting = new Spring(380, 1);
    private final Spring[] springs = {pillShown, pillExpanded, speedBump, rulerPosition, accent,
            lockShown, lockArmed, badgeShown, badgeShiftX, badgeShiftY, throttleGlow, coasting};

    private float speedStep = 0.25f;
    private float minSpeed = 0.25f;
    private float maxSpeed = 4;
    private String speedText = "";
    /** Whether a sideways drag is on, during which a change of speed shows the ruler. */
    private boolean rulerInUse;
    private long rulerUsedAt;
    private int throttleDirection;
    private float lockProgress;
    private boolean lockIsArmed;
    private String lockLabel = "";
    private long rippleStart;
    private float badgeSpeed;
    private boolean badgeLocked;
    private String badgeText = "";
    /** The player controls come and go with a layout pass, and the badge makes room for them. */
    private final ViewTreeObserver.OnGlobalLayoutListener placeBadgeOnLayout = this::placeBadge;

    private long lastFrame;

    HoldGestureOverlayView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        // Digits of the same width, so that the pill does not change its width with the speed.
        text.setFontFeatureSettings("tnum");
    }

    // region what to show

    void setFullscreen(boolean fullscreen) {
        if (this.fullscreen != fullscreen) {
            this.fullscreen = fullscreen;
            invalidate();
        }
    }

    void setSpeedRange(float step, float min, float max) {
        speedStep = step;
        minSpeed = min;
        maxSpeed = max;
    }

    void showPill(float speed) {
        setSpeedValue(speed);
        rulerPosition.snapTo(speed / speedStep);
        accent.snapTo(accentOf(speed));
        pillExpanded.snapTo(0);
        throttleGlow.snapTo(0);
        coasting.snapTo(0);
        rulerInUse = false;
        pillShown.setTarget(1);
        invalidate();
    }

    /**
     * Grows the pill to show the ruler, until the speed stays the same for a while.
     */
    void showRuler() {
        rulerInUse = true;
        rulerUsedAt = SystemClock.uptimeMillis();
        pillExpanded.setTarget(1);
        invalidate();
    }

    void setSpeed(float speed) {
        setSpeedValue(speed);
        if (rulerInUse) {
            showRuler();
        }
        rulerPosition.setTarget(speed / speedStep);
        accent.setTarget(accentOf(speed));
        speedBump.kick(6);
        invalidate();
    }

    /**
     * Shows how a sideways drag changes the speed: a glow at the end of the ruler toward which
     * the speed keeps moving, and a pale ruler while moving the finger does not change it.
     */
    void setAdjust(HoldGesture.Adjust adjust, int direction) {
        if (adjust == HoldGesture.Adjust.THROTTLE) {
            throttleDirection = direction;
        }
        throttleGlow.setTarget(adjust == HoldGesture.Adjust.THROTTLE ? 1 : 0);
        coasting.setTarget(adjust == HoldGesture.Adjust.COASTING ? 1 : 0);
        if (rulerInUse && adjust != HoldGesture.Adjust.PRECISE) {
            showRuler();
        }
        invalidate();
    }

    void hidePill() {
        pillShown.setTarget(0);
        invalidate();
    }

    void showLockTarget() {
        lockProgress = 0;
        lockIsArmed = false;
        lockLabel = str("morphe_hold_gesture_lock_hint");
        lockArmed.snapTo(0);
        lockShown.setTarget(1);
        invalidate();
    }

    void setLockProgress(float progress, boolean armed) {
        lockProgress = progress;
        if (armed != lockIsArmed) {
            if (armed) {
                rippleStart = SystemClock.uptimeMillis();
            }
            lockLabel = armed
                    ? str("morphe_hold_gesture_lock_release", speedText)
                    : str("morphe_hold_gesture_lock_hint");
        }
        lockIsArmed = armed;
        lockArmed.setTarget(armed ? 1 : 0);
        invalidate();
    }

    void hideLockTarget() {
        lockShown.setTarget(0);
        invalidate();
    }

    /**
     * @param locked False shows an open lock, for the moment after the lock ended.
     * @param alpha  0 hides the badge.
     */
    void setBadge(float speed, boolean locked, float alpha) {
        badgeShown.setTarget(alpha);
        if (alpha > 0) {
            if (badgeSpeed != speed) {
                badgeSpeed = speed;
                badgeText = VideoInformation.formatSpeedStringX(speed);
            }
            badgeLocked = locked;
            placeBadge();
        }
        invalidate();
    }

    private void placeBadge() {
        if (badgeShown.target() <= 0) return;

        final float[] shift = {0, 0};
        findFreePlaceForBadge(shift);
        if (shift[0] == badgeShiftX.target() && shift[1] == badgeShiftY.target()) return;

        if (badgeShown.value() <= 0.01f) {
            badgeShiftX.snapTo(shift[0]);
            badgeShiftY.snapTo(shift[1]);
        } else {
            badgeShiftX.setTarget(shift[0]);
            badgeShiftY.setTarget(shift[1]);
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(placeBadgeOnLayout);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(placeBadgeOnLayout);
        super.onDetachedFromWindow();
    }

    /**
     * @return Whether the point, in the coordinates of this view, is on the badge that ends the lock.
     */
    boolean isOnBadge(float x, float y) {
        if (!badgeLocked || badgeShown.target() <= 0 || badgeBounds.isEmpty()) return false;
        // Not wider, since a player control can be right next to the badge.
        final float grow = Math.max(0, (dp(BADGE_TOUCH_HEIGHT) - badgeBounds.height()) / 2);
        return x >= badgeBounds.left && x <= badgeBounds.right
                && y >= badgeBounds.top - grow && y <= badgeBounds.bottom + grow;
    }

    /**
     * Looks for the place closest to the top center of the player where the badge covers no control:
     * first sideways in the same row, then below the row.
     *
     * @param shift Receives the distance from the top center.
     */
    private void findFreePlaceForBadge(float[] shift) {
        final float width = badgeWidth() + 2 * dp(BADGE_CLEARANCE);
        final float rowTop = topMargin() - dp(BADGE_CLEARANCE);
        final float rowBottom = topMargin() + dp(BADGE_HEIGHT) + dp(BADGE_CLEARANCE);

        final int[] origin = new int[2];
        getLocationInWindow(origin);
        final List<RectF> controls = new ArrayList<>();
        if (getParent() instanceof View parent) {
            collectControls(parent, origin, rowTop, rowBottom, controls);
        }
        if (controls.isEmpty()) return;

        // Every control widens the stretch where the center of the badge cannot be.
        controls.sort((a, b) -> Float.compare(a.left, b.left));
        final float center = getWidth() / 2f;
        final float highest = getWidth() - width / 2;
        float best = Float.NaN;
        float free = width / 2;
        for (int i = 0; i <= controls.size(); i++) {
            final float freeTo = i < controls.size()
                    ? Math.min(controls.get(i).left - width / 2, highest)
                    : highest;
            if (freeTo >= free) {
                final float candidate = Math.max(free, Math.min(freeTo, center));
                if (Float.isNaN(best) || Math.abs(candidate - center) < Math.abs(best - center)) {
                    best = candidate;
                }
            }
            if (i < controls.size()) {
                free = Math.max(free, controls.get(i).right + width / 2);
            }
        }

        if (!Float.isNaN(best)) {
            shift[0] = best - center;
            return;
        }
        float bottom = rowBottom;
        for (RectF control : controls) {
            bottom = Math.max(bottom, control.bottom);
        }
        shift[1] = bottom + dp(BADGE_CLEARANCE) - topMargin();
    }

    /**
     * Collects the visible controls that reach into a row of this view, in the coordinates of this view.
     * A control is a view that is small next to the player. Larger views are searched for controls.
     */
    private void collectControls(View view, int[] origin, float rowTop, float rowBottom, List<RectF> controls) {
        if (view == this || view.getVisibility() != VISIBLE || view.getWidth() == 0) return;

        final boolean wide = view.getWidth() > getWidth() * WIDEST_CONTROL;
        final boolean tall = view.getHeight() > getHeight() * TALLEST_CONTROL;
        if (wide || tall) {
            final boolean drawsContent = !tall && isLithoHost(view);
            if (!drawsContent) {
                if (view instanceof ViewGroup group) {
                    for (int i = 0, count = group.getChildCount(); i < count; i++) {
                        collectControls(group.getChildAt(i), origin, rowTop, rowBottom, controls);
                    }
                }
                return;
            }
        }

        final int[] location = new int[2];
        view.getLocationInWindow(location);
        final RectF bounds = new RectF(location[0] - origin[0], location[1] - origin[1],
                location[0] - origin[0] + view.getWidth(), location[1] - origin[1] + view.getHeight());
        if (bounds.bottom > rowTop && bounds.top < rowBottom && bounds.right > 0 && bounds.left < getWidth()) {
            controls.add(bounds);
        }
    }

    private static boolean isLithoHost(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (LITHO_HOST_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    private float badgeWidth() {
        text.setTextSize(dp(11.5f));
        return dp(12) + dp(5) + text.measureText(badgeText) + (badgeLocked ? dp(14) : 0) + dp(20);
    }

    private void setSpeedValue(float speed) {
        speedText = VideoInformation.formatSpeedStringX(speed);
    }

    /** 0 up to 2x, 1 above 2x, 2 from 3x. */
    private static float accentOf(float speed) {
        return speed >= 3 ? 2 : speed > 2 ? 1 : 0;
    }

    // endregion

    // region drawing

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        scale = Math.max(MIN_SCALE, Math.min(1, height / (DESIGN_HEIGHT_DP * density)));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateTopInset(getRootWindowInsets());
    }

    /** Keeps the pill and the badge clear of a camera cutout that the player reaches under. */
    private void updateTopInset(WindowInsets insets) {
        float inset = 0;
        if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                final int[] location = new int[2];
                getLocationInWindow(location);
                inset = Math.max(0, cutout.getSafeInsetTop() - location[1]);
            }
        }
        if (topInset != inset) {
            topInset = inset;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        final float seconds = lastFrame == 0 ? 0 : (now - lastFrame) / 1000f;
        if (rulerInUse && now - rulerUsedAt > RULER_IDLE_MS) {
            pillExpanded.setTarget(0);
        }
        boolean moving = false;
        for (Spring spring : springs) {
            moving |= spring.advance(seconds);
        }

        drawBadge(canvas);
        drawLockTarget(canvas, now);
        drawPill(canvas, now);

        // The arrows of the pill and the ripple of the lock move without a spring.
        moving |= pillShown.value() > 0.01f || now - rippleStart < RIPPLE_MS;
        if (moving) {
            lastFrame = now;
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    private void drawPill(Canvas canvas, long now) {
        final float shown = pillShown.value();
        if (shown <= 0.01f) return;
        final float alpha = clamp01(shown);
        final float expanded = pillExpanded.value();

        text.setTextSize(dp(13));
        final float textWidth = text.measureText("8.88x");
        final float arrowsWidth = dp(13);
        final float headerWidth = textWidth + dp(6) + arrowsWidth;

        final float width = lerp(headerWidth + dp(28), dp(PILL_EXPANDED_WIDTH), expanded);
        final float height = lerp(dp(PILL_HEIGHT), dp(PILL_EXPANDED_HEIGHT), expanded);
        final float radius = lerp(dp(PILL_HEIGHT / 2), dp(18), clamp01(expanded));
        final float centerX = getWidth() / 2f;
        final float top = topMargin();
        final int accentColor = accentColor();

        canvas.save();
        final float pop = 0.82f + 0.18f * shown;
        canvas.scale(pop, pop, centerX, top + height / 2);

        rect.set(centerX - width / 2, top, centerX + width / 2, top + height);
        drawSurface(canvas, radius, SURFACE, lerpColor(WHITE, accentColor, 0.5f),
                BORDER_ALPHA + 0.2f * clamp01(accent.value()), alpha);

        final float headerY = top + lerp(dp(PILL_HEIGHT / 2), dp(17), expanded);
        final float textCenterX = centerX - headerWidth / 2 + textWidth / 2;
        final float bump = 1 + speedBump.value();
        canvas.save();
        canvas.scale(bump, bump, textCenterX, headerY);
        text.setColor(withAlpha(accentColor, alpha));
        canvas.drawText(speedText, textCenterX, headerY - (text.ascent() + text.descent()) / 2, text);
        canvas.restore();

        drawArrows(canvas, centerX + headerWidth / 2 - arrowsWidth, headerY, now, alpha);

        if (expanded > 0.02f) {
            drawRuler(canvas, centerX, top + dp(31), accentColor, alpha * clamp01(expanded), now);
        }
        canvas.restore();
    }

    /** The two triangles of YouTube's own "2x" label, nudging to the right. */
    private void drawArrows(Canvas canvas, float left, float centerY, long now, float alpha) {
        final double phase = (now % ARROW_PULSE_MS) / (double) ARROW_PULSE_MS * 2 * Math.PI;
        final float pulse = (float) (0.5 - 0.5 * Math.cos(phase));
        final float size = dp(5.5f);
        final float x = left + dp(1) * pulse;

        fill.setColor(withAlpha(WHITE, alpha * (0.65f + 0.35f * pulse)));
        for (int i = 0; i < 2; i++) {
            final float start = x + i * dp(6);
            path.rewind();
            path.moveTo(start, centerY - size / 2);
            path.lineTo(start + size * 0.9f, centerY);
            path.lineTo(start, centerY + size / 2);
            path.close();
            canvas.drawPath(path, fill);
        }
    }

    private void drawRuler(Canvas canvas, float centerX, float top, int accentColor, float alpha, long now) {
        final float halfWidth = dp(RULER_HALF_WIDTH);
        final float spacing = dp(RULER_TICK_SPACING);
        final float bottom = top + dp(18);
        final float position = rulerPosition.value();
        final float pale = clamp01(coasting.value());
        final int tickColor = lerpColor(WHITE, PALE_CYAN, pale);

        final float glow = clamp01(throttleGlow.value());
        if (glow > 0.01f) {
            // Brightest at the end, with a wave that runs toward it.
            final float strip = halfWidth * 0.6f / THROTTLE_GLOW_STRIPS;
            final float wave = (now % THROTTLE_PULSE_MS) / (float) THROTTLE_PULSE_MS;
            for (int i = 0; i < THROTTLE_GLOW_STRIPS; i++) {
                final float outward = (i + 1) / (float) THROTTLE_GLOW_STRIPS;
                final float pulse = 0.65f + 0.35f * (float) Math.cos((outward - wave) * 2 * Math.PI);
                fill.setColor(withAlpha(accentColor, alpha * glow * 0.5f * outward * outward * pulse));
                final float inner = halfWidth - strip * (THROTTLE_GLOW_STRIPS - i);
                final float from = centerX + throttleDirection * inner;
                final float to = from + throttleDirection * strip;
                canvas.drawRect(Math.min(from, to), top + dp(2), Math.max(from, to), bottom, fill);
            }
        }

        stroke.setStrokeWidth(dp(1.5f));
        final int first = (int) Math.ceil(position - halfWidth / spacing);
        final int last = (int) Math.floor(position + halfWidth / spacing);
        for (int i = first; i <= last; i++) {
            final float tickSpeed = i * speedStep;
            if (tickSpeed < minSpeed - 0.001f || tickSpeed > maxSpeed + 0.001f) continue;

            final float x = centerX + (i - position) * spacing;
            // Fade out over the outer third.
            final float fade = clamp01((1 - Math.abs(x - centerX) / halfWidth) * 3);
            final boolean whole = Math.abs(tickSpeed - Math.round(tickSpeed)) < 0.001f;
            stroke.setColor(withAlpha(tickColor, alpha * fade * (whole ? 0.72f : 0.32f)));
            canvas.drawLine(x, bottom - dp(whole ? 12 : 6), x, bottom - dp(1), stroke);
        }

        fill.setColor(withAlpha(lerpColor(accentColor, PALE_CYAN, pale), alpha));
        rect.set(centerX - dp(1.5f), top, centerX + dp(1.5f), bottom);
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill);
    }

    private void drawLockTarget(Canvas canvas, long now) {
        final float shown = lockShown.value();
        if (shown <= 0.01f) return;
        final float alpha = clamp01(shown);
        final float armed = lockArmed.value();
        final float armedColor = clamp01(armed);

        final float centerX = getWidth() / 2f;
        final float rise = (1 - shown) * dp(24);
        final float labelCenterY = getHeight() - dp(fullscreen ? 28 : 18) - dp(LABEL_HEIGHT / 2) + rise;
        // The target comes to meet the finger while it is pulled.
        final float centerY = labelCenterY - dp(LABEL_HEIGHT / 2 + 6 + LOCK_RADIUS_ARMED) - lockProgress * dp(8);
        final float radius = lerp(dp(LOCK_RADIUS), dp(LOCK_RADIUS_ARMED), armed);

        canvas.save();
        final float pop = 0.7f + 0.3f * shown;
        canvas.scale(pop, pop, centerX, centerY);

        final long rippleAge = now - rippleStart;
        if (rippleAge < RIPPLE_MS) {
            final float t = rippleAge / (float) RIPPLE_MS;
            stroke.setStrokeWidth(dp(2));
            stroke.setColor(withAlpha(WHITE, alpha * 0.9f * (1 - t)));
            canvas.drawCircle(centerX, centerY, radius * (1 + 0.85f * t), stroke);
        }

        rect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        drawSurface(canvas, radius, lerpColor(SURFACE, 0xEBFF4E45, armedColor), WHITE,
                0.22f + 0.33f * armedColor, alpha);

        final float ringRadius = radius + dp(3.5f);
        stroke.setStrokeWidth(dp(2.5f));
        stroke.setColor(withAlpha(WHITE, alpha * 0.14f));
        canvas.drawCircle(centerX, centerY, ringRadius, stroke);
        if (lockProgress > 0) {
            stroke.setColor(withAlpha(lerpColor(RED, WHITE, armedColor), alpha));
            rect.set(centerX - ringRadius, centerY - ringRadius, centerX + ringRadius, centerY + ringRadius);
            canvas.drawArc(rect, -90, 360 * lockProgress, false, stroke);
        }

        drawLockIcon(canvas, centerX, centerY, dp(LOCK_ICON_SIZE), armedColor, withAlpha(WHITE, alpha));
        canvas.restore();

        final String label = lockLabel;
        text.setTextSize(dp(11));
        final float labelWidth = text.measureText(label) + dp(20);
        rect.set(centerX - labelWidth / 2, labelCenterY - dp(LABEL_HEIGHT / 2),
                centerX + labelWidth / 2, labelCenterY + dp(LABEL_HEIGHT / 2));
        fill.setColor(withAlpha(LABEL_SURFACE, alpha));
        canvas.drawRoundRect(rect, dp(LABEL_HEIGHT / 2), dp(LABEL_HEIGHT / 2), fill);
        text.setColor(withAlpha(WHITE, alpha * 0.9f));
        canvas.drawText(label, centerX, labelCenterY - (text.ascent() + text.descent()) / 2, text);
    }

    private void drawBadge(Canvas canvas) {
        final float alpha = badgeShown.value();
        if (alpha <= 0.01f) {
            badgeBounds.setEmpty();
            return;
        }

        final float width = badgeWidth();
        final float contentWidth = width - dp(20);
        final float iconSize = dp(12);
        final float crossSize = dp(7);
        final float textWidth = text.measureText(badgeText);
        final float height = dp(BADGE_HEIGHT);
        final float centerX = getWidth() / 2f + badgeShiftX.value();
        final float top = topMargin() + badgeShiftY.value() - (1 - clamp01(alpha * 2)) * dp(6);
        final float centerY = top + height / 2;

        badgeBounds.set(centerX - width / 2, top, centerX + width / 2, top + height);
        rect.set(badgeBounds);
        drawSurface(canvas, height / 2, SURFACE, WHITE, BORDER_ALPHA, alpha);

        float x = centerX - contentWidth / 2;
        drawLockIcon(canvas, x + iconSize / 2, centerY, iconSize, badgeLocked ? 1 : 0, withAlpha(WHITE, alpha));
        x += iconSize + dp(5);
        text.setColor(withAlpha(WHITE, alpha));
        canvas.drawText(badgeText, x + textWidth / 2, centerY - (text.ascent() + text.descent()) / 2, text);
        if (badgeLocked) {
            x += textWidth + dp(7);
            stroke.setStrokeWidth(dp(1.4f));
            stroke.setColor(withAlpha(WHITE, alpha * 0.7f));
            final float half = crossSize / 2;
            final float crossX = x + half;
            canvas.drawLine(crossX - half, centerY - half, crossX + half, centerY + half, stroke);
            canvas.drawLine(crossX - half, centerY + half, crossX + half, centerY - half, stroke);
        }
    }

    /**
     * A padlock drawn with lines.
     *
     * @param closed 0 leaves the shackle open on the right, 1 closes it.
     */
    private void drawLockIcon(Canvas canvas, float centerX, float centerY, float size, float closed, int color) {
        // Coordinates of a 24 by 24 icon.
        final float unit = size / 24;
        final float left = centerX - size / 2;
        final float top = centerY - size / 2;

        stroke.setStrokeWidth(2.2f * unit);
        stroke.setColor(color);
        rect.set(left + 5 * unit, top + 11 * unit, left + 19 * unit, top + 21 * unit);
        canvas.drawRoundRect(rect, 2.5f * unit, 2.5f * unit, stroke);

        path.rewind();
        path.moveTo(left + 8 * unit, top + 11 * unit);
        path.lineTo(left + 8 * unit, top + 7 * unit);
        rect.set(left + 8 * unit, top + 3 * unit, left + 16 * unit, top + 11 * unit);
        path.arcTo(rect, 180, lerp(150, 180, closed));
        if (closed > 0) {
            path.rLineTo(0, 4 * unit * closed);
        }
        canvas.drawPath(path, stroke);
    }

    /** A dark see-through shape with a thin light border and a soft shadow, drawn into {@link #rect}. */
    private void drawSurface(Canvas canvas, float radius, int color, int borderColor, float borderAlpha, float alpha) {
        shadowFill.setColor(withAlpha(color, alpha));
        shadowFill.setShadowLayer(dp(8), 0, dp(3), withAlpha(0x66000000, alpha));
        canvas.drawRoundRect(rect, radius, radius, shadowFill);

        stroke.setStrokeWidth(dp(1));
        stroke.setColor(withAlpha(borderColor, alpha * borderAlpha));
        final float inset = dp(0.5f);
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius - inset, radius - inset, stroke);
        rect.inset(-inset, -inset);
    }

    private int accentColor() {
        final float value = accent.value();
        return value <= 1 ? lerpColor(WHITE, AMBER, clamp01(value)) : lerpColor(AMBER, RED, clamp01(value - 1));
    }

    private float topMargin() {
        return topInset + dp(fullscreen ? 16 : 10);
    }

    private float dp(float value) {
        return value * density * scale;
    }

    // endregion

    private static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static int withAlpha(int color, float alpha) {
        final int scaled = Math.round((color >>> 24) * clamp01(alpha));
        return (scaled << 24) | (color & 0x00FFFFFF);
    }

    private static int lerpColor(int from, int to, float t) {
        final int a = Math.round(lerp(from >>> 24, to >>> 24, t));
        final int r = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t));
        final int g = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t));
        final int b = Math.round(lerp(from & 0xFF, to & 0xFF, t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
