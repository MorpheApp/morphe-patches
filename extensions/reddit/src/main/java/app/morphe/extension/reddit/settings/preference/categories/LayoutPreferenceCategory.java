/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import java.util.function.Consumer;

import app.morphe.extension.reddit.patches.AlwaysShowHidePatch;
import app.morphe.extension.reddit.patches.AutoModeratorCommentsPatch;
import app.morphe.extension.reddit.patches.CollapseAllCommentsPatch;
import app.morphe.extension.reddit.patches.ColorizeCommentIndentLinesPatch;
import app.morphe.extension.reddit.patches.CopyTextPatch;
import app.morphe.extension.reddit.patches.CustomFontPatch;
import app.morphe.extension.reddit.patches.DisableHomeFeedAutoRefreshPatch;
import app.morphe.extension.reddit.patches.DisableModernHomePatch;
import app.morphe.extension.reddit.patches.DisablePostDetailSwipePatch;
import app.morphe.extension.reddit.patches.DisableRefreshOnBackPatch;
import app.morphe.extension.reddit.patches.DisableScreenshotPopupPatch;
import app.morphe.extension.reddit.patches.FeedAutoScrollPatch;
import app.morphe.extension.reddit.patches.FeedMediaMaxHeightPatch;
import app.morphe.extension.reddit.patches.FlipPostActionBarPatch;
import app.morphe.extension.reddit.patches.ForceSystemFontPatch;
import app.morphe.extension.reddit.patches.FullScreenReplyPatch;
import app.morphe.extension.reddit.patches.FullWidthFeedMediaPatch;
import app.morphe.extension.reddit.patches.HideAnswersPreviewPatch;
import app.morphe.extension.reddit.patches.HideAskButtonPatch;
import app.morphe.extension.reddit.patches.HideCommentFlairsAndBadgesPatch;
import app.morphe.extension.reddit.patches.HideCommunitiesShelfPatch;
import app.morphe.extension.reddit.patches.HideCommunityHighlightsPatch;
import app.morphe.extension.reddit.patches.HideCommunityMenuBadgePatch;
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
import app.morphe.extension.reddit.patches.SeeThroughStatusBarPatch;
import app.morphe.extension.reddit.patches.ShowFlairsInHomeFeedPatch;
import app.morphe.extension.reddit.patches.ShowViewCountPatch;
import app.morphe.extension.reddit.patches.SwipeUpForCommentsPatch;
import app.morphe.extension.reddit.patches.TabletGridFeedPatch;
import app.morphe.extension.reddit.patches.VideoPlaybackSpeedPatch;
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
        super(context, screen, "morphe_layout_screen", "morphe_screen_layout_title", "morphe_layout_screen_summary");
    }

    @Override
    public boolean getSettingsStatus() {
        return DisableModernHomePatch.isPatchIncluded() ||
                HomeFeedTabsPatch.isPatchIncluded() ||
                DisablePostDetailSwipePatch.isPatchIncluded() ||
                DisableRefreshOnBackPatch.isPatchIncluded() ||
                DisableScreenshotPopupPatch.isPatchIncluded() ||
                CopyTextPatch.isPatchIncluded() ||
                CustomFontPatch.isPatchIncluded() ||
                ForceSystemFontPatch.isPatchIncluded() ||
                HideAnswersPreviewPatch.isPatchIncluded() ||
                HideAskButtonPatch.isPatchIncluded() ||
                HideCommunitiesShelfPatch.isPatchIncluded() ||
                HideCommunityHighlightsPatch.isPatchIncluded() ||
                HideCommunityMenuBadgePatch.isPatchIncluded() ||
                HideHomeTabsPatch.isPatchIncluded() ||
                HideInteractivePostsPatch.isPatchIncluded() ||
                HidePeopleAlsoSearchForSectionPatch.isPatchIncluded() ||
                HideRelatedPostsSectionPatch.isPatchIncluded() ||
                HideTrendingShelvesPatch.isPatchIncluded() ||
                HideVideosForYouShelfPatch.isPatchIncluded() ||
                AlwaysShowHidePatch.isPatchIncluded() ||
                FullWidthFeedMediaPatch.isPatchIncluded() ||
                MediaViewerFadePatch.isPatchIncluded() ||
                KeepFeedPositionPatch.isPatchIncluded() ||
                DisableHomeFeedAutoRefreshPatch.isPatchIncluded() ||
                FeedMediaMaxHeightPatch.isPatchIncluded() ||
                HideMediaViewerComponentsPatch.isPatchIncluded() ||
                MediaViewerBlackBackgroundPatch.isPatchIncluded() ||
                SwipeUpForCommentsPatch.isPatchIncluded() ||
                KeepMediaViewerInSubredditPatch.isPatchIncluded() ||
                RemoveSubRedditDialogPatch.isPatchIncluded() ||
                SearchHiddenProfilesPatch.isPatchIncluded() ||
                HighQualityAvatarsPatch.isPatchIncluded() ||
                SeeThroughStatusBarPatch.isPatchIncluded() ||
                ShowViewCountPatch.isPatchIncluded() ||
                FlipPostActionBarPatch.isPatchIncluded() ||
                FullScreenReplyPatch.isPatchIncluded() ||
                AutoModeratorCommentsPatch.isPatchIncluded() ||
                HideCommentFlairsAndBadgesPatch.isPatchIncluded() ||
                CollapseAllCommentsPatch.isPatchIncluded() ||
                ColorizeCommentIndentLinesPatch.isPatchIncluded() ||
                RememberCollapsedCommentsPatch.isPatchIncluded() ||
                TabletGridFeedPatch.isPatchIncluded() ||
                FeedAutoScrollPatch.isPatchIncluded() ||
                PostSidePanelPatch.isPatchIncluded() ||
                VideoPlaybackSpeedPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        addSection(context, "morphe_layout_home_feed_title",
                group -> addHomeFeedPreferences(context, group));
        addSection(context, "morphe_layout_shelves_title",
                group -> addShelvesPreferences(context, group));
        addSection(context, "morphe_layout_posts_title",
                group -> addPostsPreferences(context, group));
        addSection(context, "morphe_layout_comments_title",
                group -> addCommentsPreferences(context, group));
        addSection(context, "morphe_layout_media_viewer_title",
                group -> addMediaViewerPreferences(context, group));
        addSection(context, "morphe_layout_app_title",
                group -> addAppPreferences(context, group));

        Utils.setPreferenceTitlesToMultiLine(page != null ? page : this);
    }

    /**
     * Adds the settings of a section in a titled category on the page with the modern style,
     * otherwise to the flat list of the legacy settings.
     */
    private void addSection(Context context, String titleKey, Consumer<PreferenceGroup> populate) {
        if (page == null) {
            populate.accept(this);
            return;
        }

        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(str(titleKey));
        // Add to the page first so PreferenceManager is attached while child preferences are added.
        page.addPreference(category);
        populate.accept(category);
        if (category.getPreferenceCount() == 0) {
            page.removePreference(category);
        }
    }

    private void addHomeFeedPreferences(Context context, PreferenceGroup group) {
        if (DisableModernHomePatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_MODERN_HOME
            ));
        }

        if (HomeFeedTabsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HOME_FEED_TABS
            ));
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_HOME_FEED_SWIPE
            ));
        }

        if (HideHomeTabsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_FOR_YOU_TAB
            ));
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_FOLLOWING_TAB
            ));
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_POPULAR_TAB
            ));
        }

        if (KeepFeedPositionPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.KEEP_FEED_POSITION
            ));
        }

        if (DisableHomeFeedAutoRefreshPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_HOME_FEED_AUTO_REFRESH
            ));
        }

        if (DisableRefreshOnBackPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_REFRESH_ON_BACK
            ));
        }

        if (TabletGridFeedPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.TABLET_GRID_FEED
            ));
            addIndentedPreference(group, new CustomDialogListPreference(
                    context,
                    Settings.TABLET_GRID_COLUMNS
            ));
        }

        if (FeedAutoScrollPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FEED_AUTO_SCROLL
            ));
            addIndentedPreference(group, new CustomDialogListPreference(
                    context,
                    Settings.FEED_AUTO_SCROLL_SPEED
            ));
        }

        if (FullWidthFeedMediaPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FULL_WIDTH_FEED_MEDIA
            ));
        }

        if (FeedMediaMaxHeightPatch.isPatchIncluded()) {
            group.addPreference(new NumberSettingPreference(
                    context,
                    Settings.FEED_MEDIA_MAX_HEIGHT
            ));
        }

        if (AlwaysShowHidePatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.ALWAYS_SHOW_HIDE
            ));
        }

        if (HideInteractivePostsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_INTERACTIVE_POSTS
            ));
        }

        if (ShowViewCountPatch.isPatchIncluded()) {
            group.addPreference(new CustomDialogListPreference(
                    context,
                    Settings.VIEW_COUNT
            ));
        }

        if (ShowFlairsInHomeFeedPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SHOW_FLAIRS_IN_HOME_FEED
            ));
        }
    }

    private void addShelvesPreferences(Context context, PreferenceGroup group) {
        if (HideCommunitiesShelfPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_COMMUNITIES_SHELF
            ));
        }

        if (HideTrendingShelvesPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_TRENDING_SHELVES
            ));
        }

        if (HideVideosForYouShelfPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_VIDEOS_FOR_YOU_SHELF
            ));
        }

        if (HideCommunityHighlightsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_COMMUNITY_HIGHLIGHTS
            ));
        }

        if (HideRelatedPostsSectionPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RELATED_POSTS_SECTION
            ));
        }

        if (HidePeopleAlsoSearchForSectionPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_PEOPLE_ALSO_SEARCH_FOR_SECTION
            ));
        }

        if (HideAnswersPreviewPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_ANSWERS_PREVIEW
            ));
        }

        if (HideAskButtonPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_ASK_BUTTON
            ));
        }
    }

    private void addPostsPreferences(Context context, PreferenceGroup group) {
        if (DisablePostDetailSwipePatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_POST_DETAIL_SWIPE
            ));
        }

        if (FlipPostActionBarPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FLIP_POST_ACTION_BAR
            ));
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SWAP_POST_VOTE_COMMENT_BUTTONS
            ));
        }

        if (SwipeUpForCommentsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SWIPE_UP_FOR_COMMENTS
            ));
        }

        if (FullScreenReplyPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FULL_SCREEN_REPLY
            ));
        }

        if (PostSidePanelPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.POST_SIDE_PANEL
            ));
            addIndentedPreference(group, new PostSidePanelWidthPreference(context));
            addIndentedPreference(group, new BooleanSettingPreference(
                    context,
                    Settings.POST_SIDE_PANEL_REMEMBER_WIDTH
            ));
            addIndentedPreference(group, new CustomDialogListPreference(
                    context,
                    Settings.POST_SIDE_PANEL_DIM
            ));
        }
    }

    private void addCommentsPreferences(Context context, PreferenceGroup group) {
        if (AutoModeratorCommentsPatch.isPatchIncluded()) {
            group.addPreference(new CustomDialogListPreference(
                    context,
                    Settings.AUTOMODERATOR_COMMENTS
            ));
        }

        if (HideCommentFlairsAndBadgesPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_COMMENT_USER_FLAIRS
            ));
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_COMMENT_COMMUNITY_BADGES
            ));
        }

        if (CollapseAllCommentsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.COLLAPSE_ALL_COMMENTS
            ));
            addIndentedPreference(group, new CustomDialogListPreference(
                    context,
                    Settings.COLLAPSE_ALL_COMMENTS_SCOPE
            ));
        }

        if (ColorizeCommentIndentLinesPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.COLORIZE_COMMENT_INDENT_LINES
            ));
            addIndentedPreference(group, new CustomDialogListPreference(
                    context,
                    Settings.COLORIZE_COMMENT_INDENT_LINES_PALETTE
            ));
        }

        if (RememberCollapsedCommentsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMEMBER_COLLAPSED_COMMENTS
            ));
        }
    }

    private void addMediaViewerPreferences(Context context, PreferenceGroup group) {
        if (MediaViewerFadePatch.isPatchIncluded()) {
            group.addPreference(new NumberSettingPreference(
                    context,
                    Settings.MEDIA_VIEWER_FADE
            ));
        }

        if (HideMediaViewerComponentsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_JOIN_CONVERSATION_BUTTON
            ));

            if (HideMediaViewerComponentsPatch.isHideOverlayIncluded()) {
                group.addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_MEDIA_VIEWER_OVERLAY
                ));
            }
        }

        if (MediaViewerBlackBackgroundPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.MEDIA_VIEWER_BLACK_BACKGROUND
            ));
        }

        if (KeepMediaViewerInSubredditPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.KEEP_MEDIA_VIEWER_IN_SUBREDDIT
            ));
        }

        if (VideoPlaybackSpeedPatch.isPatchIncluded()) {
            group.addPreference(new CustomDialogListPreference(
                    context,
                    Settings.VIDEO_PLAYBACK_SPEED_CONTROLS
            ));
        }
    }

    private void addAppPreferences(Context context, PreferenceGroup group) {
        if (CopyTextPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.COPY_TEXT
            ));
        }

        if (CustomFontPatch.isPatchIncluded()) {
            group.addPreference(new CustomFontTogglePreference(context));
            group.addPreference(new CustomFontFilePreference(
                    context,
                    Settings.CUSTOM_FONT_FILE_PATH
            ));
        }

        if (ForceSystemFontPatch.isPatchIncluded()) {
            group.addPreference(new ForceSystemFontPreference(context));
        }

        if (SeeThroughStatusBarPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SEE_THROUGH_STATUS_BAR
            ));
        }

        if (HighQualityAvatarsPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIGH_QUALITY_AVATARS
            ));
        }

        if (DisableScreenshotPopupPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_SCREENSHOT_POPUP
            ));
        }

        if (RemoveSubRedditDialogPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NSFW_DIALOG
            ));
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NOTIFICATION_DIALOG
            ));
        }

        if (SearchHiddenProfilesPatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SEARCH_HIDDEN_PROFILES
            ));
        }

        if (HideCommunityMenuBadgePatch.isPatchIncluded()) {
            group.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_COMMUNITY_MENU_BADGE
            ));
        }
    }
}
