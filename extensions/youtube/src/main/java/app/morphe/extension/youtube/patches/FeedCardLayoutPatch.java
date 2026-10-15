/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3768
 * https://github.com/MorpheApp/morphe-patches/pull/3769
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.util.Base64;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AccessibilityContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ActionButton;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AddToPlaylistButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AttachmentRun;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AttributedText;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AvatarViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.AvatarViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ButtonViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CapabilitiesSubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ChannelAvatar;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CommandContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ContentImage;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ContentMetadata;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ContentMetadataViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.DecoratedAvatarViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.Element;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementsExperiments;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.InnertubeCommand;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.InnertubeCommandHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeEndpoint;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeStatusEntity;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeStatusEntityKey;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeTarget;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupImage;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupMetadata;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupMetadataViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.MetadataPart;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.MetadataRow;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.RendererContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.SubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.TemplateConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.TextStyle;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThemeSubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBadge;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBadgeViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBottomOverlayViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailOverlay;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ToggleButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ToggleButtonViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.UriTemplateConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoThumbnail;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoWithContextData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoWithContextModel;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * YouTube shows the feed videos as cards (lockup.eml) to the accounts of an A/B test,
 * and with the classic layout (video_lockup_with_attachment.eml) to everyone else.
 * Converts the feed videos to the layout chosen in the settings.
 */
@SuppressWarnings("unused")
public class FeedCardLayoutPatch {

    public enum FeedCardLayout {
        CLASSIC,
        CARDS,
        CARDS_WITH_ACTION_BUTTONS
    }

    private static final boolean CARD_LAYOUT = Settings.FEED_CARD_LAYOUT.get() != FeedCardLayout.CLASSIC;
    private static final boolean ACTION_BUTTONS = Settings.FEED_CARD_LAYOUT.get() == FeedCardLayout.CARDS_WITH_ACTION_BUTTONS;

    /**
     * Template names, followed by "|hash" or "-fe|hash" (newer app versions).
     */
    private static final String CLASSIC_TEMPLATE = "video_lockup_with_attachment.eml";
    private static final String CARD_TEMPLATE = "lockup.eml";

    private static final int LOCKUP_CONTENT_TYPE_VIDEO = 1;
    private static final int LOCKUP_CONTENT_TYPE_PLAYLIST = 2;
    /**
     * Browse id of the playlist pages, followed by the playlist id.
     */
    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";

    /**
     * Fields of the models where the theme and the capabilities are written when the element is bound.
     */
    private static final int CLASSIC_MODEL_FIELD = 232954548;
    private static final int CLASSIC_THEME_FIELD = 6;
    private static final int CLASSIC_CAPABILITIES_FIELD = 29;
    private static final int CARD_MODEL_FIELD = 413471385;
    private static final int CARD_THEME_FIELD = 4;
    private static final int CARD_CAPABILITIES_FIELD = 6;

    /**
     * Title text style of the cards (regular weight).
     */
    private static final int CARD_TITLE_STYLE = 29;
    /**
     * Corner radius of the cards.
     */
    private static final int CARD_CORNER_RADIUS = Dim.dp(12);

    private static final int BUTTON_STYLE_MONO = 9;
    private static final int BUTTON_TYPE_TEXT = 3;
    private static final int BUTTON_SIZE_COMPACT = 2;
    private static final int THUMBNAIL_BADGE_STYLE_DEFAULT = 1;

    private static final int LIKE_STATUS_LIKE = 0;
    private static final int LIKE_STATUS_INDIFFERENT = 2;
    private static final int LIKE_STATUS_ENTITY_TYPE = 62;

    /**
     * Fields of the innertube commands.
     */
    private static final int INNERTUBE_COMMAND_FIELD = 169495254;
    private static final int WATCH_ENDPOINT_FIELD = 48687757;
    private static final int WATCH_ENDPOINT_VIDEO_ID_FIELD = 1;
    private static final int PLAYLIST_EDIT_ENDPOINT_FIELD = 60666189;
    private static final int PLAYLIST_EDIT_ENDPOINT_PLAYLIST_ID_FIELD = 1;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD = 138681778;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_ID_FIELD = 1;

    private static final String WATCH_LATER_PLAYLIST_ID = "WL";
    private static final String SENDER_VIEW_KEY = "com.google.android.libraries.youtube.rendering.elements.sender_view";
    /**
     * Comments panel of the watch page. Newer app versions use the first id.
     */
    private static final String[] COMMENTS_PANEL_IDS = {"comment-item-section", "engagement-panel-comments-section"};
    /**
     * Maximum time between the tap on the comment button and the loading of the watch page.
     */
    private static final long COMMENTS_OPEN_TIMEOUT_MILLIS = 30_000;

