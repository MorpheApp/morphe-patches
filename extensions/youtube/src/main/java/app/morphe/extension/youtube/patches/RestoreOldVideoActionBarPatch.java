/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.net.Uri;
import android.util.Base64;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AddToPlaylistButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ButtonViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CompactChannelBarModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CompactChannelBarViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CompactChannelBarViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.DislikeButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.Element;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.EnvironmentSubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeStatusEntityKey;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.SubscribeState;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.SubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ToggleButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ToggleButtonViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarItem;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarItemContent;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarLayoutConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarLikeCount;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarLikeDislike;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarOverflow;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarSize;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoActionBarViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SecondaryContents;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SlimVideoMetadataSectionRenderer;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.TertiaryContents;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.requests.ConfigRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class RestoreOldVideoActionBarPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface ConfigInfoInterface {
        // Methods are added during patching.
        void patch_setColdConfigData(String coldConfigData);
        void patch_setColdHashData(String coldHashData);
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface RequestInterface {
        // Method is added during patching.
        String patch_getEndpoint();
    }

    public enum VideoActionBarLayout {
        /**
         * The layout that the server sends to the account.
         */
        DEFAULT,
        /**
         * Buttons with their text inside a pill, that scroll in a row with the subscribe button.
         */
        CLASSIC,
        /**
         * The same buttons of {@link #CLASSIC} in their own row, below a row with the channel and the subscribe button.
         */
        CLASSIC_TWO_ROWS,
        /**
         * Buttons with an icon above their text, and the other buttons in a menu.
         */
        MINIMAL
    }

    /**
     * Tablets show a non-collapsed video action bar with every layout.
     * If it does not work on a foldable device, please remove this.
     */
    private static final VideoActionBarLayout LAYOUT = Utils.isTablet()
            // If 'Disable layout updates' is enabled, the layout is not changed.
            || Settings.DISABLE_LAYOUT_UPDATES.get()
            ? VideoActionBarLayout.DEFAULT
            : Settings.VIDEO_ACTION_BAR_LAYOUT.get();
    private static final boolean FIX_VIDEO_ACTION_BAR = LAYOUT == VideoActionBarLayout.CLASSIC
            || LAYOUT == VideoActionBarLayout.CLASSIC_TWO_ROWS;

    private static final String VIDEO_ACTION_BAR_TEMPLATE = "compactify_video_action_bar.eml";
    private static final int LAYOUT_STYLE_CLASSIC = 4;
    private static final int LAYOUT_STYLE_MINIMAL = 6;
    /**
     * Channel name beside the avatar, and the subscribe button at the end of the row, as on tablets.
     */
    private static final int TWO_ROWS_CHANNEL_BAR_STYLE = 2;
    private static final FeedCardLayoutPatch.VideoTemplate CHANNEL_BAR_TEMPLATE =
            new FeedCardLayoutPatch.VideoTemplate("compact_channel_bar.eml", 391108665, 6, 5);
    private static final int CHANNEL_BAR_ENVIRONMENT_FIELD = 4;
    private static final String SHARE_BUTTON_ID = "id.video.share.button";
    private static final String OVERFLOW_BUTTON_ICON = "yt_outline_experimental_overflow_horizontal_black_24";
    private static final String MORE_ACTIONS_TEXT = "accessibility_more_actions_video";
    private static final int LIKE_COUNT_ENTITY_TYPE = 445;
    private static final int CLASSIC_BUTTON_TYPE = 5;
    private static final int CLASSIC_BUTTON_SIZE = 2;
    private static final int CLASSIC_DOWNLOAD_BUTTON_STYLE = 4;
    private static final int MINIMAL_BUTTON_TYPE = 3;
    private static final int MINIMAL_BUTTON_SIZE = 4;
    private static final int MINIMAL_BUTTON_ICON_STYLE = 1;
    /**
     * Values of the action bar of the minimal layout, which are not in the action bar of the classic layout.
     */
    private static final VideoActionBarSize MINIMAL_LAYOUT_SIZE = VideoActionBarSize.newBuilder()
            .setMinButtonsWidth(150)
            .setMaxButtonsWidth(150)
            .setButtonSpacing(2)
            .setMaxVisibleButtons(5)
            .build();
    private static final String AUTHORIZATION_HEADER = "Authorization";
    /**
     * This field value is fetched when the app is first installed.
     * It does not change unless the server-side kill switch is activated.
     * The recent wide rollout of the modern video action bar is also one instance where the server-side kill switch was activated.
     */
    private static final String COLD_CONFIG_DATA_HEADER = "X-Youtube-Cold-Config-Data";
    private static final String COLD_HASH_DATA_HEADER = "X-Youtube-Cold-Hash-Data";
    private static final String VISITOR_ID_HEADER = "X-Goog-Visitor-Id";
    private static boolean needFetch = true;
    /**
     * Field number of the continuation token in the body of 'next' requests.
     * Watch page requests have no continuation. Comment requests do.
     */
    private static final int NEXT_REQUEST_CONTINUATION_FIELD = 8;
    /**
     * Time when the body of a watch page 'next' request was last built, or zero if none is pending.
     * Comment requests also use the 'next' endpoint, and overriding their config
     * prevents newly posted comments from showing until the comment sorting is changed.
     */
    private static volatile long watchNextRequestTime;
    private static final long WATCH_NEXT_REQUEST_TIMEOUT_MILLISECONDS = 10_000;

    private static void fetchRequestIfNeeded(String url, Map<String, String> requestHeaders) {
        if (Settings.INNERTUBE_COLD_CONFIG_DATA.isSetToDefault() || Settings.INNERTUBE_COLD_HASH_DATA.isSetToDefault()) {
            if (needFetch) {
                if (requestHeaders != null)  {
                    String visitorId = requestHeaders.get(VISITOR_ID_HEADER);
                    if (Utils.isNotEmpty(visitorId)) {
                        Map<String, String> minHeaders = new HashMap<>();
                        minHeaders.put(VISITOR_ID_HEADER, visitorId);

                        String authorization = requestHeaders.get(AUTHORIZATION_HEADER);
                        if (Utils.isNotEmpty(authorization)) {
                            minHeaders.put(AUTHORIZATION_HEADER, authorization);
                        }

                        needFetch = false;
                        ConfigRequest.fetchRequest(minHeaders);
                    }
                }
            }
        } else {
            needFetch = false;
        }
    }

    /**
     * Injection point.
     * <p>
     * Both layouts are the same element of the watch page with another style, and the server
     * chooses the style, so the action bar of the chosen layout is built from the element it sends.
     */
    public static byte[] setVideoActionBarLayout(byte[] bytes) {
        if (LAYOUT == VideoActionBarLayout.DEFAULT
                || !FeedVideoCardsPatch.getElementText(bytes).contains(VIDEO_ACTION_BAR_TEMPLATE)) {
            return bytes;
        }

        try {
            Element element = Element.parseFrom(bytes);
            ComponentType componentType = element.getType().getComponentType();
            LockupModel lockupModel = componentType.getModel().getLockupModel();
            VideoActionBarViewModel actionBar = lockupModel.getViewModel().getVideoActionBarViewModel();
            final boolean minimal = LAYOUT == VideoActionBarLayout.MINIMAL;
            if (actionBar.getLayoutStyle() != (minimal ? LAYOUT_STYLE_CLASSIC : LAYOUT_STYLE_MINIMAL)) {
                return bytes;
            }

            VideoActionBarViewModel.Builder newActionBar = actionBar.toBuilder().clearActions();
            if (minimal) {
                VideoActionBarOverflow.Builder overflow = VideoActionBarOverflow.newBuilder();
                for (VideoActionBarItem action : actionBar.getActionsList()) {
                    VideoActionBarItemContent content = action.getContent();
                    if (content.hasSubscribeButton()) {
                        newActionBar.setSubscribeButton(content);
                    } else if (content.hasLikeDislikeButtons()) {
                        VideoActionBarLikeDislike likeDislike = content.getLikeDislikeButtons();
                        LikeButtonViewModel likeButton = likeDislike.getLikeButton().getLikeButton();
                        DislikeButtonViewModel dislikeButton = likeDislike.getDislikeButton().getDislikeButton();
                        VideoActionBarItemContent minimalLikeButton = VideoActionBarItemContent.newBuilder()
                                .setLikeButton(likeButton.toBuilder()
                                        .setToggleButtonViewModel(layoutToggleButton(likeButton.getToggleButtonViewModel(), true))
                                        .setAnimationEnabled1(true)
                                        .setAnimationEnabled2(true)
                                        .setAnimationEnabled3(true)
                                        .setLikeAnimationUrl(likeDislike.getLikeAnimationUrl())
                                        .setLikedAnimationUrl(likeDislike.getLikedAnimationUrl())
                                        .setLikeCountAnimationParams(likeDislike.getLikeCountAnimationParams()))
                                .build();
                        VideoActionBarItemContent minimalDislikeButton = VideoActionBarItemContent.newBuilder()
                                .setDislikeButton(dislikeButton.toBuilder()
                                        .setToggleButtonViewModel(layoutToggleButton(dislikeButton.getToggleButtonViewModel(), true)))
                                .build();
                        newActionBar
                                .addActions(VideoActionBarItem.newBuilder().setContent(minimalLikeButton))
                                .addActions(VideoActionBarItem.newBuilder().setContent(minimalDislikeButton));
                        overflow.addShownItems(minimalLikeButton).addShownItems(minimalDislikeButton);
                    } else if (content.hasSaveButton()) {
                        AddToPlaylistButtonViewModel saveButton = content.getSaveButton();
                        VideoActionBarItemContent minimalSaveButton = content.toBuilder()
                                .setSaveButton(saveButton.toBuilder()
                                        .setToggleButtonViewModel(layoutToggleButton(saveButton.getToggleButtonViewModel(), true)))
                                .build();
                        newActionBar.addActions(action.toBuilder().setContent(minimalSaveButton));
                        overflow.addShownItems(minimalSaveButton);
                    } else if (SHARE_BUTTON_ID.equals(content.getButton().getTargetId())) {
                        VideoActionBarItemContent minimalShareButton = content.toBuilder()
                                .setButton(layoutButton(content.getButton(), true))
                                .build();
                        newActionBar.addActions(action.toBuilder().setContent(minimalShareButton));
                        overflow.addShownItems(minimalShareButton);
                    } else {
                        overflow.addMenuItems(content);
                    }
                }

                if (overflow.getMenuItemsCount() > 0) {
                    overflow.setMenuButton(ButtonViewModelHolder.newBuilder()
                                    .setButtonViewModel(layoutButton(ButtonViewModel.newBuilder()
                                            .setIconName(OVERFLOW_BUTTON_ICON)
                                            .setAccessibilityText(ResourceUtils.getStringIdentifier(MORE_ACTIONS_TEXT) == 0
                                                    ? ""
                                                    : ResourceUtils.getString(MORE_ACTIONS_TEXT))
                                            .build(), true)))
                            .setMenuStyle(1);
                    newActionBar.addActions(VideoActionBarItem.newBuilder()
                            .setContent(VideoActionBarItemContent.newBuilder().setOverflow(overflow)));
                }

                newActionBar.setSize(MINIMAL_LAYOUT_SIZE);
            } else {
                // The subscribe button is the first item of the classic layout.
                if (actionBar.hasSubscribeButton()) {
                    newActionBar.addActions(VideoActionBarItem.newBuilder().setContent(actionBar.getSubscribeButton()));
                }

                // The items of the minimal layout have no text, but the same items in its overflow have.
                VideoActionBarOverflow overflow = VideoActionBarOverflow.getDefaultInstance();
                for (VideoActionBarItem action : actionBar.getActionsList()) {
                    if (action.getContent().hasOverflow()) {
                        overflow = action.getContent().getOverflow();
                    }
                }
                VideoActionBarItemContent shownLikeButton = VideoActionBarItemContent.getDefaultInstance();
                VideoActionBarItemContent shownDislikeButton = VideoActionBarItemContent.getDefaultInstance();
                VideoActionBarItemContent shownSaveButton = VideoActionBarItemContent.getDefaultInstance();
                for (VideoActionBarItemContent content : overflow.getShownItemsList()) {
                    if (content.hasLikeButton()) {
                        shownLikeButton = content;
                    } else if (content.hasDislikeButton()) {
                        shownDislikeButton = content;
                    } else if (content.hasSaveButton()) {
                        shownSaveButton = content;
                    }
                }

                LikeButtonViewModel likeButton = LikeButtonViewModel.getDefaultInstance();
                for (VideoActionBarItem action : actionBar.getActionsList()) {
                    VideoActionBarItemContent content = action.getContent();
                    if (content.hasLikeButton()) {
                        likeButton = content.getLikeButton();
                    } else if (content.hasDislikeButton()) {
                        // The count of the likes is an entity of the video, with the key of the like status of another type.
                        LikeStatusEntityKey likeKey = LikeStatusEntityKey.parseFrom(Base64.decode(
                                likeButton.getLikeStatusEntityKey().replace("%3D", "="), Base64.URL_SAFE));
                        String likeCountKey = Base64.encodeToString(
                                        likeKey.toBuilder().setEntityType(LIKE_COUNT_ENTITY_TYPE).build().toByteArray(),
                                        Base64.URL_SAFE | Base64.NO_WRAP)
                                .replace("=", "%3D");

                        ToggleButtonViewModelHolder likeToggleButton = shownLikeButton.hasLikeButton()
                                ? shownLikeButton.getLikeButton().getToggleButtonViewModel()
                                : likeButton.getToggleButtonViewModel();
                        ToggleButtonViewModelHolder dislikeToggleButton = shownDislikeButton.hasDislikeButton()
                                ? shownDislikeButton.getDislikeButton().getToggleButtonViewModel()
                                : content.getDislikeButton().getToggleButtonViewModel();
                        newActionBar.addActions(VideoActionBarItem.newBuilder()
                                .setContent(VideoActionBarItemContent.newBuilder()
                                        .setLikeDislikeButtons(VideoActionBarLikeDislike.newBuilder()
                                                .setLikeButton(VideoActionBarItemContent.newBuilder()
                                                        .setLikeButton(likeButton.toBuilder()
                                                                .setToggleButtonViewModel(layoutToggleButton(likeToggleButton, false))
                                                                .clearAnimationEnabled1()
                                                                .clearAnimationEnabled2()
                                                                .clearAnimationEnabled3()
                                                                .clearLikeAnimationUrl()
                                                                .clearLikedAnimationUrl()
                                                                .clearLikeCountAnimationParams()))
                                                .setDislikeButton(VideoActionBarItemContent.newBuilder()
                                                        .setDislikeButton(content.getDislikeButton().toBuilder()
                                                                .setToggleButtonViewModel(layoutToggleButton(dislikeToggleButton, false))))
                                                .setShowLikeCount(true)
                                                .setLikeCount(VideoActionBarLikeCount.newBuilder().setEntityKey(likeCountKey))
                                                .setLikeCountAnimationParams(likeButton.getLikeCountAnimationParams())
                                                .setAnimationEnabled1(true)
                                                .setAnimationEnabled2(true)
                                                .setLikeAnimationUrl(likeButton.getLikeAnimationUrl())
                                                .setLikedAnimationUrl(likeButton.getLikedAnimationUrl()))));
                    } else if (content.hasSaveButton()) {
                        ToggleButtonViewModelHolder saveToggleButton = shownSaveButton.hasSaveButton()
                                ? shownSaveButton.getSaveButton().getToggleButtonViewModel()
                                : content.getSaveButton().getToggleButtonViewModel();
                        newActionBar.addActions(action.toBuilder().setContent(content.toBuilder()
                                .setSaveButton(content.getSaveButton().toBuilder()
                                        .setToggleButtonViewModel(layoutToggleButton(saveToggleButton, false)))));
                    } else if (SHARE_BUTTON_ID.equals(content.getButton().getTargetId())) {
                        newActionBar.addActions(action.toBuilder().setContent(content.toBuilder()
                                .setButton(layoutButton(content.getButton(), false))));
                    }
                }

                // The items of the menu of the minimal layout are in the row of the classic layout.
                for (VideoActionBarItemContent content : overflow.getMenuItemsList()) {
                    if (content.hasDownloadButton()) {
                        newActionBar.addActions(VideoActionBarItem.newBuilder().setContent(content.toBuilder()
                                .setDownloadButton(content.getDownloadButton().toBuilder()
                                        .setStyle(CLASSIC_DOWNLOAD_BUTTON_STYLE)
                                        .clearPremiumOfferText())));
                    } else if (content.hasButton()) {
                        newActionBar.addActions(VideoActionBarItem.newBuilder().setContent(content.toBuilder()
                                .setButton(layoutButton(content.getButton(), false))));
                    }
                }

                newActionBar.clearSubscribeButton().clearSize();
            }

            return withActionBar(element, componentType, lockupModel, newActionBar
                    .setLayoutStyle(minimal ? LAYOUT_STYLE_MINIMAL : LAYOUT_STYLE_CLASSIC)
                    .setLayoutConfig(actionBar.getLayoutConfig().toBuilder().setIconButtons(minimal))
                    .build())
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoActionBarLayout failure", ex);
        }

        return bytes;
    }

    /**
     * Injection point.
     * <p>
     * The channel and the buttons of the phone layouts are one element, so the row of each one is
     * another element of the section that the watch page shows, as on tablets.
     *
     * @return The section with the two rows, or null if the section does not change.
     */
    public static byte[] getTwoRowsSection(MessageLite section) {
        if (LAYOUT != VideoActionBarLayout.CLASSIC_TWO_ROWS) {
            return null;
        }

        byte[] sectionBytes;
        try {
            sectionBytes = section.toByteArray();
        } catch (IllegalStateException ex) {
            // A section that 'Hide related videos' changed in place, which cannot be serialized.
            return null;
        }

        try {
            SecondaryContents contents = SecondaryContents.parseFrom(sectionBytes);
            if (!contents.hasSlimVideoMetadataSectionRenderer()) {
                return null;
            }

            SlimVideoMetadataSectionRenderer renderer = contents.getSlimVideoMetadataSectionRenderer();
            SlimVideoMetadataSectionRenderer.Builder newRenderer = renderer.toBuilder().clearTertiaryContents();
            boolean changed = false;
            for (TertiaryContents row : renderer.getTertiaryContentsList()) {
                byte[] bytes = row.getElementRenderer().getNewElement().toByteArray();
                if (!FeedVideoCardsPatch.getElementText(bytes).contains(VIDEO_ACTION_BAR_TEMPLATE)) {
                    newRenderer.addTertiaryContents(row);
                    continue;
                }

                Element element = Element.parseFrom(setVideoActionBarLayout(bytes));
                ComponentType componentType = element.getType().getComponentType();
                LockupModel lockupModel = componentType.getModel().getLockupModel();
                VideoActionBarViewModel actionBar = lockupModel.getViewModel().getVideoActionBarViewModel();
                // The response has no element of the channel bar, so its uri is taken from the template cache.
                String channelBarUri = CHANNEL_BAR_TEMPLATE.getUri();
                if (channelBarUri == null || actionBar.getActionsCount() == 0
                        || !actionBar.getActions(0).getContent().hasSubscribeButton()) {
                    newRenderer.addTertiaryContents(row);
                    continue;
                }

                // The filters only hide the channel and its buttons inside the action bar.
                if (!Settings.HIDE_CHANNEL_PROFILE_BUTTON.get() && !Settings.HIDE_ACTION_BAR.get()) {
                    CompactChannelBarViewModel channel = CompactChannelBarViewModel.parseFrom(
                            actionBar.getActions(0).getContent().getSubscribeButton());
                    CompactChannelBarViewModel.Builder newChannel = channel.toBuilder()
                            .setChannelBarStyle(TWO_ROWS_CHANNEL_BAR_STYLE)
                            .setChannelBarHeader(channel.getChannelBarHeader().toBuilder()
                                    .setChannelBarHeaderViewModel(channel.getChannelBarHeader()
                                            .getChannelBarHeaderViewModel().toBuilder().clearStyle()));
                    if (Settings.HIDE_CHANNEL_PROFILE_SUBSCRIBE_BUTTON.get()) {
                        newChannel.clearSubscribeButton();
                    }

                    SubscriptionConfig config = FeedCardLayoutPatch.subscriptionConfig(
                            componentType.getSubscriptionConfig(), CHANNEL_BAR_TEMPLATE);
                    newRenderer.addTertiaryContents(elementRow(row, element.toBuilder()
                            .setProperties(element.getProperties().toBuilder()
                                    .setIdentifierProperties(element.getProperties().getIdentifierProperties().toBuilder()
                                            .setIdentifier(channelBarUri)))
                            .setType(element.getType().toBuilder()
                                    .setComponentType(ComponentType.newBuilder()
                                            .setTemplateConfig(FeedCardLayoutPatch.templateConfig(channelBarUri))
                                            .setModel(ElementModel.newBuilder()
                                                    .setCompactChannelBarModel(CompactChannelBarModel.newBuilder()
                                                            .setContext(lockupModel.getContext())
                                                            .setSubscribeState(SubscribeState.newBuilder().setKey(channel
                                                                    .getSubscribeButton().getSubscribeButtonViewModel()
                                                                    .getStateEntityStoreKey()))
                                                            .setViewModel(CompactChannelBarViewModelHolder.newBuilder()
                                                                    .setCompactChannelBarViewModel(newChannel))
                                                            .setUseThemeFromContext(true)))
                                            .setSubscriptionConfig(config.toBuilder()
                                                    .setEnvironmentSubscriptionConfig(EnvironmentSubscriptionConfig.newBuilder()
                                                            .setModelField(CHANNEL_BAR_TEMPLATE.modelField)
                                                            .setResultField(CHANNEL_BAR_ENVIRONMENT_FIELD)))))
                            .build()));
                }

                VideoActionBarViewModel.Builder buttons = actionBar.toBuilder().clearActions();
                for (int i = 1, count = actionBar.getActionsCount(); i < count; i++) {
                    VideoActionBarItem action = actionBar.getActions(i);
                    VideoActionBarItemContent content = action.getContent();
                    // The share button has its text, as there is enough room without the channel.
                    buttons.addActions(SHARE_BUTTON_ID.equals(content.getButton().getTargetId())
                            ? action.toBuilder().setContent(content.toBuilder().setButton(content.getButton().toBuilder()
                                    .setTitle(content.getButton().getAccessibilityText()))).build()
                            : action);
                }

                // The buttons start where the avatar of the row above starts.
                buttons.setLayoutConfig(actionBar.getLayoutConfig().toBuilder().clearEnableMarginStartAdjustment());
                newRenderer.addTertiaryContents(elementRow(row,
                        withActionBar(element, componentType, lockupModel, buttons.build())));
                changed = true;
            }

            return changed
                    ? contents.toBuilder().setSlimVideoMetadataSectionRenderer(newRenderer).build().toByteArray()
                    : null;
        } catch (Exception ex) {
            Logger.printException(() -> "getTwoRowsSection failure", ex);
        }

        return null;
    }

    private static Element withActionBar(Element element, ComponentType componentType,
                                         LockupModel lockupModel, VideoActionBarViewModel actionBar) {
        return element.toBuilder()
                .setType(element.getType().toBuilder()
                        .setComponentType(componentType.toBuilder()
                                .setModel(componentType.getModel().toBuilder()
                                        .setLockupModel(lockupModel.toBuilder()
                                                .setViewModel(lockupModel.getViewModel().toBuilder()
                                                        .setVideoActionBarViewModel(actionBar))))))
                .build();
    }

    private static TertiaryContents elementRow(TertiaryContents row, Element element)
            throws InvalidProtocolBufferException {
        return row.toBuilder()
                .setElementRenderer(row.getElementRenderer().toBuilder()
                        .setNewElement(NewElement.parseFrom(element.toByteArray())))
                .build();
    }

    /**
     * The buttons of the minimal layout have the text outside the button, below its icon.
     * The buttons of the classic layout have the text inside the button.
     */
    private static ButtonViewModel layoutButton(ButtonViewModel button, boolean minimal) {
        ButtonViewModel.Builder builder = button.toBuilder();
        if (minimal) {
            builder.clearTitle()
                    .setType(MINIMAL_BUTTON_TYPE)
                    .setButtonSize(MINIMAL_BUTTON_SIZE)
                    .setIconStyle(MINIMAL_BUTTON_ICON_STYLE);
        } else {
            // The share button of the classic layout has only its icon.
            if (SHARE_BUTTON_ID.equals(button.getTargetId())) {
                builder.clearTitle();
            }
            builder.clearIconStyle()
                    .setType(CLASSIC_BUTTON_TYPE)
                    .setButtonSize(CLASSIC_BUTTON_SIZE);
        }
        return builder.build();
    }

    private static ToggleButtonViewModelHolder layoutToggleButton(ToggleButtonViewModelHolder holder, boolean minimal) {
        ToggleButtonViewModel toggleButton = holder.getToggleButtonViewModel();
        return holder.toBuilder()
                .setToggleButtonViewModel(toggleButton.toBuilder()
                        .setDefaultButtonViewModel(toggleButton.getDefaultButtonViewModel().toBuilder()
                                .setButtonViewModel(layoutButton(toggleButton.getDefaultButtonViewModel().getButtonViewModel(), minimal)))
                        .setToggledButtonViewModel(toggleButton.getToggledButtonViewModel().toBuilder()
                                .setButtonViewModel(layoutButton(toggleButton.getToggledButtonViewModel().getButtonViewModel(), minimal))))
                .build();
    }

    /**
     * Injection point.
     * Turns off a feature flag that interferes with overriding config.
     */
    public static boolean useMediaSessionFeatureFlag(boolean original) {
        if (FIX_VIDEO_ACTION_BAR) {
            return false;
        }
        return original;
    }


    /**
     * Injection point.
     */
    public static Map<String, String> fixVideoActionBar(String url, Map<String, String> requestHeaders) {
        if (FIX_VIDEO_ACTION_BAR && url != null) {
            fetchRequestIfNeeded(url, requestHeaders);

            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (path != null && path.contains("next") && requestHeaders != null && isWatchNextRequest()) {
                if (requestHeaders.get(COLD_CONFIG_DATA_HEADER) != null) {
                    String coldConfigData = Settings.INNERTUBE_COLD_CONFIG_DATA.get();
                    if (Utils.isNotEmpty(coldConfigData)) {
                        requestHeaders.put(COLD_CONFIG_DATA_HEADER, coldConfigData);
                    }
                }
                if (requestHeaders.get(COLD_HASH_DATA_HEADER) != null) {
                    String coldHashData = Settings.INNERTUBE_COLD_HASH_DATA.get();
                    if (Utils.isNotEmpty(coldHashData)) {
                        requestHeaders.put(COLD_HASH_DATA_HEADER, coldHashData);
                    }
                }
            }
        }

        return requestHeaders;
    }

    /**
     * Whether a 'next' request is for the watch page, and not for comments.
     */
    private static boolean isWatchNextRequest() {
        final long requestTime = watchNextRequestTime;
        watchNextRequestTime = 0;
        return requestTime != 0
                && System.currentTimeMillis() - requestTime < WATCH_NEXT_REQUEST_TIMEOUT_MILLISECONDS;
    }

    /**
     * Injection point.
     * Called when the body of an InnerTube request is built.
     */
    public static void onBuildRequestBody(MessageLite body, RequestInterface request) {
        try {
            if (FIX_VIDEO_ACTION_BAR && body != null && request != null
                    && "next".equals(request.patch_getEndpoint())
                    && !hasTopLevelField(body.toByteArray(), NEXT_REQUEST_CONTINUATION_FIELD)) {
                watchNextRequestTime = System.currentTimeMillis();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onBuildRequestBody failure", ex);
        }
    }

    /**
     * @return If the serialized protocol buffer message has a top level field with the given number.
     *         If the message cannot be read, true is returned.
     */
    private static boolean hasTopLevelField(byte[] message, int fieldNumber) {
        int position = 0;
        final int end = message.length;
        while (position < end) {
            long tag = 0;
            int shift = 0;
            int value;
            do {
                // A varint is at most 10 bytes; a larger shift would wrap (shift & 63) and corrupt the tag.
                if (position >= end || shift >= 64) return true;
                value = message[position++] & 0xFF;
                tag |= (long) (value & 0x7F) << shift;
                shift += 7;
            } while ((value & 0x80) != 0);

            if ((tag >>> 3) == fieldNumber) return true;

            switch ((int) (tag & 0x7)) {
                case 0: // Varint.
                    do {
                        if (position >= end) return true;
                    } while ((message[position++] & 0x80) != 0);
                    break;
                case 1: // 64-bit.
                    // Truncated message: subtracting avoids int overflow and stops position running past the end.
                    if (end - position < 8) return true;
                    position += 8;
                    break;
                case 2: // Length delimited.
                    long length = 0;
                    shift = 0;
                    do {
                        // Same 10-byte varint limit as the tag loop above.
                        if (position >= end || shift >= 64) return true;
                        value = message[position++] & 0xFF;
                        length |= (long) (value & 0x7F) << shift;
                        shift += 7;
                    } while ((value & 0x80) != 0);
                    // A 10-byte varint can set bit 63, making length negative and moving position backwards.
                    if (length < 0 || length > end - position) return true;
                    position += (int) length;
                    break;
                case 5: // 32-bit.
                    // Truncated message: same overflow-safe check as the 64-bit case.
                    if (end - position < 4) return true;
                    position += 4;
                    break;
                default: // Groups (3, 4) and invalid wire types (6, 7): treat as unreadable.
                    return true;
            }
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static void fixVideoActionBar(ConfigInfoInterface configInfo) {
        if (FIX_VIDEO_ACTION_BAR && configInfo != null) {
            String coldConfigData = Settings.INNERTUBE_COLD_CONFIG_DATA.get();
            if (Utils.isNotEmpty(coldConfigData)) {
                configInfo.patch_setColdConfigData(coldConfigData);
            }
            String coldHashData = Settings.INNERTUBE_COLD_HASH_DATA.get();
            if (Utils.isNotEmpty(coldHashData)) {
                configInfo.patch_setColdHashData(coldHashData);
            }
        }
    }

    /**
     * Injection point.
     */
    public static String getVideoActionBarAppVersionOverride(String original) {
        return FIX_VIDEO_ACTION_BAR
                ? "20.13.41"
                : original;
    }

    /**
     * Injection point.
     */
    public static boolean fixRelatedVideoOverlay(boolean original) {
        if (FIX_VIDEO_ACTION_BAR
                && !Settings.INNERTUBE_COLD_CONFIG_DATA.isSetToDefault()
                && !Settings.INNERTUBE_COLD_HASH_DATA.isSetToDefault()) {
            return false;
        }

        return original;
    }
}
