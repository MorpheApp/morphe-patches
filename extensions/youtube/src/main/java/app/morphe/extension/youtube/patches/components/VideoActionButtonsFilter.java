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

import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ActionButtons;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SecondaryContents;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SingleColumnWatchNextResults;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public class VideoActionButtonsFilter extends Filter {

    public enum ActionButton {
        UNKNOWN(false),
        ASK(
                Settings.HIDE_ASK_BUTTON.get(),
                "yt_fill_experimental_spark",
                "yt_fill_spark"
        ),
        CHANNEL_PROFILE(false),
        CLIP(Settings.HIDE_CLIP_BUTTON.get()),
        COMMENTS(
                Settings.HIDE_COMMENTS_BUTTON.get(),
                "yt_outline_experimental_text_bubble",
                "yt_outline_message_bubble"
        ),
        DOWNLOAD(Settings.HIDE_DOWNLOAD_BUTTON.get()),
        HYPE(
                Settings.HIDE_HYPE_BUTTON.get(),
                "yt_outline_experimental_hype",
                "yt_outline_star_shooting"
        ),
        LIKE_DISLIKE(Settings.HIDE_LIKE_DISLIKE_BUTTON.get()),
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
     * Whether to perform {@link #onLazilyConvertedElementLoaded(String, List)}.
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

    /**
     * Caches a list of action buttons based on video ID.
     */
    @GuardedBy("itself")
    private static final Map<String, List<ActionButton>> actionButtonLookup =
            Utils.createSizeRestrictedMap(10);

    private static final String COMPACT_CHANNEL_BAR_PREFIX = "compact_channel_bar.e";
    private static final String COMPACTIFY_VIDEO_ACTION_BAR_PREFIX = "compactify_video_action_bar.e";
    private static final String VIDEO_ACTION_BAR_PREFIX = "video_action_bar.e";

    private final StringFilterGroup likeSubscribeGlow;

    public VideoActionButtonsFilter() {
        StringFilterGroup actionBarGroup = new StringFilterGroup(
                Settings.HIDE_ACTION_BAR,
                VIDEO_ACTION_BAR_PREFIX
        );
        addIdentifierCallbacks(actionBarGroup);


        likeSubscribeGlow = new StringFilterGroup(
                Settings.DISABLE_LIKE_SUBSCRIBE_GLOW,
                "animated_button_border.e"
        );

        addPathCallbacks(likeSubscribeGlow);
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
        if (matchedGroup == likeSubscribeGlow) {
            return Utils.startsWithAny(path, COMPACT_CHANNEL_BAR_PREFIX, COMPACTIFY_VIDEO_ACTION_BAR_PREFIX, VIDEO_ACTION_BAR_PREFIX);
        }

        return true;
    }

    /**
     * Injection point.
     * Called after {@link #onSingleColumnWatchNextResultsLoaded(MessageLite)}.
     */
    public static void onLazilyConvertedElementLoaded(@NonNull String identifier,
                                                      @NonNull List<Object> treeNodeResultList) {
        // Check if hide video action buttons is enabled.
        if (!HIDE_ACTION_BUTTON) {
            return;
        }
        // Check if it is a video action bar.
        if (!Utils.startsWithAny(identifier, COMPACTIFY_VIDEO_ACTION_BAR_PREFIX, VIDEO_ACTION_BAR_PREFIX)) {
            return;
        }
        synchronized (actionButtonLookup) {
            // Check if a video action button list exists in actionButtonLookup.
            String videoId = VideoInformation.getVideoId();
            List<ActionButton> actionButtons = actionButtonLookup.get(videoId);
            if (actionButtons == null) {
                return;
            }

            // Check that the size of actionButtons matches the size of treeNodeResultList.
            // If they don't match, unintended buttons may be hidden.
            int actionButtonSize = actionButtons.size();
            int treeNodeResultListSize = treeNodeResultList.size();
            if (actionButtonSize != treeNodeResultListSize) {
                // Should never happen, but handle just in case.
                Logger.printDebug(() -> "The sizes of the lists do not match, actionButtonSize: " + actionButtonSize + ", treeNodeResultListSize: " + treeNodeResultListSize);
                return;
            }

            // Remove buttons from treeNodeResultList by iterating over actionButtons.
            for (int i = actionButtonSize - 1; i > -1; i--) {
                ActionButton actionButton = actionButtons.get(i);
                if (actionButton.shouldHide && i < treeNodeResultListSize) {
                    treeNodeResultList.remove(i);
                }
            }
        }
    }

    /**
     * Injection point.
     * Invoke as soon as the endpoint response is received.
     * <p>
     * The code to parse the nested innerTube response structure is quite complex,
     * but due to the efficient parsing performance of the proto parser, the parsing is completed within 20ms.
     */
    public static void onSingleColumnWatchNextResultsLoaded(MessageLite messageLite) {
        // Check if hide video action buttons is enabled.
        if (!HIDE_ACTION_BUTTON) {
            return;
        }
        synchronized (actionButtonLookup) {
            try {
                var singleColumnWatchNextResults = SingleColumnWatchNextResults.parseFrom(messageLite.toByteArray());
                var primaryResults = singleColumnWatchNextResults.getPrimaryResults();
                var secondaryResults = primaryResults.getSecondaryResults();

                // Find the contents that have slimVideoMetadataSectionRenderer.
                SecondaryContents finalSecondaryContents = null;
                for (var secondaryContents : secondaryResults.getSecondaryContentsList()) {
                    if (secondaryContents.hasSlimVideoMetadataSectionRenderer()) {
                        finalSecondaryContents = secondaryContents;
                        break;
                    }
                }
                if (finalSecondaryContents == null) return;

                var slimVideoMetadataSectionRenderer = finalSecondaryContents.getSlimVideoMetadataSectionRenderer();

                // slimVideoMetadataSectionRenderer has a videoId field.
                var videoId = slimVideoMetadataSectionRenderer.getVideoId();

                // If there is already an ActionButton list in actionButtonLookup, it just exits.
                if (actionButtonLookup.containsKey(videoId)) {
                    return;
                }

                // Find a NewElement that has a video action bar.
                NewElement finalNewElement = null;
                for (var tertiaryContentsList : slimVideoMetadataSectionRenderer.getTertiaryContentsList()) {
                    var elementRenderer = tertiaryContentsList.getElementRenderer();
                    var newElement = elementRenderer.getNewElement();
                    var properties = newElement.getProperties();
                    var identifierProperties = properties.getIdentifierProperties();
                    String identifier = identifierProperties.getIdentifier();
                    if (Utils.startsWithAny(identifier, COMPACTIFY_VIDEO_ACTION_BAR_PREFIX, VIDEO_ACTION_BAR_PREFIX)) {
                        finalNewElement = newElement;
                        break;
                    }
                }
                if (finalNewElement == null) return;

                var type = finalNewElement.getType();
                var componentType = type.getComponentType();
                var model = componentType.getModel();
                List<ActionButtons> finalActionButtons = null;

                if (model.hasYoutubeModel()) { // Collapsed video action bar.
                    var youtubeModel = model.getYoutubeModel();
                    var viewModel = youtubeModel.getViewModel();
                    var compactifyVideoActionBarViewModel = viewModel.getCompactifyVideoActionBarViewModel();

                    finalActionButtons = compactifyVideoActionBarViewModel.getActionButtonsList();
                } else if (model.hasVideoActionBarModel()) { // Non-collapsed video action bar.
                    var videoActionBarModel = model.getVideoActionBarModel();
                    var videoActionBarData = videoActionBarModel.getVideoActionBarData();

                    finalActionButtons = videoActionBarData.getActionButtonsList();
                } else {
                    // Should never happen, but handle just in case.
                    Logger.printDebug(() -> "Unknown model: " + model + ", videoId: " + videoId);
                }
                if (finalActionButtons == null || finalActionButtons.isEmpty()) return;

                int size = finalActionButtons.size();
                int i = 0;
                List<ActionButton> actionButtons = new ArrayList<>(size);

                // Iterate through the action bar and populate the ActionButton list.
                for (var buttons : finalActionButtons) {
                    ActionButton actionButton = ActionButton.UNKNOWN;
                    var primaryButtonViewModel = buttons.getPrimaryButtonViewModel();
                    if (primaryButtonViewModel.hasSecondaryButtonViewModel()) {
                        var secondaryButtonViewModel = primaryButtonViewModel.getSecondaryButtonViewModel();
                        String iconName = secondaryButtonViewModel.getIconName();
                        if (iconName != null) {
                            for (ActionButton button : ActionButton.values()) {
                                if (actionButton == ActionButton.UNKNOWN) {
                                    for (String icon : button.iconNames) {
                                        if (iconName.contains(icon)) {
                                            actionButton = button;
                                            break;
                                        }
                                    }
                                }
                            }
                            // This is a new button that has not yet been implemented in settings.
                            // It will be treated as 'ActionButton.UNKNOWN' and ignored.
                            if (actionButton == ActionButton.UNKNOWN) {
                                Logger.printDebug(() -> "Unknown iconName: " + iconName + ", videoId: " + videoId);
                            }
                        }
                    } else if (primaryButtonViewModel.hasAddToPlaylistButtonViewModel()) {
                        actionButton = ActionButton.SAVE;
                    } else if (primaryButtonViewModel.hasClipButtonViewModel()) {
                        actionButton = ActionButton.CLIP;
                    }  else if (primaryButtonViewModel.hasCompactChannelBarViewModel()) {
                        actionButton = ActionButton.CHANNEL_PROFILE;
                    } else if (primaryButtonViewModel.hasDownloadButtonViewModel()) {
                        actionButton = ActionButton.DOWNLOAD;
                    } else if (primaryButtonViewModel.hasSegmentedLikeDislikeButtonViewModel()) {
                        actionButton = ActionButton.LIKE_DISLIKE;
                    } else {
                        // Due to A/B testing, a new type of ButtonViewModel was used that was not defined in the proto file.
                        Logger.printDebug(() -> "Unknown buttonViewModel: " + primaryButtonViewModel + ", videoId: " + videoId);
                    }
                    actionButtons.add(i, actionButton);
                    i++;
                }

                // Once the iteration is complete, add the ActionButton list to actionButtonLookup.
                Logger.printDebug(() -> "New video id: " + videoId + ", action buttons: " + actionButtons);
                actionButtonLookup.put(videoId, actionButtons);
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse SingleColumnWatchNextResults", ex);
            }
        }
    }
}
