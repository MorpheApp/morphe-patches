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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.patches.components.StringFilterGroupList;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.Element;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarItem;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarItemContent;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarOverflow;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarViewModel;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class VideoActionButtonsFilter extends Filter {

    public enum ActionButton {
        ASK(
                Settings.HIDE_ASK_BUTTON.get(),
                "yt_fill_experimental_spark",
                "yt_fill_spark"
        ),
        CHANNEL_PROFILE(Settings.HIDE_CHANNEL_PROFILE_BUTTON.get()),
        CLIP(Settings.HIDE_CLIP_BUTTON.get()),
        COMMENTS(
                Settings.HIDE_COMMENTS_BUTTON.get(),
                "yt_outline_experimental_text_bubble",
                "yt_outline_message_bubble"
        ),
        CONNECT(Settings.HIDE_CONNECT_BUTTON.get()),
        DISLIKE(Settings.HIDE_LIKE_DISLIKE_BUTTON.get()),
        DOWNLOAD(Settings.HIDE_DOWNLOAD_BUTTON.get()),
        HYPE(
                Settings.HIDE_HYPE_BUTTON.get(),
                "yt_outline_experimental_hype",
                "yt_outline_star_shooting"
        ),
        LIKE(Settings.HIDE_LIKE_DISLIKE_BUTTON.get()),
        LIKE_DISLIKE(Settings.HIDE_LIKE_DISLIKE_BUTTON.get()),
        MORE(Settings.HIDE_MORE_BUTTON.get()),
        PROMOTE(
                Settings.HIDE_PROMOTE_BUTTON.get(),
                "yt_outline_experimental_megaphone",
                "yt_outline_megaphone"
        ),
        REMIX(
                Settings.HIDE_REMIX_BUTTON.get(),
                "yt_outline_youtube_shorts_plus",
                "yt_outline_experimental_remix"
        ),
        REPORT(
                Settings.HIDE_REPORT_BUTTON.get(),
                "yt_outline_experimental_flag",
                "yt_outline_flag"
        ),
        SAVE(Settings.HIDE_SAVE_BUTTON.get()),
        SHARE(
                Settings.HIDE_SHARE_BUTTON.get(),
                "yt_outline_experimental_share",
                "yt_outline_share"
        ),
        SHOP(
                Settings.HIDE_SHOP_BUTTON.get(),
                "yt_outline_experimental_bag",
                "yt_outline_bag"
        ),
        STOP_ADS(
                Settings.HIDE_STOP_ADS_BUTTON.get(),
                "yt_outline_experimental_circle_slash",
                "yt_outline_slash_circle_left"
        ),
        THANKS(
                Settings.HIDE_THANKS_BUTTON.get(),
                "yt_outline_experimental_dollar_sign_heart",
                "yt_outline_dollar_sign_heart"
        );

        public final boolean shouldHide;
        @NonNull
        public final List<String> iconNames;

        ActionButton(boolean shouldHide) {
            this.shouldHide = shouldHide;
            this.iconNames = Collections.emptyList();
        }

        ActionButton(boolean shouldHide, @NonNull String... iconNames) {
            this.shouldHide = shouldHide;
            this.iconNames = Arrays.asList(iconNames);
        }
    }

    /**
     * Whether to perform {@link #hideActionButtons(byte[])}.
     */
    private static final boolean HIDE_ACTION_BUTTON;

    static {
        boolean hideActionButton = false;
        for (ActionButton button : ActionButton.values()) {
            if (button.shouldHide) {
                hideActionButton = true;
                break;
            }
        }
        HIDE_ACTION_BUTTON = hideActionButton;
    }

    private static final String COMPACT_CHANNEL_BAR_PREFIX = "compact_channel_bar.e";
    private static final String COMPACTIFY_VIDEO_ACTION_BAR_PREFIX = "compactify_video_action_bar.e";
    private static final String VIDEO_ACTION_BAR_PREFIX = "video_action_bar.e";

    private static final String ELEMENT_BUTTON_ID = "id.elements.button";
    private static final String MORE_BUTTON_PATH = "overflow_menu_button.e";

    private final StringFilterGroup actionBarGroup;
    private final StringFilterGroup likeSubscribeGlow;
    private final StringFilterGroup moreButton;
    private final StringFilterGroup channelProfile;
    private final StringFilterGroup subscribeButton;
    private final StringFilterGroupList accessibilityGroupList = new StringFilterGroupList();
    private final ByteArrayFilterGroupList bufferGroupList = new ByteArrayFilterGroupList();

    public VideoActionButtonsFilter() {
        actionBarGroup = new StringFilterGroup(
                null,
                VIDEO_ACTION_BAR_PREFIX
        );
        addIdentifierCallbacks(actionBarGroup);


        likeSubscribeGlow = new StringFilterGroup(
                Settings.DISABLE_LIKE_SUBSCRIBE_GLOW,
                "animated_button_border.e"
        );
        moreButton = new StringFilterGroup(
                Settings.HIDE_MORE_BUTTON,
                MORE_BUTTON_PATH
        );

        // The minimal layout draws no action bar without the channel in its element, so the channel is hidden here.
        channelProfile = new StringFilterGroup(
                Settings.HIDE_CHANNEL_PROFILE_BUTTON,
                "|compact_channel_bar.e"
        );

        subscribeButton = new StringFilterGroup(
                Settings.HIDE_CHANNEL_PROFILE_SUBSCRIBE_BUTTON,
                "|compact_channel_bar_animated_buttons.e"
        );

        addPathCallbacks(channelProfile, likeSubscribeGlow, moreButton, subscribeButton);

        //
        // All other action buttons.
        //
        accessibilityGroupList.addAll(
                new StringFilterGroup(
                        Settings.HIDE_LIKE_DISLIKE_BUTTON,
                        "id.video.dislike",
                        "id.video.like"
                ),
                new StringFilterGroup(
                        Settings.HIDE_SHARE_BUTTON,
                        "id.video.share"
                ),
                new StringFilterGroup(
                        Settings.HIDE_SAVE_BUTTON,
                        "id.video.add_to.button"
                )
        );
        bufferGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_ASK_BUTTON,
                        "PAyouchat"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_HYPE_BUTTON,
                        "yt_outline_experimental_hype",
                        "yt_outline_star_shooting"
                )
        );
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
        if (matchedGroup == likeSubscribeGlow) {
            return Utils.startsWithAny(path, COMPACT_CHANNEL_BAR_PREFIX, COMPACTIFY_VIDEO_ACTION_BAR_PREFIX, VIDEO_ACTION_BAR_PREFIX);
        } else if (matchedGroup == moreButton) {
            return true;
        } else if (matchedGroup == channelProfile || matchedGroup == subscribeButton) {
            // Only the Subscribe and Join buttons next to the channel avatar in the action bar.
            return Utils.startsWithAny(path, COMPACTIFY_VIDEO_ACTION_BAR_PREFIX, VIDEO_ACTION_BAR_PREFIX);
        } else if (matchedGroup == actionBarGroup) {
            if (Settings.HIDE_ACTION_BAR.get() || accessibilityGroupList.check(accessibility).isFiltered()) {
                return true;
            } else if (accessibility != null && accessibility.startsWith(ELEMENT_BUTTON_ID) && !Utils.contains(path, MORE_BUTTON_PATH)) {
                return bufferGroupList.check(buffer).isFiltered();
            }
            return false;
        }

        return true;
    }

    /**
     * Injection point.
     * <p>
     * Removes the hidden buttons from the element of the action bar, for every layout of the action bar.
     */
    public static byte[] hideActionButtons(byte[] bytes) {
        if (!HIDE_ACTION_BUTTON || !FeedVideoCardsPatch.getElementText(bytes).contains(VIDEO_ACTION_BAR_PREFIX)) {
            return bytes;
        }

        try {
            Element element = Element.parseFrom(bytes);
            ComponentType componentType = element.getType().getComponentType();
            ElementModel model = componentType.getModel();
            ElementModel.Builder newModel = model.toBuilder();

            if (model.hasVideoActionBarModel()) {
                VideoActionBarModel actionBarModel = model.getVideoActionBarModel();
                newModel.setVideoActionBarModel(actionBarModel.toBuilder()
                        .setVideoActionBarData(actionBarModel.getVideoActionBarData().toBuilder()
                                .clearActions()
                                .addAllActions(getShownActions(actionBarModel.getVideoActionBarData().getActionsList()))));
            } else if (model.getLockupModel().getViewModel().hasVideoActionBarViewModel()) {
                LockupModel lockupModel = model.getLockupModel();
                VideoActionBarViewModel actionBar = lockupModel.getViewModel().getVideoActionBarViewModel();
                List<VideoActionBarItem> shownActions = getShownActions(actionBar.getActionsList());
                VideoActionBarViewModel.Builder newActionBar = actionBar.toBuilder()
                        .clearActions()
                        .addAllActions(shownActions);
                // The minimal layout draws no action bar without an item, not even the channel.
                // The filter hides the item that is kept, by its path or its accessibility id.
                if (shownActions.isEmpty() && actionBar.hasSubscribeButton() && actionBar.getActionsCount() > 0) {
                    newActionBar.addActions(actionBar.getActions(0));
                }
                newModel.setLockupModel(lockupModel.toBuilder()
                        .setViewModel(lockupModel.getViewModel().toBuilder()
                                .setVideoActionBarViewModel(newActionBar)));
            } else {
                return bytes;
            }

            return element.toBuilder()
                    .setType(element.getType().toBuilder()
                            .setComponentType(componentType.toBuilder().setModel(newModel)))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "hideActionButtons failure", ex);
        }

        return bytes;
    }

    private static List<VideoActionBarItem> getShownActions(List<VideoActionBarItem> actions) {
        List<VideoActionBarItem> shownActions = new ArrayList<>(actions.size());
        for (VideoActionBarItem action : actions) {
            VideoActionBarItemContent content = action.getContent();
            if (!content.hasOverflow()) {
                if (!isHidden(content)) {
                    shownActions.add(action);
                }
                continue;
            }

            // The overflow menu of the minimal layout, which is not shown without its items.
            VideoActionBarOverflow.Builder overflow = content.getOverflow().toBuilder()
                    .clearMenuItems()
                    .clearShownItems();
            for (VideoActionBarItemContent item : content.getOverflow().getMenuItemsList()) {
                if (!isHidden(item)) {
                    overflow.addMenuItems(item);
                }
            }
            for (VideoActionBarItemContent item : content.getOverflow().getShownItemsList()) {
                if (!isHidden(item)) {
                    overflow.addShownItems(item);
                }
            }
            boolean hasMenuItem = false;
            for (VideoActionBarItemContent item : overflow.getMenuItemsList()) {
                hasMenuItem |= !item.hasMenuHeader();
            }
            if (!ActionButton.MORE.shouldHide && hasMenuItem) {
                shownActions.add(action.toBuilder().setContent(content.toBuilder().setOverflow(overflow)).build());
            }
        }
        return shownActions;
    }

    private static boolean isHidden(VideoActionBarItemContent content) {
        if (content.hasButton()) {
            String iconName = content.getButton().getIconName();
            for (ActionButton button : ActionButton.values()) {
                for (String icon : button.iconNames) {
                    if (iconName.contains(icon)) {
                        return button.shouldHide;
                    }
                }
            }
            return false;
        }

        final ActionButton actionButton;
        if (content.hasLikeDislikeButtons()) {
            actionButton = ActionButton.LIKE_DISLIKE;
        } else if (content.hasLikeButton()) {
            actionButton = ActionButton.LIKE;
        } else if (content.hasDislikeButton()) {
            actionButton = ActionButton.DISLIKE;
        } else if (content.hasSaveButton()) {
            actionButton = ActionButton.SAVE;
        } else if (content.hasDownloadButton()) {
            actionButton = ActionButton.DOWNLOAD;
        } else if (content.hasClipButton()) {
            actionButton = ActionButton.CLIP;
        } else if (content.hasAccountLinkButton()) {
            actionButton = ActionButton.CONNECT;
        } else if (content.hasSubscribeButton()) {
            actionButton = ActionButton.CHANNEL_PROFILE;
        } else {
            return false;
        }
        return actionButton.shouldHide;
    }
}
