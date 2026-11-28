/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.FilterGroup.FilterGroupResult;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.Element;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupViewModel;
import app.morphe.extension.youtube.patches.ChangeHeaderPatch;
import app.morphe.extension.youtube.patches.FeedViewCountPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class LayoutComponentsFilter extends Filter {
    public static final int CONTAINER_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "time_bar_chapter_title_container");

    /**
     * Key of the auto-generated chapters in the markers map of the player response.
     * Chapters written by the creator use the key "DESCRIPTION_CHAPTERS".
     */
    private static final String AUTO_CHAPTERS_KEY = "AUTO_CHAPTERS";

    /**
     * Start of the Base64 entity key of the auto-generated chapters, which encodes
     * {@link #AUTO_CHAPTERS_KEY}. The full key is "Eg1BVVRPX0NIQVBURVJTIJICKAE%3D".
     */
    private static final String AUTO_CHAPTERS_ENTITY_KEY_PREFIX = "Eg1BVVRPX0NIQVBURVJT";

    private static final ByteArrayFilterGroup mixPlaylistUrlBuffer = new ByteArrayFilterGroup(
            null,
            "?list=RD",
            "&list=RD"
    );
    private static final ByteArrayFilterGroup mixPlaylistsBuffersExceptions = new ByteArrayFilterGroup(
            null,
            "cell_description_body",
            "channel_profile"
    );

    /**
     * Template of the cards of a newer layout, which have no url of the mix.
     */
    private static final String LOCKUP_TEMPLATE = "lockup.eml";
    /**
     * Part of the name of the mix icon on the thumbnail of a card, such as "yt_outline_experimental_mix_black_24".
     */
    private static final String LOCKUP_MIX_ICON = "_mix_";
    private static final String MIX_PLAYLIST_ID_PREFIX = "RD";
    private static final int LOCKUP_CONTENT_TYPE_PLAYLIST = 2;
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private static final List<String> channelTabFilterStrings = Utils.getFilterStrings(Settings.HIDE_CHANNEL_TAB_FILTER_STRINGS);
    private static final List<String> flyoutMenuFilterStrings = Utils.getFilterStrings(Settings.HIDE_FEED_FLYOUT_MENU_FILTER_STRINGS);
    /**
     * Texts of the download item of the menus. Newer app versions also have the second text.
     */
    private static final String[] FLYOUT_DOWNLOAD_TEXTS = {"add_video_to_offline", "menu_add_to_offline"};

    @Nullable
    private static List<?> channelTabs;
    @Nullable
    private static List<?> originalChannelTabs;

    /**
     * Browse id of the community page of a channel, which shows only community posts.
     */
    private static final String COMMUNITY_PAGE_BROWSE_ID = "FEcommunity_page";

    private static final String POST_DETAIL_BROWSE_ID = "FEpost_detail";

    /**
     * Path of the metadata of a video of a channel page, such as "1M · 2d ago".
     */
    private static final String VIDEO_METADATA_CONTENT_PATH = "|metadata_content.e";

    /**
     * Spaces between the view count and the upload time of a feed subtitle with an icon.
     * The view count and the upload time have no two spaces in a row.
     */
    private static final String ICON_SUBTITLE_SEPARATOR = "  ";

    /**
     * Delimiters between the sections of a feed subtitle.
     */
    private static final String[] SUBTITLE_DELIMITERS = {" · ", " • "};

    /**
     * Browse id of the page that is shown, or empty for the search feed.
     * Also used by {@link HorizontalShelvesFilter}.
     */
    static volatile String browseId = "";

    /**
     * Injection point.
     */
    public static void setBrowseId(@Nullable String id) {
        browseId = id == null ? "" : id;
    }

    /**
     * Injection point.
     * <p>
     * The search feed is not a browse page.
     */
    public static void clearBrowseId() {
        browseId = "";
    }

    private final StringTrieSearch exceptions = new StringTrieSearch();

    private final StringFilterGroup channelProfileSubscribeButton;
    private final ByteArrayFilterGroup channelProfileSubscribeButtonBuffer;
    private final ByteArrayFilterGroup buttonComponentBuffer;
    private final StringFilterGroup channelFilterBar;
    private final StringFilterGroup channelHandle;
    private final StringFilterGroup chipBar;
    private final StringFilterGroup communityPosts;
    private final StringFilterGroup communityPostsNew;
    private final ByteArrayFilterGroup communityPostsNewBuffer;
    private final StringFilterGroup compactChannel;
    private final StringFilterGroup compactChannelMusicBanner;
    private final ByteArrayFilterGroup youtubeMusicChannelLink;
    private final StringFilterGroup compactChannelBarInner;
    private final StringFilterGroup compactChannelBarInnerButton;
    private final ByteArrayFilterGroup joinMembershipButton;
    private final StringFilterGroup expandableMetadata;
    private final ByteArrayFilterGroup summaryCardBuffer;
    private final StringFilterGroup exploreTopicsShelf;
    private final StringFilterGroup linearLayout;
    private final ByteArrayFilterGroupList linearLayoutGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup liveStream;
    private final ByteArrayFilterGroup liveStreamBuffer;
    private final StringFilterGroup libraryItem;
    private final ByteArrayFilterGroupList libraryItemGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup notificationsMenuHeader;
    private final ByteArrayFilterGroup notificationsMenuHeaderBuffer;
    private final StringFilterGroup notifyMe;
    private final StringFilterGroup playables;
    private final StringFilterGroup searchFriction;
    private final StringFilterGroup singleItemInformationPanel;
    private final StringFilterGroup subscribedChannelsBarName;
    private static final AtomicInteger singleItemInformationPanelIndex = new AtomicInteger(-1);
    private final StringFilterGroup surveys;
    private final StringFilterGroup thumbnailOverlayBadge;
    private final ByteArrayFilterGroup thumbnailCommissionBadgeBuffer;
    private final StringFilterGroup videoLabels;
    private final ByteArrayFilterGroup videoLabelsBuffer;
    private final ByteArrayFilterGroup badgeThemeBuffer;
    private final ByteArrayFilterGroup videoLabelFillIconBuffer;
    private final ByteArrayFilterGroup videoLabelOutlineIconBuffer;
    private final ByteArrayFilterGroup membersOnlyBadgeIconBuffer;
    private final StringFilterGroup videoMetadataLabels;
    private final StringFilterGroup videoRecommendationLabels;

    public enum ExpandableCardStyle {
        SHOW_ALL,
        HIDE_SUMMARY_ONLY,
        HIDE_ALL
    }

    public LayoutComponentsFilter() {
        exceptions.addPatterns(
                "comment_thread", // Whitelist comments
                "|comment.", // Whitelist comment replies
                "library_recent_shelf"
        );

        // Identifiers.

        final var cellDivider = new StringFilterGroup(
                Settings.HIDE_COMPACT_BANNER,
                // Empty padding and a relic from very old YT versions.
                // Not related to compact banner but included here to avoid adding another setting.
                "cell_divider"
        );

        exploreTopicsShelf = new StringFilterGroup(
                Settings.HIDE_HORIZONTAL_SHELVES,
                "chips_shelf"
        );

        final var liveChatReplay = new StringFilterGroup(
                Settings.HIDE_LIVE_CHAT_REPLAY_BUTTON,
                "live_chat_ep_entrypoint.e"
        );

        // The 'Invite others to message' card of the Messages section shown at the top of
        // the Notifications tab, wrapped in a linear layout and identified by a unique,
        // language independent buffer string.
        //
        // The 'Messages' shelf header above the card is deliberately not hidden: every
        // section header of the Notifications tab ('Messages', 'Notifications', 'Today',
        // 'This week', 'Older') uses the exact same identifier and an otherwise byte
        // identical buffer, and the title is localized by the server without an app string
        // resource, so there is no language independent way to match it.
        linearLayout = new StringFilterGroup(
                null,
                "linear_layout.e"
        );

        linearLayoutGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_INVITE_TO_MESSAGE_CARD,
                        "connections_inbox_zero_state"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_UNAVAILABLE_VIDEOS_HIDDEN_BANNER,
                        "basic_content"
                )
        );

        // Feed and search result items.
        liveStream = new StringFilterGroup(
                Settings.HIDE_LIVE_STREAMS,
                "video_lockup_with_attachment.e"
        );

        // Icon of the 'LIVE' thumbnail badge. Only videos that are live now have it,
        // past streams ('Streamed 2 months ago') do not.
        liveStreamBuffer = new ByteArrayFilterGroup(
                null,
                "yt_outline_live_black",
                "yt_outline_experimental_live_black"
        );

        // Built-in items of the 'You' tab, such as 'Downloads'. They are lockups, so the bottom menu
        // filter can't match their names. The settings are checked by the buffer groups.
        libraryItem = new StringFilterGroup(
                null,
                "video_lockup_with_attachment.e"
        );

        // The titles are localized, so the items are found by their thumbnail, icon, playlist or page.
        // Newer layouts of the 'You' tab show a thumbnail instead of the icon.
        libraryItemGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_ACCOUNT_MENU_BADGES,
                        "/youtube/app_shell/badges_thumbnail",
                        "FEtrophies"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_ACCOUNT_MENU_DOWNLOADS,
                        "yt_outline_download_black",
                        "yt_outline_experimental_download_black",
                        "/youtube/app_shell/download_background"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_ACCOUNT_MENU_LIKED_VIDEOS,
                        "/youtube/app_shell/thumb_up_background",
                        "/playlist?list=LL"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_ACCOUNT_MENU_WATCH_LATER,
                        "/youtube/app_shell/clock_background",
                        "/playlist?list=WL"
                )
        );

        final var privacyPolicyToS = new StringFilterGroup(
                Settings.HIDE_ACCOUNT_MENU_PRIVACY_TOS_FOOTER,
                "privacy_tos_cell.e"
        );

        // The hint shown in the player during seek gestures. The identifier is versioned.
        final var seekEduOverlay = new StringFilterGroup(
                Settings.HIDE_PLAYER_GESTURE_HINTS,
                "seek_edu_overlay"
        );

        addIdentifierCallbacks(
                cellDivider,
                exploreTopicsShelf,
                linearLayout,
                liveChatReplay,
                liveStream,
                libraryItem,
                privacyPolicyToS,
                seekEduOverlay
        );

        // Paths.

        final var artistCard = new StringFilterGroup(
                Settings.HIDE_ARTIST_CARDS,
                "official_card"
        );

        // The player audio track button does the exact same function as the audio track flyout menu option.
        // Previously this was a setting to show/hide the player button.
        // But it was decided it's simpler to always hide this button because:
        // - the button is rare
        // - always hiding makes the Morphe settings simpler and easier to understand
        // - nobody is going to notice the redundant button is always hidden
        final var audioTrackButton = new StringFilterGroup(
                null,
                "multi_feed_icon_button"
        );

        channelFilterBar = new StringFilterGroup(
                null,
                "channels_chip_bar.e"
        );

        final var channelLinksPreview = new StringFilterGroup(
                Settings.HIDE_LINKS_PREVIEW,
                "attribution.e"
        );

        final var channelMembersShelf = new StringFilterGroup(
                Settings.HIDE_MEMBERS_SHELF,
                "member_recognition_shelf"
        );

        channelProfileSubscribeButton = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBE_BUTTON_IN_CHANNEL_PAGE,
                "channel_action_buttons_phone.e"
        );
        channelProfileSubscribeButtonBuffer = new ByteArrayFilterGroup(
                null,
                "subscribe_button.e"
        );
        buttonComponentBuffer = new ByteArrayFilterGroup(
                null,
                "button.e"
        );

        channelHandle = new StringFilterGroup(
                Settings.HIDE_HANDLE,
                "content_metadata.e"
        );

        final var channelWatermark = new StringFilterGroup(
                Settings.HIDE_CHANNEL_WATERMARK,
                "featured_channel_watermark_overlay"
        );

        chipBar = new StringFilterGroup(
                Settings.HIDE_FILTER_BAR_IN_HISTORY,
                "chip_bar"
        );

        final var compactBanner = new StringFilterGroup(
                Settings.HIDE_COMPACT_BANNER,
                "compact_banner"
        );

        compactChannel = new StringFilterGroup(
                Settings.HIDE_CHANNEL_BUTTONS,
                "compact_channel.e"
        );

        // With some device layouts, such as Automotive, the YouTube Music button of a channel
        // in the search results is a banner below the channel instead of a button of the channel.
        compactChannelMusicBanner = new StringFilterGroup(
                Settings.HIDE_CHANNEL_BUTTONS,
                "button_banner.e"
        );

        youtubeMusicChannelLink = new ByteArrayFilterGroup(
                null,
                "vnd.youtube.music://music.youtube.com/channel/"
        );

        final var compactChannelBar = new StringFilterGroup(
                Settings.HIDE_CHANNEL_BAR,
                "compact_channel_bar"
        );

        compactChannelBarInner = new StringFilterGroup(
                Settings.HIDE_JOIN_MEMBERSHIP_BUTTON,
                "compact_channel_bar_inner",
                "video_description_header"
        );

        compactChannelBarInnerButton = new StringFilterGroup(
                null,
                "|button.e"
        );

        joinMembershipButton = new ByteArrayFilterGroup(
                null,
                "sponsorships"
        );

        communityPosts = new StringFilterGroup(
                Settings.HIDE_COMMUNITY_POSTS,
                "images_post_responsive_root.e",
                "images_post_root.e",
                "images_post_root_slim.e",
                "images_post_slim.e", // may be obsolete and no longer needed.
                "options_post_responsive_root.e",
                "options_post_root.e",
                "poll_post_responsive_root.e",
                "poll_post_root.e",
                "post_base_wrapper", // may be obsolete and no longer needed.
                "post_base_wrapper_slim.e",
                "post_shelf_slim.e",
                "shared_post_responsive_root.e",
                "shared_post_root.e",
                "text_post_responsive_root.e",
                "text_post_root.e",
                "text_post_root_slim.e",
                "videos_post_responsive_root.e",
                "videos_post_root.e"
        );

        communityPostsNew = new StringFilterGroup(
                Settings.HIDE_COMMUNITY_POSTS,
                "lockup.e"
        );

        communityPostsNewBuffer = new ByteArrayFilterGroup(
                null,
                "FEpost_home"
        );

        final var crowdfundingBox = new StringFilterGroup(
                Settings.HIDE_CROWDFUNDING_BOX,
                "donation_shelf"
        );

        final var emergencyBox = new StringFilterGroup(
                Settings.HIDE_EMERGENCY_BOX,
                "emergency_onebox"
        );

        expandableMetadata = new StringFilterGroup(
                null,
                "expandable_metadata",
                "inline_expander"
        );

        summaryCardBuffer = new ByteArrayFilterGroup(
                null,
                "PAfeedback_genai"
        );

        final var imageShelf = new StringFilterGroup(
                Settings.HIDE_IMAGE_SHELF,
                "image_shelf"
        );

        final var infoPanel = new StringFilterGroup(
                Settings.HIDE_INFO_PANELS,
                "publisher_transparency_panel"
        );

        final var medicalPanel = new StringFilterGroup(
                Settings.HIDE_MEDICAL_PANELS,
                "medical_panel"
        );

        notificationsMenuHeader = new StringFilterGroup(
                Settings.HIDE_NOTIFICATIONS_MENU_HEADER,
                "|ContainerType|ContainerType|"
        );

        notificationsMenuHeaderBuffer = new ByteArrayFilterGroup(
                null,
                "/youtube/answer/" // https://support.google.com/youtube/answer/
        );

        notifyMe = new StringFilterGroup(
                Settings.HIDE_NOTIFY_ME_BUTTON,
                "set_reminder_button"
        );

        playables = new StringFilterGroup(
                Settings.HIDE_PLAYABLES,
                "horizontal_gaming_shelf.e",
                "mini_game_card.e"
        );

        final var postsShelf = new StringFilterGroup(
                Settings.HIDE_POSTS_SHELF,
                "post_shelf"
        );

        searchFriction = new StringFilterGroup(
                Settings.HIDE_INFO_PANELS,
                "search_friction"
        );

        singleItemInformationPanel = new StringFilterGroup(
                Settings.HIDE_INFO_PANELS,
                "single_item_information_panel"
        );

        final var subscribedChannelsBar = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBED_CHANNELS_BAR,
                "subscriptions_channel_bar"
        );

        // The name under each channel avatar of the bar is the only text of the channel.
        subscribedChannelsBarName = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBED_CHANNELS_BAR_NAMES,
                "subscriptions_channel_bar_channel.e"
        );

        final var subscribersCommunityGuidelines = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBERS_COMMUNITY_GUIDELINES,
                "sponsorships_comments_upsell"
        );

        final var subscriptionsChipBar = new StringFilterGroup(
                Settings.HIDE_FILTER_BAR_IN_FEED,
                "subscriptions_chip_bar"
        );

        surveys = new StringFilterGroup(
                Settings.HIDE_SURVEYS,
                "in_feed_survey",
                "slimline_survey",
                "feed_nudge",
                "in_short_survey"
        );

        thumbnailOverlayBadge = new StringFilterGroup(
                Settings.HIDE_VIDEO_LABELS,
                "thumbnail_badge.e"
        );

        thumbnailCommissionBadgeBuffer = new ByteArrayFilterGroup(
                null,
                "yt_outline_experimental_bag_black",
                "yt_outline_bag_black"
        );

        final var videoThumbnail = new StringFilterGroup(
                Settings.HIDE_VIDEO_THUMBNAIL,
                "video_lockup_thumbnail.e"
        );

        videoLabels = new StringFilterGroup(
                Settings.HIDE_VIDEO_LABELS,
                "|ContainerType|ContainerType|ContainerType|ContainerType|"
        );

        videoLabelsBuffer = new ByteArrayFilterGroup(
                null,
                "badge.e"
        );

        // The text and the icon of a badge are before its theme. In the search results, a list of
        // icons used by the whole video card follows, which is not the icon of the badge.
        badgeThemeBuffer = new ByteArrayFilterGroup(
                null,
                "theme|"
        );

        // Labels such as 'Auto-dubbed' and 'Hyped' have an icon. The texts are localized, so the badges
        // are told apart by their icons. Badges without an icon, such as 'Featured', are not labels.
        // Each prefix is a separate group, as a group only returns the index of its first matched prefix.
        videoLabelFillIconBuffer = new ByteArrayFilterGroup(
                null,
                "yt_fill_"
        );

        videoLabelOutlineIconBuffer = new ByteArrayFilterGroup(
                null,
                "yt_outline_"
        );

        // 'Members only' badge of the videos of channel memberships.
        membersOnlyBadgeIconBuffer = new ByteArrayFilterGroup(
                null,
                "yt_fill_experimental_star_circle"
        );

        // Labels such as 'Auto-dubbed' or 'Hyped' in the metadata of the feed cards of a newer A/B test.
        videoMetadataLabels = new StringFilterGroup(
                Settings.HIDE_VIDEO_LABELS,
                "content_metadata.e"
        );

        final var videoTitle = new StringFilterGroup(
                Settings.HIDE_VIDEO_TITLE,
                "player_overlay_video_heading.e"
        );

        videoRecommendationLabels = new StringFilterGroup(
                Settings.HIDE_VIDEO_LABELS,
                "endorsement_header_footer.e"
        );

        final var webLinkPanel = new StringFilterGroup(
                Settings.HIDE_WEB_SEARCH_RESULTS,
                "web_link_panel",
                "web_result_panel"
        );

        // The preview of the comments is loaded separately and added to the post, so it's hidden by its component.
        final var postCommentsPreview = new StringFilterGroup(
                Settings.HIDE_POST_COMMENTS_PREVIEW,
                "post_comments_carousel.e"
        );

        // The like and dislike buttons share their data, so the dislike button alone is hidden by its component.
        final var postDislikeButton = new StringFilterGroup(
                Settings.HIDE_POST_DISLIKE_BUTTON,
                "post_dislike_button.e"
        );

        addPathCallbacks(
                artistCard,
                audioTrackButton,
                channelFilterBar,
                channelHandle,
                channelLinksPreview,
                channelMembersShelf,
                channelProfileSubscribeButton,
                channelWatermark,
                chipBar,
                compactBanner,
                compactChannelBar,
                compactChannel,
                compactChannelMusicBanner,
                compactChannelBarInner,
                communityPosts,
                communityPostsNew,
                crowdfundingBox,
                emergencyBox,
                expandableMetadata,
                imageShelf,
                infoPanel,
                medicalPanel,
                notificationsMenuHeader,
                notifyMe,
                playables,
                postCommentsPreview,
                postDislikeButton,
                postsShelf,
                searchFriction,
                singleItemInformationPanel,
                subscribedChannelsBar,
                subscribedChannelsBarName,
                subscribersCommunityGuidelines,
                subscriptionsChipBar,
                surveys,
                thumbnailOverlayBadge,
                videoThumbnail,
                videoLabels,
                videoMetadataLabels,
                videoTitle,
                videoRecommendationLabels,
                webLinkPanel
        );
    }

    /**
     * The subscribe button can't be removed from the data of the page header like the other buttons,
     * as the header keeps its space. Hide the button and the container that wraps it instead.
     *
     * @param path         Path of an element of the channel page header action buttons.
     * @param buffer       Buffer of the element.
     * @param asciiStrings Texts of the buffer.
     */
    private boolean isChannelProfileSubscribeButtonHidden(CharSequence path, byte[] buffer,
                                                          BufferAsciiStrings asciiStrings) {
        if (!buttonComponentBuffer.check(buffer).isFiltered()) {
            // The spacers between the buttons are the elements without texts outside the buttons.
            return hideChannelProfileHeaderSpacers
                    && !Utils.contains(path, "button.e")
                    && asciiStrings.getStrings().isEmpty();
        }

        return isSingleButton(buffer) && channelProfileSubscribeButtonBuffer.check(buffer).isFiltered();
    }

    /**
     * Hiding only the content of a button leaves the button and the container that wraps it,
     * and their flex style keeps the space of the button and shrinks the other buttons.
     * The buffer of a container holds the buffers of all its children,
     * so the elements to hide are the ones that hold a single button.
     */
    private boolean isSingleButton(byte[] buffer) {
        FilterGroupResult button = buttonComponentBuffer.check(buffer);
        return button.isFiltered() && !buttonComponentBuffer.check(buffer,
                button.getMatchedIndex() + button.getMatchedLength()).isFiltered();
    }

    /**
     * A container of badges holds the buffers of all its badges. A row that has the 'Members only'
     * badge is kept as a whole, and a row that has a label is hidden as a whole, such as the
     * 'products' label with the 'Earns commission' badge in the search results.
     *
     * @param buffer Buffer of a badge, or of a container of badges.
     * @return If the badge is a label to hide, and not a badge such as 'Members only' or 'Featured'.
     */
    private boolean isVideoLabelBadge(byte[] buffer) {
        boolean hasLabelIcon = false;

        FilterGroupResult badge = videoLabelsBuffer.check(buffer);
        while (badge.isFiltered()) {
            final int badgeStart = badge.getMatchedIndex();
            final int themeIndex = badgeThemeBuffer.check(buffer, badgeStart).getMatchedIndex();
            final int badgeEnd = themeIndex < 0 ? buffer.length : themeIndex;

            if (isInBadge(membersOnlyBadgeIconBuffer, buffer, badgeStart, badgeEnd)) {
                return false;
            }
            if (isInBadge(videoLabelFillIconBuffer, buffer, badgeStart, badgeEnd)
                    || isInBadge(videoLabelOutlineIconBuffer, buffer, badgeStart, badgeEnd)) {
                hasLabelIcon = true;
            }

            badge = videoLabelsBuffer.check(buffer, badgeEnd);
        }

        return hasLabelIcon;
    }

    /**
     * @return If the group matches between the start of a badge and its theme.
     */
    private static boolean isInBadge(ByteArrayFilterGroup group, byte[] buffer, int badgeStart, int badgeEnd) {
        final int index = group.check(buffer, badgeStart).getMatchedIndex();
        return index >= 0 && index < badgeEnd;
    }

    /**
     * Only the root lockup holds the whole item buffer. Its nested components share
     * a common icon list that also contains the LIVE badge, so checking them would
     * cause false positives.
     *
     * @return If the path is the root of a feed item: {@code identifier|hash|CellType|}.
     */
    private static boolean isLockupRoot(CharSequence path) {
        return Utils.endsWith(path, "|CellType|");
    }

    /**
     * YouTube now also shows community posts on a dedicated community page for channels,
     * and hiding its posts prevents any posts from loading (the page shows a loading
     * spinner forever) (<a href="https://github.com/MorpheApp/morphe-patches/issues/4101">...</a>).
     * The page is found by its browse id, as the back button it is navigated with is also
     * visible on other pages, such as the search results, and on the page below the watch page.
     * The watch page, with its related videos, comments and other panels, covers the page
     * below it, also when the video is opened from the community page
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/4170">...</a>).
     *
     * @return If the community page of a channel or the page of a post is shown,
     *         where posts must not be hidden.
     */
    static boolean isCommunityPostPage() {
        return (COMMUNITY_PAGE_BROWSE_ID.equals(browseId) || POST_DETAIL_BROWSE_ID.equals(browseId))
                && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static boolean isCommunityPostHidden(ContextInterface contextInterface) {
        if (isCommunityPostPage()) {
            return false;
        }

        return contextInterface.isHomeFeedOrRelatedVideo() || contextInterface.isSubscriptionOrLibrary();
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              CharSequence path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (matchedGroup == exploreTopicsShelf) {
            return NavigationButton.getSelectedNavigationButton(contextInterface) != NavigationButton.LIBRARY;
        }

        // The groups are excluded from the filter due to the exceptions list below.
        // Filter them separately here.
        if (matchedGroup == notifyMe || matchedGroup == surveys) {
            return true;
        }

        // Exceptions are not filtered.
        if (exceptions.matches(path)) {
            return false;
        }

        if (matchedGroup == channelFilterBar) {
            return Settings.HIDE_FILTER_BAR_IN_CHANNEL_PAGE.get();
        }

        if (matchedGroup == channelHandle) {
            return Utils.startsWith(path, "page_header.e")
                    && NavigationButton.getSelectedNavigationButton(contextInterface) == NavigationButton.LIBRARY;
        }

        if (matchedGroup == channelProfileSubscribeButton) {
            return isChannelProfileSubscribeButtonHidden(path, buffer, asciiStrings);
        }

        if (matchedGroup == chipBar) {
            return contentIndex == 0 &&
                    NavigationButton.getSelectedNavigationButton(contextInterface) == NavigationBar.NavigationButton.LIBRARY;
        }

        if (matchedGroup == communityPosts) {
            return isCommunityPostHidden(contextInterface);
        }

        if (matchedGroup == communityPostsNew) {
            // Only the root lockup holds the post endpoint, and the identifier is shared with
            // other feed items, such as videos.
            return isLockupRoot(path)
                    && communityPostsNewBuffer.check(buffer).isFiltered()
                    && isCommunityPostHidden(contextInterface);
        }

        if (matchedGroup == compactChannel) {
            return Utils.contains(path, "button.e");
        }

        if (matchedGroup == compactChannelMusicBanner) {
            return youtubeMusicChannelLink.check(buffer).isFiltered();
        }

        if (matchedGroup == compactChannelBarInner) {
            return compactChannelBarInnerButton.check(path).isFiltered()
                    // The filter may be broad, but in the context of a compactChannelBarInnerButton,
                    // it's safe to assume that the button is the only thing that should be hidden.
                    && joinMembershipButton.check(buffer).isFiltered();
        }

        if (matchedGroup == expandableMetadata) {
            ExpandableCardStyle style = Settings.HIDE_EXPANDABLE_CARD.get();
            switch (style) {
                case HIDE_ALL -> {
                    return true;
                }
                case HIDE_SUMMARY_ONLY -> {
                    return summaryCardBuffer.check(buffer).isFiltered();
                }
                default -> {
                    return false;
                }
            }
        }

        if (matchedGroup == linearLayout) {
            // The identifier is generic and used all over the app.
            if (contentIndex != 0) {
                return false;
            }

            FilterGroupResult result = linearLayoutGroupList.check(buffer);
            if (!result.isFiltered()) {
                return false;
            }

            // Check the navigation button last and only after all buffer checks pass.
            if (result.getSetting() == Settings.HIDE_INVITE_TO_MESSAGE_CARD) {
                return NavigationButton.getSelectedNavigationButton() == NavigationButton.NOTIFICATIONS;
            }

            return true;
        }

        if (matchedGroup == liveStream) {
            // Only check the whole lockup. The buffers of its nested components include
            // a shared list of icon names that also has the 'LIVE' badge icon.
            return isLockupRoot(path) && liveStreamBuffer.check(buffer).isFiltered();
        }

        if (matchedGroup == libraryItem) {
            // Only check the whole lockup, for the same reason as the live streams.
            return isLockupRoot(path)
                    && NavigationButton.getSelectedNavigationButton(contextInterface) == NavigationButton.LIBRARY
                    && libraryItemGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == notificationsMenuHeader) {
            return Utils.startsWith(path, "subscribe_menu_notifications.e")
                    && notificationsMenuHeaderBuffer.check(buffer).isFiltered();
        }

        if (matchedGroup == playables) {
            return !("FEmini_app_destination".equals(browseId));
        }

        // This identifier is used not only in players but also in search results: Until 2024, medical
        // information panels such as Covid-19 also used this identifier and were shown in the search
        // results. From 2025, the medical information panel is no longer shown in the search results.
        // Therefore, this identifier does not filter when the search bar is activated.
        if (matchedGroup == searchFriction) {
            singleItemInformationPanelIndex.set(0);
            return false;
        }

        if (matchedGroup == subscribedChannelsBarName) {
            return Utils.endsWith(path, "|TextType|");
        }

        if (matchedGroup == singleItemInformationPanel) {
            int currentIndex = singleItemInformationPanelIndex.get();

            if (currentIndex < 0) {
                return true;
            }

            if (currentIndex < 9) {
                singleItemInformationPanelIndex.incrementAndGet();
            } else {
                singleItemInformationPanelIndex.set(-1);
            }
            return false;
        }

        if (matchedGroup == thumbnailOverlayBadge) {
            return Utils.contains(path, "thumbnail_overlay_badge.e")
                    && thumbnailCommissionBadgeBuffer.check(buffer).isFiltered();
        }

        if (matchedGroup == videoLabels) {
            // Only the container of the label itself, in the metadata of a video. Badges are also used
            // outside the videos, such as in the rows of the menus, where a container that has a badge
            // can be the whole row.
            return VersionCheckPatch.IS_20_31_OR_GREATER
                    && Utils.contains(path, "lockup_metadata.e")
                    && videoLabelsBuffer.check(buffer).isFiltered()
                    && Utils.startsWith(asciiStrings.getStrings(), "badge.e")
                    && isVideoLabelBadge(buffer);
        }

        if (matchedGroup == videoMetadataLabels) {
            // Only the label itself, and not a larger part of the metadata that contains a label.
            return Utils.startsWith(asciiStrings.getStrings(), "badge.e")
                    && isVideoLabelBadge(buffer);
        }

        if (matchedGroup == videoRecommendationLabels) {
            return NavigationBar.isSearchBarActive();
        }

        return true;
    }

    /**
     * Injection point.
     * Called from a different place then the other filters.
     */
    public static boolean filterMixPlaylists(@Nullable byte[] buffer) {
        try {
            if (!Settings.HIDE_MIX_PLAYLISTS.get()) {
                return false;
            }

            if (buffer == null) {
                Logger.printDebug(() -> "buffer is null");
                return false;
            }

            if (mixPlaylistsBuffersExceptions.check(buffer).isFiltered()) {
                return false;
            }

            // The mix cards are hidden by hideMixCards() when they are loaded. The cards loaded
            // before the setting is turned on are hidden here, until the feed is loaded again.
            if (mixPlaylistUrlBuffer.check(buffer).isFiltered() || isMixCard(buffer)) {
                Logger.printDebug(() -> "Filtered mix playlist");
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "filterMixPlaylists failure", ex);
        }

        return false;
    }

    /**
     * Injection point.
     * Hides the mixes of the card layout (lockup.eml), which have no url of the mix for
     * {@link #filterMixPlaylists(byte[])}. The bytes of the element are removed, so its list item is
     * removed when it's created, without the spacing of the list that an empty list item keeps.
     */
    public static byte[] hideMixCards(byte[] bytes) {
        try {
            if (Settings.HIDE_MIX_PLAYLISTS.get() && isMixCard(bytes)) {
                Logger.printDebug(() -> "Hiding mix playlist card");
                return EMPTY_BYTE_ARRAY;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideMixCards failure", ex);
        }

        return bytes;
    }

    /**
     * @return If the element is a mix of the card layout (lockup.eml), which has the id of the mix
     *         but no url of the mix.
     */
    private static boolean isMixCard(byte[] buffer) throws InvalidProtocolBufferException {
        // The mix icon is checked first, so that only the few cards with the icon are parsed.
        final String elementText = FeedVideoCardsPatch.getElementText(buffer);
        if (!elementText.contains(LOCKUP_TEMPLATE) || !elementText.contains(LOCKUP_MIX_ICON)) {
            return false;
        }

        ElementModel model = Element.parseFrom(buffer).getType().getComponentType().getModel();
        if (!model.hasLockupModel()) {
            return false;
        }

        // A video can have the id of a mix in its tap command, so the content type is checked too.
        LockupViewModel lockup = model.getLockupModel().getViewModel().getLockupViewModel();
        return lockup.getContentType() == LOCKUP_CONTENT_TYPE_PLAYLIST
                && lockup.getContentId().startsWith(MIX_PLAYLIST_ID_PREFIX);
    }

    /**
     * ID of the 'Members only' chip of the channel filter bar.
     */
    private static final String MEMBERS_ONLY_CHIP_ID = "EhAKDgoD6gEACgcaBQoDggEA";

    private static final ByteArrayFilterGroup channelChipBarBuffer = new ByteArrayFilterGroup(
            null,
            "channels_chip_bar.e"
    );
    private static final ByteArrayFilterGroup membersOnlyChipIdBuffer = new ByteArrayFilterGroup(
            null,
            MEMBERS_ONLY_CHIP_ID
    );

    /**
     * Field numbers of the chips in the channel filter bar element,
     * and of the fields of a chip: its id, the group of the chips that exclude each other
     * (the 'Members only' and 'Public' chips), and its text.
     */
    private static final int[] CHANNEL_CHIP_PATH = {37117, 1, 2222};
    private static final int CHANNEL_CHIP_ID_FIELD = 1;
    private static final int CHANNEL_CHIP_GROUP_FIELD = 4;
    private static final int CHANNEL_CHIP_TEXT_FIELD = 7;
    private static final int CHANNEL_CHIP_TEXT_MODEL_FIELD = 515398970;
    private static final int CHANNEL_CHIP_TEXT_LABEL_FIELD = 1;
    private static final int CHANNEL_CHIP_TEXT_SELECTED_FIELD = 3;

    /**
     * The 'Public' chip is laid out after its filter bar is loaded, so it's searched again until it's shown.
     */
    private static final long PUBLIC_CHIP_SEARCH_INTERVAL_MS = 100;
    private static final int PUBLIC_CHIP_SEARCH_MAX_ATTEMPTS = 30;

    /**
     * The list of the channel tab where the 'Public' chip was last selected. Selecting or deselecting
     * a chip loads the filter bar again in the same list, so the chip is selected only once for each
     * opened tab, and the user can deselect it.
     */
    private static WeakReference<View> publicChipSelectedListRef = new WeakReference<>(null);

    /**
     * Incremented for each filter bar loaded without the 'Public' chip selected,
     * so only the search of the last loaded filter bar continues.
     */
    private static int publicChipSearchId;

    /**
     * Injection point.
     * Removes the 'Members only' chip from the data of the channel filter bar, so the filter bar lays out
     * the other chips without the empty space of the hidden chip, and selects the 'Public' chip
     * so the members only videos that can not be watched are not shown.
     */
    public static byte[] hideMembersOnlyChip(byte[] bytes) {
        try {
            if (!Settings.HIDE_MEMBERS_ONLY_CHIP.get()
                    || !channelChipBarBuffer.check(bytes).isFiltered()
                    || !membersOnlyChipIdBuffer.check(bytes).isFiltered()) {
                return bytes;
            }

            List<ProtoNode> element = ProtoNode.parse(bytes);
            if (element == null) {
                return bytes;
            }

            List<ProtoNode> chips = ProtoNode.findMessages(element, CHANNEL_CHIP_PATH);
            ProtoNode membersOnlyChip = null;
            for (ProtoNode chip : chips) {
                if (MEMBERS_ONLY_CHIP_ID.equals(chipField(chip, CHANNEL_CHIP_ID_FIELD))) {
                    membersOnlyChip = chip;
                    break;
                }
            }
            if (membersOnlyChip == null) {
                return bytes;
            }

            String group = chipField(membersOnlyChip, CHANNEL_CHIP_GROUP_FIELD);
            // The chip is the only field of its list item.
            ProtoNode membersOnlyItem = membersOnlyChip.getParent();
            if (membersOnlyItem == null) {
                return bytes;
            }
            membersOnlyItem.remove();
            Logger.printDebug(() -> "Removed 'Members only' chip");

            if (group != null) {
                for (ProtoNode chip : chips) {
                    if (chip != membersOnlyChip && group.equals(chipField(chip, CHANNEL_CHIP_GROUP_FIELD))) {
                        selectPublicChip(chip);
                        break;
                    }
                }
            }

            return ProtoNode.write(element);
        } catch (Exception ex) {
            Logger.printException(() -> "hideMembersOnlyChip failure", ex);
        }

        return bytes;
    }

    /**
     * @return The text of the field of the chip, or null if it has no such field.
     */
    @Nullable
    private static String chipField(ProtoNode chip, int fieldNumber) {
        ProtoNode field = ProtoNode.field(chip.children, fieldNumber);
        return field == null ? null : field.decodeText();
    }

    /**
     * Taps the 'Public' chip if it's not selected, once for each opened channel tab.
     * Selecting a chip loads the filtered videos and then the filter bar again, with the chip selected.
     */
    private static void selectPublicChip(ProtoNode publicChip) {
        List<ProtoNode> text = ProtoNode.message(publicChip.children,
                CHANNEL_CHIP_TEXT_FIELD, CHANNEL_CHIP_TEXT_MODEL_FIELD);
        ProtoNode selected = ProtoNode.field(text, CHANNEL_CHIP_TEXT_SELECTED_FIELD);
        if (selected != null && Long.valueOf(1).equals(selected.getVarint())) {
            return;
        }

        ProtoNode labelField = ProtoNode.field(text, CHANNEL_CHIP_TEXT_LABEL_FIELD);
        String label = labelField == null ? null : labelField.decodeText();
        if (label == null) {
            return;
        }

        Utils.runOnMainThread(() -> tapPublicChip(label, ++publicChipSearchId, PUBLIC_CHIP_SEARCH_MAX_ATTEMPTS));
    }

    /**
     * Taps the 'Public' chip view, which has the chip text as its content description,
     * unless the chip was already selected in its list.
     */
    private static void tapPublicChip(String label, final int searchId, final int attemptsLeft) {
        try {
            if (searchId != publicChipSearchId) {
                return;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) {
                return;
            }

            View chip = Utils.getChildView((ViewGroup) activity.getWindow().getDecorView(), true,
                    view -> view.isClickable() && view.isShown()
                            && TextUtils.equals(label, view.getContentDescription()));
            if (chip == null) {
                if (attemptsLeft > 1) {
                    Utils.runOnMainThreadDelayed(() -> tapPublicChip(label, searchId, attemptsLeft - 1),
                            PUBLIC_CHIP_SEARCH_INTERVAL_MS);
                } else {
                    Logger.printDebug(() -> "Could not find chip: " + label);
                }
                return;
            }

            View list = getChannelTabList(chip);
            if (list == null || list == publicChipSelectedListRef.get()) {
                Logger.printDebug(() -> "Not selecting chip again in the same list: " + label);
                return;
            }
            publicChipSelectedListRef = new WeakReference<>(list);

            Logger.printDebug(() -> "Selecting chip: " + label);
            chip.performClick();
        } catch (Exception ex) {
            Logger.printException(() -> "tapPublicChip failure", ex);
        }
    }

    /**
     * The chips are in a horizontal list, which is an item of the list of the channel tab.
     * Both lists stay the same when a chip is selected, and are new for each opened tab.
     *
     * @return The list of the channel tab, or null if the chip is not inside two lists.
     */
    @Nullable
    private static View getChannelTabList(View chip) {
        boolean isInChipList = false;
        for (ViewParent parent = chip.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof RecyclerView list) {
                if (isInChipList) {
                    return list;
                }
                isInChipList = true;
            }
        }
        return null;
    }

    /**
     * Field numbers of the view models of the buttons, in the proto of the elements.
     */
    private static final int BUTTON_VIEW_MODEL_FIELD = 461054335;
    private static final int SUBSCRIBE_BUTTON_VIEW_MODEL_FIELD = 518912898;

    private static final ByteArrayFilterGroup pageHeaderBuffer = new ByteArrayFilterGroup(
            null,
            "page_header.e"
    );

    /**
     * Ids of the page header buttons: the accessibility ids (such as 'id.sponsor_button'),
     * or the browse id of the Premium page.
     */
    private static final StringFilterGroup[] pageHeaderButtonIds = {
            new StringFilterGroup(
                    Settings.HIDE_GET_PREMIUM_BUTTON,
                    "SPunlimited"
            ),
            new StringFilterGroup(
                    Settings.HIDE_COMMUNITY_BUTTON,
                    "header_community_button"
            ),
            new StringFilterGroup(
                    Settings.HIDE_JOIN_BUTTON,
                    "sponsor_button"
            ),
            new StringFilterGroup(
                    Settings.HIDE_STORE_BUTTON,
                    "header_store_button"
            )
    };
    private static final ByteArrayFilterGroupList pageHeaderButtonIdsBufferGroupList = new ByteArrayFilterGroupList();

    static {
        for (StringFilterGroup buttonId : pageHeaderButtonIds) {
            pageHeaderButtonIdsBufferGroupList.addAll(
                    new ByteArrayFilterGroup(buttonId.setting, buttonId.filters[0].toString())
            );
        }
    }

    /**
     * If the spacers between the channel page header buttons are hidden with the subscribe button.
     */
    private static volatile boolean hideChannelProfileHeaderSpacers;

    /**
     * Injection point.
     * Removes the hidden buttons from the data of the page header (You tab and channel page),
     * so the header lays out the other buttons without the empty space of the hidden buttons.
     */
    public static byte[] hidePageHeaderButtons(byte[] bytes) {
        try {
            if (!pageHeaderBuffer.check(bytes).isFiltered()) {
                return bytes;
            }

            hideChannelProfileHeaderSpacers = false;
            if (!Settings.HIDE_SUBSCRIBE_BUTTON_IN_CHANNEL_PAGE.get()
                    && !pageHeaderButtonIdsBufferGroupList.check(bytes).isFiltered()) {
                return bytes;
            }

            List<ProtoNode> element = ProtoNode.parse(bytes);
            if (element == null) {
                return bytes;
            }

            boolean modified = false;
            for (List<ProtoNode> buttons : findButtonLists(element)) {
                int visibleButtons = 0;
                boolean hasHiddenSubscribeButton = false;

                for (ProtoNode button : new ArrayList<>(buttons)) {
                    List<ProtoNode> fields = button.children;
                    if (fields == null) {
                        continue;
                    }

                    if (ProtoNode.field(fields, SUBSCRIBE_BUTTON_VIEW_MODEL_FIELD) != null) {
                        // The header keeps the space of the subscribe button if it's removed,
                        // so it's hidden by the Litho filter.
                        hasHiddenSubscribeButton = Settings.HIDE_SUBSCRIBE_BUTTON_IN_CHANNEL_PAGE.get();
                    } else {
                        if (ProtoNode.field(fields, BUTTON_VIEW_MODEL_FIELD) != null) {
                            if (isPageHeaderButtonHidden(fields)) {
                                button.remove();
                                modified = true;
                            } else {
                                visibleButtons++;
                            }
                        }
                    }
                }

                if (hasHiddenSubscribeButton) {
                    // The spacers are only needed with 2 or more visible buttons.
                    hideChannelProfileHeaderSpacers = visibleButtons < 2;
                }
            }

            return modified ? ProtoNode.write(element) : bytes;
        } catch (Exception ex) {
            Logger.printException(() -> "hidePageHeaderButtons failure", ex);
        }

        return bytes;
    }

    /**
     * @return The fields of the messages that hold the buttons,
     *         except the ones inside the buttons (such as menus).
     */
    private static List<List<ProtoNode>> findButtonLists(List<ProtoNode> element) {
        List<ProtoNode> buttonLists = new ArrayList<>();
        for (ProtoNode buttonViewModel : ProtoNode.findMessages(element, BUTTON_VIEW_MODEL_FIELD)) {
            ProtoNode button = buttonViewModel.getParent();
            ProtoNode buttons = button == null ? null : button.getParent();
            if (buttons != null && !buttonLists.contains(buttons)) {
                buttonLists.add(buttons);
            }
        }

        List<List<ProtoNode>> outerButtonLists = new ArrayList<>(buttonLists.size());
        for (ProtoNode buttons : buttonLists) {
            ProtoNode ancestor = buttons.getParent();
            while (ancestor != null && !buttonLists.contains(ancestor)) {
                ancestor = ancestor.getParent();
            }
            List<ProtoNode> fields = buttons.children;
            if (ancestor == null && fields != null) {
                outerButtonLists.add(fields);
            }
        }

        return outerButtonLists;
    }

    /**
     * Layout of the buttons below the posts.
     */
    public enum PostButtonsLayout {
        /**
         * The layout that the server sends.
         */
        SERVER,
        /**
         * The grouped layout of the post template, with the comment and share buttons next to the like and dislike
         * buttons, so every account gets the same buttons and the hidden buttons can be found.
         * The posts of the wide layout (post_base_wrapper_slim) have no grouped layout and keep their layout.
         */
        ENFORCED
    }

    /**
     * Command of the comment button, which opens the comments panel of the post,
     * at the same path below the data of the buttons in every layout of the posts.
     */
    private static final String POST_COMMENTS_COMMAND_PREFIX = "post-comments-";
    private static final int[] POST_COMMENTS_COMMAND_PATH = {3, 1, 169495254, 138681778, 10, 3};

    /**
     * The root templates of the posts, which differ between accounts and screen sizes,
     * and the command of the comment button, which every layout of the posts has.
     */
    private static final ByteArrayFilterGroup postBuffer = new ByteArrayFilterGroup(
            null,
            "_post_root",
            "_post_responsive_root",
            "post_base_wrapper",
            "post_shelf_slim.e",
            POST_COMMENTS_COMMAND_PREFIX
    );

    /**
     * Data of the buttons below a post, in the model of the post: the like and dislike buttons,
     * the share button, the comment button, and the field that turns on the grouped layout.
     * The model is an extension field, which differs between the types of post, such as the images, text,
     * polls and videos.
     */
    private static final int[] POST_BUTTONS_PATH = {1, 1, 5};
    /**
     * Lowest field number of the extension fields, such as the models of the posts.
     */
    private static final int POST_MODEL_MIN_FIELD = 1 << 20;
    private static final int POST_LIKE_DISLIKE_BUTTONS_FIELD = 1;
    private static final int POST_SHARE_BUTTON_FIELD = 2;
    private static final int POST_COMMENT_BUTTON_FIELD = 3;
    private static final int POST_GROUPED_LAYOUT_FIELD = 7;
    /**
     * The grouped layout field set to 1 (field 7, varint).
     */
    private static final byte[] POST_GROUPED_LAYOUT_BYTES = {0x38, 0x01};

    /**
     * Data of the buttons below a post of the wide layout (post_base_wrapper_slim), such as on tablets:
     * the like and dislike buttons, and the fields that show the comment and the share buttons.
     */
    private static final int[] WIDE_POST_BUTTONS_PATH = {263835042, 4, 1, 3};
    private static final int WIDE_POST_LIKE_BUTTON_FIELD = 7;
    private static final int WIDE_POST_DISLIKE_BUTTON_FIELD = 8;
    private static final int WIDE_POST_SHOW_COMMENT_BUTTON_FIELD = 9;
    private static final int WIDE_POST_SHOW_SHARE_BUTTON_FIELD = 10;

    /**
     * Injection point.
     * Removes the data of the hidden buttons below the posts, so the post lays out the other buttons
     * without the space of the hidden ones, and turns on the grouped layout of the buttons.
     * The template of the post leaves out a button without its data in every layout, also in the layouts of other
     * accounts, which the paths of the Litho filters may not match
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/4367">...</a>).
     */
    public static byte[] setPostButtons(byte[] bytes) {
        try {
            final boolean enforced = Settings.POST_BUTTONS_LAYOUT.get() == PostButtonsLayout.ENFORCED;
            final boolean hideLikeDislike = Settings.HIDE_POST_LIKE_DISLIKE_BUTTONS.get();
            final boolean hideShare = Settings.HIDE_POST_SHARE_BUTTON.get();
            final boolean hideComment = Settings.HIDE_POST_COMMENT_BUTTON.get();
            if ((!enforced && !hideLikeDislike && !hideShare && !hideComment)
                    || !postBuffer.check(bytes).isFiltered()) {
                return bytes;
            }

            List<ProtoNode> element = ProtoNode.parse(bytes);
            if (element == null) {
                return bytes;
            }

            // The data of the buttons is found by the command of the comment button, at any depth, such as in the
            // layouts that wrap another layout of the post. The posts without comments are found by the paths of
            // the known layouts.
            List<ProtoNode> postButtons = new ArrayList<>();
            for (ProtoNode text : ProtoNode.textNodes(element)) {
                if (text.getText().startsWith(POST_COMMENTS_COMMAND_PREFIX)
                        && text.pathEndsWith(POST_COMMENTS_COMMAND_PATH)) {
                    ProtoNode buttons = text;
                    for (int i = 0; i < POST_COMMENTS_COMMAND_PATH.length && buttons != null; i++) {
                        buttons = buttons.getParent();
                    }
                    if (buttons != null && !postButtons.contains(buttons)) {
                        postButtons.add(buttons);
                    }
                }
            }
            for (ProtoNode buttons : ProtoNode.findMessages(element, POST_BUTTONS_PATH)) {
                assert (buttons.getParent() != null ? buttons.getParent().getParent() : null) != null;
                ProtoNode model = buttons.getParent().getParent().getParent();
                // Only the buttons data of a post model, which has the share or the comment button.
                if (model != null && model.getFieldNumber() >= POST_MODEL_MIN_FIELD && buttons.children != null
                        && (ProtoNode.field(buttons.children, POST_SHARE_BUTTON_FIELD) != null
                        || ProtoNode.field(buttons.children, POST_COMMENT_BUTTON_FIELD) != null)
                        && !postButtons.contains(buttons)) {
                    postButtons.add(buttons);
                }
            }
            for (ProtoNode buttons : ProtoNode.findMessages(element, WIDE_POST_BUTTONS_PATH)) {
                if (!postButtons.contains(buttons)) {
                    postButtons.add(buttons);
                }
            }

            boolean modified = false;
            for (ProtoNode buttons : postButtons) {
                List<ProtoNode> fields = buttons.children;
                if (fields == null) {
                    continue;
                }

                // The wide layout shows the comment and the share buttons with flags.
                ProtoNode showComment = ProtoNode.field(fields, WIDE_POST_SHOW_COMMENT_BUTTON_FIELD);
                ProtoNode showShare = ProtoNode.field(fields, WIDE_POST_SHOW_SHARE_BUTTON_FIELD);
                if (buttons.pathEndsWith(WIDE_POST_BUTTONS_PATH)
                        || (showComment != null && showComment.getVarint() != null)
                        || (showShare != null && showShare.getVarint() != null)) {
                    for (ProtoNode field : new ArrayList<>(fields)) {
                        final int fieldNumber = field.getFieldNumber();
                        if (hideLikeDislike && (fieldNumber == WIDE_POST_LIKE_BUTTON_FIELD
                                || fieldNumber == WIDE_POST_DISLIKE_BUTTON_FIELD)) {
                            field.remove();
                            modified = true;
                        } else if (((hideComment && fieldNumber == WIDE_POST_SHOW_COMMENT_BUTTON_FIELD)
                                || (hideShare && fieldNumber == WIDE_POST_SHOW_SHARE_BUTTON_FIELD))
                                && field.getVarint() != null) {
                            field.setVarint(0);
                            modified = true;
                        }
                    }
                    continue;
                }

                for (ProtoNode field : new ArrayList<>(fields)) {
                    final int fieldNumber = field.getFieldNumber();
                    if ((hideLikeDislike && fieldNumber == POST_LIKE_DISLIKE_BUTTONS_FIELD)
                            || (hideShare && fieldNumber == POST_SHARE_BUTTON_FIELD)
                            || (hideComment && fieldNumber == POST_COMMENT_BUTTON_FIELD)) {
                        field.remove();
                        modified = true;
                    }
                }

                if (enforced && ProtoNode.field(fields, POST_GROUPED_LAYOUT_FIELD) == null) {
                    byte[] data = ProtoNode.write(fields);
                    byte[] grouped = Arrays.copyOf(data, data.length + POST_GROUPED_LAYOUT_BYTES.length);
                    System.arraycopy(POST_GROUPED_LAYOUT_BYTES, 0, grouped, data.length, POST_GROUPED_LAYOUT_BYTES.length);
                    buttons.setPayload(grouped);
                    modified = true;
                }
            }

            return modified ? ProtoNode.write(element) : bytes;
        } catch (Exception ex) {
            Logger.printException(() -> "setPostButtons failure", ex);
        }

        return bytes;
    }

    /**
     * @return If the button has the id of a hidden button, as the full text or after the id prefix.
     */
    private static boolean isPageHeaderButtonHidden(List<ProtoNode> button) {
        for (ProtoNode text : ProtoNode.textNodes(button)) {
            String value = text.getText();
            for (StringFilterGroup buttonId : pageHeaderButtonIds) {
                String id = buttonId.filters[0].toString();
                if (buttonId.isEnabled() && value.endsWith(id)
                        && (value.length() == id.length() || value.charAt(value.length() - id.length() - 1) == '.')) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static boolean showWatermark() {
        return !Settings.HIDE_CHANNEL_WATERMARK.get();
    }

    /**
     * Injection point.
     */
    public static void hideAlbumCard(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_ALBUM_CARDS, view);
    }

    /**
     * Injection point.
     */
    public static void hideCrowdfundingBox(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_CROWDFUNDING_BOX, view);
    }

    /**
     * Injection point.
     *
     * @return If the HaTS survey of the bottom UI container should not be shown.
     */
    public static boolean hideHatsSurvey() {
        return Settings.HIDE_SURVEYS.get();
    }

    /**
     * Injection point.
     *
     * @return If every element in a box of the fullscreen engagement panel was filtered,
     *         so the empty box with its rounded border is collapsed, such as with "Hide channel bar".
     */
    public static boolean isEngagementPanelBoxEmpty(ViewGroup box) {
        final int count = box.getChildCount();
        if (count == 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            View child = box.getChildAt(i);
            if (child.getVisibility() != View.GONE && child.getMeasuredHeight() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Injection point.
     */
    public static boolean hideFloatingMicrophoneButton(final boolean original) {
        return original || Settings.HIDE_FLOATING_MICROPHONE_BUTTON.get();
    }

    /**
     * Injection point.
     */
    public static void hideLatestVideosButton(View view) {
        Utils.hideViewUnderCondition(Settings.HIDE_LATEST_VIDEOS_BUTTON.get(), view);
    }

    /**
     * Injection point.
     */
    public static int hideInFeed(final int height) {
        return Settings.HIDE_FILTER_BAR_IN_FEED.get()
                ? 0
                : height;
    }

    /**
     * Injection point.
     */
    public static int hideInSearch(int height) {
        return Settings.HIDE_FILTER_BAR_IN_SEARCH.get()
                ? 0
                : height;
    }

    /**
     * Injection point.
     */
    public static boolean hideInRelatedVideos(boolean original) {
        return !Settings.HIDE_FILTER_BAR_IN_RELATED_VIDEOS.get() && original;
    }

    /**
     * Injection point.
     */
    public static void hideInRelatedVideos(@Nullable View view) {
        if (view == null) return;
        Utils.hideViewUnderCondition(Settings.HIDE_FILTER_BAR_IN_RELATED_VIDEOS.get(), view);
    }

    private static final boolean HIDE_YOUTUBE_DOODLES_ENABLED = Settings.HIDE_YOUTUBE_DOODLES.get();

    /**
     * Injection point.
     */
    public static void setDoodleDrawable(ImageView imageView, Drawable original) {
        Drawable replacement = HIDE_YOUTUBE_DOODLES_ENABLED
                ? ChangeHeaderPatch.getDrawable(original)
                : original;
        imageView.setImageDrawable(replacement);
    }

    private static final FrameLayout.LayoutParams EMPTY_LAYOUT_PARAMS = new FrameLayout.LayoutParams(0, 0);
    private static final boolean HIDE_SHOW_MORE_BUTTON_ENABLED = Settings.HIDE_SHOW_MORE_BUTTON.get();

    /**
     * The ShowMoreButton should not always be hidden.
     * According to the preference summary, only the ShowMoreButton in search results is hidden.
     * Since the ShowMoreButton should be visible on other pages, such as channels,
     * the original values of the Views are saved in fields.
     */
    private static FrameLayout.LayoutParams cachedLayoutParams;
    private static int cachedButtonContainerMinimumHeight = -1;
    private static int cachedPlaceHolderMinimumHeight = -1;
    private static int cachedRootViewMinimumHeight = -1;

    /**
     * Injection point.
     */
    public static void hideShowMoreButton(View view, View buttonContainer, TextView textView) {
        if (HIDE_SHOW_MORE_BUTTON_ENABLED
                && view instanceof ViewGroup rootView
                && buttonContainer != null
                && textView != null
                && buttonContainer.getLayoutParams() instanceof FrameLayout.LayoutParams lp
        ) {
            View placeHolder = rootView.getChildAt(0);

            // For some users, ShowMoreButton has a PlaceHolder ViewGroup (A/B tests).
            // When a PlaceHolder is present, a different method is used to hide or show the ViewGroup.
            boolean hasPlaceHolder = placeHolder instanceof FrameLayout;

            // Only in search results, the content description of RootView and the text of TextView match.
            // Hide ShowMoreButton in search results, but show ShowMoreButton in other pages (e.g. channels).
            boolean isSearchResults = TextUtils.equals(rootView.getContentDescription(), textView.getText());

            if (hasPlaceHolder) {
                hideShowMoreButtonWithPlaceHolder(placeHolder, isSearchResults);
            } else {
                hideShowMoreButtonWithOutPlaceHolder(buttonContainer, lp, isSearchResults);
            }

            if (cachedRootViewMinimumHeight == -1) {
                cachedRootViewMinimumHeight = rootView.getMinimumHeight();
            }

            if (isSearchResults) {
                rootView.setMinimumHeight(0);
                rootView.setVisibility(View.GONE);
            } else {
                rootView.setMinimumHeight(cachedRootViewMinimumHeight);
                rootView.setVisibility(View.VISIBLE);
            }
        }
    }

    private static void hideShowMoreButtonWithPlaceHolder(View placeHolder, boolean isSearchResults) {
        if (cachedPlaceHolderMinimumHeight == -1) {
            cachedPlaceHolderMinimumHeight = placeHolder.getMinimumHeight();
        }

        if (isSearchResults) {
            placeHolder.setMinimumHeight(0);
            placeHolder.setVisibility(View.GONE);
        } else {
            placeHolder.setMinimumHeight(cachedPlaceHolderMinimumHeight);
            placeHolder.setVisibility(View.VISIBLE);
        }
    }

    private static void hideShowMoreButtonWithOutPlaceHolder(View buttonContainer, FrameLayout.LayoutParams lp,
                                                             boolean isSearchResults) {
        if (cachedButtonContainerMinimumHeight == -1) {
            cachedButtonContainerMinimumHeight = buttonContainer.getMinimumHeight();
        }

        if (cachedLayoutParams == null) {
            cachedLayoutParams = lp;
        }

        if (isSearchResults) {
            buttonContainer.setMinimumHeight(0);
            buttonContainer.setLayoutParams(EMPTY_LAYOUT_PARAMS);
            buttonContainer.setVisibility(View.GONE);
        } else {
            buttonContainer.setMinimumHeight(cachedButtonContainerMinimumHeight);
            buttonContainer.setLayoutParams(cachedLayoutParams);
            buttonContainer.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Injection point.
     */
    public static void hideSubscribedChannelsBar(View view) {
        Utils.hideViewByRemovingFromParentUnderCondition(Settings.HIDE_SUBSCRIBED_CHANNELS_BAR, view);
    }

    /**
     * Injection point.
     */
    public static int hideSubscribedChannelsBar(int original) {
        return Settings.HIDE_SUBSCRIBED_CHANNELS_BAR.get()
                ? 0
                : original;
    }

    /**
     * Injection point.
     * <p>
     * The metadata of the videos of channel pages, such as "1M · 2d ago", which is not created
     * by {@link #modifyFeedSubtitleSpan(SpannableString, float)}.
     */
    public static CharSequence modifyVideoMetadataText(ContextInterface context, CharSequence text) {
        try {
            final boolean hideViewCount = Settings.HIDE_VIEW_COUNT.get();
            final boolean hideUploadTime = Settings.HIDE_UPLOAD_TIME.get();
            if ((!hideViewCount && !hideUploadTime) || text == null
                    || !Utils.contains(context.patch_getPathBuilder(), VIDEO_METADATA_CONTENT_PATH)) {
                return text;
            }

            // Only the view count and the upload time, without the channel name.
            int[] sections = getDelimitedSubtitleSections(text);
            if (sections == null || sections[0] != 0) {
                return text;
            }

            SpannableStringBuilder builder = removeSubtitleSections(text, sections, hideViewCount, hideUploadTime);
            if (hideUploadTime && !hideViewCount) {
                // The app does not show a text of two characters or fewer here, such as "1M",
                // so a space that is not trimmed is kept.
                builder.append('\u00A0');
            }

            Logger.printDebug(() -> "Replacing video metadata: " + text + " with: " + builder);
            return builder;
        } catch (Exception ex) {
            Logger.printException(() -> "modifyVideoMetadataText failure", ex);
            return text;
        }
    }

    /**
     * Injection point.
     * <p>
     * The metadata of the videos of a playlist, such as "1M views • 2 years ago",
     * which 20.51 and lower show in a text view of its own.
     */
    public static void modifyPlaylistVideoMetadata(@Nullable TextView view) {
        try {
            final boolean hideViewCount = Settings.HIDE_VIEW_COUNT.get();
            final boolean hideUploadTime = Settings.HIDE_UPLOAD_TIME.get();
            if ((!hideViewCount && !hideUploadTime) || view == null) {
                return;
            }

            CharSequence text = view.getText();
            int[] sections = getDelimitedSubtitleSections(text);
            if (sections == null || sections[0] != 0) {
                return;
            }

            SpannableStringBuilder builder = removeSubtitleSections(text, sections, hideViewCount, hideUploadTime);
            Logger.printDebug(() -> "Replacing playlist video metadata: " + text + " with: " + builder);
            view.setText(builder);
        } catch (Exception ex) {
            Logger.printException(() -> "modifyPlaylistVideoMetadata failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static SpannableString modifyFeedSubtitleSpan(SpannableString subtitle, float truncationDimension) {
        // Before the view count is hidden, so the Feed view count patch can still find it.
        SpannableString original = FeedViewCountPatch.onFeedSubtitleCreated(subtitle);
        try {
            final boolean hideViewCount = Settings.HIDE_VIEW_COUNT.get();
            final boolean hideUploadTime = Settings.HIDE_UPLOAD_TIME.get();
            if (!hideViewCount && !hideUploadTime) {
                return original;
            }

            // Applies only for these specific dimensions.
            if (truncationDimension == 16f || truncationDimension == 42f) {
                int[] sections = getDelimitedSubtitleSections(original);
                if (sections == null) {
                    sections = getIconSubtitleSections(original);
                }
                if (sections == null) {
                    return original;
                }

                SpannableString replacement = new SpannableString(
                        removeSubtitleSections(original, sections, hideViewCount, hideUploadTime));
                Logger.printDebug(() -> "Replacing feed subtitle span: " + original + " with: " + replacement);

                return replacement;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "modifyFeedSubtitleSpan failure", ex);
        }

        return original;
    }

    /**
     * @param sections The start and end index of the view count, and the start index of the upload time.
     *                 The view count section includes a separator, so no separator is left alone.
     * @return A copy of the subtitle, which keeps its spans, without the sections that are hidden.
     */
    private static SpannableStringBuilder removeSubtitleSections(CharSequence subtitle, int[] sections,
                                                                 boolean hideViewCount, boolean hideUploadTime) {
        SpannableStringBuilder builder = new SpannableStringBuilder(subtitle);
        if (hideUploadTime) {
            builder.delete(sections[2], subtitle.length());
        }
        if (hideViewCount) {
            builder.delete(sections[0], Math.min(sections[1], builder.length()));
        }
        return builder;
    }

    /**
     * Subtitle with delimiters, such as "Channel · 1M views · 2 days ago",
     * or without the channel name, such as "1M views • 2 years ago" of the videos of a show.
     *
     * @return The start and end index of the view count, and the start index of the upload time,
     *         or null if not found.
     */
    @Nullable
    private static int[] getDelimitedSubtitleSections(CharSequence subtitle) {
        for (String delimiter : SUBTITLE_DELIMITERS) {
            final int delimiterLength = delimiter.length();

            final int firstDelimiterIndex = TextUtils.indexOf(subtitle, delimiter);
            if (firstDelimiterIndex < 0) {
                continue;
            }

            final int secondDelimiterIndex = TextUtils.indexOf(subtitle, delimiter,
                    firstDelimiterIndex + delimiterLength);
            if (secondDelimiterIndex < 0) {
                // The view count starts with a digit, unlike a channel name.
                if (firstDelimiterIndex == 0 || !Character.isDigit(subtitle.charAt(0))) {
                    return null;
                }
                return new int[]{0, firstDelimiterIndex + delimiterLength, firstDelimiterIndex};
            }

            // Ensure there is exactly 2 delimiters.
            if (TextUtils.indexOf(subtitle, delimiter, secondDelimiterIndex + delimiterLength) >= 0) {
                return null;
            }

            return new int[]{firstDelimiterIndex, secondDelimiterIndex, secondDelimiterIndex};
        }

        return null;
    }

    /**
     * Subtitle with an icon before the view count, such as "Channel   [icon]1M   2d ago".
     * The sections are separated by spaces, and the icon is drawn over some of them.
     * The channel name can also have an icon, such as the badge of an official artist channel.
     * The videos of a playlist can show no channel name, or no view count, such as "Channel   2d ago".
     *
     * @return The start and end index of the view count, and the start index of the upload time,
     *         or null if not found.
     */
    @Nullable
    private static int[] getIconSubtitleSections(Spanned subtitle) {
        // The icon of the view count is the last icon.
        ImageSpan viewCountIcon = null;
        for (ImageSpan icon : subtitle.getSpans(0, subtitle.length(), ImageSpan.class)) {
            if (viewCountIcon == null || subtitle.getSpanStart(icon) > subtitle.getSpanStart(viewCountIcon)) {
                viewCountIcon = icon;
            }
        }

        // The upload time starts with the spaces after the view count, or after the channel name.
        final int uploadTimeStartIndex = TextUtils.indexOf(subtitle, ICON_SUBTITLE_SEPARATOR,
                viewCountIcon == null ? 0 : subtitle.getSpanEnd(viewCountIcon));
        if (uploadTimeStartIndex <= 0) {
            return null;
        }
        int uploadTimeTextIndex = uploadTimeStartIndex;
        while (uploadTimeTextIndex < subtitle.length() && subtitle.charAt(uploadTimeTextIndex) == ' ') {
            uploadTimeTextIndex++;
        }
        if (TextUtils.indexOf(subtitle, ICON_SUBTITLE_SEPARATOR, uploadTimeTextIndex) >= 0) {
            return null;
        }

        if (viewCountIcon == null) {
            // No view count.
            return new int[]{uploadTimeStartIndex, uploadTimeStartIndex, uploadTimeStartIndex};
        }

        // The view count starts with the spaces before its icon.
        int viewCountStartIndex = subtitle.getSpanStart(viewCountIcon);
        while (viewCountStartIndex > 0 && subtitle.charAt(viewCountStartIndex - 1) == ' ') {
            viewCountStartIndex--;
        }

        // Without a channel name, the spaces after the view count are removed with it.
        final int viewCountEndIndex = viewCountStartIndex == 0 ? uploadTimeTextIndex : uploadTimeStartIndex;

        return new int[]{viewCountStartIndex, viewCountEndIndex, uploadTimeStartIndex};
    }

    /**
     *
     * Injection point.
     * <p>
     * Hide feed flyout menu for phone
     *
     * @param menuTitleCharSequence menu title
     */
    @Nullable
    public static CharSequence hideFlyoutMenu(@Nullable CharSequence menuTitleCharSequence) {
        if (menuTitleCharSequence == null || !Settings.HIDE_FEED_FLYOUT_MENU.get()
                || flyoutMenuFilterStrings.isEmpty()) {
            return menuTitleCharSequence;
        }

        String menuTitleString = menuTitleCharSequence.toString();

        if (isFlyoutMenuFiltered(menuTitleString)) {
            Logger.printDebug(() -> "Hiding: " + menuTitleString);
            return null;
        }

        return menuTitleCharSequence;
    }

    /**
     * Injection point.
     * <p>
     * hide feed flyout panel for tablet
     *
     * @param menuTextView          flyout text view
     * @param menuTitleCharSequence raw text
     */
    public static void hideFlyoutMenu(TextView menuTextView, CharSequence menuTitleCharSequence) {
        if (menuTitleCharSequence == null || !Settings.HIDE_FEED_FLYOUT_MENU.get()
                || flyoutMenuFilterStrings.isEmpty()
                || !(menuTextView.getParent() instanceof View parentView)) {
            return;
        }

        String menuTitleString = menuTitleCharSequence.toString();

        if (isFlyoutMenuFiltered(menuTitleString)) {
            Logger.printDebug(() -> "Hiding: " + menuTitleString);
            Utils.hideViewByLayoutParams(parentView);
        }
    }

    /**
     * Item of a menu, such as the items of the popup menus.
     */
    public interface FlyoutMenuItem {
        @Nullable
        CharSequence patch_getFlyoutMenuItemTitle();
    }

    /**
     * Injection point.
     * <p>
     * Leaves the filtered items out of the items of a popup menu, as the list popup menu
     * ignores the zero height of a hidden item and only keeps its text hidden.
     *
     * @param menuItems resolved items of one item of the menu
     */
    public static Iterable<?> hideFlyoutMenuItems(Iterable<?> menuItems) {
        if (!Settings.HIDE_FEED_FLYOUT_MENU.get() || flyoutMenuFilterStrings.isEmpty()) {
            return menuItems;
        }

        List<Object> shownMenuItems = new ArrayList<>();
        for (Object menuItem : menuItems) {
            if (menuItem instanceof FlyoutMenuItem flyoutMenuItem) {
                CharSequence menuTitleCharSequence = flyoutMenuItem.patch_getFlyoutMenuItemTitle();
                if (menuTitleCharSequence != null) {
                    String menuTitleString = menuTitleCharSequence.toString();
                    if (isFlyoutMenuFiltered(menuTitleString)) {
                        Logger.printDebug(() -> "Hiding popup menu item: " + menuTitleString);
                        continue;
                    }
                }
            }
            shownMenuItems.add(menuItem);
        }

        return shownMenuItems;
    }

    /**
     * Injection point.
     * <p>
     * Hide the items of the toolbar menu, such as the overflow menu of a playlist page.
     *
     * @param menu                  toolbar menu
     * @param menuItemId            id of the item just added to the menu
     * @param menuTitleCharSequence title of the item
     */
    public static void hideFlyoutMenu(Menu menu, int menuItemId, @Nullable CharSequence menuTitleCharSequence) {
        if (menuTitleCharSequence == null || !Settings.HIDE_FEED_FLYOUT_MENU.get()
                || flyoutMenuFilterStrings.isEmpty()) {
            return;
        }

        String menuTitleString = menuTitleCharSequence.toString();

        if (isFlyoutMenuFiltered(menuTitleString)) {
            Logger.printDebug(() -> "Hiding toolbar menu item: " + menuTitleString);
            menu.removeItem(menuItemId);
        }
    }

    /**
     * The download item shows a placeholder while the state of the download is loaded in the background,
     * and shows its real text only about a second after the menu opens. A filter of the download text
     * therefore also hides the placeholder, otherwise the item is shown and then collapses.
     */
    public static boolean isFlyoutMenuFiltered(String menuTitle) {
        final boolean isDownloadPlaceholder = menuTitle.equals(ResourceUtils.getString("menu_offline_placeholder"));

        for (String filter : flyoutMenuFilterStrings) {
            if (menuTitle.equalsIgnoreCase(filter)) {
                return true;
            }
            if (isDownloadPlaceholder) {
                for (String downloadText : FLYOUT_DOWNLOAD_TEXTS) {
                    if (ResourceUtils.getStringIdentifier(downloadText) != 0
                            && filter.equalsIgnoreCase(ResourceUtils.getString(downloadText))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     *
     * Injection point.
     * <p>
     * Rather than simply hiding the channel tab view, completely removes channel tab from list.
     * If a channel tab is removed from the list, users will not be able to open it by swiping.
     *
     * @param channelTabText Text assigned to the channel tab, such as "Shorts", "Playlists",
     *                       "Community", "Store". This text follows the user's language.
     * @return Whether to remove the channel tab from the list.
     */
    public static boolean hideChannelTab(@Nullable String channelTabText) {
        if (!Utils.isNotEmpty(channelTabText) || !Settings.HIDE_CHANNEL_TAB.get()
                || channelTabFilterStrings.isEmpty()) {
            return false;
        }

        // A page without tabs is blank. The last tab is kept, as 21.44+ also shows
        // the Home feed as a page with a single "Home" tab.
        List<?> tabs = channelTabs;
        if (tabs != null && tabs.size() <= 1) {
            return false;
        }

        for (String filter : channelTabFilterStrings) {
            if (channelTabText.equalsIgnoreCase(filter)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Injection point.
     * <p>
     * Called before the channel tabs are copied into the tab list.
     *
     * @param tabs         Tab list that hidden channel tabs are removed from.
     * @param originalTabs All channel tabs, including tabs that are later hidden.
     */
    public static void setChannelTabs(List<?> tabs, List<?> originalTabs) {
        channelTabs = tabs;
        originalChannelTabs = new ArrayList<>(originalTabs);
    }

    /**
     * Injection point.
     * <p>
     * Removing channel tabs shifts the remaining tabs, so the index of the tab to select
     * (such as the Videos tab opened from a video description) must be remapped.
     * If the tab to select is hidden, the next visible tab is selected instead.
     *
     * @param selectedIndex Index of the tab to select, relative to all channel tabs.
     * @return Index of the tab to select, relative to the visible channel tabs.
     */
    public static int getChannelTabSelectedIndex(int selectedIndex) {
        List<?> tabs = channelTabs;
        List<?> originalTabs = originalChannelTabs;
        channelTabs = null;
        originalChannelTabs = null;

        try {
            if (tabs == null || originalTabs == null || tabs.isEmpty()
                    || tabs.size() == originalTabs.size()
                    || selectedIndex < 0 || selectedIndex >= originalTabs.size()) {
                return selectedIndex;
            }

            int visibleIndex = 0;
            for (int i = 0; i < selectedIndex; i++) {
                if (tabs.contains(originalTabs.get(i))) {
                    visibleIndex++;
                }
            }

            return Math.min(visibleIndex, tabs.size() - 1);
        } catch (Exception ex) {
            Logger.printException(() -> "getChannelTabSelectedIndex failure", ex);
            return selectedIndex;
        }
    }

    /**
     * Injection point.
     */
    public static Uri hideSearchTermThumbnails(View view, Uri uri) {
        if (Settings.HIDE_SEARCH_TERM_THUMBNAILS.get()) {
            if (view != null) {
                Utils.hideViewByLayoutParams(view);
            }
            return null;
        }
        return uri;
    }

    /**
     * Injection point.
     *
     * @param typedString   Keywords typed in the search bar.
     * @return              Whether the setting is enabled and the typed string is empty.
     */
    public static boolean hideYouMayLikeSection(String typedString) {
        return Settings.HIDE_YOU_MAY_LIKE_SECTION.get()
                // The 'You may like' section is only visible when no search terms are entered.
                // To avoid unnecessary collection traversals, filtering is performed only when the typedString is empty.
                && Utils.isEmpty(typedString);
    }

    /**
     * Injection point.
     *
     * @param searchTerm    This class contains information related to search terms.
     *                      The {@code toString()} method of this class overrides the search term.
     * @param endpoint      Endpoint related with the search term.
     *                      For search history, this value is:
     *                      '/complete/deleteitems?client=youtube-android-pb&delq=${searchTerm}&deltok=${token}'.
     *                      For search suggestions, this value is null or empty.
     * @return              Whether search term is a search history or not.
     */
    public static boolean isSearchHistory(Object searchTerm, String endpoint) {
        boolean isSearchHistory = endpoint != null && endpoint.contains("/delete");
        if (!isSearchHistory) {
            Logger.printDebug(() -> "Remove search suggestion: " + searchTerm);
        }
        return isSearchHistory;
    }

    private static final List<String> accountMenuFilterStrings = Utils.getFilterStrings(Settings.HIDE_ACCOUNT_MENU_FILTER_STRINGS);
    private static final int[] accountTopItemDepths = new int[]{3, 2}; // Start from the highest depth to avoid hiding the wrong parent first
    private static final int[] accountBottomItemModernDepths = new int[]{4, 3}; // Start from the highest depth to avoid hiding the wrong parent first
    private static final int[] accountBottomItemLegacyDepths = new int[]{3, 2}; // Start from the highest depth to avoid hiding the wrong parent first

    /**
     * Injection point.
     */
    public static void hideAccountTopItem(View view, CharSequence menuTitleCharSequence) {
        hideAccountItem(view, menuTitleCharSequence, accountTopItemDepths);
    }

    /**
     * Injection point.
     */
    public static void hideAccountBottomItemModern(View view, CharSequence menuTitleCharSequence) {
        hideAccountItem(view, menuTitleCharSequence, accountBottomItemModernDepths);
    }

    /**
     * Injection point.
     */
    public static void hideAccountBottomItemLegacy(View view, CharSequence menuTitleCharSequence) {
        hideAccountItem(view, menuTitleCharSequence, accountBottomItemLegacyDepths);
    }

    private static void hideAccountItem(View textView, CharSequence menuTitleCharSequence, int[] depths) {
        try {
            if (!Settings.HIDE_ACCOUNT_MENU.get() || menuTitleCharSequence == null) return;
            if (accountMenuFilterStrings.isEmpty()) return;

            String menuTitleString = menuTitleCharSequence.toString();

            boolean matches = false;
            String menuTitleLower = menuTitleString.toLowerCase(Locale.ROOT);
            for (String filter : accountMenuFilterStrings) {
                if (menuTitleLower.contains(filter.toLowerCase(Locale.ROOT))) {
                    matches = true;
                    break;
                }
            }
            if (!matches) return;

            // Not all versions have the same depth. So perform a scan
            // along all available depths, to find the right one.
            for (int depth : depths) {
                ViewParent parent = Utils.getParentView(textView, depth);
                if (parent instanceof View current) {
                    Utils.hideViewByLayoutParams(current);
                    current.setVisibility(View.GONE);
                    if (current.getLayoutParams() instanceof ViewGroup.MarginLayoutParams marginParams) {
                        marginParams.setMargins(0, 0, 0, 0);
                        current.setLayoutParams(marginParams);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideAccountItem failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void hideChaptersTimelineButton(View view) {
        // The button shows either the chapter or 'In this video', so it is removed only if both are hidden.
        // Otherwise, it is hidden by showTimeBarEntryPoint(boolean, boolean).
        if (view != null && Settings.HIDE_CHAPTERS_BUTTON.get() && Settings.HIDE_IN_THIS_VIDEO_BUTTON.get()) {
            Utils.hideViewByLayoutParams(view);
            view.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     * <p>
     * The button next to the timestamp shows the chapter title, or 'In this video'
     * (which opens the transcript) for videos without chapters.
     *
     * @param show      If YouTube shows the button.
     * @param isChapter If the button shows the chapter title.
     * @return If the button is shown.
     */
    public static boolean showTimeBarEntryPoint(boolean show, boolean isChapter) {
        if (!show) {
            return false;
        }
        return isChapter
                ? !Settings.HIDE_CHAPTERS_BUTTON.get()
                : !Settings.HIDE_IN_THIS_VIDEO_BUTTON.get();
    }

    /**
     * Injection point.
     * <p>
     * Older app targets always show the button next to the timestamp,
     * and show the chapter title in it separately.
     *
     * @return If the chapter title is shown.
     */
    public static boolean showTimeBarChapterTitle(boolean show) {
        return show && !Settings.HIDE_CHAPTERS_BUTTON.get();
    }

    /**
     * Injection point.
     * <p>
     * Older app targets always show the button next to the timestamp,
     * and show 'In this video' in it separately.
     *
     * @return If 'In this video' is shown.
     */
    public static boolean showTimeBarInThisVideo(boolean show) {
        return show && !Settings.HIDE_IN_THIS_VIDEO_BUTTON.get();
    }

    /**
     * Injection point.
     * <p>
     * Some app targets show the chapter title next to the timestamp as its own view, instead
     * of inside the entry point button hidden by {@link #hideChaptersTimelineButton(View)}.
     */
    public static void hideChapterTitle(View view) {
        if (view == null || !Settings.HIDE_CHAPTERS_BUTTON.get()) {
            return;
        }

        try {
            Utils.hideViewByLayoutParams(view);
            view.setVisibility(View.GONE);

            // The chip around the title is a separate parent view.
            if (CONTAINER_ID != 0 && view.getParent() instanceof View parent && parent.getId() == CONTAINER_ID) {
                Utils.hideViewByLayoutParams(parent);
                parent.setVisibility(View.GONE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideChapterTitle failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called before YouTube adds a list of timeline markers, such as chapters.
     *
     * @param markersKey Key of the markers list.
     * @return If the markers are not added.
     */
    public static boolean hideAutoGeneratedChapters(@Nullable String markersKey) {
        return Settings.HIDE_AUTO_GENERATED_CHAPTERS.get()
                && markersKey != null
                && (markersKey.equals(AUTO_CHAPTERS_KEY)
                || markersKey.startsWith(AUTO_CHAPTERS_ENTITY_KEY_PREFIX));
    }

    /**
     * Injection point.
     * <p>
     * Called with the measure spec of a row of an app bar menu. The 'Help &amp; feedback' row is
     * measured at no height, so it takes no space in the menu.
     */
    public static int hideHelpFeedbackMenuRow(View row, int heightMeasureSpec) {
        if (!Settings.HIDE_HELP_FEEDBACK_MENU.get()) {
            return heightMeasureSpec;
        }

        try {
            View title = row.findViewById(ResourceUtils.getIdentifier(ResourceType.ID, "title"));
            int helpStringId = ResourceUtils.getIdentifier(ResourceType.STRING, "menu_help");
            if (title instanceof TextView textView && helpStringId != 0
                    && row.getContext().getString(helpStringId).contentEquals(textView.getText())) {
                return View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideHelpFeedbackMenuRow failure", ex);
        }

        return heightMeasureSpec;
    }

    /**
     * Injection point.
     */
    public static void hideSyncButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SYNC_BUTTON, view);
    }

    /**
     * Touch and playback speed callbacks all run on the main thread.
     */
    private static boolean tapAndHoldActive;

    /**
     * Injection point.
     */
    public static void onTapAndHoldStart() {
        tapAndHoldActive = true;
    }

    /**
     * Injection point.
     */
    public static void onTapAndHoldEnd() {
        tapAndHoldActive = false;
    }

    /**
     * Injection point.
     *
     * @return If the player controls should not be shown by a vertical drag.
     */
    public static boolean hideTapAndHoldGradient() {
        return tapAndHoldActive && Settings.HIDE_TAP_AND_HOLD_GRADIENT.get();
    }
}
