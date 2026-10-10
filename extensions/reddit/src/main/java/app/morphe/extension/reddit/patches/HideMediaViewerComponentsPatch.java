/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideMediaViewerComponentsPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If hiding the media viewer overlay was included during patching.
     *         It requires a newer app version than the rest of this patch.
     */
    public static boolean isHideOverlayIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return If the media viewer shows the "See the conversation" button. Newer versions show it
     *         in a dock below the media.
     */
    public static boolean showJoinConversationButton(boolean original) {
        return original && !Settings.HIDE_JOIN_CONVERSATION_BUTTON.get();
    }

    /**
     * Injection point.
     *
     * @return If a media viewer page skips the status bar padding. Images and videos skip it,
     *         but without the dock they would then be centered too high.
     */
    public static boolean skipStatusBarPadding(boolean original) {
        return original && !Settings.HIDE_JOIN_CONVERSATION_BUTTON.get();
    }

    /**
     * Injection point.
     * Called when the media viewer shows a post.
     *
     * @return True to start with the title, buttons and video controls hidden.
     */
    public static boolean hideOverlay() {
        return Settings.HIDE_MEDIA_VIEWER_OVERLAY.get();
    }
}
