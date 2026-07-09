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

import static app.morphe.extension.youtube.patches.ChangeFormFactorPatch.FormFactor.LARGE;
import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Optional;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class ChangeFormFactorPatch {

    public enum FormFactor {
        /**
         * Unmodified, and same as un-patched.
         */
        DEFAULT(null, false),
        /**
         * <pre>
         * Some changes include:
         * - Explore tab is present.
         * - watch history is missing.
         * - feed thumbnails fade in.
         */
        UNKNOWN(0, true),
        SMALL(1, false),
        LARGE(2, false),
        /**
         * Cars with 'Google built-in'.
         * Layout seems identical to {@link #UNKNOWN}
         * even when using an Android Automotive device.
         */
        AUTOMOTIVE(3, true),
        WEARABLE(4, true);

        @Nullable
        final Integer formFactorType;
        final boolean isBroken;

        FormFactor(@Nullable Integer formFactorType, boolean isBroken) {
            this.formFactorType = formFactorType;
            this.isBroken = isBroken;
        }
    }

    private static final FormFactor FORM_FACTOR = Settings.CHANGE_FORM_FACTOR.get();

    @Nullable
    private static final Integer FORM_FACTOR_TYPE = FORM_FACTOR.formFactorType;
    private static final boolean IS_BROKEN_FORM_FACTOR = FORM_FACTOR.isBroken;
    private static final boolean TABLET_LAYOUT_IN_PLAYER =
            FORM_FACTOR != LARGE && Settings.TABLET_LAYOUT_IN_PLAYER.get();

    public static final class TabletLayoutInPlayerAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.CHANGE_FORM_FACTOR.get() != LARGE;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.CHANGE_FORM_FACTOR);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called before {@link #replaceBrokenFormFactor(int)}.
     * Called from all endpoints.
     */
    public static int getUniversalFormFactor(int original) {
        if (FORM_FACTOR_TYPE == null) {
            return original;
        }
        if (IS_BROKEN_FORM_FACTOR
                && !PlayerType.getCurrent().isMaximizedOrFullscreen()
                && !NavigationBar.isSearchBarActive()) {
            // Automotive type shows error 400 when opening a channel page and using some explore tab.
            // This is a bug in unpatched YouTube that occurs on actual Android Automotive devices.
            // Work around the issue by using the tablet form factor if not in search and the
            // navigation back button is present.
            if (NavigationBar.isBackButtonVisible()
                    // Do not change library tab otherwise watch history is hidden.
                    // Do this check last since the current navigation button is required.
                    || NavigationButton.getSelectedNavigationButton() == NavigationButton.LIBRARY) {
                // The form factor most similar to AUTOMOTIVE is LARGE, so it is replaced with LARGE.
                return Optional.ofNullable(LARGE.formFactorType).orElse(original);
            }
        }

        return FORM_FACTOR_TYPE;
    }

    /**
     * Injection point.
     * <p>
     * Called after {@link #getUniversalFormFactor(int)}.
     * Called from the '/get_watch', '/guide', '/next' and '/reel' endpoints.
     * <p>
     * The '/guide' endpoint relates to navigation buttons.
     * If {@link #IS_BROKEN_FORM_FACTOR} is true in this endpoint,
     * the explore button (which is deprecated) will be shown.
     * <p>
     * The '/get_watch' and '/next' endpoints relate to elements below the player (channel bar, comments, related videos).
     * If {@link #IS_BROKEN_FORM_FACTOR} is true in this endpoint,
     * the video description panel will not open.
     * <p>
     * The '/reel' endpoint relates to Shorts player.
     * If {@link #IS_BROKEN_FORM_FACTOR} is true in this endpoint,
     * the Shorts comment panel will not open.
     */
    public static int replaceBrokenFormFactor(int original) {
        if (IS_BROKEN_FORM_FACTOR || TABLET_LAYOUT_IN_PLAYER) {
            // The form factor most similar to AUTOMOTIVE is LARGE, so it is replaced with LARGE.
            return Optional.ofNullable(LARGE.formFactorType).orElse(original);
        } else {
            return original;
        }
    }

    /**
     * Injection point.
     * <p>
     * This method check whatever the list of player's litho elements is empty, when the tablet
     * layout setting is set to off but the app is not restarted correctly, by running in
     * onResume() mode instead of onCreate().
     * <p>
     **/
     public static boolean checkPlayerLithoElementsListSize(List<?> list) {
        return list.isEmpty();
    }
}