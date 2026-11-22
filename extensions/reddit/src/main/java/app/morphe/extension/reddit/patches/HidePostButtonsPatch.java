/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5022
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;

/**
 * Hides the buttons of posts in the feeds, the post detail screen and the media viewer.
 * <p>
 * The action bar of posts is also used by comments, so only the buttons that the posts pass
 * to it are hidden. The vote and comment buttons are always
 * shown by the action bar, so their states are marked as hidden. The other buttons are not
 * shown if they are null.
 * <p>
 * The media viewer can also show the buttons in a column on the side of the post instead,
 * which only it uses.
 * <p>
 * The Award button of comments is also hidden, which is shown by its own composable.
 */
@SuppressWarnings("unused")
public final class HidePostButtonsPatch {

    /**
     * Added during patching to the states of the action bar vote buttons and buttons.
     */
    public interface HideableState {
        boolean patch_isHidden();

        void patch_setHidden(boolean hidden);
    }

    /**
     * Test tags of the counts of the buttons on the side of a post in the media viewer.
     */
    private static final String MEDIA_VIEWER_COMMENT_COUNT = "comment_count";
    private static final String MEDIA_VIEWER_REPOST_COUNT = "repost_count";
    private static final String MEDIA_VIEWER_SHARE_COUNT = "post_share_count";
    private static final String MEDIA_VIEWER_MOD_COUNT = "mod_count";

    private HidePostButtonsPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If hiding the buttons below posts was included during patching.
     *         It requires a newer app version than hiding the Join button.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isActionBarIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean hideCommentAwardButton() {
        return Settings.HIDE_POST_AWARD_BUTTON.get();
    }

    /**
     * Injection point.
     */
    public static boolean hideJoinButton() {
        return Settings.HIDE_POST_JOIN_BUTTON.get();
    }

    /**
     * Injection point. Called with the vote state of a post, before its action bar is shown.
     */
    public static void setVoteButtonsHidden(Object state) {
        if (Settings.HIDE_POST_VOTE_BUTTONS.get()) {
            ((HideableState) state).patch_setHidden(true);
        }
    }

    /**
     * Injection point. Called with the comment button of a post, before its action bar is shown.
     */
    public static void setCommentButtonHidden(Object state) {
        if (Settings.HIDE_POST_COMMENT_BUTTON.get()) {
            ((HideableState) state).patch_setHidden(true);
        }
    }

    /**
     * Injection point.
     *
     * @param button Composable lambda of the award button, or null if not shown.
     */
    @Nullable
    public static Object getAwardButton(@Nullable Object button) {
        return Settings.HIDE_POST_AWARD_BUTTON.get() ? null : button;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Object getRepostButton(@Nullable Object state) {
        return Settings.HIDE_POST_REPOST_BUTTON.get() ? null : state;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Object getShareButton(@Nullable Object state) {
        return Settings.HIDE_POST_SHARE_BUTTON.get() ? null : state;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Object getModButton(@Nullable Object state) {
        return Settings.HIDE_POST_MOD_BUTTON.get() ? null : state;
    }

    private static boolean areVoteAndCommentButtonsHidden() {
        return Settings.HIDE_POST_VOTE_BUTTONS.get() && Settings.HIDE_POST_COMMENT_BUTTON.get();
    }

    /**
     * Injection point.
     *
     * @return If the action bar of a post is not shown, as all of its buttons are hidden.
     */
    public static boolean hideActionBar(@Nullable Object repost, @Nullable Object share, @Nullable Object mod) {
        return repost == null && share == null && mod == null && areVoteAndCommentButtonsHidden();
    }

    /**
     * Injection point. Action bar before 2026.39.0, which also has an award button.
     *
     * @return If the action bar of a post is not shown, as all of its buttons are hidden.
     */
    public static boolean hideActionBar(@Nullable Object award, @Nullable Object repost,
                                        @Nullable Object share, @Nullable Object mod) {
        return award == null && hideActionBar(repost, share, mod);
    }

    /**
     * Injection point. Called by the action bar vote buttons and buttons of posts and comments.
     *
     * @return If the vote buttons or button are not shown.
     */
    public static boolean isHidden(Object state) {
        return ((HideableState) state).patch_isHidden();
    }

    /**
     * Injection point.
     *
     * @return If the vote buttons on the side of a post in the media viewer are not shown.
     */
    public static boolean hideMediaViewerVoteButtons() {
        return Settings.HIDE_POST_VOTE_BUTTONS.get();
    }

    /**
     * Injection point.
     *
     * @param countTestTag Test tag of the count of a button on the side of a post in the media viewer.
     * @return If the button is not shown.
     */
    public static boolean hideMediaViewerButton(@Nullable String countTestTag) {
        if (countTestTag == null) return false;

        return switch (countTestTag) {
            case MEDIA_VIEWER_COMMENT_COUNT -> Settings.HIDE_POST_COMMENT_BUTTON.get();
            case MEDIA_VIEWER_REPOST_COUNT -> Settings.HIDE_POST_REPOST_BUTTON.get();
            case MEDIA_VIEWER_SHARE_COUNT -> Settings.HIDE_POST_SHARE_BUTTON.get();
            case MEDIA_VIEWER_MOD_COUNT -> Settings.HIDE_POST_MOD_BUTTON.get();
            default -> false;
        };
    }
}
