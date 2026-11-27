/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;
import app.morphe.extension.youtube.shared.EngagementPanel;

@SuppressWarnings("unused")
public class ShortsAutoplayPatch {

    private static final String FLYOUT_ICON_NAME = YouTubeActivityHook.USE_BOLD_ICONS
            ? "yt_outline_experimental_playlist_vd_theme_24"
            : "yt_outline_list_play_arrow_black_24";

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for the menu of the Shorts player.
     * Adds an item that turns Shorts autoplay on or off without opening the settings.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex) {
        try {
            if (!Settings.SHORTS_AUTOPLAY_FLYOUT_BUTTON.get()) {
                return nextButtonIndex;
            }

            final boolean enabled = Settings.SHORTS_AUTOPLAY.get();
            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable(FLYOUT_ICON_NAME),
                    str(enabled
                            ? "morphe_shorts_autoplay_flyout_title_on"
                            : "morphe_shorts_autoplay_flyout_title_off"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        final boolean newValue = !Settings.SHORTS_AUTOPLAY.get();
                        Settings.SHORTS_AUTOPLAY.save(newValue);
                        Logger.printDebug(() -> "Shorts autoplay set from the menu: " + newValue);
                        Utils.showToastShort(str(newValue
                                ? "morphe_shorts_autoplay_flyout_toast_on"
                                : "morphe_shorts_autoplay_flyout_toast_off"));
                    },
                    nextButtonIndex
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }

    private enum ShortsLoopBehavior {
        UNKNOWN,
        /**
         * Repeat the same Short forever!
         */
        REPEAT,
        /**
         * Play once, then advanced to the next Short.
         */
        SINGLE_PLAY,
        /**
         * Pause playback after 1 play.
         */
        END_SCREEN,
        /**
         * Play once, then advanced to the next Short.
         * Only found in 21.10+
         */
        AUTO_ADVANCE,
        /**
         * Additional auto advance types, only found in 21.17+
         * Enum suggests the Short plays more than once then advances,
         * but forcing this with 21.17+ does not seem to work (YT code may not be complete).
         */
        AUTO_ADVANCE_POST_TWO_LOOPS,
        AUTO_ADVANCE_POST_THREE_LOOPS,
        AUTO_ADVANCE_POST_FOUR_LOOPS,
        AUTO_ADVANCE_POST_FIVE_LOOPS;

        static void setYTEnumValue(Enum<?> ytBehavior) {
            for (ShortsLoopBehavior rvBehavior : values()) {
                if (ytBehavior.name().endsWith(rvBehavior.name())) {
                    rvBehavior.ytEnumValue = ytBehavior;

                    Logger.printDebug(() -> rvBehavior + " set to YT enum: " + ytBehavior.name());
                    return;
                }
            }

            Logger.printException(() -> "Unknown Shorts loop behavior: " + ytBehavior.name());
        }

        /**
         * YouTube enum value of the obfuscated enum type.
         */
        private Enum<?> ytEnumValue;
    }

    /**
     * Injection point.
     */
    public static void setYTShortsRepeatEnum(Enum<?> ytEnum) {
        try {
            for (Enum<?> ytBehavior : Objects.requireNonNull(ytEnum.getClass().getEnumConstants())) {
                ShortsLoopBehavior.setYTEnumValue(ytBehavior);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setYTShortsRepeatEnum failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static Enum<?> changeShortsRepeatBehavior(Enum<?> original) {
        try {
            Activity activity = Utils.getActivity();
            final boolean inBackgroundPiP = activity != null && activity.isInPictureInPictureMode();
            boolean autoplay = inBackgroundPiP
                    ? Settings.SHORTS_AUTOPLAY_BACKGROUND.get()
                    : Settings.SHORTS_AUTOPLAY.get();

            // Like the app's own auto advance, a Short with a panel open, such as the comments,
            // is repeated. Otherwise, the Short behind the panel keeps the layout of the open panel,
            // and its overlay is out of place when going back to it.
            if (autoplay && !inBackgroundPiP) {
                String panelId = EngagementPanel.getLastOpenedShortsPanelId();
                if (panelId != null) {
                    Logger.printDebug(() -> "Not advancing, panel is open: " + panelId);
                    autoplay = false;
                }
            }

            ShortsLoopBehavior autoPlayBehavior = VersionCheckPatch.IS_21_10_OR_GREATER
                    ? ShortsLoopBehavior.AUTO_ADVANCE
                    : ShortsLoopBehavior.SINGLE_PLAY;

            Enum<?> overrideBehavior = (autoplay
                    ? autoPlayBehavior
                    : ShortsLoopBehavior.REPEAT).ytEnumValue;

            if (overrideBehavior != null) {
                Logger.printDebug(() -> {
                    String name = (original == null ? "unknown (null)" : original.name());
                    return overrideBehavior == original
                            ? "Behavior setting is same as original. Using original: " + name
                            : "Changing Shorts repeat behavior from: " + name + " to: " + overrideBehavior.name();
                });

                return overrideBehavior;
            }

            if (original == null) {
                // Cannot return null, as null is used to indicate the Short was autoplayed.
                // Unpatched app replaces null with unknown enum type (appears to fix for bad api data).
                Enum<?> unknown = ShortsLoopBehavior.UNKNOWN.ytEnumValue;
                Logger.printDebug(() -> "Original is null, returning: " + unknown.name());
                return unknown;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "changeShortsRepeatState failure", ex);
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static boolean isAutoPlay(Enum<?> original) {
        return ShortsLoopBehavior.SINGLE_PLAY.ytEnumValue == original;
    }
}
