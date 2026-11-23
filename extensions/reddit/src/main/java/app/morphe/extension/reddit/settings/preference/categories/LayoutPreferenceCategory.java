/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.AutoModeratorCommentsPatch;
import app.morphe.extension.reddit.patches.CollapseAllCommentsPatch;
import app.morphe.extension.reddit.patches.ColorizeCommentIndentLinesPatch;
import app.morphe.extension.reddit.patches.CopyTextPatch;
import app.morphe.extension.reddit.patches.CustomFontPatch;
import app.morphe.extension.reddit.patches.DisableModernHomePatch;
import app.morphe.extension.reddit.patches.DisableScreenshotPopupPatch;
import app.morphe.extension.reddit.patches.FeedAutoScrollPatch;
import app.morphe.extension.reddit.patches.FeedMediaMaxHeightPatch;
import app.morphe.extension.reddit.patches.FlipPostActionBarPatch;
import app.morphe.extension.reddit.patches.ForceSystemFontPatch;
import app.morphe.extension.reddit.patches.FullScreenReplyPatch;
import app.morphe.extension.reddit.patches.FullWidthFeedMediaPatch;
import app.morphe.extension.reddit.patches.HideAskButtonPatch;
import app.morphe.extension.reddit.patches.HideCommunitiesShelfPatch;
import app.morphe.extension.reddit.patches.HideHomeTabsPatch;
import app.morphe.extension.reddit.patches.HideInteractivePostsPatch;
import app.morphe.extension.reddit.patches.HideMediaViewerComponentsPatch;
import app.morphe.extension.reddit.patches.HidePeopleAlsoSearchForSectionPatch;
import app.morphe.extension.reddit.patches.HideRelatedPostsSectionPatch;
import app.morphe.extension.reddit.patches.HideTrendingShelvesPatch;
import app.morphe.extension.reddit.patches.HideVideosForYouShelfPatch;
import app.morphe.extension.reddit.patches.HighQualityAvatarsPatch;
import app.morphe.extension.reddit.patches.HomeFeedTabsPatch;
import app.morphe.extension.reddit.patches.KeepFeedPositionPatch;
import app.morphe.extension.reddit.patches.KeepMediaViewerInSubredditPatch;
import app.morphe.extension.reddit.patches.MediaViewerBlackBackgroundPatch;
import app.morphe.extension.reddit.patches.MediaViewerFadePatch;
import app.morphe.extension.reddit.patches.PostSidePanelPatch;
import app.morphe.extension.reddit.patches.RememberCollapsedCommentsPatch;
import app.morphe.extension.reddit.patches.RemoveSubRedditDialogPatch;
import app.morphe.extension.reddit.patches.SearchHiddenProfilesPatch;
import app.morphe.extension.reddit.patches.ShowFlairsInHomeFeedPatch;
import app.morphe.extension.reddit.patches.ShowViewCountPatch;
import app.morphe.extension.reddit.patches.SwipeUpForCommentsPatch;
import app.morphe.extension.reddit.patches.TabletGridFeedPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.CustomFontFilePreference;
import app.morphe.extension.reddit.settings.preference.CustomFontTogglePreference;
import app.morphe.extension.reddit.settings.preference.ForceSystemFontPreference;
import app.morphe.extension.reddit.settings.preference.NumberSettingPreference;
import app.morphe.extension.reddit.settings.preference.PostSidePanelWidthPreference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