    /**
     * Invisible characters at the end of the accessibility labels of the cards and of their comment button.
     */
    private static final char CARD_LABEL_MARKER = '\u200B';
    private static final char COMMENT_BUTTON_LABEL_MARKER = '\u200C';

    /**
     * Thumbnail url, such as /vi/, /vi_webp/ or /vi_lc/ (localized thumbnails).
     */
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/vi(?:_\\w+)?/([\\w-]{11})/");
    /**
     * Spaces of newer app versions, or the dot of older versions.
     */
    private static final Pattern METADATA_SEPARATOR_PATTERN = Pattern.compile(" {2,}| · ");

    private static final ByteTrieSearch elementSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(CARD_LAYOUT ? CLASSIC_TEMPLATE : CARD_TEMPLATE)
    );

    /**
     * Minimum time between the lookups of a template that is not cached yet.
     */
    private static final long TEMPLATE_LOOKUP_INTERVAL_MILLIS = 10_000;

    @Nullable
    private static String classicTemplateUri;
    private static long classicTemplateLookupTime;
    @Nullable
    private static String cardTemplateUri;
    private static long cardTemplateLookupTime;
    @Nullable
    private static Icons icons;
    private static int feedId;
    @Nullable
    private static volatile String commentsVideoId;
    private static volatile long commentsRequestTime;

    /**
     * Icons of the action buttons. The bold icons are not available on older versions of the app.
     */
    private static final class Icons {
        final String like;
        final String liked;
        final String comment;
        final String save;
        final String saved;

        Icons(String like, String liked, String comment, String save, String saved) {
            this.like = like;
            this.liked = liked;
            this.comment = comment;
            this.save = save;
            this.saved = saved;
        }

        static Icons get() {
            if (ResourceUtils.getDrawableIdentifier("yt_delhi_thumbs_up_not_filled_24dp") != 0
                    && ResourceUtils.getDrawableIdentifier("yt_delhi_comment_24dp") != 0
                    && ResourceUtils.getDrawableIdentifier("yt_delhi_bookmark_not_filled_24dp") != 0) {
                return new Icons(
                        "yt_delhi_thumbs_up_not_filled_24dp",
                        "yt_delhi_thumbs_up_filled_24dp",
                        "yt_delhi_comment_24dp",
                        "yt_delhi_bookmark_not_filled_24dp",
                        "yt_delhi_bookmark_filled_24dp"
                );
            }

            return new Icons(
                    "yt_outline_thumb_up_black_24",
                    "yt_fill_thumb_up_black_24",
                    "yt_outline_message_bubble_left_black_24",
                    "yt_outline_bookmark_black_24",
                    "yt_fill_bookmark_black_24"
            );
        }
    }


    /**
     * Injection point.
     */
    public static byte[] setFeedCardLayout(byte[] bytes) {
        try {
            if (!elementSearch.matches(bytes)) {
                return bytes;
            }

            Element element = Element.parseFrom(bytes);
            ComponentType componentType = element.getType().getComponentType();
            String uri = componentType.getTemplateConfig().getUriTemplateConfig().getUri();
            ElementModel model = componentType.getModel();

            ComponentType converted = null;
            if (CARD_LAYOUT) {
                if (isTemplate(uri, CLASSIC_TEMPLATE) && model.hasVideoWithContextModel()) {
                    converted = convertToCard(componentType, model.getVideoWithContextModel());
                }
            } else if (isTemplate(uri, CARD_TEMPLATE) && model.hasLockupModel()) {
                converted = convertToClassic(componentType, model.getLockupModel());
            }

            if (converted == null) {
                return bytes;
            }

            return element.toBuilder()
                    .setType(element.getType().toBuilder().setComponentType(converted))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "setFeedCardLayout failure", ex);
        }

        return bytes;
    }

    /**
     * Injection point.
     * Called when a child view is added to a RecyclerView.
     */
    public static void onChildAdded(RecyclerView recyclerView, View child) {
        try {
            if (!CARD_LAYOUT || child == null) {
                return;
            }

            // The watch page list has other items too, and its cards are found when they are drawn.
            if (recyclerView.getId() != getFeedId()) {
                // The views of the feed can be recycled in other lists.
                setCardOutline(child, false);
                return;
            }

            setCardOutline(child, true);
        } catch (Exception ex) {
            Logger.printException(() -> "onChildAdded failure", ex);
        }
    }

    /**
     * Injection point.
     * Called with the original color of a Litho background drawable, before the theme changes it.
     */
    public static int getCardColor(Drawable drawable, int color) {
        try {
            if (CARD_LAYOUT && Color.alpha(color) == 255 && isInCard(drawable)) {
                // The theme patch changes only some exact colors, such as the background of the cards.
                // The lowest bit of blue is not visible.
                return color ^ 1;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getCardColor failure", ex);
        }
        return color;
    }

    private static boolean isInCard(Drawable drawable) {
        Drawable.Callback callback = drawable.getCallback();
        while (callback instanceof Drawable parent) {
            callback = parent.getCallback();
        }
        if (!(callback instanceof View view)) {
            return false;
        }

        while (true) {
            // The card can be drawn before it's added to the list.
            if (isCardLabel(view.getContentDescription())) {
                setCardOutline(view, true);
                return true;
            }
            ViewParent parent = view.getParent();
            if (parent instanceof RecyclerView recyclerView) {
                return recyclerView.getId() == getFeedId();
            }
            if (!(parent instanceof View parentView)) {
                return false;
            }
            view = parentView;
        }
    }

    private static void setCardOutline(View view, boolean card) {
        final boolean isCard = view.getOutlineProvider() instanceof CardOutlineProvider;
        if (card && !isCard) {
            view.setOutlineProvider(new CardOutlineProvider());
            view.setClipToOutline(true);
        } else if (!card && isCard) {
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(false);
        }
    }

    /**
     * The accessibility label of the converted videos ends with an invisible character,
     * to find the cards in lists that have other items.
     */
    private static boolean isCardLabel(@Nullable CharSequence label) {
        return endsWith(label, CARD_LABEL_MARKER);
    }

    private static boolean isCommentButtonLabel(@Nullable CharSequence label) {
        return endsWith(label, COMMENT_BUTTON_LABEL_MARKER);
    }

    private static boolean endsWith(@Nullable CharSequence text, char c) {
        return text != null && text.length() > 0 && text.charAt(text.length() - 1) == c;
    }

    /**
     * Injection point.
     * Called when a video is opened.
     */
    public static void onWatchCommand(@Nullable Object command, @Nullable Map<?, ?> map) {
        try {
            if (!ACTION_BUTTONS || map == null) {
                return;
            }

            commentsVideoId = null;
            // The app keeps only the known fields of the commands, so the button is found by its label.
            if (!(map.get(SENDER_VIEW_KEY) instanceof View senderView)
                    || !isCommentButtonLabel(senderView.getContentDescription())
                    || !(command instanceof MessageLite message)) {
                return;
            }

            List<ProtoNode> fields = ProtoNode.parse(message.toByteArray());
            ProtoNode videoIdField = fields == null ? null : ProtoNode.field(
                    ProtoNode.message(fields, WATCH_ENDPOINT_FIELD), WATCH_ENDPOINT_VIDEO_ID_FIELD);
            if (videoIdField == null || !videoIdField.isText()) {
                return;
            }

            String videoId = videoIdField.getText();
            Logger.printDebug(() -> "Opening the comments of: " + videoId);
            commentsVideoId = videoId;
            commentsRequestTime = System.currentTimeMillis();
        } catch (Exception ex) {
            Logger.printException(() -> "onWatchCommand failure", ex);
        }
    }

    /**
     * Injection point.
     * Called when the watch page of a video is loaded.
     *
     * @return The command that opens the comments panel, or null.
     */
    @Nullable
    public static byte[] getCommentsCommand(@Nullable Object watchNextResponse) {
        try {
            String videoId = commentsVideoId;
            if (videoId == null || !(watchNextResponse instanceof MessageLite message)) {
                return null;
            }
            if (System.currentTimeMillis() - commentsRequestTime > COMMENTS_OPEN_TIMEOUT_MILLIS) {
                commentsVideoId = null;
                return null;
            }

            byte[] response = message.toByteArray();
            if (FlyoutUtils.byteIndexOf(response, videoId.getBytes(StandardCharsets.UTF_8)) < 0) {
                return null; // Response of another video.
            }
            commentsVideoId = null;

            for (String panelId : COMMENTS_PANEL_IDS) {
                if (FlyoutUtils.byteIndexOf(response, panelId.getBytes(StandardCharsets.UTF_8)) >= 0) {
                    Logger.printDebug(() -> "Opening comments panel: " + panelId);
                    return showEngagementPanelCommand(panelId);
                }
            }
            Logger.printDebug(() -> "Comments panel not found");
        } catch (Exception ex) {
            Logger.printException(() -> "getCommentsCommand failure", ex);
        }
        return null;
    }

    private static byte[] showEngagementPanelCommand(String panelId) throws IOException {
        ByteArrayOutputStream endpoint = new ByteArrayOutputStream();
        CodedOutputStream endpointOutput = CodedOutputStream.newInstance(endpoint);
        endpointOutput.writeString(SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_ID_FIELD, panelId);
        endpointOutput.flush();

        ByteArrayOutputStream command = new ByteArrayOutputStream();
        CodedOutputStream commandOutput = CodedOutputStream.newInstance(command);
        commandOutput.writeByteArray(SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD, endpoint.toByteArray());
        commandOutput.flush();
        return command.toByteArray();
    }

    private static final class CardOutlineProvider extends ViewOutlineProvider {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                    CARD_CORNER_RADIUS);
        }
    }

    private static int getFeedId() {
        if (feedId == 0) {
            feedId = ResourceUtils.getIdentifier(ResourceType.ID, "results");
        }
        return feedId;
    }

    @Nullable
    private static ComponentType convertToCard(ComponentType componentType, VideoWithContextModel classic)
            throws IOException {
        VideoWithContextData data = classic.getData();
        VideoData videoData = data.getVideoData();
        VideoThumbnail thumbnail = videoData.getThumbnail();
        String templateUri = getCardTemplateUri();
        if (templateUri == null || !videoData.getLockupMetadata().hasLockupMetadataViewModel()) {
            return null;
        }

        // Playlists open their page, and show the number of videos instead of the duration.
        InnertubeCommandHolder onTap = InnertubeCommandHolder.parseFrom(data.getOnTap());
        final boolean isPlaylist = onTap.getInnertubeCommand().hasBrowseEndpoint();
        String contentId;
        String badgeText;
        String badgeLabel;
        if (isPlaylist) {
            String browseId = onTap.getInnertubeCommand().getBrowseEndpoint().getBrowseId();
            if (!browseId.startsWith(PLAYLIST_BROWSE_ID_PREFIX) || thumbnail.getVideoCountText().isEmpty()) {
                return null;
            }
            contentId = browseId.substring(PLAYLIST_BROWSE_ID_PREFIX.length());
            badgeText = thumbnail.getVideoCountText();
            badgeLabel = badgeText;
        } else {
            contentId = getVideoId(thumbnail.getImage());
            if (contentId == null) {
                return null;
            }
            badgeText = thumbnail.getTimestampText();
            badgeLabel = thumbnail.getTimestampAccessibilityText();
        }

        ThumbnailViewModel.Builder thumbnailViewModel = ThumbnailViewModel.newBuilder()
                .setImage(thumbnail.getImage());
        if (!badgeText.isEmpty()) {
            thumbnailViewModel.addOverlays(ThumbnailOverlay.newBuilder()
                    .setThumbnailBottomOverlayViewModel(ThumbnailBottomOverlayViewModel.newBuilder()
                            .addBadges(ThumbnailBadge.newBuilder()
                                    .setThumbnailBadgeViewModel(ThumbnailBadgeViewModel.newBuilder()
                                            .setText(badgeText)
                                            .setBadgeStyle(THUMBNAIL_BADGE_STYLE_DEFAULT)
                                            .setRendererContext(RendererContext.newBuilder()
                                                    .setAccessibilityContext(AccessibilityContext.newBuilder()
                                                            .setLabel(badgeLabel)))))));
        }

        LockupMetadataViewModel.Builder metadata = videoData.getLockupMetadata()
                .getLockupMetadataViewModel().toBuilder()
                .setTitleStyle(TextStyle.newBuilder().setStyle(CARD_TITLE_STYLE));
        metadata.setMetadata(splitMetadataRows(metadata.getMetadata()));

        // The images of the classic layout have the same format of the images of the cards.
        if (videoData.hasDecoratedAvatar()) {
            metadata.setImage(videoData.getDecoratedAvatar());
        } else if (videoData.hasAvatarStack()) {
            metadata.setImage(videoData.getAvatarStack());
        } else if (videoData.hasAvatar()) {
            ChannelAvatar avatar = videoData.getAvatar();
            metadata.setImage(LockupImage.newBuilder()
                    .setDecoratedAvatarViewModel(DecoratedAvatarViewModel.newBuilder()
                            .setAvatar(AvatarViewModelHolder.newBuilder()
                                    .setAvatarViewModel(AvatarViewModel.newBuilder()
                                            .setImage(avatar.getImage())
                                            .setAvatarImageSize(avatar.getAvatarImageSize())))
                            .setA11YLabel(avatar.getAccessibilityText())
                            .setRendererContext(RendererContext.newBuilder()
                                    .setCommandContext(CommandContext.newBuilder()
                                            .setOnTap(avatar.getEndpoint())))));
        }

        InnertubeCommandHolder menuOnTap = InnertubeCommandHolder.parseFrom(data.getMenuOnTap());
        metadata.setMenuButton(ButtonViewModelHolder.newBuilder()
                .setButtonViewModel(button("MORE_VERT", data.getMenuAccessibilityText(), menuOnTap)));

        if (ACTION_BUTTONS && !isPlaylist) {
            addActionButtons(metadata, contentId, data);
        }

        LockupViewModel lockupViewModel = LockupViewModel.newBuilder()
                .setContentImage(ContentImage.newBuilder().setThumbnailViewModel(thumbnailViewModel))
                .setMetadata(LockupMetadata.newBuilder().setLockupMetadataViewModel(metadata))
                .setContentId(contentId)
                .setContentType(isPlaylist ? LOCKUP_CONTENT_TYPE_PLAYLIST : LOCKUP_CONTENT_TYPE_VIDEO)
                .setRendererContext(RendererContext.newBuilder()
                        .setAccessibilityContext(AccessibilityContext.newBuilder()
                                .setLabel(data.getAccessibilityText() + CARD_LABEL_MARKER))
                        .setCommandContext(CommandContext.newBuilder().setOnTap(data.getOnTap())))
                .build();

        LockupModel card = LockupModel.newBuilder()
                .setViewModel(LockupViewModelHolder.newBuilder().setLockupViewModel(lockupViewModel))
                .setContext(setCardExperiments(classic.getContext(), true))
                .setEnvironment(classic.getEnvironment())
                .build();

        return componentType.toBuilder()
                .setTemplateConfig(templateConfig(templateUri))
                .setModel(ElementModel.newBuilder().setLockupModel(card))
                .setSubscriptionConfig(subscriptionConfig(componentType.getSubscriptionConfig(),
                        CARD_MODEL_FIELD, CARD_THEME_FIELD, CARD_CAPABILITIES_FIELD))
                .build();
    }

    @Nullable
    private static ComponentType convertToClassic(ComponentType componentType, LockupModel card) {
        LockupViewModel lockupViewModel = card.getViewModel().getLockupViewModel();
        if (lockupViewModel.getContentType() != LOCKUP_CONTENT_TYPE_VIDEO
                || !lockupViewModel.getContentImage().hasThumbnailViewModel()) {
            return null;
        }

        String templateUri = getClassicTemplateUri();
        if (templateUri == null) {
            return null;
        }

        ThumbnailViewModel thumbnailViewModel = lockupViewModel.getContentImage().getThumbnailViewModel();
        VideoThumbnail.Builder thumbnail = VideoThumbnail.newBuilder()
                .setImage(thumbnailViewModel.getImage());
        for (ThumbnailOverlay overlay : thumbnailViewModel.getOverlaysList()) {
            for (ThumbnailBadge badge : overlay.getThumbnailBottomOverlayViewModel().getBadgesList()) {
                ThumbnailBadgeViewModel badgeViewModel = badge.getThumbnailBadgeViewModel();
                if (!badgeViewModel.getText().isEmpty()) {
                    thumbnail.setTimestampText(badgeViewModel.getText())
                            .setTimestampAccessibilityText(badgeViewModel.getRendererContext()
                                    .getAccessibilityContext().getLabel());
                }
            }
        }

        LockupMetadataViewModel metadata = lockupViewModel.getMetadata().getLockupMetadataViewModel();
        VideoData.Builder videoData = VideoData.newBuilder()
                .setThumbnail(thumbnail)
                .setLockupMetadata(LockupMetadata.newBuilder()
                        .setLockupMetadataViewModel(metadata.toBuilder()
                                .setMetadata(joinMetadataRows(metadata.getMetadata()))
                                .clearImage()
                                .clearMenuButton()
                                .clearTitleStyle()
                                .clearActionButtons()));
        if (metadata.getImage().hasDecoratedAvatarViewModel()) {
            DecoratedAvatarViewModel avatar = metadata.getImage().getDecoratedAvatarViewModel();
            AvatarViewModel avatarViewModel = avatar.getAvatar().getAvatarViewModel();
            videoData.setAvatar(ChannelAvatar.newBuilder()
                    .setImage(avatarViewModel.getImage())
                    .setAccessibilityText(avatar.getA11YLabel())
                    .setAvatarImageSize(avatarViewModel.getAvatarImageSize())
                    .setEndpoint(avatar.getRendererContext().getCommandContext().getOnTap()));
        }

        ButtonViewModel menuButton = metadata.getMenuButton().getButtonViewModel();
        RendererContext rendererContext = lockupViewModel.getRendererContext();
        VideoWithContextModel classic = VideoWithContextModel.newBuilder()
                .setData(VideoWithContextData.newBuilder()
                        .setVideoData(videoData)
                        .setAccessibilityText(rendererContext.getAccessibilityContext().getLabel())
                        .setMenuAccessibilityText(menuButton.getAccessibilityText())
                        .setOnTap(rendererContext.getCommandContext().getOnTap())
                        .setMenuOnTap(menuButton.getOnTap().toByteString()))
                .setContext(setCardExperiments(card.getContext(), false))
                .setEnvironment(card.getEnvironment())
                .build();

        return componentType.toBuilder()
                .setTemplateConfig(templateConfig(templateUri))
                .setModel(ElementModel.newBuilder().setVideoWithContextModel(classic))
                .setSubscriptionConfig(subscriptionConfig(componentType.getSubscriptionConfig(),
                        CLASSIC_MODEL_FIELD, CLASSIC_THEME_FIELD, CLASSIC_CAPABILITIES_FIELD))
                .build();
    }

    /**
     * The classic layout shows the channel, the views and the date in one text separated by spaces,
     * and the cards show the channel above the views and the date.
     */
    private static ContentMetadata splitMetadataRows(ContentMetadata metadata) {
        ContentMetadataViewModel viewModel = metadata.getContentMetadataViewModel();
        MetadataPart part = null;
        for (MetadataRow row : viewModel.getMetadataRowsList()) {
            if (row.getMetadataPartsCount() > 0) {
                if (part != null || row.getMetadataPartsCount() > 1) {
                    return metadata; // Already split.
                }
                part = row.getMetadataParts(0);
            }
        }
        if (part == null) {
            return metadata;
        }

        AttributedText text = part.getText();
        String content = text.getContent();
        Matcher separator = METADATA_SEPARATOR_PATTERN.matcher(content);
        if (!separator.find()) {
            return metadata;
        }

        // The views icon is an attachment on the spaces before the views.
        final int channelEnd = separator.start();
        int secondRowStart = separator.end();
        for (AttachmentRun run : text.getAttachmentRunsList()) {
            final int start = run.getStartIndex();
            if (start >= channelEnd && start < secondRowStart) {
                secondRowStart = start;
            }
        }

        AttributedText.Builder secondRowText = AttributedText.newBuilder()
                .setContent(content.substring(secondRowStart));
        for (AttachmentRun run : text.getAttachmentRunsList()) {
            if (run.getStartIndex() >= secondRowStart) {
                secondRowText.addAttachmentRuns(run.toBuilder()
                        .setStartIndex(run.getStartIndex() - secondRowStart));
            }
        }

        // The accessibility label is 'Channel, views, date'.
        String label = part.getAccessibilityLabel();
        final int labelSeparator = label.indexOf(", ");

        return ContentMetadata.newBuilder()
                .setContentMetadataViewModel(viewModel.toBuilder()
                        .clearMetadataRows()
                        .addMetadataRows(MetadataRow.newBuilder()
                                .addMetadataParts(MetadataPart.newBuilder()
                                        .setText(AttributedText.newBuilder()
                                                .setContent(content.substring(0, channelEnd)))))
                        .addMetadataRows(MetadataRow.newBuilder()
                                .addMetadataParts(MetadataPart.newBuilder()
                                        .setText(secondRowText)
                                        .setAccessibilityLabel(labelSeparator < 0
                                                ? label
                                                : label.substring(labelSeparator + 2))))
                        .setDelimiter(" "))
                .build();
    }

    /**
     * Joins the metadata rows of a card into one text, like the classic layout.
     */
    private static ContentMetadata joinMetadataRows(ContentMetadata metadata) {
        ContentMetadataViewModel viewModel = metadata.getContentMetadataViewModel();
        if (viewModel.getMetadataRowsCount() < 2) {
            return metadata;
        }

        StringBuilder content = new StringBuilder();
        StringBuilder label = new StringBuilder();
        AttributedText.Builder text = AttributedText.newBuilder();
        for (MetadataRow row : viewModel.getMetadataRowsList()) {
            for (MetadataPart part : row.getMetadataPartsList()) {
                String partContent = part.getText().getContent();
                if (partContent.isEmpty()) {
                    continue;
                }
                if (content.length() > 0) {
                    content.append("   ");
                }
                final int offset = content.length();
                content.append(partContent);
                for (AttachmentRun run : part.getText().getAttachmentRunsList()) {
                    text.addAttachmentRuns(run.toBuilder().setStartIndex(run.getStartIndex() + offset));
                }

                String partLabel = part.getAccessibilityLabel().isEmpty()
                        ? partContent.trim()
                        : part.getAccessibilityLabel();
                if (label.length() > 0) {
                    label.append(", ");
                }
                label.append(partLabel);
            }
        }

        return ContentMetadata.newBuilder()
                .setContentMetadataViewModel(viewModel.toBuilder()
                        .clearMetadataRows()
                        .addMetadataRows(MetadataRow.newBuilder()
                                .addMetadataParts(MetadataPart.newBuilder()
                                        .setText(text.setContent(content.toString()))
                                        .setAccessibilityLabel(label.toString())))
                        .clearDelimiter())
                .build();
    }

    /**
     * The comment button opens the video with the comments, which are opened when the watch page is loaded,
     * and the save button saves the video to 'Watch later'.
     */
    private static void addActionButtons(LockupMetadataViewModel.Builder metadata, String videoId,
                                         VideoWithContextData data) throws IOException {
        Icons icons = getIcons();
        InnertubeCommandHolder saveOnTap = findSaveToWatchLaterCommand(data.getMenuOnTap());
        if (saveOnTap == null) {
            // Without the item, such as in some menus, the button opens the menu.
            saveOnTap = InnertubeCommandHolder.parseFrom(data.getMenuOnTap());
        }

        metadata.addActionButtons(ActionButton.newBuilder()
                        .setLikeButtonViewModel(likeButton(videoId, icons)))
                .addActionButtons(ActionButton.newBuilder()
                        .setButtonViewModel(button(icons.comment,
                                str("morphe_feed_card_layout_comments") + COMMENT_BUTTON_LABEL_MARKER,
                                InnertubeCommandHolder.parseFrom(data.getOnTap()))))
                .addActionButtons(ActionButton.newBuilder()
                        .setAddToPlaylistButtonViewModel(AddToPlaylistButtonViewModel.newBuilder()
                                .setToggleButtonViewModel(toggleButton(
                                        button(icons.save, "", saveOnTap),
                                        button(icons.saved, "", saveOnTap)))));
    }

    private static LikeButtonViewModel likeButton(String videoId, Icons icons) {
        String entityKey = Base64.encodeToString(LikeStatusEntityKey.newBuilder()
                        .setVideoId(videoId)
                        .setEntityType(LIKE_STATUS_ENTITY_TYPE)
                        .setVersion(1)
                        .build()
                        .toByteArray(),
                Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "%3D");

        return LikeButtonViewModel.newBuilder()
                .setToggleButtonViewModel(toggleButton(
                        button(icons.like, "", likeCommand(videoId, LIKE_STATUS_LIKE)),
                        button(icons.liked, "", likeCommand(videoId, LIKE_STATUS_INDIFFERENT))))
                .setLikeStatusEntityKey(entityKey)
                .setLikeStatusEntity(LikeStatusEntity.newBuilder()
                        .setKey(entityKey)
                        .setLikeStatus(LIKE_STATUS_INDIFFERENT))
                .setLikeCountEnabled(1)
                .build();
    }

    private static InnertubeCommandHolder likeCommand(String videoId, int status) {
        return InnertubeCommandHolder.newBuilder()
                .setInnertubeCommand(InnertubeCommand.newBuilder()
                        .setLikeEndpoint(LikeEndpoint.newBuilder()
                                .setStatus(status)
                                .setTarget(LikeTarget.newBuilder().setVideoId(videoId))))
                .build();
    }

    private static ToggleButtonViewModelHolder toggleButton(ButtonViewModel defaultButton,
                                                            ButtonViewModel toggledButton) {
        return ToggleButtonViewModelHolder.newBuilder()
                .setToggleButtonViewModel(ToggleButtonViewModel.newBuilder()
                        .setDefaultButtonViewModel(ButtonViewModelHolder.newBuilder()
                                .setButtonViewModel(defaultButton))
                        .setToggledButtonViewModel(ButtonViewModelHolder.newBuilder()
                                .setButtonViewModel(toggledButton)))
                .build();
    }

    /**
     * Icon button without a background.
     */
    private static ButtonViewModel button(String iconName, String accessibilityText, InnertubeCommandHolder onTap) {
        return ButtonViewModel.newBuilder()
                .setIconName(iconName)
                .setAccessibilityText(accessibilityText)
                .setOnTap(onTap)
                .setStyle(BUTTON_STYLE_MONO)
                .setType(BUTTON_TYPE_TEXT)
                .setButtonSize(BUTTON_SIZE_COMPACT)
                .build();
    }

    private static TemplateConfig templateConfig(String uri) {
        return TemplateConfig.newBuilder()
                .setUriTemplateConfig(UriTemplateConfig.newBuilder().setUri(uri))
                .build();
    }

    /**
     * Keeps only the theme and capabilities subscriptions, written to the fields of the new model.
     */
    private static SubscriptionConfig subscriptionConfig(SubscriptionConfig config, int modelField,
                                                         int themeField, int capabilitiesField) {
        SubscriptionConfig.Builder builder = SubscriptionConfig.newBuilder();
        if (config.hasThemeSubscriptionConfig()) {
            ThemeSubscriptionConfig theme = config.getThemeSubscriptionConfig();
            builder.setThemeSubscriptionConfig(theme.toBuilder()
                    .setModelField(modelField)
                    .setMapping(theme.getMapping().toBuilder().setResultField(themeField)));
        }
        if (config.hasCapabilitiesSubscriptionConfig()) {
            CapabilitiesSubscriptionConfig capabilities = config.getCapabilitiesSubscriptionConfig();
            builder.setCapabilitiesSubscriptionConfig(capabilities.toBuilder()
                    .setModelField(modelField)
                    .setMapping(capabilities.getMapping().toBuilder().setResultField(capabilitiesField)));
        }
        return builder.build();
    }

    private static ElementContext setCardExperiments(ElementContext context, boolean enabled) {
        ElementsExperiments experiments = context.getElementsExperiments();
        return context.toBuilder()
                .setElementsExperiments(experiments.toBuilder()
                        .setCardLayoutExperiments(experiments.getCardLayoutExperiments().toBuilder()
                                .setEnableColorSampledBackground(enabled)
                                .setEnableMetadataBackground(enabled)))
                .build();
    }

    private static boolean isTemplate(String uri, String template) {
        return uri.startsWith(template) && (uri.startsWith("|", template.length())
                || uri.startsWith("-fe|", template.length()));
    }

    @Nullable
    private static String getVideoId(ByteString thumbnailImage) {
        Matcher matcher = VIDEO_ID_PATTERN.matcher(thumbnailImage.toString(StandardCharsets.ISO_8859_1));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Icons getIcons() {
        if (icons == null) {
            icons = Icons.get();
        }
        return icons;
    }

    @Nullable
    private static String getCardTemplateUri() {
        final long now = System.currentTimeMillis();
        if (cardTemplateUri == null && now - cardTemplateLookupTime > TEMPLATE_LOOKUP_INTERVAL_MILLIS) {
            cardTemplateLookupTime = now;
            cardTemplateUri = findCachedTemplateUri(CARD_TEMPLATE);
        }
        return cardTemplateUri;
    }

    @Nullable
    private static String getClassicTemplateUri() {
        final long now = System.currentTimeMillis();
        if (classicTemplateUri == null && now - classicTemplateLookupTime > TEMPLATE_LOOKUP_INTERVAL_MILLIS) {
            classicTemplateLookupTime = now;
            classicTemplateUri = findCachedTemplateUri(CLASSIC_TEMPLATE);
        }
        return classicTemplateUri;
    }

    /**
     * The uri of a template includes the hash of its version, so it's taken from the template cache
     * of the app, which has the templates the app received.
     *
     * @return The last cached version of the template, or null if the template is not cached.
     */
    @Nullable
    private static String findCachedTemplateUri(String template) {
        try {
            File metadata = new File(Utils.getContext().getFilesDir(), "ElementsResourceCacheMetadata");
            if (!metadata.isFile()) {
                return null;
            }

            String uri = null;
            CodedInputStream input = CodedInputStream.newInstance(Files.readAllBytes(metadata.toPath()));
            while (!input.isAtEnd()) {
                final int tag = input.readTag();
                if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    input.skipField(tag);
                    continue;
                }

                CodedInputStream entry = CodedInputStream.newInstance(input.readByteArray());
                while (!entry.isAtEnd()) {
                    final int entryTag = entry.readTag();
                    if (WireFormat.getTagFieldNumber(entryTag) == 1
                            && WireFormat.getTagWireType(entryTag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        String key = entry.readString();
                        if (isTemplate(key, template)) {
                            uri = key;
                        }
                    } else {
                        entry.skipField(entryTag);
                    }
                }
            }

            String found = uri;
            Logger.printDebug(() -> "Cached template: " + found);
            return uri;
        } catch (Exception ex) {
            Logger.printException(() -> "findCachedTemplateUri failure", ex);
            return null;
        }
    }

    /**
     * @return The command of the 'Save to Watch later' item of the menu, or null if the menu has no such item.
     */
    @Nullable
    private static InnertubeCommandHolder findSaveToWatchLaterCommand(ByteString menu) throws IOException {
        List<ProtoNode> message = ProtoNode.parse(menu.toByteArray());
        if (message == null) {
            return null;
        }

        for (ProtoNode endpoint : ProtoNode.findMessages(message, PLAYLIST_EDIT_ENDPOINT_FIELD)) {
            ProtoNode playlistId = ProtoNode.field(endpoint.children, PLAYLIST_EDIT_ENDPOINT_PLAYLIST_ID_FIELD);
            ProtoNode command = endpoint.getParent();
            if (playlistId != null && WATCH_LATER_PLAYLIST_ID.equals(playlistId.getText())
                    && command != null && command.children != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(INNERTUBE_COMMAND_FIELD, ProtoNode.write(command.children));
                output.flush();
                return InnertubeCommandHolder.parseFrom(bytes.toByteArray());
            }
        }
        return null;
    }
}
