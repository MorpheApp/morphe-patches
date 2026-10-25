/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.function.Supplier;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.swipecontrols.misc.Rectangle;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsUtils;

/**
 * <pre>
 *  +------------------------------------------------------------------+
 *  |                         40dp (top dead)                          |
 *  +----------+-------------+----------------+-------------+----------+
 *  |          |    left     |      top       |    right    |          |
 *  |  20dp    |    (up/dn)  |   (left/right) |    (up/dn)  |  20dp    |
 *  |          |    + top    |   speed_h%     |    + top    |          |
 *  +- - - - - +- - - - - - -+- - - - - - - - +- - - - - - -+- - - - - +
 *  |  dead    |    left     |     dead       |    right    |  dead    |
 *  |          |   zone_w%   |   100-2*z%     |   zone_w%   |          |
 *  +----------+-------------+----------------+-------------+----------+
 *  |                        60dp (bottom dead)                        |
 *  +------------------------------------------------------------------+
 *
 *  left / right - vertical swipe (up/down), span full effective height
 *  top          - horizontal swipe (left/right), top speed_h% of effective height;
 *                 overlaps with the top of the left and right zones
 *
 *  Each zone performs the action it is assigned in the settings.
 * </pre>
 */
public final class SwipeZonesController {

    private final Activity host;

    /**
     * Provides the screen rectangle, used until the player bounds are known.
     */
    private final Supplier<Rectangle> fallbackScreenRect;

    /**
     * 20dp, in pixels.
     */
    private final int dp20;

    /**
     * 40dp, in pixels.
     */
    private final int dp40;

    /**
     * 80dp, in pixels.
     */
    private final int dp80;

    /**
     * ID for R.id.inset_controls_overlay_wrapper.
     */
    private final int sizeAdjustableOverlayId;

    /**
     * Current bounding rectangle of the player.
     */
    @Nullable
    private Rectangle playerRect;

    public SwipeZonesController(Activity host, Supplier<Rectangle> fallbackScreenRect) {
        this.host = host;
        this.fallbackScreenRect = fallbackScreenRect;
        dp20 = SwipeControlsUtils.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP, 20);
        dp40 = SwipeControlsUtils.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP, 40);
        dp80 = SwipeControlsUtils.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP, 80);
        sizeAdjustableOverlayId = ResourceUtils.getIdentifier(
                host, ResourceType.ID, "inset_controls_overlay_wrapper");
    }

    /**
     * Rectangle of the area that is effectively usable for swipe controls.
     */
    private Rectangle getEffectiveSwipeRect() {
        maybeAttachPlayerBoundsListener();
        Rectangle p = playerRect != null ? playerRect : fallbackScreenRect.get();
        return new Rectangle(
                p.x + dp20,
                p.y + dp40,
                p.width - 2 * dp20,
                p.height - dp20 - dp80
        );
    }

    /**
     * The rectangle of the left control zone.
     */
    public Rectangle getLeft() {
        Rectangle eRect = getEffectiveSwipeRect();
        final int zoneWidth = (eRect.width * SeekBarPreference.clampToRange(Settings.SWIPE_ZONE_WIDTH)) / 100;
        return new Rectangle(
                eRect.left,
                eRect.top,
                zoneWidth,
                eRect.height
        );
    }

    /**
     * The rectangle of the right control zone.
     */
    public Rectangle getRight() {
        Rectangle eRect = getEffectiveSwipeRect();
        final int zoneWidth = (eRect.width * SeekBarPreference.clampToRange(Settings.SWIPE_ZONE_WIDTH)) / 100;
        return new Rectangle(
                eRect.right - zoneWidth,
                eRect.top,
                zoneWidth,
                eRect.height
        );
    }

    /**
     * The rectangle of the top control zone.
     */
    public Rectangle getTop() {
        Rectangle eRect = getEffectiveSwipeRect();
        final int zoneHeight = (eRect.height * SeekBarPreference.clampToRange(Settings.SWIPE_SPEED_ZONE_HEIGHT)) / 100;
        return new Rectangle(
                eRect.left,
                eRect.top,
                eRect.width,
                zoneHeight
        );
    }

    /**
     * Tries to attach a listener to the player_view and update the player rectangle.
     * Once a listener is attached, this function does nothing.
     */
    private void maybeAttachPlayerBoundsListener() {
        if (playerRect != null) return;
        View sizeAdjustableOverlay = host.findViewById(sizeAdjustableOverlayId);
        if (sizeAdjustableOverlay != null) {
            onPlayerViewLayout(sizeAdjustableOverlay);
            sizeAdjustableOverlay.addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            onPlayerViewLayout(sizeAdjustableOverlay));
        }
    }

    /**
     * Updates the player rectangle on player_view layout.
     *
     * @param sizeAdjustableOverlay The player view.
     */
    private void onPlayerViewLayout(View sizeAdjustableOverlay) {
        // the player surface is centered in the player view
        // figure out the width of the surface including the padding (same on the left and right side)
        // and use that width for the player rectangle size
        // this automatically excludes any engagement panel from the rect
        final int playerWidthWithPadding = sizeAdjustableOverlay.getWidth() + ((int) sizeAdjustableOverlay.getX() * 2);
        playerRect = new Rectangle(
                (int) sizeAdjustableOverlay.getX(),
                (int) sizeAdjustableOverlay.getY(),
                Math.min(sizeAdjustableOverlay.getWidth(), playerWidthWithPadding),
                sizeAdjustableOverlay.getHeight()
        );
    }
}
