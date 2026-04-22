/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.Utils.getFilterStrings;
import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.ChangeHeaderPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public final class LayoutComponentsFilter extends Filter {
    private static final ByteArrayFilterGroup mixPlaylistsBufferExceptions = new ByteArrayFilterGroup(
            null,
            "cell_description_body",
            "channel_profile"
    );
    private static final ByteArrayFilterGroup mixPlaylists = new ByteArrayFilterGroup(
            null,
            "&list="
    );

    private static final List<String> channelTabFilterStrings = getFilterStrings(Settings.HIDE_CHANNEL_TAB_FILTER_STRINGS);
    private static final List<String> flyoutMenuFilterStrings = getFilterStrings(Settings.HIDE_FEED_FLYOUT_MENU_FILTER_STRINGS);

    private final StringTrieSearch exceptions = new StringTrieSearch();
    private final StringFilterGroup communityPosts;
    private final StringFilterGroup surveys;
    private final StringFilterGroup notifyMe;
    private final StringFilterGroup searchFriction;
    private final StringFilterGroup singleItemInformationPanel;
    private static volatile int singleItemInformationPanelIndex = -1;
    private final StringFilterGroup expandableMetadata;
    private final ByteArrayFilterGroup productCardBuffer;
    private final ByteArrayFilterGroup summaryCardBuffer;
    private final StringFilterGroup compactChannelBarInner;
    private final StringFilterGroup compactChannelBarInnerButton;
    private final ByteArrayFilterGroup joinMembershipButton;
    private final StringFilterGroup chipBar;
    private final StringFilterGroup channelProfile;
    private final StringFilterGroupList channelProfileGroupList;

    public enum ExpandableCardStyle {
        SHOW_ALL,
        HIDE_PRODUCT_ONLY,
        HIDE_SUMMARY_ONLY,
        HIDE_PRODUCT_AND_SUMMARY,
        HIDE_ALL
    }

    public LayoutComponentsFilter() {
        exceptions.addPatterns(
                "home_video_with_context",
                "related_video_with_context",
                "search_video_with_context",
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

        final var exploreTopicsShelf = new StringFilterGroup(
                Settings.HIDE_HORIZONTAL_SHELVES,
                "chips_shelf"
        );

        final var liveChatReplay = new StringFilterGroup(
                Settings.HIDE_LIVE_CHAT_REPLAY_BUTTON,
                "live_chat_ep_entrypoint.e"
        );

        addIdentifierCallbacks(
                cellDivider,
                exploreTopicsShelf,
                liveChatReplay
        );

        // Paths.

        communityPosts = new StringFilterGroup(
                Settings.HIDE_COMMUNITY_POSTS,
                "images_post_root.e",
                "images_post_root_slim.e",
                "images_post_slim.e", // may be obsolete and no longer needed.
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

        final var subscribersCommunityGuidelines = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBERS_COMMUNITY_GUIDELINES,
                "sponsorships_comments_upsell"
        );

        final var channelMembersShelf = new StringFilterGroup(
                Settings.HIDE_MEMBERS_SHELF,
                "member_recognition_shelf"
        );

        final var compactBanner = new StringFilterGroup(
                Settings.HIDE_COMPACT_BANNER,
                "compact_banner"
        );

        final var crowdfundingBox = new StringFilterGroup(
                Settings.HIDE_CROWDFUNDING_BOX,
                "donation_shelf"
        );

        final var subscriptionsChipBar = new StringFilterGroup(
                Settings.HIDE_FILTER_BAR_FEED_IN_FEED,
                "subscriptions_chip_bar"
        );

        final var subscribedChannelsBar = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBED_CHANNELS_BAR,
                "subscriptions_channel_bar"
        );

        chipBar = new StringFilterGroup(
                Settings.HIDE_FILTER_BAR_FEED_IN_HISTORY,
                "chip_bar"
        );

        surveys = new StringFilterGroup(
                Settings.HIDE_SURVEYS,
                "in_feed_survey",
                "slimline_survey",
                "feed_nudge"
        );

        final var medicalPanel = new StringFilterGroup(
                Settings.HIDE_MEDICAL_PANELS,
                "medical_panel"
        );

        final var paidPromotion = new StringFilterGroup(
                Settings.HIDE_PAID_PROMOTION_LABEL,
                "paid_content_overlay"
        );

        final var infoPanel = new StringFilterGroup(
                Settings.HIDE_INFO_PANELS,
                "publisher_transparency_panel"
        );

        searchFriction = new StringFilterGroup(
                Settings.HIDE_INFO_PANELS,
                "search_friction"
        );

        singleItemInformationPanel = new StringFilterGroup(
                Settings.HIDE_INFO_PANELS,
                "single_item_information_panel"
        );

        final var postsShelf = new StringFilterGroup(
                Settings.HIDE_POSTS_SHELF,
                "post_shelf"
        );

        final var channelLinksPreview = new StringFilterGroup(
                Settings.HIDE_LINKS_PREVIEW,
                "attribution.e"
        );

        final var emergencyBox = new StringFilterGroup(
                Settings.HIDE_EMERGENCY_BOX,
                "emergency_onebox"
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

        final var artistCard = new StringFilterGroup(
                Settings.HIDE_ARTIST_CARDS,
                "official_card"
        );

        expandableMetadata = new StringFilterGroup(
                null,
                "expandable_metadata"
        );

        productCardBuffer = new ByteArrayFilterGroup(
                null,
                "gstatic.com/shopping"
        );

        summaryCardBuffer = new ByteArrayFilterGroup(
                null,
                "PAfeedback_genai"
        );

        final var compactChannelBar = new StringFilterGroup(
                Settings.HIDE_CHANNEL_BAR,
                "compact_channel_bar"
        );

        final var relatedVideos = new StringFilterGroup(
                Settings.HIDE_QUICK_ACTIONS_RELATED_VIDEOS,
                "fullscreen_related_videos"
        );

        final var playables = new StringFilterGroup(
                Settings.HIDE_PLAYABLES,
                "horizontal_gaming_shelf.e",
                "mini_game_card.e"
        );

        final var quickActions = new StringFilterGroup(
                Settings.HIDE_QUICK_ACTIONS,
                "quick_actions"
        );

        final var imageShelf = new StringFilterGroup(
                Settings.HIDE_IMAGE_SHELF,
                "image_shelf"
        );

        final var timedReactions = new StringFilterGroup(
                Settings.HIDE_TIMED_REACTIONS,
                "emoji_control_panel",
                "timed_reaction"
        );

        notifyMe = new StringFilterGroup(
                Settings.HIDE_NOTIFY_ME_BUTTON,
                "set_reminder_button"
        );

        compactChannelBarInner = new StringFilterGroup(
                Settings.HIDE_JOIN_MEMBERSHIP_BUTTON,
                "compact_channel_bar_inner"
        );

        compactChannelBarInnerButton = new StringFilterGroup(
                null,
                "|button.e"
        );

        joinMembershipButton = new ByteArrayFilterGroup(
                null,
                "sponsorships"
        );

        final var channelWatermark = new StringFilterGroup(
                Settings.HIDE_CHANNEL_WATERMARK,
                "featured_channel_watermark_overlay"
        );

        final var forYouShelf = new StringFilterGroup(
                Settings.HIDE_HORIZONTAL_SHELVES,
                "mixed_content_shelf"
        );

        final var videoRecommendationLabels = new StringFilterGroup(
                Settings.HIDE_VIDEO_RECOMMENDATION_LABELS,
                "endorsement_header_footer.e"
        );

        final var videoTitle = new StringFilterGroup(
                Settings.HIDE_VIDEO_TITLE,
                "player_overlay_video_heading.e"
        );

        final var webLinkPanel = new StringFilterGroup(
                Settings.HIDE_WEB_SEARCH_RESULTS,
                "web_link_panel",
                "web_result_panel"
        );

        channelProfile = new StringFilterGroup(
                null,
                "channel_profile.e",
                "page_header.e"
        );
        channelProfileGroupList = new StringFilterGroupList();
        channelProfileGroupList.addAll(new StringFilterGroup(
                        Settings.HIDE_COMMUNITY_BUTTON,
                        "community_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_JOIN_BUTTON,
                        "sponsor_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_STORE_BUTTON,
                        "header_store_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_SUBSCRIBE_BUTTON_IN_CHANNEL_PAGE,
                        "subscribe_button"
                )
        );

        addPathCallbacks(
                artistCard,
                audioTrackButton,
                channelLinksPreview,
                channelMembersShelf,
                channelProfile,
                channelWatermark,
                chipBar,
                compactBanner,
                compactChannelBar,
                compactChannelBarInner,
                communityPosts,
                crowdfundingBox,
                emergencyBox,
                expandableMetadata,
                forYouShelf,
                imageShelf,
                infoPanel,
                medicalPanel,
                notifyMe,
                paidPromotion,
                playables,
                postsShelf,
                quickActions,
                relatedVideos,
                searchFriction,
                singleItemInformationPanel,
                subscribedChannelsBar,
                subscribersCommunityGuidelines,
                subscriptionsChipBar,
                surveys,
                timedReactions,
                videoTitle,
                videoRecommendationLabels,
                webLinkPanel
        );
    }

    @Override
    boolean isFiltered(ContextInterface contextInterface,
                       String identifier,
                       String accessibility,
                       String path,
                       byte[] buffer,
                       StringFilterGroup matchedGroup,
                       FilterContentType contentType,
                       int contentIndex) {
        // This identifier is used not only in players but also in search results:
        // Until 2024, medical information panels such as Covid-19 also used this identifier and were shown in the search results.
        // From 2025, the medical information panel is no longer shown in the search results.
        // Therefore, this identifier does not filter when the search bar is activated.
        if (matchedGroup == searchFriction) {
            singleItemInformationPanelIndex = 0;
            return false;
        }
        if (matchedGroup == singleItemInformationPanel) {
            if (singleItemInformationPanelIndex >= 0) {
                if (singleItemInformationPanelIndex < 9) {
                    singleItemInformationPanelIndex++;
                } else {
                    singleItemInformationPanelIndex = -1;
                }
                return false;
            } else {
                return true;
            }
        }

        // The groups are excluded from the filter due to the exceptions list below.
        // Filter them separately here.
        if (matchedGroup == notifyMe || matchedGroup == surveys) {
            return true;
        }

        if (matchedGroup == expandableMetadata) {
            ExpandableCardStyle style = Settings.HIDE_EXPANDABLE_CARD.get();
            switch (style) {
                case HIDE_ALL -> {
                    return true;
                }
                case HIDE_PRODUCT_ONLY -> {
                    return productCardBuffer.check(buffer).isFiltered();
                }
                case HIDE_SUMMARY_ONLY -> {
                    return summaryCardBuffer.check(buffer).isFiltered();
                }
                case HIDE_PRODUCT_AND_SUMMARY -> {
                    return summaryCardBuffer.check(buffer).isFiltered() || productCardBuffer.check(buffer).isFiltered();
                }
                default -> {
                    return false;
                }
            }
        }

        if (matchedGroup == channelProfile) {
            return channelProfileGroupList.check(accessibility).isFiltered();
        }

        if (matchedGroup == communityPosts) {
            return contextInterface.isHomeFeedOrRelatedVideo() || contextInterface.isSubscriptionOrLibrary();
        }

        if (exceptions.matches(path)) return false; // Exceptions are not filtered.

        if (matchedGroup == compactChannelBarInner) {
            return compactChannelBarInnerButton.check(path).isFiltered()
                    // The filter may be broad, but in the context of a compactChannelBarInnerButton,
                    // it's safe to assume that the button is the only thing that should be hidden.
                    && joinMembershipButton.check(buffer).isFiltered();
        }

        if (matchedGroup == chipBar) {
            return contentIndex == 0 && NavigationButton.getSelectedNavigationButton() == NavigationButton.LIBRARY;
        }

        return true;
    }

    /**
     * Injection point.
     * Called from a different place then the other filters.
     */
    public static boolean filterMixPlaylists(@Nullable byte[] buffer) {
        // Edit: This hook may no longer be needed, and mix playlist filtering
        //       might be possible using the existing litho filters.
        try {
            if (!Settings.HIDE_MIX_PLAYLISTS.get()) {
                return false;
            }

            if (buffer == null) {
                Logger.printDebug(() -> "buffer is null");
                return false;
            }

            if (mixPlaylists.check(buffer).isFiltered()
                    // Prevent hiding the description of some videos accidentally.
                    && !mixPlaylistsBufferExceptions.check(buffer).isFiltered()) {
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
     */
    public static void hideLiveChatDonatorsBar(View view) {
        if (view == null || !Settings.HIDE_LIVE_CHAT_DONATORS_BAR.get()) {
            return;
        }

        view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                if (view.getParent() instanceof RecyclerView shelfContainerRecycleView) {
                    shelfContainerRecycleView.setVisibility(RecyclerView.GONE);
                }
            }
        });
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
        return Settings.HIDE_FILTER_BAR_FEED_IN_FEED.get()
                ? 0
                : height;
    }

    /**
     * Injection point.
     */
    public static int hideInSearch(int height) {
        return Settings.HIDE_FILTER_BAR_FEED_IN_SEARCH.get()
                ? 0
                : height;
    }

    private static final boolean HIDE_FILTER_BAR_FEED_IN_RELATED_VIDEOS_ENABLED
            = Settings.HIDE_FILTER_BAR_FEED_IN_RELATED_VIDEOS.get();

    /**
     * Injection point.
     */
    public static int hideInRelatedVideos(int height) {
        return HIDE_FILTER_BAR_FEED_IN_RELATED_VIDEOS_ENABLED
                ? 0
                : height;
    }

    /**
     * Injection point.
     */
    public static boolean hideInRelatedVideos(boolean original) {
        return HIDE_FILTER_BAR_FEED_IN_RELATED_VIDEOS_ENABLED || original;
    }

    /**
     * Injection point.
     */
    public static void hideInRelatedVideos(View chipView) {
        Utils.hideViewUnderCondition(HIDE_FILTER_BAR_FEED_IN_RELATED_VIDEOS_ENABLED, chipView);
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
     */
    public static SpannableString modifyFeedSubtitleSpan(SpannableString original, float truncationDimension) {
        try {
            final boolean hideViewCount = Settings.HIDE_VIEW_COUNT.get();
            final boolean hideUploadTime = Settings.HIDE_UPLOAD_TIME.get();
            if (!hideViewCount && !hideUploadTime) {
                return original;
            }

            // Applies only for these specific dimensions.
            if (truncationDimension == 16f || truncationDimension == 42f) {
                String delimiter = " · ";
                final int delimiterLength = delimiter.length();

                // Index includes the starting delimiter.
                final int viewCountStartIndex = TextUtils.indexOf(original, delimiter);
                if (viewCountStartIndex < 0) {
                    return original;
                }

                final int uploadTimeStartIndex = TextUtils.indexOf(original, delimiter,
                        viewCountStartIndex + delimiterLength);
                if (uploadTimeStartIndex < 0) {
                    return original;
                }

                // Ensure there is exactly 2 delimiters.
                if (TextUtils.indexOf(original, delimiter,
                        uploadTimeStartIndex + delimiterLength) >= 0) {
                    return original;
                }

                // Make a mutable copy that keeps existing span styling.
                SpannableStringBuilder builder = new SpannableStringBuilder(original);

                // Remove the sections.
                if (hideUploadTime) {
                    builder.delete(uploadTimeStartIndex, original.length());
                }

                if (hideViewCount) {
                    builder.delete(viewCountStartIndex, uploadTimeStartIndex);
                }

                SpannableString replacement = new SpannableString(builder);
                Logger.printDebug(() -> "Replacing feed subtitle span: " + original + " with: " + replacement);

                return replacement;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "modifyFeedSubtitleSpan failure", ex);
        }

        return original;
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

        for (String filter : flyoutMenuFilterStrings) {
            if (menuTitleString.equalsIgnoreCase(filter)) {
                return null;
            }
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

        for (String filter : flyoutMenuFilterStrings) {
            if (menuTitleString.equalsIgnoreCase(filter)) {
                Utils.hideViewByLayoutParams(parentView);
            }
        }
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

        for (String filter : channelTabFilterStrings) {
            if (channelTabText.equalsIgnoreCase(filter)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static boolean hideSearchTermThumbnails() {
        return Settings.HIDE_SEARCH_TERM_THUMBNAILS.get();
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
                && TextUtils.isEmpty(typedString);
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
}
