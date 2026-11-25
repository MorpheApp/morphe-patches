/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.migrateOldSettingToNew;
import static app.morphe.extension.shared.settings.Setting.parent;
import static app.morphe.extension.shared.settings.Setting.parentNot;

import app.morphe.extension.reddit.patches.AutoModeratorCommentsPatch.AutoModeratorComments;
import app.morphe.extension.reddit.patches.CollapseAllCommentsPatch.CollapseAllScope;
import app.morphe.extension.reddit.patches.ColorizeCommentIndentLinesPatch.CommentLinePalette;
import app.morphe.extension.reddit.patches.FeedAutoScrollPatch.ScrollSpeed;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.FrostedGlassAvailability;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.GlassColor;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.GlassStyle;
import app.morphe.extension.reddit.patches.PostSidePanelPatch.FeedDim;
import app.morphe.extension.reddit.patches.PostSidePanelPatch.PanelWidth;
import app.morphe.extension.reddit.patches.PrivateBrowsingModePatch.PrivateBrowsingMode;
import app.morphe.extension.reddit.patches.TabletGridFeedPatch.GridColumns;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;

public class Settings extends BaseSettings {
    // Ads
    public static final BooleanSetting HIDE_COMMENT_ADS = new BooleanSetting("morphe_hide_comment_ads", TRUE, true);
    public static final BooleanSetting HIDE_POST_ADS = new BooleanSetting("morphe_hide_post_ads", TRUE, true);

