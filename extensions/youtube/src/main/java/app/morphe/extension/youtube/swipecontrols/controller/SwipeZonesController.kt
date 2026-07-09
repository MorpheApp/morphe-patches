/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller

import android.app.Activity
import android.util.TypedValue
import android.view.View
import app.morphe.extension.shared.ResourceType
import app.morphe.extension.shared.ResourceUtils.getIdentifier
import app.morphe.extension.youtube.settings.Settings
import app.morphe.extension.youtube.swipecontrols.misc.Rectangle
import app.morphe.extension.youtube.swipecontrols.misc.applyDimension
import kotlin.math.min

/**
 *  +------------------------------------------------------------------+
 *  |                         40dp (top dead)                          |
 *  +----------+-------------+----------------+-------------+----------+
 *  |          |  brightness |     speed      |   volume    |          |
 *  |  20dp    |    (↑ ↓)    |     (← →)      |    (↑ ↓)    |  20dp    |
 *  |          |  + speed    |   speed_h%     |  + speed    |          |
 *  +- - - - - +- - - - - - -+- - - - - - - - +- - - - - - -+- - - - - +
 *  |  dead    |  brightness |     dead       |   volume    |  dead    |
 *  |          |   zone_w%   |   100-2*z%     |   zone_w%   |          |
 *  +----------+-------------+----------------+-------------+----------+
 *  |                        60dp (bottom dead)                        |
 *  +------------------------------------------------------------------+
 *
 *  brightness / volume - vertical swipe (↑ ↓), span full effective height
 *  speed               - horizontal swipe (← →), top speed_h% of effective height;
 *                        overlaps with the top of brightness and volume zones
 */
@Suppress("PrivatePropertyName")
class SwipeZonesController(
    private val host: Activity,
    private val fallbackScreenRect: () -> Rectangle,
) {
    /**
     * 20dp, in pixels.
     */
    private val _20dp = 20.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP)

    /**
     * 40dp, in pixels.
     */
    private val _40dp = 40.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP)

    /**
     * 80dp, in pixels.
     */
    private val _80dp = 80.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP)

    /**
     * ID for R.id.inset_controls_overlay_wrapper.
     */
    private val sizeAdjustableOverlayId = getIdentifier(
        host, ResourceType.ID, "inset_controls_overlay_wrapper"
    )

    /**
     * Current bounding rectangle of the player.
     */
    private var playerRect: Rectangle? = null

    /**
     * Rectangle of the area that is effectively usable for swipe controls.
     */
    private val effectiveSwipeRect: Rectangle
        get() {
            maybeAttachPlayerBoundsListener()
            val p = if (playerRect != null) playerRect!! else fallbackScreenRect()
            return Rectangle(
                p.x + _20dp,
                p.y + _40dp,
                p.width - 2 * _20dp,
                p.height - _20dp - _80dp,
            )
        }

    /**
     * The rectangle of the volume control zone.
     */
    val volume: Rectangle
        get() {
            val eRect = effectiveSwipeRect
            val zoneWidth = (eRect.width * maxOf(5, minOf(50, Settings.SWIPE_ZONE_WIDTH.get()))) / 100
            return Rectangle(
                eRect.right - zoneWidth,
                eRect.top,
                zoneWidth,
                eRect.height,
            )
        }

    /**
     * The rectangle of the screen brightness control zone.
     */
    val brightness: Rectangle
        get() {
            val eRect = effectiveSwipeRect
            val zoneWidth = (eRect.width * maxOf(5, minOf(50, Settings.SWIPE_ZONE_WIDTH.get()))) / 100
            return Rectangle(
                eRect.left,
                eRect.top,
                zoneWidth,
                eRect.height,
            )
        }

    /**
     * The rectangle of the playback speed control zone (top strip of the effective swipe area).
     * Height is controlled by [Settings.SWIPE_SPEED_ZONE_HEIGHT] as a percentage of the player height.
     */
    val speed: Rectangle
        get() {
            val eRect = effectiveSwipeRect
            val zoneHeight = (eRect.height * maxOf(5, minOf(75, Settings.SWIPE_SPEED_ZONE_HEIGHT.get()))) / 100
            return Rectangle(
                eRect.left,
                eRect.top,
                eRect.width,
                zoneHeight,
            )
        }

    /**
     * Tries to attach a listener to the player_view and update the player rectangle.
     * Once a listener is attached, this function does nothing.
     */
    private fun maybeAttachPlayerBoundsListener() {
        if (playerRect != null) return
        host.findViewById<View>(sizeAdjustableOverlayId)?.let {
            onPlayerViewLayout(it)
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                onPlayerViewLayout(it)
            }
        }
    }

    /**
     * Updates the player rectangle on player_view layout.
     *
     * @param sizeAdjustableOverlay The player view.
     */
    private fun onPlayerViewLayout(sizeAdjustableOverlay: View) {
        // the player surface is centered in the player view
        // figure out the width of the surface including the padding (same on the left and right side)
        // and use that width for the player rectangle size
        // this automatically excludes any engagement panel from the rect
        val playerWidthWithPadding = sizeAdjustableOverlay.width + (sizeAdjustableOverlay.x.toInt() * 2)
        playerRect = Rectangle(
            sizeAdjustableOverlay.x.toInt(),
            sizeAdjustableOverlay.y.toInt(),
            min(sizeAdjustableOverlay.width, playerWidthWithPadding),
            sizeAdjustableOverlay.height,
        )
    }
}
