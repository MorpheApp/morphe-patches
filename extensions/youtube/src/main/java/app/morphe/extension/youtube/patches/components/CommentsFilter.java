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

import static app.morphe.extension.shared.Utils.getFilterStrings;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class CommentsFilter extends Filter {

    private static final String CHIP_BAR_PATH_PREFIX = "chip_bar.e";
    private static final String COMMENT_COMPOSER_PATH = "comment_composer.e";
    private static final String VIDEO_LOCKUP_WITH_ATTACHMENT_PATH = "video_lockup_with_attachment.e";
    private static final String VIDEO_METADATA_CAROUSEL_PATH = "video_metadata_carousel.e";

    private static final List<String> commentsCarouselFilterStrings = getFilterStrings(Settings.HIDE_COMMENTS_CAROUSEL_FILTER_STRINGS);

    private final StringFilterGroup comments;
    private final StringFilterGroup commentComposerButtons;
    private final ByteArrayFilterGroupList commentComposerButtonsGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup commentsFilterBar;
    private final StringFilterGroup emojiAndTimestampButtons;
    private final StringFilterGroup previewCommentDotsSelector;
    public CommentsFilter() {
        var channelGuidelines = new StringFilterGroup(
                Settings.HIDE_COMMENTS_CHANNEL_GUIDELINES,
                "channel_guidelines_entry_banner"
        );

        var chatSummary = new StringFilterGroup(
                Settings.HIDE_COMMENTS_AI_CHAT_SUMMARY,
                "live_chat_summary_banner.e"
        );

        comments = new StringFilterGroup(
                null,
                "video_metadata_carousel",
                "_comments"
        );

        var commentsByMembers = new StringFilterGroup(
                Settings.HIDE_COMMENTS_BY_MEMBERS_HEADER,
                "sponsorships_comments_header.e",
                "sponsorships_comments_footer.e"
        );

        commentComposerButtons = new StringFilterGroup(
                null,
                "|ContainerType|ContainerType|ContainerType|ContainerType|",
                "composer_main_action_button.e"
        );

        commentComposerButtonsGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_COMMENTS_CREATE_A_SHORT_BUTTON,
                        "composer_short_creation_button.e"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_COMMENTS_THANKS_BUTTON,
                        "super_thanks_button.e"
                )
        );

        commentsFilterBar = new StringFilterGroup(
                Settings.HIDE_FILTER_BAR_IN_COMMENTS,
                CHIP_BAR_PATH_PREFIX
        );

        var commentsPrompts = new StringFilterGroup(
                Settings.HIDE_COMMENTS_PROMPTS,
                "comment_filter_context.e",
                "timed_comments_welcome.e",
                "timed_comments_end.e"
        );

        var communityGuidelines = new StringFilterGroup(
                Settings.HIDE_COMMENTS_COMMUNITY_GUIDELINES,
                "community_guidelines"
        );

        emojiAndTimestampButtons = new StringFilterGroup(
                Settings.HIDE_COMMENTS_EMOJI_AND_TIMESTAMP_BUTTONS,
                "|CellType|ContainerType|ContainerType|ContainerType|ContainerType|ContainerType|"
        );

        var giftAnimationAndCards = new StringFilterGroup(
                Settings.HIDE_COMMENTS_GIFT_ANIMATION_AND_CARDS,
                "gift_overlay.e",
                "gift_attribution_card_classic_live.e"
        );

        var previewComment = new StringFilterGroup(
                Settings.HIDE_COMMENTS_PREVIEW_COMMENT,
                "|carousel_item",
                "comments_entry_point_teaser",
                "comments_entry_point_simplebox"
        );

        previewCommentDotsSelector = new StringFilterGroup(
                Settings.HIDE_COMMENTS_PREVIEW_COMMENT,
                VIDEO_METADATA_CAROUSEL_PATH
        );

        var thanksButton = new StringFilterGroup(
                Settings.HIDE_COMMENTS_THANKS_BUTTON,
                "super_thanks_button.e"
        );

        addPathCallbacks(
                channelGuidelines,
                chatSummary,
                comments,
                commentsByMembers,
                commentComposerButtons,
                commentsFilterBar,
                commentsPrompts,
                communityGuidelines,
                emojiAndTimestampButtons,
                giftAnimationAndCards,
                previewComment,
                previewCommentDotsSelector,
                thanksButton
        );
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (matchedGroup == comments) {
            if (path.startsWith(VIDEO_LOCKUP_WITH_ATTACHMENT_PATH)) {
                return Settings.HIDE_COMMENTS_SECTION_IN_HOME_FEED.get();
            }
            return Settings.HIDE_COMMENTS_SECTION.get();
        }

        if (matchedGroup == commentComposerButtons) {
            return commentComposerButtonsGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == commentsFilterBar) {
            return Settings.HIDE_FILTER_BAR_IN_COMMENTS.get() && PlayerType.getCurrent().isMaximizedOrFullscreen();
        }

        if (matchedGroup == emojiAndTimestampButtons) {
            return path.startsWith(COMMENT_COMPOSER_PATH);
        }

        if (matchedGroup == previewCommentDotsSelector) {
            return path.contains("carousel_header") && path.endsWith("|ContainerType|ContainerType|ContainerType|");
        }

        return true;
    }

    /**
     * Injection point.
     */
    public static void hideInComments(View view) {
        if (view == null || !Settings.HIDE_FILTER_BAR_IN_COMMENTS.get()) {
            return;
        }

        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                view.setLayoutParams(lp);
            }
            view.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     */
    public static byte[] onCommentsLoaded(byte[] bytes) {
        if (Settings.HIDE_COMMENTS_CAROUSEL.get() && !commentsCarouselFilterStrings.isEmpty()) {
            try {
                var newElement = NewElement.parseFrom(bytes).toBuilder();
                var identifier = newElement.getProperties().getIdentifierProperties().getIdentifier();
                if (identifier != null && identifier.contains(VIDEO_METADATA_CAROUSEL_PATH)) {
                    var type = newElement.getType().toBuilder();
                    var componentType = type.getComponentType().toBuilder();
                    var model = componentType.getModel().toBuilder();
                    var videoMetadataCarouselModel = model.getVideoMetadataCarouselModel().toBuilder();
                    var data = videoMetadataCarouselModel.getData().toBuilder();
                    var carouselTitleDatasList = data.getCarouselTitleDatasList();

                    boolean modified = false;

                    for (int i = carouselTitleDatasList.size() - 1; i > -1; i--) {
                        var carouselTitleData = carouselTitleDatasList.get(i);

                        String title = carouselTitleData.getTitle();
                        Logger.printDebug(() -> "comments title: " + title);

                        if (title != null) {
                            for (String filter : commentsCarouselFilterStrings) {
                                if (title.contains(filter)) {
                                    data.removeCarouselItemDatas(i);
                                    data.removeCarouselTitleDatas(i);
                                    modified = true;
                                }
                            }
                        }
                    }

                    if (modified) {
                        var newBuild = data.build();
                        videoMetadataCarouselModel.clearData();
                        videoMetadataCarouselModel.setData(newBuild);

                        var newVideoMetadataCarouselModel = videoMetadataCarouselModel.build();
                        model.clearVideoMetadataCarouselModel();
                        model.setVideoMetadataCarouselModel(newVideoMetadataCarouselModel);

                        var newModel = model.build();
                        componentType.clearModel();
                        componentType.setModel(newModel);

                        var newComponentType = componentType.build();
                        type.clearComponentType();
                        type.setComponentType(newComponentType);

                        var newType = type.build();
                        newElement.clearType();
                        newElement.setType(newType);

                        return newElement.build().toByteArray();
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse newElement", ex);
            }
        }

        return bytes;
    }

    /**
     * Injection point.
     */
    public static void hideCommentsInfoButton(View view) {
        if (Settings.HIDE_COMMENTS_INFO_BUTTON.get()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0);
            view.setLayoutParams(lp);
            view.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     */
    public static void hideCommentsFilterBarOptions(@NonNull String identifier,
                                                   @NonNull List<Object> treeNodeResultList) {
        try {
            if (Settings.HIDE_COMMENTS_FILTER_BAR_OPTIONS.get()
                    && identifier.startsWith(CHIP_BAR_PATH_PREFIX)
                    // Playlist sort button uses same components and must only filter if the player is opened.
                    && PlayerType.getCurrent().isMaximizedOrFullscreen()
            ) {
                int treeNodeResultListSize = treeNodeResultList.size();
                if (treeNodeResultListSize > 2) {
                    treeNodeResultList.subList(1, treeNodeResultListSize - 1).clear();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to hide comment filter bar options", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void hideLiveChatDonatorsBar(View view) {
        if (view == null || !Settings.HIDE_COMMENTS_LIVE_CHAT_DONATORS_BAR.get()) {
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
    public static void hideLiveChatEmojiButton(View view) {
        if (Settings.HIDE_COMMENTS_EMOJI_AND_TIMESTAMP_BUTTONS.get() && view != null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = 0;
                view.setLayoutParams(lp);
            }

            view.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Injection point.
     */
    public static void hideLiveChatThanksButton(View view) {
        if (Settings.HIDE_COMMENTS_THANKS_BUTTON.get() && view != null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = 0;
                lp.height = 0;
                view.setLayoutParams(lp);
            }

            view.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     */
    public static void hideLiveChatGiftButton(View view) {
        if (Settings.HIDE_COMMENTS_GIFT_BUTTON.get() && view != null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = 0;
                lp.height = 0;

                if (lp instanceof ViewGroup.MarginLayoutParams marginLp) {
                    marginLp.setMargins(0, 0, 0, 0);
                    marginLp.setMarginStart(0);
                    marginLp.setMarginEnd(0);
                }

                view.setLayoutParams(lp);
            }

            view.setPadding(0, 0, 0, 0);
            view.setVisibility(View.GONE);
        }
    }
}
