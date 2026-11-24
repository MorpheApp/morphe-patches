/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static app.morphe.extension.youtube.holdgesture.Blend.clamp01;
import static app.morphe.extension.youtube.holdgesture.Blend.lerp;
import static app.morphe.extension.youtube.holdgesture.Blend.lerpColor;
import static app.morphe.extension.youtube.holdgesture.Blend.withAlpha;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

/**
 * Draws the launcher over the player: a fan of round items around a center, a line from the
 * center to the finger, and the name of the item that the finger points at.
 * Takes no touches. The controller tells it what to show.
 */
final class HoldLauncherView extends View {

    static final class Item {
        final Drawable icon;
        final String label;
        /** Whether the item is something that is switched on now. */
        final boolean on;

        Item(Drawable icon, String label, boolean on) {
            this.icon = icon;
            this.label = label;
            this.on = on;
        }
    }

    private static final int BACKDROP = 0x5906080E;
    private static final int ITEM_SURFACE = 0xDB161820;
    private static final int ITEM_INK = 0xFF111318;
    private static final int CAPTION_SURFACE = 0xE0101218;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF4E45;
    private static final int RED_INK = 0xFFFF6B63;

    private static final float ANCHOR_RADIUS = 8;
    private static final float POINTER_RADIUS = 5;
    private static final float ICON_SIZE = 20;
    /** Size of an item while the finger points at another one, next to its usual size. */
    private static final float DIMMED_SIZE = 20f / 22;
    private static final float DIMMED_ALPHA = 0.62f;
    private static final float CAPTION_HEIGHT = 26;
    private static final float CAPTION_GAP = 8;
    private static final float CAPTION_EDGE_MARGIN = 6;
    /** Sides of an item that are tried for its name. */
    private static final int CAPTION_DIRECTIONS = 8;
    /** A direction that points down more than this, as its sine, is below the item. */
    private static final float CAPTION_BELOW = 0.5f;

    private static final long STAGGER_MS = 25;
    private static final long RIPPLE_MS = 450;
    /** The item that was picked stays this long after the others have gone. */
    private static final long PICKED_STAYS_MS = 160;
    private static final long PICKED_FADE_MS = 200;
    private static final long FLASH_MS = 280;
    private static final float FLASH_ALPHA = 0.7f;

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashes = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Spring shown = new Spring(300, 1);
    private final Spring anySelected = new Spring(300, 1);
    private final Spring captionShown = new Spring(420, 0.7f);
    private final Spring[] itemOut = new Spring[RadialLayout.MAX_ITEMS];
    private final Spring[] itemSelected = new Spring[RadialLayout.MAX_ITEMS];

    @Nullable
    private RadialLayout layout;
    private Item[] items = new Item[0];
    private long openedAt;
    private float pointerX;
    private float pointerY;
    private int selected = -1;
    /** Item whose name the caption shows, also while the caption goes away. */
    private int captionItem = -1;
    private int picked = -1;
    private long closedAt;
    private long flashStart;
    private long lastFrame;