    // Navigation bar
    public static final BooleanSetting DISABLE_BOTTOM_BAR_AUTO_HIDE = new BooleanSetting("morphe_disable_bottom_bar_auto_hide", FALSE);
    public static final BooleanSetting GLASS_NAVIGATION_BAR = new BooleanSetting("morphe_glass_navigation_bar", FALSE, true);
    public static final EnumSetting<GlassColor> GLASS_NAVIGATION_BAR_COLOR = new EnumSetting<>("morphe_glass_navigation_bar_color", GlassColor.AUTO, true, parent(GLASS_NAVIGATION_BAR));
    public static final EnumSetting<GlassStyle> GLASS_NAVIGATION_BAR_STYLE = new EnumSetting<>("morphe_glass_navigation_bar_style", GlassStyle.TINTED, true, parent(GLASS_NAVIGATION_BAR));
    public static final IntegerSetting GLASS_NAVIGATION_BAR_BLUR = new IntegerSetting("morphe_glass_navigation_bar_blur", 60, true, new FrostedGlassAvailability());
    public static final IntegerSetting GLASS_NAVIGATION_BAR_OPACITY = new IntegerSetting("morphe_glass_navigation_bar_opacity", 70, true, parent(GLASS_NAVIGATION_BAR));
    public static final BooleanSetting GLASS_NAVIGATION_BAR_ROUND_ENDS = new BooleanSetting("morphe_glass_navigation_bar_round_ends", TRUE, true, parent(GLASS_NAVIGATION_BAR));
    public static final IntegerSetting GLASS_NAVIGATION_BAR_HEIGHT = new IntegerSetting("morphe_glass_navigation_bar_height", 100, true, parent(GLASS_NAVIGATION_BAR));
    public static final IntegerSetting GLASS_NAVIGATION_BAR_WIDTH = new IntegerSetting("morphe_glass_navigation_bar_width", 100, true, parent(GLASS_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_ANSWERS_BUTTON = new BooleanSetting("morphe_hide_answers_button", FALSE, true);
    public static final BooleanSetting HIDE_CHAT_BUTTON = new BooleanSetting("morphe_hide_chat_button", FALSE, true);
    public static final BooleanSetting HIDE_CREATE_BUTTON = new BooleanSetting("morphe_hide_create_button", FALSE, true);
    public static final BooleanSetting HIDE_DISCOVER_BUTTON = new BooleanSetting("morphe_hide_discover_button", FALSE, true);
    public static final BooleanSetting HIDE_GAMES_BUTTON = new BooleanSetting("morphe_hide_games_button", FALSE, true);

    // Post buttons
    public static final BooleanSetting HIDE_POST_AWARD_BUTTON = new BooleanSetting("morphe_hide_post_award_button", FALSE, true);
    public static final BooleanSetting HIDE_POST_COMMENT_BUTTON = new BooleanSetting("morphe_hide_post_comment_button", FALSE, true);
    public static final BooleanSetting HIDE_POST_JOIN_BUTTON = new BooleanSetting("morphe_hide_post_join_button", FALSE, true);
    public static final BooleanSetting HIDE_POST_MOD_BUTTON = new BooleanSetting("morphe_hide_post_mod_button", FALSE, true);
    public static final BooleanSetting HIDE_POST_REPOST_BUTTON = new BooleanSetting("morphe_hide_post_repost_button", FALSE, true);
    public static final BooleanSetting HIDE_POST_SHARE_BUTTON = new BooleanSetting("morphe_hide_post_share_button", FALSE, true);
    public static final BooleanSetting HIDE_POST_SHARE_COUNT = new BooleanSetting("morphe_hide_post_share_count", FALSE, true);
    public static final BooleanSetting HIDE_POST_VOTE_BUTTONS = new BooleanSetting("morphe_hide_post_vote_buttons", FALSE, true);
    public static final BooleanSetting HIDE_POST_VOTE_COUNT = new BooleanSetting("morphe_hide_post_vote_count", FALSE, true);

    // Sidebar
    public static final BooleanSetting HIDE_ABOUT_SHELF = new BooleanSetting("morphe_hide_about_shelf", FALSE, true);
    public static final BooleanSetting HIDE_DISCOVER_COMMUNITIES_SHELF = new BooleanSetting("morphe_hide_discover_communities_shelf", FALSE, true);
    public static final BooleanSetting HIDE_GAMES_ON_REDDIT_SHELF = new BooleanSetting("morphe_hide_games_on_reddit_shelf", FALSE, true);
    public static final BooleanSetting HIDE_LATEST_SHELF = new BooleanSetting("morphe_hide_latest_shelf", FALSE, true);
    public static final BooleanSetting HIDE_POPULAR_SHELF = new BooleanSetting("morphe_hide_popular_shelf", FALSE, true);
    public static final BooleanSetting HIDE_RECENTLY_VISITED_SHELF = new BooleanSetting("morphe_hide_recently_visited_shelf", FALSE, true);
    public static final BooleanSetting HIDE_REDDIT_PRO_SHELF = new BooleanSetting("morphe_hide_reddit_pro_shelf", FALSE, true);
    public static final BooleanSetting HIDE_RESOURCES_SHELF = new BooleanSetting("morphe_hide_resources_shelf", FALSE, true);
    public static final BooleanSetting HIDE_START_A_COMMUNITY_SHELF = new BooleanSetting("morphe_hide_start_a_community_shelf", FALSE, true);

    // Filters
    /**
     * Flair filters such as gaming::Discussion, one per line.
     */
    public static final StringSetting FLAIR_FILTERS = new StringSetting("morphe_flair_filters", "");
    public static final BooleanSetting FLAIR_FILTERS_REMEMBER_SEEN = new BooleanSetting("morphe_flair_filters_remember_seen", FALSE);
    /**
     * Regular expressions, one per line.
     */
    public static final StringSetting KEYWORD_FILTERS = new StringSetting("morphe_keyword_filters", "");

    // Layout
    public static final BooleanSetting ALWAYS_SHOW_HIDE = new BooleanSetting("morphe_always_show_hide", FALSE);
    public static final EnumSetting<AutoModeratorComments> AUTOMODERATOR_COMMENTS = new EnumSetting<>("morphe_automoderator_comments", AutoModeratorComments.SHOW);
    public static final BooleanSetting COLLAPSE_ALL_COMMENTS = new BooleanSetting("morphe_collapse_all_comments", TRUE);
    public static final EnumSetting<CollapseAllScope> COLLAPSE_ALL_COMMENTS_SCOPE = new EnumSetting<>("morphe_collapse_all_comments_scope", CollapseAllScope.TOP_COMMENTS, parent(COLLAPSE_ALL_COMMENTS));
    public static final BooleanSetting COLORIZE_COMMENT_INDENT_LINES = new BooleanSetting("morphe_colorize_comment_indent_lines", FALSE);
    public static final EnumSetting<CommentLinePalette> COLORIZE_COMMENT_INDENT_LINES_PALETTE = new EnumSetting<>("morphe_colorize_comment_indent_lines_palette", CommentLinePalette.RAINBOW, parent(COLORIZE_COMMENT_INDENT_LINES));
    public static final BooleanSetting COPY_TEXT = new BooleanSetting("morphe_copy_text", FALSE, true);
    public static final BooleanSetting CUSTOM_FONT = new BooleanSetting("morphe_custom_font", FALSE, true);
    public static final StringSetting CUSTOM_FONT_FILE_PATH = new StringSetting("morphe_custom_font_file_path", "", true);
    public static final BooleanSetting DISABLE_HOME_FEED_AUTO_REFRESH = new BooleanSetting("morphe_disable_home_feed_auto_refresh", FALSE);
    public static final BooleanSetting DISABLE_HOME_FEED_SWIPE = new BooleanSetting("morphe_disable_home_feed_swipe", FALSE, true);
    public static final BooleanSetting DISABLE_MODERN_HOME = new BooleanSetting("morphe_disable_modern_home", FALSE, true);
    public static final BooleanSetting DISABLE_REFRESH_ON_BACK = new BooleanSetting("morphe_disable_refresh_on_back", FALSE);
    public static final BooleanSetting DISABLE_SCREENSHOT_POPUP = new BooleanSetting("morphe_disable_screenshot_popup", TRUE, true);
    public static final BooleanSetting FEED_AUTO_SCROLL = new BooleanSetting("morphe_feed_auto_scroll", FALSE, true);
    public static final EnumSetting<ScrollSpeed> FEED_AUTO_SCROLL_SPEED = new EnumSetting<>("morphe_feed_auto_scroll_speed", ScrollSpeed.MEDIUM, parent(FEED_AUTO_SCROLL));
    public static final FloatSetting FEED_MEDIA_MAX_HEIGHT = new FloatSetting("morphe_feed_media_max_height", 1.33f);
    public static final BooleanSetting FLIP_POST_ACTION_BAR = new BooleanSetting("morphe_flip_post_action_bar", FALSE, true);
    public static final BooleanSetting FORCE_SYSTEM_FONT = new BooleanSetting("morphe_force_system_font", FALSE, true);
    public static final BooleanSetting FULL_SCREEN_REPLY = new BooleanSetting("morphe_full_screen_reply", FALSE, true);
    public static final BooleanSetting FULL_WIDTH_FEED_MEDIA = new BooleanSetting("morphe_full_width_feed_media", FALSE, true);
    public static final BooleanSetting HIDE_ASK_BUTTON = new BooleanSetting("morphe_hide_ask_button", FALSE, true);
    public static final BooleanSetting HIDE_COMMUNITIES_SHELF = new BooleanSetting("morphe_hide_communities_shelf", FALSE, true);
    public static final BooleanSetting HIDE_FOLLOWING_TAB = new BooleanSetting("morphe_hide_following_tab", FALSE, true, parentNot(DISABLE_MODERN_HOME));
    public static final BooleanSetting HIDE_FOR_YOU_TAB = new BooleanSetting("morphe_hide_for_you_tab", FALSE, true, parentNot(DISABLE_MODERN_HOME));
    public static final BooleanSetting HIDE_INTERACTIVE_POSTS = new BooleanSetting("morphe_hide_interactive_posts", FALSE, true);
    public static final BooleanSetting HIDE_JOIN_CONVERSATION_BUTTON = new BooleanSetting("morphe_hide_join_conversation_button", FALSE);
    public static final BooleanSetting HIDE_MEDIA_VIEWER_OVERLAY = new BooleanSetting("morphe_hide_media_viewer_overlay", FALSE);
    public static final BooleanSetting HIDE_PEOPLE_ALSO_SEARCH_FOR_SECTION = new BooleanSetting("morphe_hide_people_also_search_for_section", FALSE, true);
    public static final BooleanSetting HIDE_POPULAR_TAB = new BooleanSetting("morphe_hide_popular_tab", FALSE, true, parentNot(DISABLE_MODERN_HOME));
    public static final BooleanSetting HIDE_RELATED_POSTS_SECTION = new BooleanSetting("morphe_hide_related_posts_section", FALSE, true);
    public static final BooleanSetting HIDE_TRENDING_SHELVES = new BooleanSetting("morphe_hide_trending_shelves", FALSE, true);
    public static final BooleanSetting HIDE_VIDEOS_FOR_YOU_SHELF = new BooleanSetting("morphe_hide_videos_for_you_shelf", FALSE, true);
    public static final BooleanSetting HIGH_QUALITY_AVATARS = new BooleanSetting("morphe_high_quality_avatars", TRUE);
    public static final BooleanSetting HOME_FEED_TABS = new BooleanSetting("morphe_home_feed_tabs", FALSE, true, parentNot(DISABLE_MODERN_HOME));
    public static final BooleanSetting KEEP_FEED_POSITION = new BooleanSetting("morphe_keep_feed_position", TRUE);
    public static final BooleanSetting KEEP_MEDIA_VIEWER_IN_SUBREDDIT = new BooleanSetting("morphe_keep_media_viewer_in_subreddit", FALSE);
    public static final BooleanSetting MEDIA_VIEWER_BLACK_BACKGROUND = new BooleanSetting("morphe_media_viewer_black_background", FALSE);
    public static final FloatSetting MEDIA_VIEWER_FADE = new FloatSetting("morphe_media_viewer_fade", 1f);
    public static final BooleanSetting POST_SIDE_PANEL = new BooleanSetting("morphe_post_side_panel", TRUE);
    public static final EnumSetting<PanelWidth> POST_SIDE_PANEL_WIDTH =
            new EnumSetting<>("morphe_post_side_panel_width", PanelWidth.ONE_THIRD, parent(POST_SIDE_PANEL));
    public static final BooleanSetting POST_SIDE_PANEL_REMEMBER_WIDTH =
            new BooleanSetting("morphe_post_side_panel_remember_width", TRUE, parent(POST_SIDE_PANEL));
    public static final EnumSetting<FeedDim> POST_SIDE_PANEL_DIM =
            new EnumSetting<>("morphe_post_side_panel_dim", FeedDim.MEDIUM, parent(POST_SIDE_PANEL));
    public static final BooleanSetting REMEMBER_COLLAPSED_COMMENTS = new BooleanSetting("morphe_remember_collapsed_comments", TRUE);
    public static final BooleanSetting REMOVE_NOTIFICATION_DIALOG = new BooleanSetting("morphe_remove_notification_dialog", FALSE, true);
    public static final BooleanSetting REMOVE_NSFW_DIALOG = new BooleanSetting("morphe_remove_nsfw_dialog", FALSE, true);
    public static final BooleanSetting SEARCH_HIDDEN_PROFILES = new BooleanSetting("morphe_search_hidden_profiles", FALSE);
    public static final BooleanSetting SHOW_FLAIRS_IN_HOME_FEED = new BooleanSetting("morphe_show_flairs_in_home_feed", FALSE, true, "morphe_show_flairs_in_home_feed_user_dialog_message");
    public static final BooleanSetting SHOW_VIEW_COUNT = new BooleanSetting("morphe_show_view_count", FALSE, true);
    public static final BooleanSetting SWAP_POST_VOTE_COMMENT_BUTTONS = new BooleanSetting("morphe_swap_post_vote_comment_buttons", FALSE, true);
    public static final BooleanSetting SWIPE_UP_FOR_COMMENTS = new BooleanSetting("morphe_swipe_up_for_comments", FALSE, true);
    public static final BooleanSetting TABLET_GRID_FEED = new BooleanSetting("morphe_tablet_grid_feed", TRUE);
    public static final EnumSetting<GridColumns> TABLET_GRID_COLUMNS = new EnumSetting<>("morphe_tablet_grid_columns", GridColumns.AUTO, parent(TABLET_GRID_FEED));

    // Miscellaneous
    public static final BooleanSetting DOWNLOAD_ALL_FROM_GALLERY = new BooleanSetting("morphe_download_all_from_gallery", TRUE);
    public static final StringSetting DOWNLOAD_FOLDER = new StringSetting("morphe_download_folder", "");
    public static final StringSetting DOWNLOAD_PATH_PATTERN = new StringSetting("morphe_download_path_pattern", "");
    /**
     * JSON object of account names to their download options, which override the options above.
     */
    public static final StringSetting DOWNLOAD_ACCOUNTS = new StringSetting("morphe_download_accounts", "");
    public static final BooleanSetting OPEN_LINKS_DIRECTLY = new BooleanSetting("morphe_open_links_directly", TRUE);
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY = new BooleanSetting("morphe_open_links_externally", TRUE);
    public static final EnumSetting<PrivateBrowsingMode> PRIVATE_BROWSING_MODE = new EnumSetting<>("morphe_private_browsing_mode", PrivateBrowsingMode.DEFAULT, true);
    public static final BooleanSetting REDGIFS_AUDIO = new BooleanSetting("morphe_redgifs_audio", TRUE, true);
    public static final BooleanSetting REDGIFS_HD = new BooleanSetting("morphe_redgifs_hd", FALSE);
    public static final BooleanSetting SANITIZE_SHARING_LINKS = new BooleanSetting("morphe_sanitize_sharing_links", TRUE);
    public static final BooleanSetting SHARE_POST_CONTENT = new BooleanSetting("morphe_share_post_content", FALSE);

    /**
     * Highest app version that was ever launched. Used to detect an app downgrade.
     */
    public static final StringSetting HIGHEST_APP_VERSION = new StringSetting("morphe_highest_app_version", "", false, false);

    /**
     * Post side panel width dragged to, as a fraction of the feed width. 0 if not dragged
     * since the panel width was last chosen.
     */
    public static final FloatSetting POST_SIDE_PANEL_DRAGGED_WIDTH = new FloatSetting("morphe_post_side_panel_dragged_width", 0f, false, false);

    private static final BooleanSetting DEPRECATED_HIDE_RECOMMENDED_COMMUNITIES_SHELF = new BooleanSetting("morphe_hide_recommended_communities_shelf", FALSE, true);
    private static final BooleanSetting DEPRECATED_HIDE_TRENDING_TODAY_SHELF = new BooleanSetting("morphe_hide_trending_today_shelf", FALSE, true);
    private static final BooleanSetting DEPRECATED_SANITIZE_URL_QUERY = new BooleanSetting("morphe_sanitize_url_query", TRUE);

    static {
        SeekBarPreference.register(new SeekBarConfig(GLASS_NAVIGATION_BAR_BLUR,
                0, 100, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(GLASS_NAVIGATION_BAR_OPACITY,
                0, 100, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(GLASS_NAVIGATION_BAR_HEIGHT,
                70, 150, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(GLASS_NAVIGATION_BAR_WIDTH,
                50, 100, 5, "%"));

        // region Migration

        migrateOldSettingToNew(DEPRECATED_HIDE_RECOMMENDED_COMMUNITIES_SHELF, HIDE_COMMUNITIES_SHELF);
        migrateOldSettingToNew(DEPRECATED_HIDE_TRENDING_TODAY_SHELF, HIDE_TRENDING_SHELVES);
        migrateOldSettingToNew(DEPRECATED_SANITIZE_URL_QUERY, SANITIZE_SHARING_LINKS);

        // endregion
    }
}