@SuppressWarnings("deprecation")
public class LayoutPreferenceCategory extends ConditionalPreferenceCategory {
    public LayoutPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_layout_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return DisableModernHomePatch.isPatchIncluded() ||
                HomeFeedTabsPatch.isPatchIncluded() ||
                DisableScreenshotPopupPatch.isPatchIncluded() ||
                CopyTextPatch.isPatchIncluded() ||
                CustomFontPatch.isPatchIncluded() ||
                ForceSystemFontPatch.isPatchIncluded() ||
                HideAskButtonPatch.isPatchIncluded() ||
                HideCommunitiesShelfPatch.isPatchIncluded() ||
                HideHomeTabsPatch.isPatchIncluded() ||
                HideInteractivePostsPatch.isPatchIncluded() ||
                HidePeopleAlsoSearchForSectionPatch.isPatchIncluded() ||
                HideRelatedPostsSectionPatch.isPatchIncluded() ||
                HideTrendingShelvesPatch.isPatchIncluded() ||
                HideVideosForYouShelfPatch.isPatchIncluded() ||
                FullWidthFeedMediaPatch.isPatchIncluded() ||
                MediaViewerFadePatch.isPatchIncluded() ||
                KeepFeedPositionPatch.isPatchIncluded() ||
                FeedMediaMaxHeightPatch.isPatchIncluded() ||
                HideMediaViewerComponentsPatch.isPatchIncluded() ||
                MediaViewerBlackBackgroundPatch.isPatchIncluded() ||
                SwipeUpForCommentsPatch.isPatchIncluded() ||
                KeepMediaViewerInSubredditPatch.isPatchIncluded() ||
                RemoveSubRedditDialogPatch.isPatchIncluded() ||
                SearchHiddenProfilesPatch.isPatchIncluded() ||
                HighQualityAvatarsPatch.isPatchIncluded() ||
                ShowViewCountPatch.isPatchIncluded() ||
                FlipPostActionBarPatch.isPatchIncluded() ||
                FullScreenReplyPatch.isPatchIncluded() ||
                AutoModeratorCommentsPatch.isPatchIncluded() ||
                CollapseAllCommentsPatch.isPatchIncluded() ||
                ColorizeCommentIndentLinesPatch.isPatchIncluded() ||
                RememberCollapsedCommentsPatch.isPatchIncluded() ||
                TabletGridFeedPatch.isPatchIncluded() ||
                FeedAutoScrollPatch.isPatchIncluded() ||
                PostSidePanelPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (CopyTextPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.COPY_TEXT
            ));
        }

        if (CustomFontPatch.isPatchIncluded()) {
            addPreference(new CustomFontTogglePreference(context));
            addPreference(new CustomFontFilePreference(
                    context,
                    Settings.CUSTOM_FONT_FILE_PATH
            ));
        }

        if (DisableModernHomePatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_MODERN_HOME
            ));
        }

        if (HomeFeedTabsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HOME_FEED_TABS
            ));
        }

        if (DisableScreenshotPopupPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_SCREENSHOT_POPUP
            ));
        }

        if (ForceSystemFontPatch.isPatchIncluded()) {
            addPreference(new ForceSystemFontPreference(context));
        }

        if (HideAskButtonPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_ASK_BUTTON
            ));
        }

        if (HideCommunitiesShelfPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_COMMUNITIES_SHELF
            ));
        }

        if (HideHomeTabsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_FOR_YOU_TAB
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_FOLLOWING_TAB
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_POPULAR_TAB
            ));
        }

        if (HideInteractivePostsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_INTERACTIVE_POSTS
            ));
        }

        if (HidePeopleAlsoSearchForSectionPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_PEOPLE_ALSO_SEARCH_FOR_SECTION
            ));
        }

        if (HideRelatedPostsSectionPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RELATED_POSTS_SECTION
            ));
        }

        if (HideTrendingShelvesPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_TRENDING_SHELVES
            ));
        }

        if (HideVideosForYouShelfPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_VIDEOS_FOR_YOU_SHELF
            ));
        }

        if (HighQualityAvatarsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIGH_QUALITY_AVATARS
            ));
        }

        if (RemoveSubRedditDialogPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NSFW_DIALOG
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NOTIFICATION_DIALOG
            ));
        }

        if (SearchHiddenProfilesPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SEARCH_HIDDEN_PROFILES
            ));
        }

        if (ShowViewCountPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SHOW_VIEW_COUNT
            ));
        }

        if (FullWidthFeedMediaPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FULL_WIDTH_FEED_MEDIA
            ));
        }

        if (MediaViewerFadePatch.isPatchIncluded()) {
            addPreference(new NumberSettingPreference(
                    context,
                    Settings.MEDIA_VIEWER_FADE
            ));
        }

        if (KeepFeedPositionPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.KEEP_FEED_POSITION
            ));
        }

        if (FeedMediaMaxHeightPatch.isPatchIncluded()) {
            addPreference(new NumberSettingPreference(
                    context,
                    Settings.FEED_MEDIA_MAX_HEIGHT
            ));
        }

        if (HideMediaViewerComponentsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_JOIN_CONVERSATION_BUTTON
            ));

            if (HideMediaViewerComponentsPatch.isHideOverlayIncluded()) {
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_MEDIA_VIEWER_OVERLAY
                ));
            }
        }

        if (MediaViewerBlackBackgroundPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.MEDIA_VIEWER_BLACK_BACKGROUND
            ));
        }

        if (SwipeUpForCommentsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SWIPE_UP_FOR_COMMENTS
            ));
        }

        if (KeepMediaViewerInSubredditPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.KEEP_MEDIA_VIEWER_IN_SUBREDDIT
            ));
        }

        if (FlipPostActionBarPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FLIP_POST_ACTION_BAR
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SWAP_POST_VOTE_COMMENT_BUTTONS
            ));
        }

        if (FullScreenReplyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FULL_SCREEN_REPLY
            ));
        }

        if (AutoModeratorCommentsPatch.isPatchIncluded()) {
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.AUTOMODERATOR_COMMENTS
            ));
        }

        if (CollapseAllCommentsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.COLLAPSE_ALL_COMMENTS
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.COLLAPSE_ALL_COMMENTS_SCOPE
            ));
        }

        if (ColorizeCommentIndentLinesPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.COLORIZE_COMMENT_INDENT_LINES
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.COLORIZE_COMMENT_INDENT_LINES_PALETTE
            ));
        }

        if (RememberCollapsedCommentsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMEMBER_COLLAPSED_COMMENTS
            ));
        }

        if (TabletGridFeedPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.TABLET_GRID_FEED
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.TABLET_GRID_COLUMNS
            ));
        }

        if (FeedAutoScrollPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FEED_AUTO_SCROLL
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.FEED_AUTO_SCROLL_SPEED
            ));
        }

        if (PostSidePanelPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.POST_SIDE_PANEL
            ));
            addPreference(new PostSidePanelWidthPreference(context));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.POST_SIDE_PANEL_REMEMBER_WIDTH
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.POST_SIDE_PANEL_DIM
            ));
        }

        Utils.setPreferenceTitlesToMultiLine(this);

        if (ShowFlairsInHomeFeedPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SHOW_FLAIRS_IN_HOME_FEED
            ));
        }
    }
}