    HoldLauncherView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        stroke.setStyle(Paint.Style.STROKE);
        dashes.setStyle(Paint.Style.STROKE);
        dashes.setStrokeWidth(2 * density);
        dashes.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0));
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < itemOut.length; i++) {
            itemOut[i] = new Spring(380, 0.62f);
            itemSelected[i] = new Spring(420, 0.7f);
        }
    }

    // region what to show

    /**
     * @return The area that the launcher has to fit into, in dp: this view without what a camera
     *         cutout takes of it, where the player reaches under one.
     */
    RectF safeArea() {
        float left = 0;
        float top = 0;
        float right = getWidth();
        float bottom = getHeight();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final WindowInsets insets = getRootWindowInsets();
            final DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            if (cutout != null) {
                final int[] location = new int[2];
                getLocationInWindow(location);
                final View root = getRootView();
                left = Math.max(left, cutout.getSafeInsetLeft() - location[0]);
                top = Math.max(top, cutout.getSafeInsetTop() - location[1]);
                right = Math.min(right, root.getWidth() - cutout.getSafeInsetRight() - location[0]);
                bottom = Math.min(bottom, root.getHeight() - cutout.getSafeInsetBottom() - location[1]);
            }
        }
        return new RectF(left / density, top / density, right / density, bottom / density);
    }

    /**
     * @param layout   Positions in dp, in the coordinates of this view.
     * @param items    As many as the layout has places.
     * @param pointerX Where the finger is, as in the layout.
     */
    void open(RadialLayout layout, Item[] items, float pointerX, float pointerY) {
        this.layout = layout;
        this.items = items;
        this.pointerX = pointerX;
        this.pointerY = pointerY;
        openedAt = SystemClock.uptimeMillis();
        selected = -1;
        captionItem = -1;
        picked = -1;
        anySelected.snapTo(0);
        captionShown.snapTo(0);
        for (int i = 0; i < itemOut.length; i++) {
            itemOut[i].snapTo(0);
            itemSelected[i].snapTo(0);
        }
        shown.setTarget(1);
        invalidate();
    }

    /**
     * @param selected Index of the item that the finger points at, or -1.
     */
    void setPointer(float x, float y, int selected) {
        pointerX = x;
        pointerY = y;
        if (this.selected != selected) {
            this.selected = selected;
            for (int i = 0; i < items.length; i++) {
                itemSelected[i].setTarget(i == selected ? 1 : 0);
            }
            anySelected.setTarget(selected >= 0 ? 1 : 0);
            if (selected >= 0) {
                captionItem = selected;
                captionShown.snapTo(0);
            }
            captionShown.setTarget(selected >= 0 ? 1 : 0);
        }
        invalidate();
    }

    /**
     * @param picked Whether the item that the finger points at is run. It leaves last then.
     */
    void close(boolean picked) {
        if (layout == null || shown.target() == 0) return;

        this.picked = picked ? selected : -1;
        closedAt = SystemClock.uptimeMillis();
        shown.setTarget(0);
        captionShown.setTarget(0);
        invalidate();
    }

    /** A short white flash over the player, like the shutter of a camera. */
    void flash() {
        flashStart = SystemClock.uptimeMillis();
        invalidate();
    }

    // endregion

    // region drawing

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        final float seconds = lastFrame == 0 ? 0 : (now - lastFrame) / 1000f;
        boolean moving = shown.advance(seconds) | anySelected.advance(seconds) | captionShown.advance(seconds);

        final RadialLayout current = layout;
        final long pickedAge = picked >= 0 ? now - closedAt : Long.MAX_VALUE;
        final boolean pickedShows = pickedAge < PICKED_STAYS_MS + PICKED_FADE_MS;
        if (current != null && (shown.value() > 0.01f || pickedShows)) {
            final boolean opening = shown.target() > 0;
            for (int i = 0; i < items.length; i++) {
                if (opening && now - openedAt >= i * STAGGER_MS) {
                    itemOut[i].setTarget(1);
                } else if (opening) {
                    moving = true;
                }
                moving |= itemOut[i].advance(seconds) | itemSelected[i].advance(seconds);
            }
            drawLauncher(canvas, current, now, pickedAge);
            moving |= pickedShows;
        } else if (shown.target() == 0) {
            layout = null;
        }

        final long flashAge = now - flashStart;
        if (flashAge < FLASH_MS) {
            canvas.drawColor(withAlpha(WHITE, FLASH_ALPHA * (1 - flashAge / (float) FLASH_MS)));
            moving = true;
        }

        if (moving) {
            lastFrame = now;
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    private void drawLauncher(Canvas canvas, RadialLayout layout, long now, long pickedAge) {
        final float alpha = clamp01(shown.value());
        final float scale = layout.scale;
        final float centerX = layout.centerX * density;
        final float centerY = layout.centerY * density;

        canvas.drawColor(withAlpha(BACKDROP, alpha));

        dashes.setColor(withAlpha(WHITE, alpha * 0.38f));
        canvas.drawLine(centerX, centerY, pointerX * density, pointerY * density, dashes);
        fill.setColor(withAlpha(WHITE, alpha * 0.85f));
        canvas.drawCircle(pointerX * density, pointerY * density, POINTER_RADIUS * scale * density, fill);

        fill.setColor(withAlpha(WHITE, alpha * 0.18f));
        canvas.drawCircle(centerX, centerY, ANCHOR_RADIUS * scale * density, fill);
        stroke.setStrokeWidth(2 * density);
        stroke.setColor(withAlpha(WHITE, alpha * 0.75f));
        canvas.drawCircle(centerX, centerY, ANCHOR_RADIUS * scale * density, stroke);

        final float dimmed = clamp01(anySelected.value());
        for (int i = 0; i < items.length; i++) {
            float itemAlpha = alpha;
            if (i == picked) {
                itemAlpha = Math.max(alpha, 1 - clamp01((pickedAge - PICKED_STAYS_MS) / (float) PICKED_FADE_MS));
                if (pickedAge < RIPPLE_MS) {
                    final float t = pickedAge / (float) RIPPLE_MS;
                    stroke.setStrokeWidth(2 * density);
                    stroke.setColor(withAlpha(WHITE, 0.9f * (1 - t)));
                    canvas.drawCircle(layout.itemX[i] * density, layout.itemY[i] * density,
                            RadialLayout.ITEM_RADIUS_SELECTED * scale * density * (1 + 0.7f * t), stroke);
                }
            }
            drawItem(canvas, layout, i, itemAlpha, dimmed);
        }

        if (captionItem >= 0 && captionItem < items.length && captionShown.value() > 0.01f) {
            drawCaption(canvas, layout, captionItem, alpha);
        }
    }

    private void drawItem(Canvas canvas, RadialLayout layout, int index, float alpha, float dimmed) {
        final Item item = items[index];
        final float out = itemOut[index].value();
        if (out <= 0.01f || alpha <= 0.01f) return;

        final float select = itemSelected[index].value();
        final float selectColor = clamp01(select);
        final float dim = dimmed * (1 - selectColor);
        final float x = lerp(layout.centerX, layout.itemX[index], out) * density;
        final float y = lerp(layout.centerY, layout.itemY[index], out) * density;
        final float size = lerp(lerp(1, DIMMED_SIZE, dim), RadialLayout.ITEM_RADIUS_SELECTED / RadialLayout.ITEM_RADIUS,
                select) * (0.2f + 0.8f * out) * layout.scale * density;
        final float radius = RadialLayout.ITEM_RADIUS * size;
        final float visible = alpha * clamp01(out * 1.6f) * lerp(1, DIMMED_ALPHA, dim);

        surface.setColor(withAlpha(lerpColor(ITEM_SURFACE, WHITE, selectColor), visible));
        surface.setShadowLayer(lerp(8, 13, selectColor) * density, 0, lerp(3, 0, selectColor) * density,
                withAlpha(lerpColor(0x73000000, 0x8CFFFFFF, selectColor), visible));
        canvas.drawCircle(x, y, radius, surface);

        stroke.setStrokeWidth(1.5f * density);
        final int border = item.on ? RED : withAlpha(WHITE, 0.22f);
        stroke.setColor(withAlpha(lerpColor(border, WHITE, selectColor), visible));
        canvas.drawCircle(x, y, radius - 0.75f * density, stroke);

        final int half = Math.round(ICON_SIZE / 2 * size);
        final Drawable icon = item.icon;
        icon.setBounds(Math.round(x) - half, Math.round(y) - half, Math.round(x) + half, Math.round(y) + half);
        icon.setTint(lerpColor(item.on ? RED_INK : WHITE, ITEM_INK, selectColor));
        icon.setAlpha(Math.round(255 * clamp01(visible)));
        icon.draw(canvas);
    }

    /**
     * The name goes next to the item, on the side away from the center, which the hand does not
     * cover. Where the player ends there or another item is in the way, it goes to the nearest
     * side that is free.
     */
    private void drawCaption(Canvas canvas, RadialLayout layout, int index, float alpha) {
        final float scale = layout.scale * density;
        final float show = captionShown.value();
        final String label = items[index].label;
        text.setTextSize(12 * scale);
        final float width = text.measureText(label) + 26 * scale;
        final float height = CAPTION_HEIGHT * scale;
        final float margin = CAPTION_EDGE_MARGIN * density;
        final float itemX = layout.itemX[index] * density;
        final float itemY = layout.itemY[index] * density;
        final float reach = (RadialLayout.ITEM_RADIUS_SELECTED + CAPTION_GAP) * scale;
        final double outward = Math.atan2(itemY - layout.centerY * density, itemX - layout.centerX * density);

        // Outward, as far as there is room, if no side is free, such as in a very small player.
        float x = itemX + (float) Math.cos(outward) * (reach + width / 2);
        float y = itemY + (float) Math.sin(outward) * (reach + height / 2);
        // Outward first, then turned away from that by an eighth of a turn more each time, to both sides.
        // Below the item is where the hand is, so that is for when no other side is free.
        for (int turn = 0; turn < 2 * CAPTION_DIRECTIONS; turn++) {
            final boolean alsoBelow = turn >= CAPTION_DIRECTIONS;
            final int step = turn % CAPTION_DIRECTIONS;
            final double angle = outward
                    + (step % 2 == 0 ? 1 : -1) * ((step + 1) / 2) * 2 * Math.PI / CAPTION_DIRECTIONS;
            final float towardX = (float) Math.cos(angle);
            final float towardY = (float) Math.sin(angle);
            if (towardY > CAPTION_BELOW != alsoBelow) continue;

            // As far out as it takes to clear the item in that direction.
            final float distance = reach + Math.abs(towardX) * width / 2 + Math.abs(towardY) * height / 2;
            final float sideX = itemX + towardX * distance;
            final float sideY = itemY + towardY * distance;
            if (captionFits(layout, index, sideX, sideY, width, height, margin)) {
                x = sideX;
                y = sideY;
                break;
            }
        }
        x = Math.max(margin + width / 2, Math.min(getWidth() - margin - width / 2, x));
        y = Math.max(margin + height / 2, Math.min(getHeight() - margin - height / 2, y));

        final float visible = alpha * clamp01(show);
        canvas.save();
        final float pop = 0.9f + 0.1f * show;
        canvas.scale(pop, pop, x, y);
        rect.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
        surface.setColor(withAlpha(CAPTION_SURFACE, visible));
        surface.setShadowLayer(8 * density, 0, 3 * density, withAlpha(0x73000000, visible));
        canvas.drawRoundRect(rect, height / 2, height / 2, surface);
        stroke.setStrokeWidth(density);
        stroke.setColor(withAlpha(WHITE, visible * 0.22f));
        rect.inset(density / 2, density / 2);
        canvas.drawRoundRect(rect, height / 2, height / 2, stroke);
        text.setColor(withAlpha(WHITE, visible));
        canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2, text);
        canvas.restore();
    }

    /**
     * @return Whether the name of an item is inside of this view there and covers no item.
     */
    private boolean captionFits(RadialLayout layout, int index, float x, float y,
                                float width, float height, float margin) {
        if (x - width / 2 < margin || x + width / 2 > getWidth() - margin
                || y - height / 2 < margin || y + height / 2 > getHeight() - margin) {
            return false;
        }
        for (int i = 0; i < items.length; i++) {
            final float radius = (i == index ? RadialLayout.ITEM_RADIUS_SELECTED : RadialLayout.ITEM_RADIUS)
                    * layout.scale * density;
            // Distance from the middle of the item to the nearest point of the name.
            final float nearX = Math.max(Math.abs(layout.itemX[i] * density - x) - width / 2, 0);
            final float nearY = Math.max(Math.abs(layout.itemY[i] * density - y) - height / 2, 0);
            if (nearX * nearX + nearY * nearY < radius * radius) return false;
        }
        return true;
    }

    // endregion
}
