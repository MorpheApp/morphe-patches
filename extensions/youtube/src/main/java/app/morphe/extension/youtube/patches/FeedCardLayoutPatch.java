/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3768
 * https://github.com/MorpheApp/morphe-patches/pull/3769
 * https://github.com/MorpheApp/morphe-patches/pull/3801
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.util.Base64;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.view.TextureView;
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
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ClientResource;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CommandContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ContentImage;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ContentMetadata;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ContentMetadataViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.DecoratedAvatarViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.Element;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementType;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ElementsExperiments;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.HorizontalShelfData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.HorizontalShelfModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.InnertubeCommand;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.InnertubeCommandHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeEndpoint;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeStatusEntity;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeStatusEntityKey;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LikeTarget;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupInlinePlayback;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupImage;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupMetadata;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupMetadataViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupPadding;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.LockupViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.MetadataPart;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.MetadataRow;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.RendererContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShelfHeaderData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShelfHeaderModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.GridVideoModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShortsShelfModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShortsVideoCellData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShortsVideoCellModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.SubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.TemplateConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.TextStyle;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThemeSubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBadge;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBadgeIcon;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBadgeIconSource;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBadgeViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailBottomOverlayViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailElementOverlay;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailOverlay;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailOverlayProgressBar;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailOverlayProgressBarViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ThumbnailViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ToggleButtonViewModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ToggleButtonViewModelHolder;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.UriTemplateConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoThumbnail;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoWithContextData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.VideoWithContextModel;
import app.morphe.extension.youtube.patches.theme.SeekbarColorPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

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

    /**
     * Interfaces to use obfuscated methods of the lists.
     */
    public interface ElementItemInterface {
        // Method is added during patching.
        byte[] patch_getElementBytes();
    }

    private static final boolean CARD_LAYOUT = Settings.FEED_CARD_LAYOUT.get() != FeedCardLayout.CLASSIC;
    private static final boolean ACTION_BUTTONS = Settings.FEED_CARD_LAYOUT.get() == FeedCardLayout.CARDS_WITH_ACTION_BUTTONS;

    /**
     * Template names, followed by "|hash" or by a suffix and "|hash" (newer app versions), such as "-fe|hash".
     */
    private static final String CLASSIC_TEMPLATE = "video_lockup_with_attachment.eml";
    private static final String CARD_TEMPLATE = "lockup.eml";
    private static final String THUMBNAIL_BOTTOM_OVERLAY_TEMPLATE = "thumbnail_bottom_overlay.eml";
    private static final String SHELF_HEADER_TEMPLATE = "shelf_header.eml";
    private static final String HORIZONTAL_SHELF_TEMPLATE = "horizontal_shelf.eml";
    private static final String SHORTS_VIDEO_CELL_TEMPLATE = "shorts_video_cell.eml";
    private static final String SHORTS_SHELF_TEMPLATE = "shorts_shelf.eml";
    private static final String GRID_VIDEO_TEMPLATE = "grid_video_wrapper.eml";

    private static final int LOCKUP_CONTENT_TYPE_VIDEO = 1;
    private static final int LOCKUP_CONTENT_TYPE_PLAYLIST = 2;
    /**
     * Browse id of the playlist pages, followed by the playlist id.
     */
    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";

    /**
     * Templates of the feed videos, with the field of their model and the known fields of the model
     * where the theme and the capabilities are written when the element is bound.
     */
    private static final VideoTemplate CLASSIC = new VideoTemplate(CLASSIC_TEMPLATE, 232954548, 6, 29);
    private static final VideoTemplate CARD = new VideoTemplate(CARD_TEMPLATE, 413471385, 4, 6);
    /**
     * Bottom overlay of the thumbnails, with the same model of the cards.
     */
    private static final VideoTemplate THUMBNAIL_BOTTOM_OVERLAY = new VideoTemplate(
            THUMBNAIL_BOTTOM_OVERLAY_TEMPLATE, 413471385, 4, 6);
    /**
     * Suffix of the template names of newer app versions, before "|hash", such as "-fe".
     */
    private static final Pattern TEMPLATE_SUFFIX_PATTERN = Pattern.compile("-\\w+");

    /**
     * Title text style of the cards (regular weight).
     */
    private static final int CARD_TITLE_STYLE = 29;
    /**
     * Corner radius of the cards.
     */
    private static final int CARD_CORNER_RADIUS = Dim.dp(12);
    /**
     * Padding of the cards, in dp, which is the spacing between the cards in every list.
     * The spacing that the lists add between the cards is removed.
     */
    private static final int CARD_HORIZONTAL_PADDING_DP = 12;
    private static final int CARD_VERTICAL_PADDING_DP = 6;
    /**
     * The videos of the vertical shelves are laid out almost to the edges of the list,
     * with the menu button near the right edge, so their card is wider than the other cards.
     */
    private static final int VIDEO_SHELF_HORIZONTAL_PADDING_DP = 4;
    /**
     * Spacing between the edges of the horizontal shelf cards and the content of the first and the last item
     * of their list. The items of the shelves have different paddings, and some have none.
     */
    private static final int SHELF_LIST_PADDING_DP = 8;

    private static final CardOutlineProvider CARD_OUTLINE = new CardOutlineProvider(
            Dim.dp(CARD_HORIZONTAL_PADDING_DP), Dim.dp(CARD_VERTICAL_PADDING_DP));
    /**
     * Outline of the items of the feed that are not converted, such as the cards that YouTube sends.
     */
    private static final CardOutlineProvider FEED_ITEM_OUTLINE = new CardOutlineProvider(0, 0);
    /**
     * Clips of the Shorts of the Shorts shelves, by their rounded corners
     * (top left, top right, bottom left and bottom right bits).
     */
    private static final ShortsCellOutlineProvider[] SHORTS_CELL_OUTLINES = new ShortsCellOutlineProvider[16];
    static {
        for (int corners = 0; corners < SHORTS_CELL_OUTLINES.length; corners++) {
            SHORTS_CELL_OUTLINES[corners] = new ShortsCellOutlineProvider(
                    (corners & 8) != 0, (corners & 4) != 0, (corners & 2) != 0, (corners & 1) != 0);
        }
    }
    private static final ShortsCellOutlineProvider SHORTS_CELL_OUTLINE = SHORTS_CELL_OUTLINES[0];

    private static final int BUTTON_STYLE_MONO = 9;
    private static final int BUTTON_TYPE_TEXT = 3;
    private static final int BUTTON_SIZE_COMPACT = 2;
    private static final int THUMBNAIL_BADGE_STYLE_DEFAULT = 1;
    private static final int THUMBNAIL_BADGE_STYLE_LIVE = 2;
    private static final int THUMBNAIL_PROGRESS_BAR_STYLE_EDGE = 3;

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
     * Id of the comments panel of other app versions, looked for if the response has none of the known ids.
     */
    private static final Pattern COMMENTS_PANEL_ID_PATTERN = Pattern.compile("[a-z][a-z-]*comment[a-z-]*-section");
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
     * Invisible character at the end of the accessibility label of the Shorts shelf header.
     */
    private static final char SHELF_HEADER_LABEL_MARKER = '\u200D';
    /**
     * Invisible character at the end of the accessibility label of the Shorts of the Shorts shelf.
     */
    private static final char SHORTS_CELL_LABEL_MARKER = '\uFEFF';
    /**
     * Invisible character at the end of the label of the other shelf headers, such as in the channel home.
     */
    private static final char VIDEO_SHELF_HEADER_LABEL_MARKER = '\u2060';
    /**
     * Invisible character at the end of the label of the videos of the vertical shelves below a shelf header.
     */
    private static final char VIDEO_SHELF_ROW_LABEL_MARKER = '\u2061';
    /**
     * Icon of the Shorts shelf header, which the modern header of the card layout doesn't show.
     */
    private static final String SHORTS_SHELF_ICON = "ic_youtube_shorts";

    /**
     * Thumbnail url, such as /vi/, /vi_webp/ or /vi_lc/ (localized thumbnails).
     */
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/vi(?:_\\w+)?/([\\w-]{11})/");
    /**
     * Spaces of newer app versions, or the dot of older versions.
     */
    private static final Pattern METADATA_SEPARATOR_PATTERN = Pattern.compile(" {2,}| · ");

    private static final ByteTrieSearch elementSearch = new ByteTrieSearch(
            CARD_LAYOUT
                    ? ByteTrieSearch.convertStringsToBytes(CLASSIC_TEMPLATE, SHELF_HEADER_TEMPLATE,
                    HORIZONTAL_SHELF_TEMPLATE, SHORTS_VIDEO_CELL_TEMPLATE, SHORTS_SHELF_TEMPLATE,
                    GRID_VIDEO_TEMPLATE)
                    : ByteTrieSearch.convertStringsToBytes(CARD_TEMPLATE)
    );
    /**
     * Label of the shelf headers, in the bytes of the converted elements.
     */
    private static final ByteTrieSearch shelfHeaderLabelSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(String.valueOf(SHELF_HEADER_LABEL_MARKER))
    );
    /**
     * Thumbnails of the Shorts, the same that the Shorts filter looks for.
     */
    private static final ByteTrieSearch shortsThumbnailSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(
                    "/frame0.jpg",
                    "/oardefault.jpg",
                    "/oar1.jpg",
                    "/oar2.jpg",
                    "/oar3.jpg")
    );

    /**
     * Minimum time between the lookups of a template that is not cached yet.
     */
    private static final long TEMPLATE_LOOKUP_INTERVAL_MILLIS = 10_000;
    /**
     * Minimum time between the checks of the elements of a template, after the template is taken from an element.
     */
    private static final long TEMPLATE_CHECK_INTERVAL_MILLIS = 60_000;

    @Nullable
    private static Icons icons;
    /**
     * Whether the elements are converted cards, by the bytes of the converted element, which the list items keep.
     * The arrays are compared by identity.
     */
    private static final Map<byte[], Boolean> cardElements = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * List item without the spacing of the list.
     */
    private static final Object ITEM_WITHOUT_DIVIDER = new Object();
    /**
     * Clips of the Shorts of the Shorts shelves, by the screen bounds of the Shorts and of their thumbnails.
     */
    private static final Map<String, ViewOutlineProvider> shortsCellOutlines = new HashMap<>();
    @Nullable
    private static WeakReference<View> inlinePlayerContainer;
    private static long inlinePlayerSearchTime;
    private static final long INLINE_PLAYER_SEARCH_INTERVAL_MILLIS = 500;
    /**
     * Container of the inline player of the videos (feed autoplay), outside the list.
     * Its outline rounds the top corners of the player over the thumbnail of a card.
     */
    @Nullable
    private static WeakReference<View> cardInlinePlayerContainer;
    private static long cardInlinePlayerSearchTime;
    /**
     * Clip of the container before the outline of the cards was set.
     */
    private static boolean cardInlinePlayerClipToOutline;
    private static final ViewOutlineProvider CARD_INLINE_PLAYER_OUTLINE = new ViewOutlineProvider() {
        /**
         * The bottom of the rounded rectangle is moved out of the view, which keeps the bottom corners square.
         */
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + CARD_CORNER_RADIUS, CARD_CORNER_RADIUS);
        }
    };
    /**
     * Lists that update the clips of the Shorts before every frame.
     */
    private static final Map<RecyclerView, Boolean> listsWithShortsOutlineListener =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Videos of vertical shelves that were below a shelf header, to keep drawing the card
     * after the header is scrolled out of the list.
     */
    private static final Map<View, Boolean> videoShelfRows = new WeakHashMap<>();
    /**
     * List items that are horizontal shelves or Shorts carousels with the card layout, found by the converted
     * element of the item when the list sets its spacing. Their header, which has the label of the shelf,
     * is not mounted while it is scrolled out of the list. The views are recycled, and the list sets
     * the spacing of a view again whenever the view shows another element.
     */
    private static final Map<View, Boolean> shelfCardItems = new WeakHashMap<>();
    /**
     * Lists of horizontal shelves whose padding was changed while they were scrolled to the start.
     */
    private static final Map<View, Boolean> shelfListsAtStart = new WeakHashMap<>();
    /**
     * The list item whose spacing is set, until its element is known.
     */
    private static WeakReference<View> dividerItemView = new WeakReference<>(null);
    /**
     * Whether the elements are shelves with the card layout, by the bytes of the converted element.
     * The arrays are compared by identity.
     */
    private static final Map<byte[], Boolean> shelfCardElements = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Backgrounds of the items of the feed that were not mounted yet when their color was looked up.
     */
    private static final Map<Drawable, Boolean> unmountedItemBackgrounds = new WeakHashMap<>();
    /**
     * Edges of the last Shorts shelf card, for the header of a shelf whose Shorts are not in the list yet.
     */
    private static int shortsCardLeft;
    private static int shortsCardRight;
    @Nullable
    private static Paint shelfPaint;
    private static boolean shelfPaintDarkMode;
    /**
     * Updates the outline of the list items when they are laid out, after the label of a recycled view is set.
     */
    private static final View.OnLayoutChangeListener CARD_LAYOUT_LISTENER =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    updateCardOutline(view);
                } catch (Exception ex) {
                    Logger.printException(() -> "onLayoutChange failure", ex);
                }
            };
    private static int feedId;
    @Nullable
    private static volatile String commentsVideoId;
    private static volatile long commentsRequestTime;

    /**
     * Template of the feed videos that the elements are converted to.
     * The uri includes the hash of the template version, and the subscriptions of the element write
     * the theme and the capabilities to fields of the model. Both are taken from the elements of the template
     * that the server sends, so they follow the new versions of the template. Until such an element is seen,
     * the uri is taken from the template cache of the app, and the fields are the known ones.
     */
    private static final class VideoTemplate {
        final String name;
        final int modelField;
        final ByteTrieSearch search;
        volatile int themeField;
        volatile int capabilitiesField;
        @Nullable
        private volatile String uri;
        /**
         * Whether the uri and the fields were taken from an element.
         */
        private volatile boolean fromElement;
        private long cacheLookupTime;
        private long checkTime;

        VideoTemplate(String name, int modelField, int themeField, int capabilitiesField) {
            this.name = name;
            this.modelField = modelField;
            this.themeField = themeField;
            this.capabilitiesField = capabilitiesField;
            this.search = new ByteTrieSearch(ByteTrieSearch.convertStringsToBytes(name));
        }

        @Nullable
        String getUri() {
            if (uri == null) {
                final long now = System.currentTimeMillis();
                if (now - cacheLookupTime > TEMPLATE_LOOKUP_INTERVAL_MILLIS) {
                    cacheLookupTime = now;
                    // The uri of a template includes the hash of its version, so it's taken from the template
                    // cache of the app, which has the templates the app received. The last cached version
                    // of the template is used, or none if the template is not cached.
                    String cached = null;
                    try {
                        File metadata = new File(Utils.getContext().getFilesDir(), "ElementsResourceCacheMetadata");
                        if (metadata.isFile()) {
                            CodedInputStream input = CodedInputStream.newInstance(
                                    Files.readAllBytes(metadata.toPath()));
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
                                            && WireFormat.getTagWireType(entryTag)
                                            == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                                        String key = entry.readString();
                                        if (isTemplate(key, name)) {
                                            cached = key;
                                        }
                                    } else {
                                        entry.skipField(entryTag);
                                    }
                                }
                            }

                            String found = cached;
                            Logger.printDebug(() -> "Cached template: " + found);
                        }
                    } catch (Exception ex) {
                        cached = null;
                        Logger.printException(() -> "getUri failure", ex);
                    }
                    // An element of the template can be seen while the cache is read.
                    if (uri == null) {
                        uri = cached;
                    }
                }
            }
            return uri;
        }
    }

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
    }

    /**
     * Injection point.
     */
    public static byte[] setFeedCardLayout(byte[] bytes) {
        try {
            if (!elementSearch.matches(bytes)) {
                // The elements of the layout of the setting give the current version of its template:
                // the uri and the fields are taken from the element, if it's an element of the template.
                // After the first element, the elements are checked once in a while, to follow a new version.
                VideoTemplate template = CARD_LAYOUT ? CARD : CLASSIC;
                final long now = System.currentTimeMillis();
                if ((!template.fromElement || now - template.checkTime >= TEMPLATE_CHECK_INTERVAL_MILLIS)
                        && template.search.matches(bytes)) {
                    ComponentType componentType = Element.parseFrom(bytes).getType().getComponentType();
                    String elementUri = componentType.getTemplateConfig().getUriTemplateConfig().getUri();
                    if (isTemplate(elementUri, template.name)) {
                        template.checkTime = now;

                        SubscriptionConfig config = componentType.getSubscriptionConfig();
                        if (config.hasThemeSubscriptionConfig()) {
                            ThemeSubscriptionConfig theme = config.getThemeSubscriptionConfig();
                            if (theme.getModelField() == template.modelField
                                    && theme.getMapping().getResultField() > 0) {
                                template.themeField = theme.getMapping().getResultField();
                            }
                        }
                        if (config.hasCapabilitiesSubscriptionConfig()) {
                            CapabilitiesSubscriptionConfig capabilities = config.getCapabilitiesSubscriptionConfig();
                            if (capabilities.getModelField() == template.modelField
                                    && capabilities.getMapping().getResultField() > 0) {
                                template.capabilitiesField = capabilities.getMapping().getResultField();
                            }
                        }

                        if (!elementUri.equals(template.uri)) {
                            Logger.printDebug(() -> "Template of an element: " + elementUri + " theme field: "
                                    + template.themeField + " capabilities field: " + template.capabilitiesField);
                        }
                        template.uri = elementUri;
                        template.fromElement = true;
                    }
                }
                return bytes;
            }

            Element element = Element.parseFrom(bytes);
            ComponentType componentType = element.getType().getComponentType();
            String uri = componentType.getTemplateConfig().getUriTemplateConfig().getUri();
            ElementModel model = componentType.getModel();

            ComponentType converted = null;
            if (CARD_LAYOUT) {
                if (isTemplate(uri, CLASSIC_TEMPLATE) && model.hasVideoWithContextModel()) {
                    VideoWithContextModel classic = model.getVideoWithContextModel();
                    toCard: {
                        VideoWithContextData data = classic.getData();
                        VideoData videoData = data.getVideoData();
                        VideoThumbnail thumbnail = videoData.getThumbnail();

                        // The compact items are rows of lists, such as the videos of a playlist or the 'Downloads' row
                        // of the library. The cards have no attachment, such as the description and the 'Buy or rent'
                        // button of the movies. The horizontal items, such as the search results on tablets, keep their
                        // layout.
                        if (data.getCompact() || data.getHorizontal() || !data.getAttachment().isEmpty()) {
                            break toCard;
                        }

                        // The filters find the hidden Shorts and live streams by the classic layout.
                        if (thumbnail.getBadgeStyle() == THUMBNAIL_BADGE_STYLE_LIVE
                                && Settings.HIDE_LIVE_STREAMS.get()) {
                            break toCard;
                        }
                        if ((Settings.HIDE_SHORTS_HOME.get() || Settings.HIDE_SHORTS_SUBSCRIPTIONS.get()
                                || Settings.HIDE_SHORTS_SEARCH.get() || Settings.HIDE_SHORTS_HISTORY.get())
                                && shortsThumbnailSearch.matches(thumbnail.getImage().toByteArray())) {
                            // The elements are parsed when the feed that shows them is loaded, so the Shorts keep the
                            // classic layout only in the feed whose setting hides them, the same feeds of the Shorts
                            // filter, and are cards in the others.
                            final boolean shortsHidden;
                            if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
                                // The Shorts below the player are treated as the Shorts of the Home feed.
                                shortsHidden = Settings.HIDE_SHORTS_HOME.get();
                            } else if (NavigationBar.isSearchBarActive()) {
                                // Must check second, as search can be from any tab.
                                shortsHidden = Settings.HIDE_SHORTS_SEARCH.get();
                            } else {
                                NavigationBar.NavigationButton selectedNavButton =
                                        NavigationBar.NavigationButton.getSelectedNavigationButton();
                                if (selectedNavButton == null) {
                                    // Unknown tab, treat the same as home.
                                    shortsHidden = Settings.HIDE_SHORTS_HOME.get();
                                } else {
                                    shortsHidden = switch (selectedNavButton) {
                                        case HOME -> Settings.HIDE_SHORTS_HOME.get();
                                        case SEARCH -> Settings.HIDE_SHORTS_SEARCH.get();
                                        case SUBSCRIPTIONS -> Settings.HIDE_SHORTS_SUBSCRIPTIONS.get();
                                        case LIBRARY -> Settings.HIDE_SHORTS_HISTORY.get();
                                        default -> false;
                                    };
                                }
                            }
                            if (shortsHidden) {
                                break toCard;
                            }
                        }

                        String templateUri = CARD.getUri();
                        if (templateUri == null ||!videoData.getLockupMetadata().hasLockupMetadataViewModel()) {
                            break toCard;
                        }

                        // Playlists open their page, and show the number of videos instead of the duration.
                        InnertubeCommandHolder onTap = InnertubeCommandHolder.parseFrom(data.getOnTap());
                        final boolean isPlaylist = onTap.getInnertubeCommand().hasBrowseEndpoint();
                        String contentId;
                        String badgeText;
                        String badgeLabel;
                        if (isPlaylist) {
                            String browseId = onTap.getInnertubeCommand().getBrowseEndpoint().getBrowseId();
                            if (!browseId.startsWith(PLAYLIST_BROWSE_ID_PREFIX)
                                    || thumbnail.getVideoCountText().isEmpty()) {
                                break toCard;
                            }
                            contentId = browseId.substring(PLAYLIST_BROWSE_ID_PREFIX.length());
                            badgeText = thumbnail.getVideoCountText();
                            badgeLabel = badgeText;
                        } else {
                            Matcher videoId = VIDEO_ID_PATTERN.matcher(
                                    thumbnail.getImage().toString(StandardCharsets.ISO_8859_1));
                            contentId = videoId.find() ? videoId.group(1) : null;
                            if (contentId == null) {
                                break toCard;
                            }
                            badgeText = thumbnail.getTimestampText();
                            badgeLabel = thumbnail.getTimestampAccessibilityText();
                        }

                        ThumbnailViewModel.Builder thumbnailViewModel = ThumbnailViewModel.newBuilder()
                                .setImage(thumbnail.getImage());
                        if (!badgeText.isEmpty()) {
                            ThumbnailBadgeViewModel.Builder badge = ThumbnailBadgeViewModel.newBuilder()
                                    .setText(badgeText)
                                    .setBadgeStyle(THUMBNAIL_BADGE_STYLE_DEFAULT)
                                    .setRendererContext(RendererContext.newBuilder()
                                            .setAccessibilityContext(AccessibilityContext.newBuilder()
                                                    .setLabel(badgeLabel)));
                            // The badge of the videos that are live now is red, with the live icon of the classic
                            // layout.
                            if (!isPlaylist && thumbnail.getBadgeStyle() == THUMBNAIL_BADGE_STYLE_LIVE) {
                                badge.setBadgeStyle(THUMBNAIL_BADGE_STYLE_LIVE);
                                if (!thumbnail.getBadgeIcon().isEmpty()) {
                                    badge.setIcon(ThumbnailBadgeIcon.newBuilder()
                                            .addSources(ThumbnailBadgeIconSource.newBuilder()
                                                    .setClientResource(ClientResource.newBuilder()
                                                            .setImageName(thumbnail.getBadgeIcon()))));
                                }
                            }

                            thumbnailViewModel.addOverlays(ThumbnailOverlay.newBuilder()
                                    .setThumbnailBottomOverlayViewModel(ThumbnailBottomOverlayViewModel.newBuilder()
                                            .addBadges(ThumbnailBadge.newBuilder().setThumbnailBadgeViewModel(badge))));
                        }

                        // The card thumbnail shows only the badges of the bottom overlays,
                        // so the watch progress is drawn by the bottom overlay template in an element over the
                        // thumbnail.
                        final float progress = thumbnail.getProgress();
                        if (SeekbarColorPatch.showWatchHistoryProgressDrawable(progress > 0)) {
                            String bottomOverlayUri = THUMBNAIL_BOTTOM_OVERLAY.getUri();
                            if (bottomOverlayUri != null) {
                                ThumbnailBottomOverlayViewModel progressBar =
                                        ThumbnailBottomOverlayViewModel.newBuilder()
                                        .setProgressBar(ThumbnailOverlayProgressBar.newBuilder()
                                                .setThumbnailOverlayProgressBarViewModel(
                                                        ThumbnailOverlayProgressBarViewModel.newBuilder()
                                                        .setProgress(progress)
                                                        .setStyle(THUMBNAIL_PROGRESS_BAR_STYLE_EDGE)))
                                        .build();
                                ComponentType progressComponent = ComponentType.newBuilder()
                                        .setTemplateConfig(templateConfig(bottomOverlayUri))
                                        .setModel(ElementModel.newBuilder().setLockupModel(LockupModel.newBuilder()
                                                .setViewModel(LockupViewModelHolder.newBuilder()
                                                        .setThumbnailBottomOverlayViewModel(progressBar))
                                                .setContext(classic.getContext())))
                                        .setSubscriptionConfig(subscriptionConfig(
                                                componentType.getSubscriptionConfig(), THUMBNAIL_BOTTOM_OVERLAY))
                                        .build();
                                thumbnailViewModel.addOverlays(ThumbnailOverlay.newBuilder()
                                        .setThumbnailElementOverlay(ThumbnailElementOverlay.newBuilder()
                                                .setElement(Element.newBuilder()
                                                        .setType(ElementType.newBuilder()
                                                                .setComponentType(progressComponent)))));
                            }
                        }

                        LockupMetadataViewModel.Builder metadata = videoData.getLockupMetadata()
                                .getLockupMetadataViewModel().toBuilder()
                                .setTitleStyle(TextStyle.newBuilder().setStyle(CARD_TITLE_STYLE));
                        // The classic layout shows the channel, the views and the date in one text separated by spaces,
                        // and the cards show the channel above the views and the date.
                        splitRows: {
                            ContentMetadataViewModel viewModel = metadata.getMetadata().getContentMetadataViewModel();
                            MetadataPart part = null;
                            for (MetadataRow row : viewModel.getMetadataRowsList()) {
                                if (row.getMetadataPartsCount() > 0) {
                                    if (part != null || row.getMetadataPartsCount() > 1) {
                                        break splitRows; // Already split.
                                    }
                                    part = row.getMetadataParts(0);
                                }
                            }
                            if (part == null) {
                                break splitRows;
                            }

                            AttributedText text = part.getText();
                            String content = text.getContent();
                            Matcher separator = METADATA_SEPARATOR_PATTERN.matcher(content);
                            if (!separator.find()) {
                                break splitRows;
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

                            metadata.setMetadata(ContentMetadata.newBuilder()
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
                                    .build());
                        }

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

                        // The comment button opens the video with the comments, which are opened when the watch page
                        // is loaded, and the save button saves the video to 'Watch later'.
                        if (ACTION_BUTTONS && !isPlaylist) {
                            if (icons == null) {
                                // The bold icons are not available on older versions of the app.
                                if (ResourceUtils.getDrawableIdentifier("yt_delhi_thumbs_up_not_filled_24dp") != 0
                                        && ResourceUtils.getDrawableIdentifier("yt_delhi_comment_24dp") != 0
                                        && ResourceUtils.getDrawableIdentifier("yt_delhi_bookmark_not_filled_24dp")
                                        != 0) {
                                    icons = new Icons(
                                            "yt_delhi_thumbs_up_not_filled_24dp",
                                            "yt_delhi_thumbs_up_filled_24dp",
                                            "yt_delhi_comment_24dp",
                                            "yt_delhi_bookmark_not_filled_24dp",
                                            "yt_delhi_bookmark_filled_24dp"
                                    );
                                } else {
                                    icons = new Icons(
                                            "yt_outline_thumb_up_black_24",
                                            "yt_fill_thumb_up_black_24",
                                            "yt_outline_message_bubble_left_black_24",
                                            "yt_outline_bookmark_black_24",
                                            "yt_fill_bookmark_black_24"
                                    );
                                }
                            }

                            // The command of the 'Save to Watch later' item of the menu.
                            InnertubeCommandHolder saveOnTap = null;
                            List<ProtoNode> message = ProtoNode.parse(data.getMenuOnTap().toByteArray());
                            if (message != null) {
                                for (ProtoNode endpoint
                                        : ProtoNode.findMessages(message, PLAYLIST_EDIT_ENDPOINT_FIELD)) {
                                    ProtoNode playlistId = ProtoNode.field(
                                            endpoint.children, PLAYLIST_EDIT_ENDPOINT_PLAYLIST_ID_FIELD);
                                    ProtoNode command = endpoint.getParent();
                                    if (playlistId != null && WATCH_LATER_PLAYLIST_ID.equals(playlistId.getText())
                                            && command != null && command.children != null) {
                                        ByteArrayOutputStream commandBytes = new ByteArrayOutputStream();
                                        CodedOutputStream output = CodedOutputStream.newInstance(commandBytes);
                                        output.writeByteArray(INNERTUBE_COMMAND_FIELD,
                                                ProtoNode.write(command.children));
                                        output.flush();
                                        saveOnTap = InnertubeCommandHolder.parseFrom(commandBytes.toByteArray());
                                        break;
                                    }
                                }
                            }
                            if (saveOnTap == null) {
                                // Without the item, such as in some menus, the button opens the menu.
                                saveOnTap = InnertubeCommandHolder.parseFrom(data.getMenuOnTap());
                            }

                            String entityKey = Base64.encodeToString(LikeStatusEntityKey.newBuilder()
                                            .setVideoId(contentId)
                                            .setEntityType(LIKE_STATUS_ENTITY_TYPE)
                                            .setVersion(1)
                                            .build()
                                            .toByteArray(),
                                    Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "%3D");

                            metadata.addActionButtons(ActionButton.newBuilder()
                                            .setLikeButtonViewModel(LikeButtonViewModel.newBuilder()
                                                    .setToggleButtonViewModel(toggleButton(
                                                            button(icons.like, "",
                                                                    likeCommand(contentId, LIKE_STATUS_LIKE)),
                                                            button(icons.liked, "",
                                                                    likeCommand(contentId, LIKE_STATUS_INDIFFERENT))))
                                                    .setLikeStatusEntityKey(entityKey)
                                                    .setLikeStatusEntity(LikeStatusEntity.newBuilder()
                                                            .setKey(entityKey)
                                                            .setLikeStatus(LIKE_STATUS_INDIFFERENT))
                                                    .setLikeCountEnabled(1)))
                                    .addActionButtons(ActionButton.newBuilder()
                                            .setButtonViewModel(button(icons.comment,
                                                    str("morphe_feed_card_layout_comments")
                                                            + COMMENT_BUTTON_LABEL_MARKER,
                                                    InnertubeCommandHolder.parseFrom(data.getOnTap()))))
                                    .addActionButtons(ActionButton.newBuilder()
                                            .setAddToPlaylistButtonViewModel(AddToPlaylistButtonViewModel.newBuilder()
                                                    .setToggleButtonViewModel(toggleButton(
                                                            button(icons.save, "", saveOnTap),
                                                            button(icons.saved, "", saveOnTap)))));
                        }

                        LockupViewModel.Builder lockupViewModel = LockupViewModel.newBuilder()
                                .setContentImage(ContentImage.newBuilder().setThumbnailViewModel(thumbnailViewModel))
                                .setMetadata(LockupMetadata.newBuilder().setLockupMetadataViewModel(metadata))
                                .setContentId(contentId)
                                .setContentType(isPlaylist ? LOCKUP_CONTENT_TYPE_PLAYLIST : LOCKUP_CONTENT_TYPE_VIDEO)
                                .setRendererContext(RendererContext.newBuilder()
                                        .setAccessibilityContext(AccessibilityContext.newBuilder()
                                                .setLabel(data.getAccessibilityText() + CARD_LABEL_MARKER))
                                        .setCommandContext(CommandContext.newBuilder().setOnTap(data.getOnTap())));
                        // The cards play the video inline (feed autoplay) with the same commands of the classic layout.
                        if (!data.getInlinePlayback().isEmpty()) {
                            lockupViewModel.setInlinePlayback(LockupInlinePlayback.newBuilder()
                                    .setInlinePlayback(data.getInlinePlayback()));
                        }

                        LockupModel card = LockupModel.newBuilder()
                                .setViewModel(LockupViewModelHolder.newBuilder().setLockupViewModel(lockupViewModel))
                                .setContext(setCardExperiments(classic.getContext(), true))
                                .setEnvironment(classic.getEnvironment())
                                .setPadding(LockupPadding.newBuilder()
                                        .setStart(CARD_HORIZONTAL_PADDING_DP)
                                        .setEnd(CARD_HORIZONTAL_PADDING_DP)
                                        .setTop(CARD_VERTICAL_PADDING_DP)
                                        .setBottom(CARD_VERTICAL_PADDING_DP))
                                .build();

                        converted = componentType.toBuilder()
                                .setTemplateConfig(templateConfig(templateUri))
                                .setModel(ElementModel.newBuilder().setLockupModel(card))
                                .setSubscriptionConfig(subscriptionConfig(componentType.getSubscriptionConfig(), CARD))
                                .build();
                    }
                } else if (isTemplate(uri, SHELF_HEADER_TEMPLATE) && model.hasShelfHeaderModel()) {
                    // The shelves of the card layout, such as the Shorts shelf, have the modern header,
                    // with a bigger title and without the icon.
                    // The template shows it if the server enables it and the header asks for it.
                    ShelfHeaderModel header = model.getShelfHeaderModel();
                    ShelfHeaderData data = header.getData();
                    if (!data.getIcon().toString(StandardCharsets.ISO_8859_1).contains(SHORTS_SHELF_ICON)) {
                        // The other shelf headers keep their own style, and are only found in the lists by their label,
                        // to draw a card behind the videos below them.
                        converted = componentType.toBuilder()
                                .setModel(ElementModel.newBuilder().setShelfHeaderModel(header.toBuilder()
                                        .setData(data.toBuilder()
                                                .setTitle(data.getTitle() + VIDEO_SHELF_HEADER_LABEL_MARKER)
                                                .setAccessibilityText(data.getAccessibilityText()
                                                        + VIDEO_SHELF_HEADER_LABEL_MARKER))))
                                .build();
                    } else {
                        converted = componentType.toBuilder()
                                .setModel(ElementModel.newBuilder().setShelfHeaderModel(header.toBuilder()
                                        .setData(modernShelfHeader(data))))
                                .build();
                    }
                } else if (isTemplate(uri, HORIZONTAL_SHELF_TEMPLATE) && model.hasHorizontalShelfModel()) {
                    // The horizontal shelves of videos have the modern header, like the Shorts shelf,
                    // and the shelf is a card with the header and the videos.
                    HorizontalShelfModel shelf = model.getHorizontalShelfModel();
                    HorizontalShelfData data = shelf.getData();
                    if (data.hasHeader()) {
                        converted = componentType.toBuilder()
                                .setModel(ElementModel.newBuilder().setHorizontalShelfModel(shelf.toBuilder()
                                        .setData(data.toBuilder()
                                                .setHeader(modernShelfHeader(data.getHeader())))))
                                .build();
                    }
                } else if (isTemplate(uri, SHORTS_VIDEO_CELL_TEMPLATE) && model.hasShortsVideoCellModel()) {
                    // The label of the Shorts ends with an invisible character, to find the Shorts shelf in the lists.
                    ShortsVideoCellModel cell = model.getShortsVideoCellModel();
                    ShortsVideoCellData data = cell.getData();
                    converted = componentType.toBuilder()
                            .setModel(ElementModel.newBuilder().setShortsVideoCellModel(cell.toBuilder()
                                    .setData(data.toBuilder()
                                            .setAccessibilityText(data.getAccessibilityText()
                                                    + SHORTS_CELL_LABEL_MARKER))))
                            .build();
                } else if (isTemplate(uri, SHORTS_SHELF_TEMPLATE) && model.hasShortsShelfModel()) {
                    // The shelves of Shorts in a carousel, such as in the search results, have the modern header
                    // like the other shelves, and the shelf is a card with the header and the Shorts.
                    ShortsShelfModel shelf = model.getShortsShelfModel();
                    if (shelf.hasHeader()) {
                        converted = componentType.toBuilder()
                                .setModel(ElementModel.newBuilder().setShortsShelfModel(shelf.toBuilder()
                                        .setHeader(modernShelfHeader(shelf.getHeader()))))
                                .build();
                    }
                } else if (isTemplate(uri, GRID_VIDEO_TEMPLATE) && model.hasGridVideoModel()) {
                    // The label of the videos of the vertical shelves ends with an invisible character,
                    // to draw the shelf below the header as a card.
                    GridVideoModel video = model.getGridVideoModel();
                    converted = componentType.toBuilder()
                            .setModel(ElementModel.newBuilder().setGridVideoModel(video.toBuilder()
                                    .setAccessibilityText(video.getAccessibilityText() + VIDEO_SHELF_ROW_LABEL_MARKER)))
                            .build();
                }
            } else if (isTemplate(uri, CARD_TEMPLATE) && model.hasLockupModel()) {
                LockupModel card = model.getLockupModel();
                toClassic: {
                    LockupViewModel lockupViewModel = card.getViewModel().getLockupViewModel();
                    if (lockupViewModel.getContentType() != LOCKUP_CONTENT_TYPE_VIDEO
                            || !lockupViewModel.getContentImage().hasThumbnailViewModel()) {
                        break toClassic;
                    }

                    String templateUri = CLASSIC.getUri();
                    if (templateUri == null) {
                        break toClassic;
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
                    // Joins the metadata rows of a card into one text, like the classic layout.
                    ContentMetadata rows = metadata.getMetadata();
                    ContentMetadataViewModel viewModel = rows.getContentMetadataViewModel();
                    if (viewModel.getMetadataRowsCount() >= 2) {

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

                        rows = ContentMetadata.newBuilder()
                                .setContentMetadataViewModel(viewModel.toBuilder()
                                        .clearMetadataRows()
                                        .addMetadataRows(MetadataRow.newBuilder()
                                                .addMetadataParts(MetadataPart.newBuilder()
                                                        .setText(text.setContent(content.toString()))
                                                        .setAccessibilityLabel(label.toString())))
                                        .clearDelimiter())
                                .build();
                    }
                    VideoData.Builder videoData = VideoData.newBuilder()
                            .setThumbnail(thumbnail)
                            .setLockupMetadata(LockupMetadata.newBuilder()
                                    .setLockupMetadataViewModel(metadata.toBuilder()
                                            .setMetadata(rows)
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
                                    .setMenuOnTap(menuButton.getOnTap().toByteString())
                                    .setInlinePlayback(lockupViewModel.getInlinePlayback().getInlinePlayback()))
                            .setContext(setCardExperiments(card.getContext(), false))
                            .setEnvironment(card.getEnvironment())
                            .build();

                    converted = componentType.toBuilder()
                            .setTemplateConfig(templateConfig(templateUri))
                            .setModel(ElementModel.newBuilder().setVideoWithContextModel(classic))
                            .setSubscriptionConfig(subscriptionConfig(componentType.getSubscriptionConfig(), CLASSIC))
                            .build();
                }
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

            // The list draws the background of the Shorts shelf below its items.
            if (recyclerView.willNotDraw()) {
                recyclerView.setWillNotDraw(false);
            }

            // The cards are found by their label. The views are recycled while scrolling, also between lists,
            // and the label of the new item can be set after the view is added.
            updateCardOutline(child);
            child.removeOnLayoutChangeListener(CARD_LAYOUT_LISTENER);
            child.addOnLayoutChangeListener(CARD_LAYOUT_LISTENER);
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
            if (CARD_LAYOUT && Color.alpha(color) == 255) {
                boolean inCard = false;
                View view = getDrawableView(drawable);
                while (view != null) {
                    // The card can be drawn before it's added to the list.
                    CardOutlineProvider outline = getCardOutline(view.getContentDescription());
                    if (outline != null) {
                        setCardOutline(view, outline);
                        inCard = true;
                        break;
                    }
                    ViewParent parent = view.getParent();
                    if (parent instanceof RecyclerView recyclerView) {
                        if (recyclerView.getId() == getFeedId()) {
                            // The background is mounted before the content of the item,
                            // so a bar of chips is found only after the item is mounted.
                            if (view instanceof ViewGroup group && group.getChildCount() == 0) {
                                unmountedItemBackgrounds.put(drawable, Boolean.TRUE);
                            }
                            inCard = !isChipBar(view);
                        }
                        break;
                    }
                    view = parent instanceof View parentView ? parentView : null;
                }

                if (inCard) {
                    // The theme patch changes only some exact colors, such as the background of the cards.
                    // The lowest bit of blue is not visible.
                    return color ^ 1;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getCardColor failure", ex);
        }
        return color;
    }

    /**
     * Injection point.
     * Called when the spacing of a list item is looked up.
     *
     * @return The item, or an item without spacing if the item is a card, which has its own spacing.
     */
    public static Object getDividerItem(@Nullable Object item) {
        try {
            if (CARD_LAYOUT && item instanceof ElementItemInterface element) {
                byte[] bytes = element.patch_getElementBytes();
                if (bytes != null) {
                    Boolean card = cardElements.get(bytes);
                    if (card == null) {
                        card = CARD.search.matches(bytes) && isCardLabel(Element.parseFrom(bytes).getType()
                                .getComponentType().getModel().getLockupModel().getViewModel().getLockupViewModel()
                                .getRendererContext().getAccessibilityContext().getLabel());
                        cardElements.put(bytes, card);
                    }
                    if (card) {
                        return ITEM_WITHOUT_DIVIDER;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getDividerItem failure", ex);
        }
        return item;
    }

    /**
     * Injection point.
     * Called when the list sets the spacing of an item, with the view of the item.
     */
    public static void setDividerItemView(View view) {
        try {
            if (!CARD_LAYOUT) {
                return;
            }

            shelfCardItems.remove(view);
            dividerItemView = new WeakReference<>(view);
        } catch (Exception ex) {
            Logger.printException(() -> "setDividerItemView failure", ex);
        }
    }

    /**
     * Injection point.
     * Called with the item of the view of {@link #setDividerItemView(View)}, before the spacing is looked up.
     * The item is a shelf with the card layout if the header of its horizontal shelf or Shorts carousel
     * has the label of the shelf header.
     */
    public static void setDividerItem(@Nullable Object item) {
        try {
            if (!CARD_LAYOUT) {
                return;
            }

            View view = dividerItemView.get();
            dividerItemView.clear();
            if (view == null || !(item instanceof ElementItemInterface element)) {
                return;
            }
            byte[] bytes = element.patch_getElementBytes();
            if (bytes == null) {
                return;
            }

            Boolean shelf = shelfCardElements.get(bytes);
            if (shelf == null) {
                shelf = false;
                // Only the elements with the marker are parsed.
                if (shelfHeaderLabelSearch.matches(bytes)) {
                    ElementModel model = Element.parseFrom(bytes).getType().getComponentType().getModel();
                    if (model.hasHorizontalShelfModel()) {
                        shelf = endsWith(model.getHorizontalShelfModel().getData().getHeader()
                                .getAccessibilityText(), SHELF_HEADER_LABEL_MARKER);
                    } else if (model.hasShortsShelfModel()) {
                        shelf = endsWith(model.getShortsShelfModel().getHeader().getAccessibilityText(),
                                SHELF_HEADER_LABEL_MARKER);
                    }
                }
                shelfCardElements.put(bytes, shelf);
            }
            if (shelf) {
                shelfCardItems.put(view, Boolean.TRUE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setDividerItem failure", ex);
        }
    }

    @Nullable
    private static View getDrawableView(Drawable drawable) {
        Drawable.Callback callback = drawable.getCallback();
        while (callback instanceof Drawable parent) {
            callback = parent.getCallback();
        }
        return callback instanceof View view ? view : null;
    }

    private static void updateCardOutline(View child) {
        CardOutlineProvider outline = getCardOutline(child.getContentDescription());
        // The items of the feed are cards, also the ones that YouTube sends as cards and are not converted.
        if (outline == null && child.getParent() instanceof RecyclerView recyclerView
                && recyclerView.getId() == getFeedId() && !isChipBar(child)) {
            outline = FEED_ITEM_OUTLINE;
        }
        setCardOutline(child, outline);
    }

    /**
     * The bar of chips of the feed, such as the sort buttons of the channel tabs, is an item of the feed
     * with the chips in a nested list, and it's not a card. The horizontal shelves also have a nested list,
     * but they are cards with a shelf header.
     */
    private static boolean isChipBar(View item) {
        return hasNestedList(item, 0) && !isShelfHeader(item);
    }

    private static void setCardOutline(View view, @Nullable CardOutlineProvider outline) {
        ViewOutlineProvider current = view.getOutlineProvider();
        if (outline == current) {
            return;
        }
        if (outline != null) {
            view.setOutlineProvider(outline);
            view.setClipToOutline(true);
        } else if (current instanceof CardOutlineProvider) {
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(false);
        }
    }

    /**
     * The accessibility label of the converted videos ends with an invisible character,
     * to find the cards in lists that have other items.
     *
     * @return The outline of the card, or null if the label is not the label of a card.
     */
    @Nullable
    private static CardOutlineProvider getCardOutline(@Nullable CharSequence label) {
        return isCardLabel(label) ? CARD_OUTLINE : null;
    }

    private static boolean isCardLabel(@Nullable CharSequence label) {
        return endsWith(label, CARD_LABEL_MARKER);
    }

    /**
     * Injection point.
     * Called when a RecyclerView draws below its items.
     * The header of the Shorts shelf has the background of the cards, with the rounded top corners,
     * and the Shorts below it fill the rest of the card. The horizontal shelves are cards.
     * The background is drawn by the list, because the views of the elements reset their own background.
     */
    public static void onListDraw(RecyclerView list, Canvas canvas) {
        try {
            if (!CARD_LAYOUT || canvas == null) {
                return;
            }
            // The autoplay of a Short adds a player with its own rounded clip, while the list is not drawn again.
            // The clips of the Shorts are checked before every frame of the window.
            if (!listsWithShortsOutlineListener.containsKey(list)) {
                listsWithShortsOutlineListener.put(list, Boolean.TRUE);
                WeakReference<RecyclerView> listRef = new WeakReference<>(list);
                list.getViewTreeObserver().addOnPreDrawListener(() -> {
                    try {
                        RecyclerView current = listRef.get();
                        if (current != null) {
                            // The color of the backgrounds of the items that were not mounted yet is looked up
                            // again once the item is mounted, if the item is a bar of chips.
                            if (!unmountedItemBackgrounds.isEmpty()) {
                                for (Drawable background : new ArrayList<>(unmountedItemBackgrounds.keySet())) {
                                    View view = getDrawableView(background);
                                    while (view != null && !(view.getParent() instanceof RecyclerView)) {
                                        view = view.getParent() instanceof View parent ? parent : null;
                                    }
                                    if (!(view instanceof ViewGroup item)) {
                                        unmountedItemBackgrounds.remove(background);
                                        continue;
                                    }
                                    if (item.getChildCount() == 0) {
                                        continue;
                                    }
                                    unmountedItemBackgrounds.remove(background);
                                    if (isChipBar(item)) {
                                        // The color is looked up when the bounds change.
                                        Rect bounds = background.copyBounds();
                                        background.setBounds(0, 0, 0, 0);
                                        background.setBounds(bounds);
                                        background.invalidateSelf();
                                        updateCardOutline(item);
                                    }
                                }
                            }

                            // The label of a recycled card can be set after the card is laid out, so the card
                            // keeps the outline of the item it replaced, without the spacing of the cards.
                            for (int i = 0, count = current.getChildCount(); i < count; i++) {
                                View item = current.getChildAt(i);
                                if (item.getOutlineProvider() != CARD_OUTLINE
                                        && isCardLabel(item.getContentDescription())) {
                                    setCardOutline(item, CARD_OUTLINE);
                                }
                            }

                            // The card of a horizontal shelf clips its list, and the items of some shelves have
                            // no padding, so their content is cut by the edges of the card. The padding of the list
                            // moves the content of the first item, and of the last item at the end of the list,
                            // inside the card.
                            for (int i = 0, count = current.getChildCount(); i < count; i++) {
                                View shelf = current.getChildAt(i);
                                if (shelf.getHeight() <= 0
                                        || !(shelfCardItems.containsKey(shelf) || isShelfHeader(shelf))
                                        || !(findNestedList(shelf, 0) instanceof ViewGroup shelfList)) {
                                    continue;
                                }

                                View first = null;
                                View last = null;
                                for (int j = 0, listCount = shelfList.getChildCount(); j < listCount; j++) {
                                    View child = shelfList.getChildAt(j);
                                    if (child.getWidth() <= 0) {
                                        continue;
                                    }
                                    if (first == null || child.getLeft() < first.getLeft()) {
                                        first = child;
                                    }
                                    if (last == null || child.getRight() > last.getRight()) {
                                        last = child;
                                    }
                                }
                                if (first == null) {
                                    continue;
                                }
                                // The list keeps its scroll position when its padding changes, which moves the first
                                // item before the padding, so the list is scrolled back to its start.
                                if (shelfListsAtStart.remove(shelfList) != null
                                        && first.getLeft() != shelfList.getPaddingLeft()) {
                                    shelfList.scrollBy(first.getLeft() - shelfList.getPaddingLeft(), 0);
                                    continue;
                                }

                                int[] shelfLocation = new int[2];
                                shelf.getLocationOnScreen(shelfLocation);
                                int[] listLocation = new int[2];
                                shelfList.getLocationOnScreen(listLocation);
                                final int cardInset = Dim.dp(CARD_HORIZONTAL_PADDING_DP + SHELF_LIST_PADDING_DP);
                                int[] content = new int[2];
                                int paddingLeft = shelfList.getPaddingLeft();
                                int paddingRight = shelfList.getPaddingRight();

                                // The first item is the leftmost one only while the list is scrolled to the start.
                                if (!shelfList.canScrollHorizontally(-1) && getContentBounds(first, content)) {
                                    paddingLeft += shelfLocation[0] + cardInset
                                            - (listLocation[0] + first.getLeft() + content[0]);
                                }
                                // The last item is at the end of the list only if the items don't fit the list.
                                if (shelfList.canScrollHorizontally(-1) && !shelfList.canScrollHorizontally(1)
                                        && getContentBounds(last, content)) {
                                    paddingRight += listLocation[0] + last.getLeft() + content[1]
                                            - (shelfLocation[0] + shelf.getWidth() - cardInset);
                                }

                                paddingLeft = Math.max(0, paddingLeft);
                                paddingRight = Math.max(0, paddingRight);
                                if (paddingLeft == shelfList.getPaddingLeft()
                                        && paddingRight == shelfList.getPaddingRight()) {
                                    continue;
                                }
                                if (paddingLeft != shelfList.getPaddingLeft()) {
                                    shelfListsAtStart.put(shelfList, Boolean.TRUE);
                                }
                                // The items are drawn in the padding while scrolling, up to the edges of the card.
                                shelfList.setClipToPadding(false);
                                shelfList.setPadding(paddingLeft, shelfList.getPaddingTop(), paddingRight,
                                        shelfList.getPaddingBottom());
                            }

                            // Updates the clips of the Shorts of the Shorts shelves, without drawing the shelves.
                            shortsCellOutlines.clear();
                            for (int i = 0, count = current.getChildCount(); i < count; i++) {
                                View item = current.getChildAt(i);
                                if (item.getHeight() > 0 && isShortsRow(item)) {
                                    View above = findItemAbove(current, item);
                                    if (above == null || !isShortsRow(above)) {
                                        drawShortsShelfCard(current, above, item, null);
                                    }
                                }
                            }

                            final boolean feed = current.getId() == getFeedId();
                            if (feed) {
                                // The autoplay of a Short shows the player in a container above the Short, outside
                                // the list, which clips the player with its own rounded background. It has the clip
                                // of the Short below it.
                                View container = inlinePlayerContainer == null ? null : inlinePlayerContainer.get();
                                if (container == null || !container.isAttachedToWindow()) {
                                    final long now = System.currentTimeMillis();
                                    if (shortsCellOutlines.isEmpty()
                                            || now - inlinePlayerSearchTime < INLINE_PLAYER_SEARCH_INTERVAL_MILLIS) {
                                        container = null;
                                    } else {
                                        inlinePlayerSearchTime = now;
                                        container = findInlinePlayerContainer(current.getRootView(), current, 0);
                                        inlinePlayerContainer = container == null
                                                ? null
                                                : new WeakReference<>(container);
                                    }
                                }

                                if (container != null) {
                                    ViewOutlineProvider outline = shortsCellOutlines.get(getScreenBounds(container));
                                    if (outline != null) {
                                        if (container.getOutlineProvider() != outline) {
                                            container.setOutlineProvider(outline);
                                        }
                                    } else if (container.getOutlineProvider() instanceof ShortsCellOutlineProvider) {
                                        container.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                                    }
                                }
                            }

                            if (feed) {
                                // The autoplay of a video shows the player in a container below the list, outside
                                // the list, and the card shows it through its thumbnail. The container has square
                                // corners, so it gets the rounded top corners of the card while it's over
                                // the thumbnail of a card.
                                View container = cardInlinePlayerContainer == null
                                        ? null
                                        : cardInlinePlayerContainer.get();
                                if (container == null || !container.isAttachedToWindow()) {
                                    final long now = System.currentTimeMillis();
                                    if (now - cardInlinePlayerSearchTime < INLINE_PLAYER_SEARCH_INTERVAL_MILLIS) {
                                        container = null;
                                    } else {
                                        cardInlinePlayerSearchTime = now;
                                        container = findCardInlinePlayerContainer(current.getRootView(), current, 0);
                                        cardInlinePlayerContainer = container == null
                                                ? null
                                                : new WeakReference<>(container);
                                    }
                                }

                                if (container != null) {
                                    if (isOverCardThumbnail(current, container)) {
                                        if (container.getOutlineProvider() != CARD_INLINE_PLAYER_OUTLINE) {
                                            cardInlinePlayerClipToOutline = container.getClipToOutline();
                                            container.setOutlineProvider(CARD_INLINE_PLAYER_OUTLINE);
                                            container.setClipToOutline(true);
                                        }
                                    } else if (container.getOutlineProvider() == CARD_INLINE_PLAYER_OUTLINE) {
                                        container.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                                        // Over a Short, the container keeps its clip, which gets the outline of the
                                        // Short.
                                        container.setClipToOutline(cardInlinePlayerClipToOutline
                                                || shortsCellOutlines.containsKey(getScreenBounds(container)));
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "onPreDraw failure", ex);
                    }
                    return true;
                });
            }

            for (int i = 0, count = list.getChildCount(); i < count; i++) {
                View item = list.getChildAt(i);
                if (item.getHeight() <= 0) {
                    continue;
                }

                // First visible video of a vertical shelf of videos.
                if (isVideoShelfRow(item)) {
                    View above = findItemAbove(list, item);
                    if (above == null || !isVideoShelfRow(above)) {
                        // The videos of a vertical shelf are below the shelf header, and the shelf is one card
                        // with the header and the videos. The videos are also used in lists without a shelf
                        // header, so a card is drawn only if the videos are below a shelf header, or were below
                        // one before the header was scrolled out of the list.
                        final boolean belowHeader = above != null && isVideoShelfHeader(above);
                        if (!belowHeader && above != null) {
                            // Not a shelf. The views are recycled, so they are no longer videos of a shelf.
                            for (View row = item; row != null && isVideoShelfRow(row); row = findItemBelow(list, row)) {
                                videoShelfRows.remove(row);
                            }
                        } else if (belowHeader || videoShelfRows.containsKey(item)) {
                            float bottom = item.getY() + item.getHeight();
                            View row = item;
                            while (row != null && isVideoShelfRow(row)) {
                                videoShelfRows.put(row, Boolean.TRUE);
                                bottom = row.getY() + row.getHeight();
                                row = findItemBelow(list, row);
                            }

                            final float top = belowHeader ? above.getY() : item.getY();
                            final float x = item.getX();
                            final int horizontalPadding = Dim.dp(VIDEO_SHELF_HORIZONTAL_PADDING_DP);
                            canvas.drawRoundRect(x + horizontalPadding, top, x + item.getWidth() - horizontalPadding,
                                    bottom, CARD_CORNER_RADIUS, CARD_CORNER_RADIUS, getShelfPaint(list.getContext()));
                        }
                    }
                    continue;
                }

                // First visible row of a Shorts shelf.
                if (isShortsRow(item)) {
                    View above = findItemAbove(list, item);
                    if (above == null || !isShortsRow(above)) {
                        drawShortsShelfCard(list, above, item, canvas);
                    }
                    continue;
                }

                View header = item;
                final boolean shelfHeader = isShelfHeader(header);
                if (!shelfHeader && !shelfCardItems.containsKey(item)) {
                    continue;
                }

                // The horizontal shelves have the header and the videos in the same list item,
                // which is a card like the videos of the feed. The header is not mounted while
                // it is scrolled out of the list, and the item is then found by its element.
                if (hasNestedList(header, 0)) {
                    setCardOutline(header, CARD_OUTLINE);
                    final float x = header.getX();
                    final float y = header.getY();
                    final int horizontalPadding = Dim.dp(CARD_HORIZONTAL_PADDING_DP);
                    final int verticalPadding = Dim.dp(CARD_VERTICAL_PADDING_DP);
                    canvas.drawRoundRect(x + horizontalPadding, y + verticalPadding,
                            x + header.getWidth() - horizontalPadding, y + header.getHeight() - verticalPadding,
                            CARD_CORNER_RADIUS, CARD_CORNER_RADIUS, getShelfPaint(list.getContext()));
                    continue;
                }

                if (!shelfHeader) {
                    continue;
                }

                // The rows of Shorts below the header are not in the list yet.
                View below = findItemBelow(list, header);
                if (below == null || !isShortsRow(below)) {
                    final float x = header.getX();
                    final float top = header.getY();
                    final float bottom = top + header.getHeight();
                    final int left = shortsCardLeft > 0 ? shortsCardLeft : Dim.dp(CARD_HORIZONTAL_PADDING_DP);
                    final int right = shortsCardRight > 0 ? shortsCardRight : header.getWidth() - left;
                    canvas.save();
                    canvas.clipRect(x + left, top, x + right, bottom);
                    canvas.drawRoundRect(x + left, top, x + right, bottom + CARD_CORNER_RADIUS,
                            CARD_CORNER_RADIUS, CARD_CORNER_RADIUS, getShelfPaint(list.getContext()));
                    canvas.restore();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onListDraw failure", ex);
        }
    }

    /**
     * @return The header of the Shorts shelf, which is the Shorts shelf header or another shelf header
     *         above the Shorts, or null if the item above the Shorts is not a shelf header.
     */
    @Nullable
    private static View getShortsShelfHeader(@Nullable View above) {
        return above != null && (isShelfHeader(above) || isVideoShelfHeader(above)) ? above : null;
    }

    private static boolean isVideoShelfRow(View item) {
        return hasLabelMarker(item, VIDEO_SHELF_ROW_LABEL_MARKER, 0);
    }

    private static boolean isVideoShelfHeader(View item) {
        return hasLabelMarker(item, VIDEO_SHELF_HEADER_LABEL_MARKER, 0);
    }

    /**
     * The Shorts shelf is one card, with the header and the rows of Shorts below it.
     * Only the outer corners of the card are rounded, and the Shorts fill the card.
     *
     * @param above The list item above the first row of Shorts, or null if the item above it is not in the list.
     */
    private static void drawShortsShelfCard(RecyclerView list, @Nullable View above, View firstRow,
                                            @Nullable Canvas canvas) {
        View header = getShortsShelfHeader(above);
        // The rows of Shorts are the list items below the header, which can be scrolled out of the list.
        List<View> rows = new ArrayList<>();
        View item = firstRow;
        while (item != null && isShortsRow(item)) {
            rows.add(item);
            item = findItemBelow(list, item);
        }

        // The card is as wide as the Shorts, and ends at the bottom of the last row of Shorts.
        // The Shorts are laid out in columns centered in the row, and the last row can have fewer Shorts,
        // such as one Short on the left. The card is centered in the row too, so it keeps its width
        // while only the last row is in the list.
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        final float top = header != null ? header.getY() : firstRow.getY();
        float bottom = firstRow.getY();
        List<List<View>> rowCells = new ArrayList<>(rows.size());
        int[] rowLocation = new int[2];
        int[] cellLocation = new int[2];
        for (View row : rows) {
            row.getLocationOnScreen(rowLocation);
            List<View> cells = new ArrayList<>();
            findShortsCells(row, cells, 0);
            for (View cell : cells) {
                cell.getLocationOnScreen(cellLocation);
                final int cellLeft = cellLocation[0] - rowLocation[0];
                final int cellRight = cellLeft + cell.getWidth();
                left = Math.min(left, Math.min(cellLeft, row.getWidth() - cellRight));
                right = Math.max(right, Math.max(cellRight, row.getWidth() - cellLeft));
                bottom = Math.max(bottom, row.getY() + cellLocation[1] - rowLocation[1] + cell.getHeight());
            }
            rowCells.add(cells);
        }
        if (left >= right) {
            return;
        }
        shortsCardLeft = left;
        shortsCardRight = right;

        // The outer Shorts have rounded outer corners: the top corners in the first row,
        // also below the header, and the bottom corners of the card in the last row.
        // The first row in the list is the first row of the shelf only if an item is above it,
        // or if the list is scrolled to the top. Otherwise the rows above it are scrolled out of the list.
        final boolean firstRowOfShelf = above != null || !list.canScrollVertically(-1);
        for (int i = 0; i < rows.size(); i++) {
            final boolean topRow = i == 0 && firstRowOfShelf;
            final boolean bottomRow = i == rows.size() - 1;
            rows.get(i).getLocationOnScreen(rowLocation);
            for (View cell : rowCells.get(i)) {
                cell.getLocationOnScreen(cellLocation);
                final int cellLeft = cellLocation[0] - rowLocation[0];
                final boolean leftColumn = cellLeft <= left;
                final boolean rightColumn = cellLeft + cell.getWidth() >= right;
                final int corners = (topRow && leftColumn ? 8 : 0) | (topRow && rightColumn ? 4 : 0)
                        | (bottomRow && leftColumn ? 2 : 0) | (bottomRow && rightColumn ? 1 : 0);
                // The Shorts clip their thumbnail with the rounded outline of a view inside the Short,
                // which is replaced. The views are recycled, so it's checked every time the list is drawn.
                ViewOutlineProvider outline = SHORTS_CELL_OUTLINES[corners];
                setShortsCellOutline(cell, cell.getWidth(), outline, 0);

                // The screen bounds of the Short, for the inline player above it.
                shortsCellOutlines.put(getScreenBounds(cell), outline);
            }
        }

        if (canvas == null) {
            return;
        }
        final float x = firstRow.getX();
        canvas.drawRoundRect(x + left, top, x + right, bottom,
                CARD_CORNER_RADIUS, CARD_CORNER_RADIUS, getShelfPaint(list.getContext()));
    }

    /**
     * @return The view that clips the inline player (a SurfaceView or a TextureView) above a Short, outside the feed.
     */
    @Nullable
    private static View findInlinePlayerContainer(View view, RecyclerView feed, int depth) {
        if (view == feed || depth > 40) {
            return null;
        }
        if (view instanceof SurfaceView || view instanceof TextureView) {
            for (ViewParent parent = view.getParent(); parent instanceof View parentView;
                 parent = parentView.getParent()) {
                if (parentView.getClipToOutline() && shortsCellOutlines.containsKey(getScreenBounds(parentView))) {
                    return parentView;
                }
            }
            return null;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View container = findInlinePlayerContainer(group.getChildAt(i), feed, depth + 1);
                if (container != null) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * @return The highest view with the bounds of the inline player (a SurfaceView or a TextureView)
     *         over the thumbnail of a card, outside the feed.
     */
    @Nullable
    private static View findCardInlinePlayerContainer(View view, RecyclerView feed, int depth) {
        if (view == feed || depth > 40) {
            return null;
        }
        if (view instanceof SurfaceView || view instanceof TextureView) {
            View container = null;
            final String bounds = getScreenBounds(view);
            for (ViewParent parent = view.getParent(); parent instanceof View parentView
                    && bounds.equals(getScreenBounds(parentView)); parent = parentView.getParent()) {
                container = parentView;
            }
            return container != null && isOverCardThumbnail(feed, container) ? container : null;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View container = findCardInlinePlayerContainer(group.getChildAt(i), feed, depth + 1);
                if (container != null) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * @return If the view starts at the top of a card, inside the padding of the card, with the width of the card.
     */
    private static boolean isOverCardThumbnail(RecyclerView list, View view) {
        int[] viewLocation = new int[2];
        view.getLocationOnScreen(viewLocation);
        int[] itemLocation = new int[2];
        for (int i = 0, count = list.getChildCount(); i < count; i++) {
            View item = list.getChildAt(i);
            if (item.getOutlineProvider() != CARD_OUTLINE || item.getHeight() <= 0) {
                continue;
            }
            item.getLocationOnScreen(itemLocation);
            // The padding of the card is laid out from dp with rounding, so it can differ by a pixel on each side.
            if (Math.abs(viewLocation[0] - itemLocation[0] - CARD_OUTLINE.horizontalPadding) <= 1
                    && Math.abs(viewLocation[1] - itemLocation[1] - CARD_OUTLINE.verticalPadding) <= 1
                    && Math.abs(view.getWidth() - item.getWidth() + 2 * CARD_OUTLINE.horizontalPadding) <= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShortsRow(View item) {
        return hasLabelMarker(item, SHORTS_CELL_LABEL_MARKER, 0);
    }

    private static void findShortsCells(View view, List<View> cells, int depth) {
        if (endsWith(view.getContentDescription(), SHORTS_CELL_LABEL_MARKER)) {
            cells.add(view);
            return;
        }
        if (depth < 4 && view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                findShortsCells(group.getChildAt(i), cells, depth + 1);
            }
        }
    }

    private static String getScreenBounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[0] + "," + location[1] + "," + view.getWidth() + "," + view.getHeight();
    }

    /**
     * The thumbnail and the animated preview of the Short have their own clipping views,
     * as wide as the Short.
     */
    private static void setShortsCellOutline(View view, int cellWidth, ViewOutlineProvider outline, int depth) {
        if (depth > 0 && view.getClipToOutline() && view.getWidth() == cellWidth) {
            if (view.getOutlineProvider() != outline) {
                view.setOutlineProvider(outline);
            }
            // The screen bounds of the clipped thumbnail, for the inline player above it.
            // The Shorts of the grids have the title below the thumbnail, so the player is shorter than the Short.
            shortsCellOutlines.put(getScreenBounds(view), outline);
        }
        if (depth < 4 && view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                setShortsCellOutline(group.getChildAt(i), cellWidth, outline, depth + 1);
            }
        }
    }

    /**
     * Outline of the Shorts of the Shorts shelf, with only the rounded corners of the card.
     * The rounded rectangle extends beyond the view on the sides that have no rounded corners.
     */
    private static final class ShortsCellOutlineProvider extends ViewOutlineProvider {
        private final boolean topLeft;
        private final boolean topRight;
        private final boolean bottomLeft;
        private final boolean bottomRight;

        ShortsCellOutlineProvider(boolean topLeft, boolean topRight, boolean bottomLeft, boolean bottomRight) {
            this.topLeft = topLeft;
            this.topRight = topRight;
            this.bottomLeft = bottomLeft;
            this.bottomRight = bottomRight;
        }

        /**
         * The outline has one radius for all the corners, so the sides without rounded corners
         * are moved out of the view, which keeps their corners square.
         */
        @Override
        public void getOutline(View view, Outline outline) {
            final int width = view.getWidth();
            final int height = view.getHeight();
            if (!topLeft && !topRight && !bottomLeft && !bottomRight) {
                outline.setRect(0, 0, width, height);
                return;
            }
            outline.setRoundRect(
                    topLeft || bottomLeft ? 0 : -CARD_CORNER_RADIUS,
                    topLeft || topRight ? 0 : -CARD_CORNER_RADIUS,
                    topRight || bottomRight ? width : width + CARD_CORNER_RADIUS,
                    bottomLeft || bottomRight ? height : height + CARD_CORNER_RADIUS,
                    CARD_CORNER_RADIUS);
        }
    }

    private static boolean hasNestedList(View view, int depth) {
        return findNestedList(view, depth) != null;
    }

    @Nullable
    private static View findNestedList(View view, int depth) {
        if (depth > 0 && view instanceof RecyclerView) {
            return view;
        }
        if (depth < 4 && view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View list = findNestedList(group.getChildAt(i), depth + 1);
                if (list != null) {
                    return list;
                }
            }
        }
        return null;
    }

    /**
     * @param bounds The left and the right edge of the views inside the item, relative to the item.
     * @return If the item has visible views.
     */
    private static boolean getContentBounds(View item, int[] bounds) {
        bounds[0] = Integer.MAX_VALUE;
        bounds[1] = Integer.MIN_VALUE;
        addContentBounds(item, 0, bounds, 0);
        return bounds[0] < bounds[1];
    }

    /**
     * The content is in the views without children, such as the thumbnail and the text of the item.
     */
    private static void addContentBounds(View view, int left, int[] bounds, int depth) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        if (depth < 8 && view instanceof ViewGroup group && group.getChildCount() > 0) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                addContentBounds(child, left + child.getLeft(), bounds, depth + 1);
            }
            return;
        }
        bounds[0] = Math.min(bounds[0], left);
        bounds[1] = Math.max(bounds[1], left + view.getWidth());
    }

    @Nullable
    private static View findItemAbove(RecyclerView list, View item) {
        for (int i = 0, count = list.getChildCount(); i < count; i++) {
            View child = list.getChildAt(i);
            if (child != item && child.getBottom() == item.getTop()) {
                return child;
            }
        }
        return null;
    }

    @Nullable
    private static View findItemBelow(RecyclerView list, View item) {
        for (int i = 0, count = list.getChildCount(); i < count; i++) {
            View child = list.getChildAt(i);
            if (child != item && child.getTop() == item.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private static boolean isShelfHeader(View item) {
        return hasLabelMarker(item, SHELF_HEADER_LABEL_MARKER, 0);
    }

    /**
     * The labels of the shelf header and of the Shorts are on views inside the list item.
     */
    private static boolean hasLabelMarker(View view, char marker, int depth) {
        if (endsWith(view.getContentDescription(), marker)) {
            return true;
        }
        if (depth < 4 && view instanceof ViewGroup group && !(view instanceof RecyclerView)) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (hasLabelMarker(group.getChildAt(i), marker, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Paint of the shelf cards: the additive background of the theme, a translucent gray over the feed,
     * which is visible with any background color of the feed (the theme patch also changes the raised background).
     */
    private static Paint getShelfPaint(Context context) {
        final boolean darkMode = Utils.isDarkModeEnabled();
        if (shelfPaint == null || shelfPaintDarkMode != darkMode) {
            int color = darkMode ? 0x1AFFFFFF : 0x0D000000;
            final int attribute = ResourceUtils.getIdentifier(ResourceType.ATTR, "ytAdditiveBackground");
            TypedValue value = new TypedValue();
            if (attribute != 0 && context.getTheme().resolveAttribute(attribute, value, true)) {
                color = value.resourceId != 0 ? context.getColor(value.resourceId) : value.data;
            }
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            shelfPaint = paint;
            shelfPaintDarkMode = darkMode;
        }
        return shelfPaint;
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
                    || !endsWith(senderView.getContentDescription(), COMMENT_BUTTON_LABEL_MARKER)
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

            Matcher panelId = COMMENTS_PANEL_ID_PATTERN.matcher(new String(response, StandardCharsets.ISO_8859_1));
            if (panelId.find()) {
                String foundId = panelId.group();
                Logger.printDebug(() -> "Opening unknown comments panel: " + foundId);
                return showEngagementPanelCommand(foundId);
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

    /**
     * Outline of the card, inside the padding of the card.
     */
    private static final class CardOutlineProvider extends ViewOutlineProvider {
        private final int horizontalPadding;
        private final int verticalPadding;

        CardOutlineProvider(int horizontalPadding, int verticalPadding) {
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(horizontalPadding, verticalPadding, view.getWidth() - horizontalPadding,
                    view.getHeight() - verticalPadding, CARD_CORNER_RADIUS);
        }
    }

    private static int getFeedId() {
        if (feedId == 0) {
            feedId = ResourceUtils.getIdentifier(ResourceType.ID, "results");
        }
        return feedId;
    }

    /**
     * The label of the header ends with an invisible character, to find the shelves in the lists.
     */
    private static ShelfHeaderData modernShelfHeader(ShelfHeaderData data) {
        return data.toBuilder()
                .clearIcon()
                .setTitle(data.getTitle() + SHELF_HEADER_LABEL_MARKER)
                .setAccessibilityText(data.getAccessibilityText() + SHELF_HEADER_LABEL_MARKER)
                .setStyle(data.getStyle().toBuilder().setModernType(true))
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
    private static SubscriptionConfig subscriptionConfig(SubscriptionConfig config, VideoTemplate template) {
        SubscriptionConfig.Builder builder = SubscriptionConfig.newBuilder();
        if (config.hasThemeSubscriptionConfig()) {
            ThemeSubscriptionConfig theme = config.getThemeSubscriptionConfig();
            builder.setThemeSubscriptionConfig(theme.toBuilder()
                    .setModelField(template.modelField)
                    .setMapping(theme.getMapping().toBuilder().setResultField(template.themeField)));
        }
        if (config.hasCapabilitiesSubscriptionConfig()) {
            CapabilitiesSubscriptionConfig capabilities = config.getCapabilitiesSubscriptionConfig();
            builder.setCapabilitiesSubscriptionConfig(capabilities.toBuilder()
                    .setModelField(template.modelField)
                    .setMapping(capabilities.getMapping().toBuilder().setResultField(template.capabilitiesField)));
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
        if (!uri.startsWith(template)) {
            return false;
        }
        final int hashStart = uri.indexOf('|', template.length());
        return hashStart == template.length() || (hashStart > 0
                && TEMPLATE_SUFFIX_PATTERN.matcher(uri.substring(template.length(), hashStart)).matches());
    }
}
