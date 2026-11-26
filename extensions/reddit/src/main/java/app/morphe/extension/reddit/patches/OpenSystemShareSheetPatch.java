/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5004
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedSettings;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    /**
     * Event handler of a share sheet.
     */
    public interface ShareSheetEventHandlerInterface {
        // Methods are added during patching.

        /**
         * @return If the share sheet shares something. Overflow menus use the share sheet without sharing anything.
         */
        boolean patch_isShareable();

        /**
         * @return Actions shown above the share targets, such as the actions of an overflow menu.
         */
        @Nullable
        List<?> patch_getActions();

        Enum<?> patch_getShareTrigger();

        /**
         * @return Click event of "Share via...", which opens the system share sheet.
         */
        Object patch_getShareViaEvent();
    }

    /**
     * Share sheet opened after a screenshot, if enabled by a feature flag.
     */
    private static final String SCREENSHOT_SHARE_TRIGGER = "ScreenshotAutoOpen";

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * "Share" of overflow menus shows the share targets in the overflow menu.
     * Replaces it with "Share via...", which closes the overflow menu and opens the system share sheet.
     *
     * @param action Clicked action of the share sheet.
     * @return The action to handle.
     */
    public static Object getShareSheetAction(Object action, Object openShareSheetAction, Object shareViaAction) {
        if (!SharedSettings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return action;
        }

        return action == openShareSheetAction ? shareViaAction : action;
    }

    /**
     * Injection point.
     * Replaces the event that loads the content of the share sheet with the click event of
     * "Share via...", which closes the share sheet and opens the system share sheet.
     *
     * @param screenInitEvent Event sent when the share sheet is opened.
     * @return The event to send.
     */
    public static Object getScreenInitEvent(Object screenInitEvent, ShareSheetEventHandlerInterface handler) {
        if (!SharedSettings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return screenInitEvent;
        }

        try {
            if (!handler.patch_isShareable()) {
                return screenInitEvent;
            }

            List<?> actions = handler.patch_getActions();
            if (actions != null && !actions.isEmpty()) {
                // Overflow menus also show actions such as save, hide and report.
                Logger.printDebug(() -> "Share sheet has actions, opening the in-app share sheet");
                return screenInitEvent;
            }

            if (SCREENSHOT_SHARE_TRIGGER.equals(handler.patch_getShareTrigger().name())) {
                // Opened without a tap on a share button.
                return screenInitEvent;
            }

            return handler.patch_getShareViaEvent();
        } catch (Exception ex) {
            Logger.printException(() -> "getScreenInitEvent failure", ex);
        }

        return screenInitEvent;
    }
}
