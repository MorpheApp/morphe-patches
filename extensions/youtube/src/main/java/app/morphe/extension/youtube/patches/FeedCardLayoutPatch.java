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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
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
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CardLayoutExperiments;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CapabilitiesSubscriptionConfig;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ChannelAvatar;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ClientResource;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CollectionStackColors;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CommandContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CompactPlaylistData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.CompactPlaylistModel;
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
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.OpenPopupAction;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.RendererContext;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShelfHeaderData;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShelfHeaderModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.GridVideoMetadata;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.GridVideoModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShortsShelfModel;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass.ShortsPivotItemModel;
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
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.innertube.PlaylistEdit;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.patches.components.LayoutComponentsFilter;
import app.morphe.extension.youtube.patches.theme.SeekbarColorPatch;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistRoutes;
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
        /**
         * The layout that the server sends to the account.
         */
        DEFAULT,
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

    private static final FeedCardLayout LAYOUT = Settings.FEED_CARD_LAYOUT.get();
    private static final boolean CARD_LAYOUT = LAYOUT == FeedCardLayout.CARDS
            || LAYOUT == FeedCardLayout.CARDS_WITH_ACTION_BUTTONS;
    private static final boolean ACTION_BUTTONS = LAYOUT == FeedCardLayout.CARDS_WITH_ACTION_BUTTONS;

    /**
     * Template names, followed by "|hash" or by a suffix and "|hash" (newer app versions), such as "-fe|hash".
     */
    private static final String CLASSIC_TEMPLATE = "video_lockup_with_attachment.eml";
    private static final String CARD_TEMPLATE = "lockup.eml";
    private static final String THUMBNAIL_BOTTOM_OVERLAY_TEMPLATE = "thumbnail_bottom_overlay.eml";
    private static final String SHELF_HEADER_TEMPLATE = "shelf_header.eml";
    private static final String HORIZONTAL_SHELF_TEMPLATE = "horizontal_shelf.eml";
    private static final String SHORTS_VIDEO_CELL_TEMPLATE = "shorts_video_cell.eml";
    /** Template of the Shorts of the Shorts shelf of the channel home. */
    private static final String SHORTS_PIVOT_ITEM_TEMPLATE = "shorts_pivot_item.eml";
    private static final String SHORTS_SHELF_TEMPLATE = "shorts_shelf.eml";
    private static final String GRID_VIDEO_TEMPLATE = "grid_video_wrapper.eml";
    /**
     * Template of the playlists and the podcasts of the vertical shelves, such as in the channel home,
     * with the same model as {@link #GRID_VIDEO_TEMPLATE}.
     */
    private static final String GRID_COLLECTION_TEMPLATE = "grid_video.eml";
    /**
     * Template of the playlists and the podcasts in a row of a list, such as in the vertical shelves
     * of the channel home, below the playlists of {@link #GRID_COLLECTION_TEMPLATE}.
     */
    private static final String COMPACT_PLAYLIST_TEMPLATE = "compact_playlist.eml";

    private static final int LOCKUP_CONTENT_TYPE_VIDEO = 1;
    private static final int LOCKUP_CONTENT_TYPE_PLAYLIST = 2;
    /**
     * Browse id of the playlist pages, followed by the playlist id.
     */
    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";
    private static final String MIX_PLAYLIST_ID_PREFIX = "RD";
    /**
     * Values of the playlists and the mixes of the classic layout.
     */
    private static final int PLAYLIST_BADGE_ICON_TYPE = 4;
    private static final int MIX_BADGE_ICON_TYPE = 8;
    private static final int COLLECTION_BADGE_COLOR = 0xCC595959;
    private static final int COLLECTION_BADGE_DARK_COLOR = 0xCC232323;
    private static final int COLLECTION_STACK_FRONT_COLOR = 0xCC999999;
    private static final int COLLECTION_STACK_BACK_COLOR = 0xCC8C8C8C;

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
     * Corner radius of the cards, in dp.
     */
    private static final int CARD_CORNER_RADIUS_DP = 12;
    /**
     * Padding of the cards, in dp, which is the spacing between the cards in every list.
     * The spacing that the lists add between the cards is removed.
     */
    private static final int CARD_HORIZONTAL_PADDING_DP = 12;
    private static final int CARD_VERTICAL_PADDING_DP = 6;
    /**
     * The videos of the vertical shelves are laid out almost to the edges of the list, with the menu button
     * near the right edge, and their content starts this far beyond the spacing of the cards.
     */
    private static final int VIDEO_SHELF_HORIZONTAL_PADDING_DP = 4;
    /**
     * Spacing between the edges of the horizontal shelf cards and the content of the first and the last item
     * of their list. The items of the shelves have different paddings, and some have none.
     */
    private static final int SHELF_LIST_PADDING_DP = 8;

    private static final CardOutlineProvider CARD_OUTLINE = new CardOutlineProvider(
            CARD_HORIZONTAL_PADDING_DP, CARD_VERTICAL_PADDING_DP);
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
    private static final int ACTION_ADD_VIDEO = PlaylistEdit.PlaylistEditActionType.ACTION_ADD_VIDEO_VALUE;

    /**
     * Fields of the innertube commands.
     */
    private static final int WATCH_ENDPOINT_FIELD = 48687757;
    private static final int WATCH_ENDPOINT_VIDEO_ID_FIELD = 1;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD = 138681778;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_ID_FIELD = 1;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_FIELD = 2;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_IDENTIFIER_FIELD = 10;
    /**
     * Fields of the engagement panels of the watch next response.
     */
    private static final int WATCH_NEXT_ENGAGEMENT_PANELS_FIELD = 25;
    private static final int ENGAGEMENT_PANEL_SECTION_LIST_RENDERER_FIELD = 138681066;
    private static final int ENGAGEMENT_PANEL_PANEL_ID_FIELD = 1;
    private static final int ENGAGEMENT_PANEL_HEADER_FIELD = 2;
    private static final int ENGAGEMENT_PANEL_TITLE_HEADER_RENDERER_FIELD = 138681548;
    private static final int ENGAGEMENT_PANEL_TITLE_HEADER_SUBHEADER_FIELD = 19;
    private static final int ENGAGEMENT_PANEL_VISIBILITY_FIELD = 9;
    private static final int ENGAGEMENT_PANEL_VISIBILITY_EXPANDED = 1;
    private static final int ENGAGEMENT_PANEL_IDENTIFIER_FIELD = 18;
    private static final int ENGAGEMENT_PANEL_IDENTIFIER_SURFACE_FIELD = 1;
    private static final int ENGAGEMENT_PANEL_IDENTIFIER_TAG_FIELD = 3;
    /**
     * The panels of this surface can be shown over the feed, and load their content with 'next' requests,
     * as the comments of Shorts.
     */
    private static final int ENGAGEMENT_PANEL_SURFACE_SHORTS = 4;

    private static final String WATCH_LATER_PLAYLIST_ID = "WL";
    /**
     * Menu items that the menu of the cards with a save button doesn't have.
     */
    private static final StringRef SAVE_TO_WATCH_LATER = StringRef.sfc("morphe_save_to_watch_later_flyout_title");
    private static final StringRef SAVE_TO_PLAYLIST = StringRef.sfc("accessibility_save_to_playlists");
    private static final String SENDER_VIEW_KEY = "com.google.android.libraries.youtube.rendering.elements.sender_view";
    /**
     * Comments panel of the watch page. Newer clients use the first id.
     */
    private static final String[] COMMENTS_PANEL_IDS = {"comment-item-section", "engagement-panel-comments-section"};
    /**
     * Start of the ids of the replies panels that the comments open, such as 'FEcomment_watch_replies_panel'.
     */
    private static final String COMMENT_REPLIES_PANEL_ID_PREFIX = "FEcomment";
    private static final String ENGAGEMENT_PANEL_SURFACE_UNKNOWN = "ENGAGEMENT_PANEL_SURFACE_UNKNOWN";

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
     * Invisible character before the marker of the cards, in the label of the cards without inline playback.
     */
    private static final char NO_INLINE_PLAYBACK_CARD_LABEL_MARKER = '\u2062';
    /**
     * Invisible character before the marker of the Shorts, in the label of the Shorts of the Shorts shelf
     * of the channel home, whose rows fill the width with the list.
     */
    private static final char SHORTS_PIVOT_CELL_LABEL_MARKER = '\u2063';
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

    /**
     * Templates of the elements that are converted. Every element is searched on the main thread,
     * so the element is searched as a string in native code, see {@link FeedVideoCardsPatch#getElementText(byte[])}.
     */
    private static final String[] elementTemplates = CARD_LAYOUT
            ? new String[]{CLASSIC_TEMPLATE, SHELF_HEADER_TEMPLATE, HORIZONTAL_SHELF_TEMPLATE,
            SHORTS_VIDEO_CELL_TEMPLATE, SHORTS_PIVOT_ITEM_TEMPLATE, SHORTS_SHELF_TEMPLATE, GRID_VIDEO_TEMPLATE,
            GRID_COLLECTION_TEMPLATE, COMPACT_PLAYLIST_TEMPLATE}
            : new String[]{CARD_TEMPLATE};
    /**
     * Labels of the shelf headers, in the bytes of the converted elements.
     */
    private static final ByteTrieSearch shelfHeaderLabelSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(String.valueOf(SHELF_HEADER_LABEL_MARKER),
                    String.valueOf(VIDEO_SHELF_HEADER_LABEL_MARKER))
    );
    /**
     * Link to the movies page, in the bytes of the movies.
     */
    private static final ByteTrieSearch moviesPageSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes("FEstorefront")
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
     * Minimum time between the lookups of a template in the template cache of the app.
     */
    private static final long TEMPLATE_LOOKUP_INTERVAL_MILLIS = 10_000;
    /**
     * Time before the cache is looked up again while no version of a template is known,
     * or while the version that is used was not found by the last lookup.
     */
    private static final long TEMPLATE_CONFIRM_INTERVAL_MILLIS = 1000;
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
            final int radius = getCardCornerRadius();
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + radius, radius);
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
     * Rows of the shelves that are scaled down to the width of the cards, see {@link #onListDraw(RecyclerView, Canvas)}.
     * The views are recycled, so they are scaled back when they show something else.
     */
    private static final Map<View, Boolean> scaledShelfRows = new WeakHashMap<>();
    /**
     * List items that are horizontal shelves or Shorts carousels with the card layout, found by the converted
     * element of the item when the list sets its spacing. Their header, which has the label of the shelf,
     * is not mounted while it is scrolled out of the list. The views are recycled, and the list sets
     * the spacing of a view again whenever the view shows another element.
     */
    private static final Map<View, Boolean> shelfCardItems = new WeakHashMap<>();
    /**
     * List items that are shelf headers -> the marker at the end of their label, found by the converted element
     * of the item when the list sets its spacing, like {@link #shelfCardItems}. The label is not mounted while
     * the part of the header that has it is scrolled out of the list.
     */
    private static final Map<View, Character> shelfHeaderItems = new WeakHashMap<>();
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
     * The marker at the end of the label of the elements that are shelf headers, or 0 for the other elements,
     * by the bytes of the converted element. The arrays are compared by identity.
     */
    private static final Map<byte[], Character> shelfHeaderElements = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Backgrounds of the items of the feed that were not mounted yet when their color was looked up.
     */
    private static final Map<Drawable, Boolean> unmountedItemBackgrounds = new WeakHashMap<>();
    /**
     * Litho backgrounds of the cards. They keep their color on the see-through watch page of the
     * Enhanced Ambient mode, which otherwise turns them translucent where nothing else covers them.
     */
    public static final Map<Drawable, Boolean> cardBackgrounds = new WeakHashMap<>();
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
    /**
     * Density that the app used to lay out the cards, measured from the padding of the cards, or 0 until it
     * differs from the density of the display. The app keeps the layout of the lists when the density
     * of the display changes, until the lists are laid out again.
     */
    private static float cardLayoutDensity;

    /**
     * Template that the elements are converted to, such as the feed videos.
     * The uri includes the hash of the template version, and the subscriptions of the element write
     * the theme and the capabilities to fields of the model. Both are taken from the elements of the template
     * that the server sends, so they follow the new versions of the template. Until such an element is seen,
     * the uri is taken from the template cache of the app, and the fields are the known ones.
     */
    static final class VideoTemplate {
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
        /**
         * Whether the last lookup did not find the version of the uri in the cache,
         * which is replaced only if the next lookup does not find it either.
         */
        private volatile boolean missingInCache;
        private volatile long cacheLookupTime;
        private volatile long synchronousLookupTime;
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
            // The app drops the older versions of the templates from its cache, also while it runs,
            // and the elements of a template version that is no longer cached are not shown.
            // So the version is looked up again once in a while, also the version of an element,
            // which is the one the server sends now, and is kept while the app has it.
            // Until a version is found, the cache is looked up more often, as the elements that are
            // converted meanwhile keep the layout of the server.
            final long now = System.currentTimeMillis();
            if (now - cacheLookupTime > (uri == null || missingInCache
                    ? TEMPLATE_CONFIRM_INTERVAL_MILLIS
                    : TEMPLATE_LOOKUP_INTERVAL_MILLIS)) {
                // The element that is converted now gets the uri of a lookup on its thread,
                // at most as often as before a version is found.
                final boolean synchronous = uri == null
                        && now - synchronousLookupTime > TEMPLATE_LOOKUP_INTERVAL_MILLIS;
                if (synchronous) {
                    synchronousLookupTime = now;
                }
                cacheLookupTime = now;
                // The uri of a template includes the hash of its version, so it's taken from the template
                // cache of the app, which has the templates the app received. The last cached version
                // of the template is used, or none if the template is not cached.
                Runnable lookup = () -> {
                    // The cached versions of the template, from the oldest to the newest.
                    List<String> cached = new ArrayList<>();
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
                                            cached.add(key);
                                        }
                                    } else {
                                        entry.skipField(entryTag);
                                    }
                                }
                            }
                        }
                    } catch (InvalidProtocolBufferException ex) {
                        // The app rewrites the cache while it runs, so the file can be read while it's
                        // incomplete. The uri found before is kept, and the cache is read again soon.
                        Logger.printDebug(() -> "Template cache is being written", ex);
                        cacheLookupTime = System.currentTimeMillis() - TEMPLATE_LOOKUP_INTERVAL_MILLIS
                                + TEMPLATE_CONFIRM_INTERVAL_MILLIS;
                        return;
                    } catch (Exception ex) {
                        Logger.printException(() -> "getUri failure", ex);
                        return;
                    }
                    // An element of the template can be seen while the cache is read.
                    synchronized (this) {
                        String current = uri;
                        // The app rewrites the cache in the same file while it runs, so a file that is read
                        // while it's rewritten can end before the template without an error. Without the uri,
                        // the elements would keep the layout of the server, so a version that is not found
                        // is replaced only if the next lookup does not find it either.
                        if (current != null && !cached.contains(current) && !missingInCache) {
                            missingInCache = true;
                            Logger.printDebug(() -> "Template not found in the cache: " + current);
                            return;
                        }
                        missingInCache = false;

                        String newest = cached.isEmpty() ? null : cached.get(cached.size() - 1);
                        // The version of an element is kept while it's cached, and while no version is cached,
                        // as the app can use it before it's written to the cache.
                        if (fromElement
                                ? newest == null || cached.contains(current)
                                : Objects.equals(current, newest)) {
                            return;
                        }
                        Logger.printDebug(() -> "Cached template: " + newest);
                        uri = newest;
                        fromElement = false;
                    }
                };
                if (synchronous) {
                    lookup.run();
                } else {
                    Utils.runOnBackgroundThread(lookup);
                }
            }
            return uri;
        }
    }

    /**
     * Icons of the action buttons. The bold icons are not available on older versions of the app.
     */
    @SuppressWarnings("ClassCanBeRecord")
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
        if (LAYOUT == FeedCardLayout.DEFAULT) {
            return bytes;
        }

        try {
            final String elementText = FeedVideoCardsPatch.getElementText(bytes);
            if (!Utils.containsAny(elementText, elementTemplates)) {
                // The elements of the layout of the setting give the current version of its template:
                // the uri and the fields are taken from the element, if it's an element of the template.
                // After the first element, the elements are checked once in a while, to follow a new version.
                VideoTemplate template = CARD_LAYOUT ? CARD : CLASSIC;
                final long now = System.currentTimeMillis();
                if ((!template.fromElement || now - template.checkTime >= TEMPLATE_CHECK_INTERVAL_MILLIS)
                        && elementText.contains(template.name)) {
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
                        // A lookup of the cache can replace the uri at the same time.
                        synchronized (template) {
                            template.uri = elementUri;
                            template.fromElement = true;
                            template.missingInCache = false;
                        }
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

                        // The filters find the hidden Shorts, live streams, mix playlists and movies by the classic layout.
                        if (thumbnail.getBadgeStyle() == THUMBNAIL_BADGE_STYLE_LIVE
                                && Settings.HIDE_LIVE_STREAMS.get()) {
                            break toCard;
                        }
                        // The mix playlists are found by the url of the mix, which the cards don't have.
                        if (LayoutComponentsFilter.filterMixPlaylists(bytes)) {
                            break toCard;
                        }
                        // The movies are found by the link to the movies page, as in the ads filter.
                        if (Settings.HIDE_MOVIES_SECTION.get() && moviesPageSearch.matches(bytes)) {
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
                            // Without the item, such as in some menus, the button opens the menu.
                            PlaylistQueueRenderer.NavigationEndpoint watchLaterCommand = findWatchLaterCommand(
                                    PlaylistQueueRenderer.Menu.parseFrom(menuOnTap.getInnertubeCommand()
                                            .getOpenPopupAction().getPopup()).getMenuRenderer());
                            InnertubeCommandHolder saveOnTap = menuOnTap;
                            if (watchLaterCommand != null) {
                                saveOnTap = InnertubeCommandHolder.newBuilder()
                                        .setInnertubeCommand(InnertubeCommand.parseFrom(watchLaterCommand.toByteString()))
                                        .build();
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

                        // The template draws the background below the thumbnail only for the cards with inline
                        // playback, so the list draws it for the other cards, such as the playlists.
                        String label = data.getAccessibilityText();
                        if (data.getInlinePlayback().isEmpty()) {
                            label += NO_INLINE_PLAYBACK_CARD_LABEL_MARKER;
                        }

                        LockupViewModel.Builder lockupViewModel = LockupViewModel.newBuilder()
                                .setContentImage(ContentImage.newBuilder().setThumbnailViewModel(thumbnailViewModel))
                                .setMetadata(LockupMetadata.newBuilder().setLockupMetadataViewModel(metadata))
                                .setContentId(contentId)
                                .setContentType(isPlaylist ? LOCKUP_CONTENT_TYPE_PLAYLIST : LOCKUP_CONTENT_TYPE_VIDEO)
                                .setRendererContext(RendererContext.newBuilder()
                                        .setAccessibilityContext(AccessibilityContext.newBuilder()
                                                .setLabel(label + CARD_LABEL_MARKER))
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
                } else if (isTemplate(uri, SHORTS_PIVOT_ITEM_TEMPLATE) && model.hasShortsPivotItemModel()) {
                    // The Shorts of the Shorts shelf of the channel home, below its header, are found the same way,
                    // so the shelf is a card with the header and the Shorts, like the Shorts shelf of the feed.
                    // Their rows fill the width with the list, and are scaled down inside the card when drawn.
                    ShortsPivotItemModel item = model.getShortsPivotItemModel();
                    ShortsVideoCellData data = item.getData();
                    if (!data.getAccessibilityText().isEmpty()) {
                        converted = componentType.toBuilder()
                                .setModel(ElementModel.newBuilder().setShortsPivotItemModel(item.toBuilder()
                                        .setData(data.toBuilder()
                                                .setAccessibilityText(data.getAccessibilityText()
                                                        + SHORTS_PIVOT_CELL_LABEL_MARKER + SHORTS_CELL_LABEL_MARKER))))
                                .build();
                    }
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
                } else if ((isTemplate(uri, GRID_VIDEO_TEMPLATE) || isTemplate(uri, GRID_COLLECTION_TEMPLATE))
                        && model.hasGridVideoModel()) {
                    // The label of the videos of the vertical shelves ends with an invisible character,
                    // to draw the shelf below the header as a card.
                    GridVideoModel video = model.getGridVideoModel();
                    String label = video.getAccessibilityText();
                    if (label.isEmpty()) {
                        // The playlists and the podcasts have no label, so they get the label of their texts,
                        // as a compact playlist, such as "Title - Channel · Playlist - 20 videos - Updated 2 days ago".
                        // Accessibility services read the label instead of the texts.
                        GridVideoMetadata metadata = video.getData().getMetadata();
                        StringBuilder texts = new StringBuilder();
                        for (ByteString text : List.of(metadata.getTitle(), metadata.getSubtitle(),
                                video.getData().getThumbnail().getCollectionCountText(), metadata.getDetails())) {
                            if (!text.isEmpty()) {
                                //noinspection SizeReplaceableByIsEmpty
                                if (texts.length() > 0) {
                                    texts.append(" - ");
                                }
                                texts.append(text.toStringUtf8());
                            }
                        }
                        label = texts.toString();
                    }
                    converted = componentType.toBuilder()
                            .setModel(ElementModel.newBuilder().setGridVideoModel(video.toBuilder()
                                    .setAccessibilityText(label + VIDEO_SHELF_ROW_LABEL_MARKER)))
                            .build();
                } else if (isTemplate(uri, COMPACT_PLAYLIST_TEMPLATE) && model.hasCompactPlaylistModel()) {
                    // The playlists in a row of a vertical shelf are drawn on the card of the shelf too.
                    CompactPlaylistModel playlist = model.getCompactPlaylistModel();
                    CompactPlaylistData data = playlist.getData();
                    if (!data.getAccessibilityText().isEmpty()) {
                        converted = componentType.toBuilder()
                                .setModel(ElementModel.newBuilder().setCompactPlaylistModel(playlist.toBuilder()
                                        .setData(data.toBuilder()
                                                .setAccessibilityText(data.getAccessibilityText()
                                                        + VIDEO_SHELF_ROW_LABEL_MARKER))))
                                .build();
                    }
                }
            } else if (isTemplate(uri, CARD_TEMPLATE) && model.hasLockupModel()) {
                LockupModel card = model.getLockupModel();
                toClassic: {
                    LockupViewModel lockupViewModel = card.getViewModel().getLockupViewModel();
                    final boolean isPlaylist = lockupViewModel.getContentType() == LOCKUP_CONTENT_TYPE_PLAYLIST;
                    ContentImage contentImage = lockupViewModel.getContentImage();
                    // The playlists and the mixes show a stack of thumbnails.
                    ThumbnailViewModel thumbnailViewModel = contentImage.hasCollectionThumbnailViewModel()
                            ? contentImage.getCollectionThumbnailViewModel().getPrimaryThumbnail().getThumbnailViewModel()
                            : contentImage.getThumbnailViewModel();
                    if ((!isPlaylist && lockupViewModel.getContentType() != LOCKUP_CONTENT_TYPE_VIDEO)
                            || thumbnailViewModel.getImage().isEmpty()) {
                        break toClassic;
                    }

                    String templateUri = CLASSIC.getUri();
                    if (templateUri == null) {
                        break toClassic;
                    }

                    VideoThumbnail.Builder thumbnail = VideoThumbnail.newBuilder()
                            .setImage(thumbnailViewModel.getImage());
                    for (ThumbnailOverlay overlay : thumbnailViewModel.getOverlaysList()) {
                        List<ThumbnailBadge> badges = overlay.hasThumbnailOverlayBadgeViewModel()
                                ? overlay.getThumbnailOverlayBadgeViewModel().getThumbnailBadgesList()
                                : overlay.getThumbnailBottomOverlayViewModel().getBadgesList();
                        for (ThumbnailBadge badge : badges) {
                            ThumbnailBadgeViewModel badgeViewModel = badge.getThumbnailBadgeViewModel();
                            if (!badgeViewModel.getText().isEmpty()) {
                                if (isPlaylist) {
                                    thumbnail.setVideoCountText(badgeViewModel.getText());
                                } else {
                                    thumbnail.setTimestampText(badgeViewModel.getText())
                                            .setTimestampAccessibilityText(badgeViewModel.getRendererContext()
                                                    .getAccessibilityContext().getLabel());
                                }
                            }
                        }
                    }

                    final String playlistId = lockupViewModel.getContentId();
                    Matcher mixVideoId = VIDEO_ID_PATTERN.matcher(
                            thumbnailViewModel.getImage().toString(StandardCharsets.ISO_8859_1));
                    final boolean isMix = isPlaylist && playlistId.startsWith(MIX_PLAYLIST_ID_PREFIX) && mixVideoId.find();
                    if (isPlaylist) {
                        // The server samples these colors from the thumbnail, so the gray values are used.
                        thumbnail.setIsPlaylist(!isMix)
                                .setIsMix(isMix)
                                .setCollectionStyle1(1)
                                .setCollectionStyle2(1)
                                .setCollectionStyle3(3)
                                .setBadgeIconType(isMix ? MIX_BADGE_ICON_TYPE : PLAYLIST_BADGE_ICON_TYPE)
                                .setBadgeColor(COLLECTION_BADGE_COLOR)
                                .setBadgeDarkColor(COLLECTION_BADGE_DARK_COLOR)
                                .setStackColors(CollectionStackColors.newBuilder()
                                        .setFrontColor(COLLECTION_STACK_FRONT_COLOR)
                                        .setBackColor(COLLECTION_STACK_BACK_COLOR));
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
                                //noinspection SizeReplaceableByIsEmpty
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
                                //noinspection SizeReplaceableByIsEmpty
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
                    if (isPlaylist) {
                        // The mix playlists are found by the url of the mix, which the cards don't have.
                        videoData.setUrl(isMix
                                ? "https://www.youtube.com/watch?v=" + mixVideoId.group(1) + "&list=" + playlistId
                                : "https://www.youtube.com/playlist?list=" + playlistId);
                    }
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
                    InnertubeCommandHolder menuOnTap = menuButton.getOnTap();
                    // The cards with a save button have no save items in the menu,
                    // so the items are added to the menu of the classic layout, which has no save button.
                    boolean hasSaveButton = false;
                    for (ActionButton button : metadata.getActionButtonsList()) {
                        hasSaveButton |= button.hasAddToPlaylistButtonViewModel();
                    }
                    OpenPopupAction openPopupAction = menuOnTap.getInnertubeCommand().getOpenPopupAction();
                    PlaylistQueueRenderer.Menu popup = hasSaveButton && !isPlaylist
                            ? PlaylistQueueRenderer.Menu.parseFrom(openPopupAction.getPopup())
                            : PlaylistQueueRenderer.Menu.getDefaultInstance();
                    PlaylistQueueRenderer.MenuRenderer menu = popup.getMenuRenderer();
                    if (menu.getItemsCount() > 0 && findWatchLaterCommand(menu) == null) {
                        // The save items follow the items shown by a condition, such as the queue items.
                        int saveItemsIndex = 0;
                        while (saveItemsIndex < menu.getItemsCount()
                                && (menu.getItems(saveItemsIndex).hasMenuConditionalServiceItemRenderer()
                                || menu.getItems(saveItemsIndex).hasMenuConditionalNavigationItemRenderer())) {
                            saveItemsIndex++;
                        }

                        // The commands of the save items of the classic layout.
                        final String videoId = lockupViewModel.getContentId();
                        var saveToWatchLater = PlaylistQueueRenderer.MenuServiceItem.newBuilder()
                                .setText(PlaylistQueueRenderer.Text.newBuilder()
                                        .setSimpleText(SAVE_TO_WATCH_LATER.toString()))
                                .setIcon(Icon.newBuilder().setYtIconType(YTIconType.ADD_TO_WATCH_LATER))
                                .setServiceEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                                        .setPlaylistEditEndpoint(PlaylistQueueRenderer.PlaylistEditEndpoint.newBuilder()
                                                .setPlaylistId(WATCH_LATER_PLAYLIST_ID)
                                                .addActions(PlaylistQueueRenderer.PlaylistEditAction.newBuilder()
                                                        .setAction(ACTION_ADD_VIDEO)
                                                        .setAddedVideoId(videoId))));
                        var saveToPlaylist = PlaylistQueueRenderer.MenuNavigationItem.newBuilder()
                                .setText(PlaylistQueueRenderer.Text.newBuilder()
                                        .setSimpleText(SAVE_TO_PLAYLIST.toString()))
                                .setIcon(Icon.newBuilder().setYtIconType(YTIconType.BOOKMARK_BORDER))
                                .setNavigationEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                                        .setAddToPlaylistEndpoint(PlaylistQueueRenderer.AddToPlaylistEndpoint.newBuilder()
                                                .setVideoId(videoId)));
                        menu = menu.toBuilder()
                                .addItems(saveItemsIndex, PlaylistQueueRenderer.MenuItem.newBuilder()
                                        .setMenuServiceItemRenderer(saveToWatchLater))
                                .addItems(saveItemsIndex + 1, PlaylistQueueRenderer.MenuItem.newBuilder()
                                        .setMenuNavigationItemRenderer(saveToPlaylist))
                                .build();

                        menuOnTap = menuOnTap.toBuilder()
                                .setInnertubeCommand(menuOnTap.getInnertubeCommand().toBuilder()
                                        .setOpenPopupAction(openPopupAction.toBuilder()
                                                .setPopup(popup.toBuilder().setMenuRenderer(menu).build().toByteString())))
                                .build();
                    }
                    RendererContext rendererContext = lockupViewModel.getRendererContext();
                    VideoWithContextModel classic = VideoWithContextModel.newBuilder()
                            .setData(VideoWithContextData.newBuilder()
                                    .setCollectionStack(isPlaylist ? 1 : 0)
                                    .setVideoData(videoData)
                                    .setAccessibilityText(rendererContext.getAccessibilityContext().getLabel())
                                    .setMenuAccessibilityText(menuButton.getAccessibilityText())
                                    .setOnTap(rendererContext.getCommandContext().getOnTap())
                                    .setMenuOnTap(menuOnTap.toByteString())
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

                // The other cards, such as the posts, have no classic layout. The experiments of the card layout are
                // sent only to the accounts of the A/B test, so without them the card style is not drawn.
                ElementsExperiments experiments = card.getContext().getElementsExperiments();
                if (converted == null && experiments.hasCardLayoutExperiments()) {
                    converted = componentType.toBuilder()
                            .setModel(model.toBuilder().setLockupModel(card.toBuilder()
                                    .setContext(card.getContext().toBuilder()
                                            .setElementsExperiments(experiments.toBuilder().clearCardLayoutExperiments()))))
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

            addListPreDrawListener(recyclerView);

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
                    if (isCardLabel(view.getContentDescription())) {
                        // The label can be on a view inside the list item, which the padding of the card
                        // already moves in, so the outline is set on the list item, or on the column of a row of cards.
                        View item = view;
                        View column = view;
                        while (item.getParent() instanceof View parentView && !(parentView instanceof RecyclerView)) {
                            column = item;
                            item = parentView;
                        }
                        setCardOutline(isCardRow(item) ? column : item, CARD_OUTLINE);
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
                    cardBackgrounds.put(drawable, Boolean.TRUE);
                    // The theme patch changes only some exact colors, such as the background of the cards.
                    // The lowest bit of blue is not visible.
                    return color ^ 1;
                }
            }
            // The drawables are recycled for other elements.
            cardBackgrounds.remove(drawable);
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
            shelfHeaderItems.remove(view);
            dividerItemView = new WeakReference<>(view);
        } catch (Exception ex) {
            Logger.printException(() -> "setDividerItemView failure", ex);
        }
    }

    /**
     * Injection point.
     * Called with the item of the view of {@link #setDividerItemView(View)}, before the spacing is looked up.
     * The item is a shelf with the card layout if the header of its horizontal shelf or Shorts carousel
     * has the label of the shelf header, and a shelf header if its label has the marker of a shelf header.
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
            Character header = shelfHeaderElements.get(bytes);
            if (shelf == null || header == null) {
                shelf = false;
                header = 0;
                // Only the elements with a marker are parsed.
                if (shelfHeaderLabelSearch.matches(bytes)) {
                    ElementModel model = Element.parseFrom(bytes).getType().getComponentType().getModel();
                    if (model.hasHorizontalShelfModel()) {
                        shelf = endsWith(model.getHorizontalShelfModel().getData().getHeader()
                                .getAccessibilityText(), SHELF_HEADER_LABEL_MARKER);
                    } else if (model.hasShortsShelfModel()) {
                        shelf = endsWith(model.getShortsShelfModel().getHeader().getAccessibilityText(),
                                SHELF_HEADER_LABEL_MARKER);
                    } else if (model.hasShelfHeaderModel()) {
                        String label = model.getShelfHeaderModel().getData().getAccessibilityText();
                        if (endsWith(label, SHELF_HEADER_LABEL_MARKER)
                                || endsWith(label, VIDEO_SHELF_HEADER_LABEL_MARKER)) {
                            header = label.charAt(label.length() - 1);
                        }
                    }
                }
                shelfCardElements.put(bytes, shelf);
                shelfHeaderElements.put(bytes, header);
            }
            if (shelf) {
                shelfCardItems.put(view, Boolean.TRUE);
            }
            if (header != 0) {
                shelfHeaderItems.put(view, header);
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
        if (isCardRow(child)) {
            setCardOutline(child, null);
            ViewGroup row = (ViewGroup) child;
            for (int i = 0, count = row.getChildCount(); i < count; i++) {
                View column = row.getChildAt(i);
                setCardOutline(column, hasLabelMarker(column, CARD_LABEL_MARKER, 0) ? CARD_OUTLINE : null);
            }
            return;
        }

        // The columns of a recycled row of cards keep their outline.
        if (child instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                setCardOutline(group.getChildAt(i), null);
            }
        }

        // The label is on the list item, or on a view inside it.
        CardOutlineProvider outline = hasLabelMarker(child, CARD_LABEL_MARKER, 0) ? CARD_OUTLINE : null;
        // The rows of the Shorts shelf of the channel home are scaled down inside the card of the shelf,
        // with rounded corners, see onListDraw.
        if (outline == null && isShortsPivotRow(child)) {
            outline = FEED_ITEM_OUTLINE;
        }
        // The items of the feed are cards, also the ones that YouTube sends as cards and are not converted.
        if (outline == null && child.getParent() instanceof RecyclerView recyclerView
                && recyclerView.getId() == getFeedId() && !isChipBar(child)) {
            outline = FEED_ITEM_OUTLINE;
        }
        setCardOutline(child, outline);
    }

    /**
     * The grids of the watch page on large screens have a row of cards per list item, with each card
     * in a column of the row. The padding of the card moves the content inside the column,
     * so the outline is set on the columns. The label is on the column or on a view inside it.
     * A column is at most half as wide as the row, while the card of a list item that is one card
     * is only narrower than the item by its padding, which doesn't follow the density of the display
     * until the list is laid out again.
     */
    private static boolean isCardRow(View item) {
        if (!(item instanceof ViewGroup row) || isCardLabel(item.getContentDescription())) {
            return false;
        }
        for (int i = 0, count = row.getChildCount(); i < count; i++) {
            View column = row.getChildAt(i);
            final int width = column.getWidth();
            if (width > 0 && 2 * width <= item.getWidth() && hasLabelMarker(column, CARD_LABEL_MARKER, 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bar of chips of the feed, such as the sort buttons of the channel tabs, is an item of the feed
     * with the chips in a nested list, and it's not a card. The horizontal shelves also have a nested list,
     * but they are cards with a shelf header.
     */
    private static boolean isChipBar(View item) {
        return hasNestedList(item) && !isShelfHeader(item);
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
     */
    private static boolean isCardLabel(@Nullable CharSequence label) {
        return endsWith(label, CARD_LABEL_MARKER);
    }

    /**
     * The autoplay of a Short adds a player with its own rounded clip, while the list is not drawn again.
     * The clips of the Shorts and the padding of the horizontal shelves are checked before every frame of the window.
     * The listener is added when the list adds an item, so the items are checked before they are first drawn.
     */
    private static void addListPreDrawListener(RecyclerView list) {
        if (!listsWithShortsOutlineListener.containsKey(list)) {
            listsWithShortsOutlineListener.put(list, Boolean.TRUE);
            WeakReference<RecyclerView> listRef = new WeakReference<>(list);
            list.getViewTreeObserver().addOnPreDrawListener(() -> {
                // If the padding of a shelf list changes, the frame is drawn again with the new padding,
                // so the shelf is never shown with the old padding.
                boolean shelfPaddingChanged = false;
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

                        // The padding of a card, around the view with the label of the card, gives the density
                        // that the app used to lay out the cards. It changes when the density of the display
                        // changes and the list is laid out again.
                        for (int i = 0, count = current.getChildCount(); i < count; i++) {
                            if (!(current.getChildAt(i) instanceof ViewGroup item) || item.getChildCount() != 1) {
                                continue;
                            }
                            View card = item.getChildAt(0);
                            final int left = card.getLeft();
                            if (left <= 0 || !isCardLabel(card.getContentDescription())
                                    || Math.abs(card.getTop() * CARD_HORIZONTAL_PADDING_DP
                                    - left * CARD_VERTICAL_PADDING_DP) > CARD_HORIZONTAL_PADDING_DP) {
                                continue;
                            }
                            if (cardPx(CARD_HORIZONTAL_PADDING_DP) != left) {
                                cardLayoutDensity = (float) left / CARD_HORIZONTAL_PADDING_DP;
                                for (int j = 0; j < count; j++) {
                                    invalidateCardOutlines(current.getChildAt(j), 0);
                                }
                                View container = cardInlinePlayerContainer == null
                                        ? null
                                        : cardInlinePlayerContainer.get();
                                if (container != null) {
                                    container.invalidateOutline();
                                }
                                current.invalidate();
                            }
                            break;
                        }

                        // The label of a recycled card can be set after the card is laid out, so the card
                        // keeps the outline of the item it replaced, without the spacing of the cards.
                        // The label can be on a view inside the item.
                        for (int i = 0, count = current.getChildCount(); i < count; i++) {
                            View item = current.getChildAt(i);
                            if (item.getOutlineProvider() != CARD_OUTLINE
                                    && hasLabelMarker(item, CARD_LABEL_MARKER, 0)) {
                                updateCardOutline(item);
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
                                shelfPaddingChanged = true;
                                continue;
                            }

                            int[] shelfLocation = new int[2];
                            shelf.getLocationOnScreen(shelfLocation);
                            int[] listLocation = new int[2];
                            shelfList.getLocationOnScreen(listLocation);
                            final int cardInset = cardPx(CARD_HORIZONTAL_PADDING_DP + SHELF_LIST_PADDING_DP);
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
                            shelfPaddingChanged = true;
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
                return !shelfPaddingChanged;
            });
        }
    }

    /**
     * Injection point.
     * Called when a RecyclerView draws below its items.
     * The header of the Shorts shelf has the background of the cards, with the rounded top corners,
     * and the Shorts below it fill the rest with the card. The horizontal shelves are cards.
     * The background is drawn by the list, because the views of the elements reset their own background.
     */
    public static void onListDraw(RecyclerView list, Canvas canvas) {
        try {
            if (!CARD_LAYOUT || canvas == null) {
                return;
            }

            final int cornerRadius = getCardCornerRadius();
            // The rows of the shelves of the channel home are as wide as the list, so they are scaled down
            // to be inside the cards of the shelves, which have the spacing of the cards of the feed.
            // The views are drawn after the list, so the scale is set before they are drawn.
            // The rows that are not scaled in this draw are scaled back after the loop.
            List<View> scaledRows = new ArrayList<>();
            for (int i = 0, count = list.getChildCount(); i < count; i++) {
                View item = list.getChildAt(i);
                if (item.getHeight() <= 0) {
                    continue;
                }

                // The Shorts of the Shorts shelf of the channel home fill the width with the list,
                // and the card of the shelf is around them like the Shorts shelf of the feed.
                if (isShortsPivotRow(item)) {
                    final float scale = 1f - 2f * cardPx(CARD_HORIZONTAL_PADDING_DP) / item.getWidth();
                    item.setPivotX(item.getWidth() / 2f);
                    item.setPivotY(item.getHeight() / 2f);
                    item.setScaleX(scale);
                    item.setScaleY(scale);
                    setCardOutline(item, FEED_ITEM_OUTLINE);
                    scaledRows.add(item);
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
                            // The content of the videos starts at the spacing of the content of the cards, and
                            // their menu button is near the right edge of the list. The rows keep the start of
                            // their content, and are scaled down so their right edge is at the edge of the card.
                            final int horizontalPadding = cardPx(CARD_HORIZONTAL_PADDING_DP);
                            final int contentStart = cardPx(VIDEO_SHELF_HORIZONTAL_PADDING_DP + CARD_HORIZONTAL_PADDING_DP);
                            float bottom = item.getY() + item.getHeight();
                            View row = item;
                            while (row != null && isVideoShelfRow(row)) {
                                videoShelfRows.put(row, Boolean.TRUE);
                                final float scale = (float) (row.getWidth() - horizontalPadding - contentStart)
                                        / (row.getWidth() - contentStart);
                                row.setPivotX(FeedViewCountPatch.isRtl(row) ? row.getWidth() - contentStart : contentStart);
                                row.setPivotY(row.getHeight() / 2f);
                                row.setScaleX(scale);
                                row.setScaleY(scale);
                                scaledRows.add(row);
                                bottom = row.getY() + row.getHeight() * (1 + scale) / 2;
                                row = findItemBelow(list, row);
                            }

                            final float top = belowHeader ? above.getY() : item.getY();
                            final float x = item.getX();
                            canvas.drawRoundRect(x + horizontalPadding, top, x + item.getWidth() - horizontalPadding,
                                    bottom, cornerRadius, cornerRadius, getShelfPaint(list.getContext()));
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

                // The rows of cards of the grids have the cards in the columns of the row.
                if (isCardRow(item)) {
                    ViewGroup row = (ViewGroup) item;
                    final int horizontalPadding = cardPx(CARD_HORIZONTAL_PADDING_DP);
                    final int verticalPadding = cardPx(CARD_VERTICAL_PADDING_DP);
                    for (int j = 0, columns = row.getChildCount(); j < columns; j++) {
                        View column = row.getChildAt(j);
                        if (isCardWithoutBackground(column)) {
                            final float x = row.getX() + column.getX();
                            final float y = row.getY() + column.getY();
                            canvas.drawRoundRect(x + horizontalPadding, y + verticalPadding,
                                    x + column.getWidth() - horizontalPadding,
                                    y + column.getHeight() - verticalPadding,
                                    cornerRadius, cornerRadius, getShelfPaint(list.getContext()));
                        }
                    }
                    continue;
                }

                //noinspection UnnecessaryLocalVariable
                View header = item;
                final boolean shelfHeader = isShelfHeader(header);
                final boolean cardWithoutBackground = isCardWithoutBackground(item);
                if (!shelfHeader && !shelfCardItems.containsKey(item) && !cardWithoutBackground) {
                    continue;
                }

                // The horizontal shelves have the header and the videos in the same list item,
                // which is a card like the videos of the feed. The header is not mounted while
                // it is scrolled out of the list, and the item is then found by its element.
                if (cardWithoutBackground || hasNestedList(header)) {
                    setCardOutline(header, CARD_OUTLINE);
                    final float x = header.getX();
                    final float y = header.getY();
                    final int horizontalPadding = cardPx(CARD_HORIZONTAL_PADDING_DP);
                    final int verticalPadding = cardPx(CARD_VERTICAL_PADDING_DP);
                    canvas.drawRoundRect(x + horizontalPadding, y + verticalPadding,
                            x + header.getWidth() - horizontalPadding, y + header.getHeight() - verticalPadding,
                            cornerRadius, cornerRadius, getShelfPaint(list.getContext()));
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
                    final int left = shortsCardLeft > 0 ? shortsCardLeft : cardPx(CARD_HORIZONTAL_PADDING_DP);
                    final int right = shortsCardRight > 0 ? shortsCardRight : header.getWidth() - left;
                    canvas.save();
                    canvas.clipRect(x + left, top, x + right, bottom);
                    canvas.drawRoundRect(x + left, top, x + right, bottom + cornerRadius,
                            cornerRadius, cornerRadius, getShelfPaint(list.getContext()));
                    canvas.restore();
                }
            }

            for (View row : scaledShelfRows.keySet().toArray(new View[0])) {
                //noinspection EqualsBetweenInconvertibleTypes
                if (row != null && row.getParent() == list && !scaledRows.contains(row)) {
                    scaledShelfRows.remove(row);
                    row.setScaleX(1);
                    row.setScaleY(1);
                    updateCardOutline(row);
                }
            }
            for (View row : scaledRows) {
                scaledShelfRows.put(row, Boolean.TRUE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onListDraw failure", ex);
        }
    }

    private static boolean isVideoShelfRow(View item) {
        return hasLabelMarker(item, VIDEO_SHELF_ROW_LABEL_MARKER, 0);
    }

    private static boolean isVideoShelfHeader(View item) {
        Character header = shelfHeaderItems.get(item);
        return header != null ? header == VIDEO_SHELF_HEADER_LABEL_MARKER
                : hasLabelMarker(item, VIDEO_SHELF_HEADER_LABEL_MARKER, 0);
    }

    /**
     * The Shorts shelf is one card, with the header and the rows of Shorts below it.
     * Only the outer corners of the card are rounded, and the Shorts fill the card.
     *
     * @param above The list item above the first row of Shorts, or null if the item above it is not in the list.
     */
    private static void drawShortsShelfCard(RecyclerView list, @Nullable View above, @NonNull View firstRow,
                                            @Nullable Canvas canvas) {
        // The Shorts shelf header or another shelf header above the Shorts,
        // or null if the item above the Shorts is not a shelf header.
        View header = above != null && (isShelfHeader(above) || isVideoShelfHeader(above)) ? above : null;
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
        // The positions are where the rows are drawn, as the rows of the channel home are scaled down.
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        final float top = header != null
                ? header.getY()
                : firstRow.getY() + firstRow.getPivotY() * (1 - firstRow.getScaleY());
        float bottom = firstRow.getY();
        List<List<View>> rowCells = new ArrayList<>(rows.size());
        int[] rowLocation = new int[2];
        int[] cellLocation = new int[2];
        for (View row : rows) {
            // The location of a view on the screen is where it's drawn.
            row.getLocationOnScreen(rowLocation);
            final float rowLeft = row.getPivotX() * (1 - row.getScaleX());
            final float rowTop = row.getY() + row.getPivotY() * (1 - row.getScaleY());
            List<View> cells = new ArrayList<>();
            findShortsCells(row, cells, 0);
            for (View cell : cells) {
                cell.getLocationOnScreen(cellLocation);
                final int cellLeft = Math.round(rowLeft + cellLocation[0] - rowLocation[0]);
                final int cellRight = cellLeft + Math.round(cell.getWidth() * row.getScaleX());
                left = Math.min(left, Math.min(cellLeft, row.getWidth() - cellRight));
                right = Math.max(right, Math.max(cellRight, row.getWidth() - cellLeft));
                bottom = Math.max(bottom, rowTop + cellLocation[1] - rowLocation[1]
                        + cell.getHeight() * row.getScaleY());
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
        // or if the list is scrolled to the top. Otherwise, the rows above it are scrolled out of the list.
        final boolean firstRowOfShelf = above != null || !list.canScrollVertically(-1);
        for (int i = 0; i < rows.size(); i++) {
            final boolean topRow = i == 0 && firstRowOfShelf;
            final boolean bottomRow = i == rows.size() - 1;
            View row = rows.get(i);
            row.getLocationOnScreen(rowLocation);
            final float rowLeft = row.getPivotX() * (1 - row.getScaleX());
            for (View cell : rowCells.get(i)) {
                cell.getLocationOnScreen(cellLocation);
                final int cellLeft = Math.round(rowLeft + cellLocation[0] - rowLocation[0]);
                final boolean leftColumn = cellLeft <= left;
                final boolean rightColumn = cellLeft + Math.round(cell.getWidth() * row.getScaleX()) >= right;
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
        final int cornerRadius = getCardCornerRadius();
        canvas.drawRoundRect(x + left, top, x + right, bottom,
                cornerRadius, cornerRadius, getShelfPaint(list.getContext()));
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
            if (Math.abs(viewLocation[0] - itemLocation[0] - CARD_OUTLINE.horizontalPadding()) <= 1
                    && Math.abs(viewLocation[1] - itemLocation[1] - CARD_OUTLINE.verticalPadding()) <= 1
                    && Math.abs(view.getWidth() - item.getWidth() + 2 * CARD_OUTLINE.horizontalPadding()) <= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShortsRow(View item) {
        return hasLabelMarker(item, SHORTS_CELL_LABEL_MARKER, 0);
    }

    /**
     * @return If the item is a row of the Shorts shelf of the channel home, whose Shorts fill the width with the list.
     */
    private static boolean isShortsPivotRow(View item) {
        CharSequence label = findLabelMarker(item, SHORTS_CELL_LABEL_MARKER, 0);
        return label != null && label.length() > 1
                && label.charAt(label.length() - 2) == SHORTS_PIVOT_CELL_LABEL_MARKER;
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
            final int radius = getCardCornerRadius();
            outline.setRoundRect(
                    topLeft || bottomLeft ? 0 : -radius,
                    topLeft || topRight ? 0 : -radius,
                    topRight || bottomRight ? width : width + radius,
                    bottomLeft || bottomRight ? height : height + radius,
                    radius);
        }
    }

    private static boolean hasNestedList(View view) {
        return findNestedList(view, 0) != null;
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
        for (boolean skipEmptyHosts : new boolean[]{true, false}) {
            bounds[0] = Integer.MAX_VALUE;
            bounds[1] = Integer.MIN_VALUE;
            addContentBounds(item, 0, bounds, 0, skipEmptyHosts);
            // The content of a mounted item can be only in its hosts, such as images drawn by the hosts.
            if (bounds[0] < bounds[1] || !(item instanceof ViewGroup group) || group.getChildCount() == 0) {
                break;
            }
        }
        return bounds[0] < bounds[1];
    }

    /**
     * The content is in the views without children, such as the thumbnail and the text of the item.
     * A Litho host without children and texts is not content, such as an item whose content Litho did not mount yet,
     * which would move the content by the size of its padding until it's mounted, and again when Litho mounts it again.
     */
    private static void addContentBounds(View view, int left, int[] bounds, int depth, boolean skipEmptyHosts) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        if (depth < 8 && view instanceof ViewGroup group && group.getChildCount() > 0) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                addContentBounds(child, left + child.getLeft(), bounds, depth + 1, skipEmptyHosts);
            }
            return;
        }
        if (skipEmptyHosts && view instanceof ComponentHost host) {
            TextContent textContent = host.getTextContent();
            if (textContent == null || textContent.getTextItems().isEmpty()) {
                return;
            }
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
        Character header = shelfHeaderItems.get(item);
        return header != null ? header == SHELF_HEADER_LABEL_MARKER
                : hasLabelMarker(item, SHELF_HEADER_LABEL_MARKER, 0);
    }

    /**
     * The labels of the shelf header and of the Shorts are on views inside the list item.
     */
    @SuppressWarnings("SameParameterValue")
    private static boolean hasLabelMarker(View view, char marker, int depth) {
        return findLabelMarker(view, marker, depth) != null;
    }

    /**
     * @return The label of the view or of a view inside it that ends with the marker,
     *         or null if no label ends with the marker.
     */
    @Nullable
    private static CharSequence findLabelMarker(View view, char marker, int depth) {
        CharSequence label = view.getContentDescription();
        if (endsWith(label, marker)) {
            return label;
        }
        if (depth < 4 && view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                CharSequence childLabel = findLabelMarker(group.getChildAt(i), marker, depth + 1);
                if (childLabel != null) {
                    return childLabel;
                }
            }
        }
        return null;
    }

    /**
     * The cards without inline playback, such as the playlists, have no background below the thumbnail.
     * The label is on the card, or on a view inside it, depending on the server experiments of the account.
     */
    private static boolean isCardWithoutBackground(View card) {
        CharSequence label = findLabelMarker(card, CARD_LABEL_MARKER, 0);
        return label != null && label.length() > 1
                && label.charAt(label.length() - 2) == NO_INLINE_PLAYBACK_CARD_LABEL_MARKER;
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
        //noinspection SizeReplaceableByIsEmpty
        return text != null && text.length() > 0 && text.charAt(text.length() - 1) == c;
    }

    /**
     * Injection point.
     * The comment button of the cards opens the comments panel of the video over the feed,
     * the same as the comment button of the cards of the server, instead of opening the video.
     *
     * @return If the command was handled, and the video is not opened.
     */
    public static boolean openCommentsPanel(MessageLite command, @Nullable Map<?, ?> map) {
        try {
            // The app keeps only the known fields of the commands, so the button is found by its label.
            if (!ACTION_BUTTONS || map == null
                    || !(map.get(SENDER_VIEW_KEY) instanceof View senderView)
                    || !endsWith(senderView.getContentDescription(), COMMENT_BUTTON_LABEL_MARKER)) {
                return false;
            }

            byte[] watchCommand = command.toByteArray();
            List<ProtoNode> fields = ProtoNode.parse(watchCommand);
            ProtoNode videoIdField = fields == null ? null : ProtoNode.field(
                    ProtoNode.message(fields, WATCH_ENDPOINT_FIELD), WATCH_ENDPOINT_VIDEO_ID_FIELD);
            if (videoIdField == null || !videoIdField.isText()) {
                return false;
            }

            String videoId = videoIdField.getText();
            Logger.printDebug(() -> "Opening the comments panel of: " + videoId);
            // The headers of the account that is signed in now, not when the request runs.
            Map<String, String> authHeaders = AuthUtils.getRequestHeader();

            Utils.runOnBackgroundThread(() -> {
                byte[] panelCommand = null;
                try {
                    // The comments panel of the watch page has the token that loads the comments.
                    byte[] requestBody = PlaylistRoutes.videoBody(videoId);
                    HttpURLConnection connection = PlaylistRoutes.getConnection(
                            PlaylistRoutes.GET_ENGAGEMENT_PANELS, authHeaders);
                    connection.setFixedLengthStreamingMode(requestBody.length);
                    connection.getOutputStream().write(requestBody);

                    final int responseCode = connection.getResponseCode();
                    List<ProtoNode> response = responseCode == Requester.HTTP_STATUS_CODE_SUCCESS
                            ? ProtoNode.parse(Requester.parseBytes(connection))
                            : null;
                    if (response == null) {
                        Logger.printInfo(() -> "Engagement panels request failed with code: " + responseCode);
                    } else {
                        for (ProtoNode panel : response) {
                            if (panel.getFieldNumber() != WATCH_NEXT_ENGAGEMENT_PANELS_FIELD
                                    || panel.children == null) continue;

                            ProtoNode renderer = ProtoNode.field(panel.children,
                                    ENGAGEMENT_PANEL_SECTION_LIST_RENDERER_FIELD);
                            ProtoNode panelIdField = renderer == null || renderer.children == null
                                    ? null
                                    : ProtoNode.field(renderer.children, ENGAGEMENT_PANEL_PANEL_ID_FIELD);
                            if (panelIdField == null || !panelIdField.isText()
                                    || !Utils.equalsAny(panelIdField.getText(), COMMENTS_PANEL_IDS)) continue;

                            String panelId = panelIdField.getText();
                            // The panel is shown over the feed, which hides the panels of other surfaces.
                            ByteArrayOutputStream identifier = new ByteArrayOutputStream();
                            CodedOutputStream identifierOutput = CodedOutputStream.newInstance(identifier);
                            identifierOutput.writeEnum(ENGAGEMENT_PANEL_IDENTIFIER_SURFACE_FIELD,
                                    ENGAGEMENT_PANEL_SURFACE_SHORTS);
                            identifierOutput.writeString(ENGAGEMENT_PANEL_IDENTIFIER_TAG_FIELD, panelId);
                            identifierOutput.flush();

                            // The sort chips of the header are an Elements component, whose template comes with
                            // the other templates of the watch page, which the response does not have.
                            // Without its template, the component is an empty space.
                            ProtoNode subheader = ProtoNode.field(ProtoNode.message(renderer.children,
                                            ENGAGEMENT_PANEL_HEADER_FIELD, ENGAGEMENT_PANEL_TITLE_HEADER_RENDERER_FIELD),
                                    ENGAGEMENT_PANEL_TITLE_HEADER_SUBHEADER_FIELD);
                            if (subheader != null) {
                                subheader.remove();
                            }

                            // The panels of the watch page are hidden until the watch page shows them.
                            ByteArrayOutputStream shownRenderer = new ByteArrayOutputStream();
                            CodedOutputStream rendererOutput = CodedOutputStream.newInstance(shownRenderer);
                            rendererOutput.writeRawBytes(ProtoNode.write(renderer.children));
                            rendererOutput.writeEnum(ENGAGEMENT_PANEL_VISIBILITY_FIELD,
                                    ENGAGEMENT_PANEL_VISIBILITY_EXPANDED);
                            rendererOutput.writeByteArray(ENGAGEMENT_PANEL_IDENTIFIER_FIELD, identifier.toByteArray());
                            rendererOutput.flush();

                            ByteArrayOutputStream engagementPanel = new ByteArrayOutputStream();
                            CodedOutputStream panelOutput = CodedOutputStream.newInstance(engagementPanel);
                            panelOutput.writeByteArray(ENGAGEMENT_PANEL_SECTION_LIST_RENDERER_FIELD,
                                    shownRenderer.toByteArray());
                            panelOutput.flush();

                            ByteArrayOutputStream endpoint = new ByteArrayOutputStream();
                            CodedOutputStream endpointOutput = CodedOutputStream.newInstance(endpoint);
                            endpointOutput.writeString(SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_ID_FIELD, panelId);
                            endpointOutput.writeByteArray(SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_FIELD,
                                    engagementPanel.toByteArray());
                            endpointOutput.writeByteArray(SHOW_ENGAGEMENT_PANEL_ENDPOINT_IDENTIFIER_FIELD,
                                    identifier.toByteArray());
                            endpointOutput.flush();

                            ByteArrayOutputStream commandBytes = new ByteArrayOutputStream();
                            CodedOutputStream commandOutput = CodedOutputStream.newInstance(commandBytes);
                            commandOutput.writeByteArray(SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD, endpoint.toByteArray());
                            commandOutput.flush();
                            panelCommand = commandBytes.toByteArray();
                            break;
                        }
                    }
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Engagement panels request failed: " + videoId, ex);
                }

                // Without the comments panel, such as when the comments are turned off, the video is opened.
                byte[] commandToRun = panelCommand != null ? panelCommand : watchCommand;
                Utils.runOnMainThread(() -> CommandRouterPatch.executeCommand(commandToRun, new HashMap<>()));
            });
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "openCommentsPanel failure", ex);
        }
        return false;
    }

    /**
     * Injection point.
     * The commands of the comments, such as the command that opens the replies, show their panel without a surface,
     * and the app shows a panel without a surface with the panels of the watch page, which is not open.
     *
     * @return If the panel can be shown over the feed, when the feed shows the comments panel.
     */
    public static boolean isPanelWithoutSurface(@Nullable Enum<?> surface) {
        return ACTION_BUTTONS && surface != null && ENGAGEMENT_PANEL_SURFACE_UNKNOWN.equals(surface.name())
                && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    /**
     * Injection point.
     *
     * @param shownPanelId The panel that is shown over the feed, if any.
     * @return If the panel without a surface is shown over the feed, with the comments panel that the cards opened.
     */
    public static boolean showPanelOverFeed(@Nullable String shownPanelId) {
        if (shownPanelId == null || !(Utils.equalsAny(shownPanelId, COMMENTS_PANEL_IDS)
                || shownPanelId.startsWith(COMMENT_REPLIES_PANEL_ID_PREFIX))) {
            return false;
        }

        Logger.printDebug(() -> "Showing the panel over the feed, above: " + shownPanelId);
        return true;
    }

    /**
     * @return The pixels of the dp of the cards, in the density that the app used to lay out the cards.
     */
    private static int cardPx(float dp) {
        final float density = cardLayoutDensity > 0 ? cardLayoutDensity : Dim.getMetrics().density;
        return Math.round(dp * density);
    }

    private static int getCardCornerRadius() {
        return cardPx(CARD_CORNER_RADIUS_DP);
    }

    /**
     * The outlines are built again when the density of the cards changes, because the views keep their outline
     * until their size changes.
     */
    private static void invalidateCardOutlines(View view, int depth) {
        ViewOutlineProvider outline = view.getOutlineProvider();
        if (outline instanceof CardOutlineProvider || outline instanceof ShortsCellOutlineProvider) {
            view.invalidateOutline();
        }
        if (depth < 4 && view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                invalidateCardOutlines(group.getChildAt(i), depth + 1);
            }
        }
    }

    /**
     * Outline of the card, inside the padding of the card, in dp.
     */
    private static final class CardOutlineProvider extends ViewOutlineProvider {
        private final int horizontalPaddingDp;
        private final int verticalPaddingDp;

        CardOutlineProvider(int horizontalPaddingDp, int verticalPaddingDp) {
            this.horizontalPaddingDp = horizontalPaddingDp;
            this.verticalPaddingDp = verticalPaddingDp;
        }

        int horizontalPadding() {
            return cardPx(horizontalPaddingDp);
        }

        int verticalPadding() {
            return cardPx(verticalPaddingDp);
        }

        @Override
        public void getOutline(View view, Outline outline) {
            final int horizontalPadding = horizontalPadding();
            final int verticalPadding = verticalPadding();
            outline.setRoundRect(horizontalPadding, verticalPadding, view.getWidth() - horizontalPadding,
                    view.getHeight() - verticalPadding, getCardCornerRadius());
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

    /**
     * @return The command of the 'Save to Watch later' item of the menu, or null if the menu has no such item.
     */
    @Nullable
    private static PlaylistQueueRenderer.NavigationEndpoint findWatchLaterCommand(
            PlaylistQueueRenderer.MenuRenderer menu) {
        for (PlaylistQueueRenderer.MenuItem item : menu.getItemsList()) {
            PlaylistQueueRenderer.NavigationEndpoint command = item.getMenuServiceItemRenderer().getServiceEndpoint();
            if (WATCH_LATER_PLAYLIST_ID.equals(command.getPlaylistEditEndpoint().getPlaylistId())) {
                return command;
            }
        }
        return null;
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

    static TemplateConfig templateConfig(String uri) {
        return TemplateConfig.newBuilder()
                .setUriTemplateConfig(UriTemplateConfig.newBuilder().setUri(uri))
                .build();
    }

    /**
     * Keeps only the theme and capabilities subscriptions, written to the fields of the new model.
     */
    static SubscriptionConfig subscriptionConfig(SubscriptionConfig config, VideoTemplate template) {
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

    /**
     * The experiments that the templates of the cards and of the classic layout read are sent by the server
     * for the layout of the account, and some change the layout, such as one that leaves the menu button out
     * of the cards. The converted elements would get the experiments meant for the other layout, so they have
     * only the experiments of the style of the cards, which the cards turn on and the classic layout turns off.
     * Every other experiment of the cards is left out, also the ones that the server adds later.
     */
    private static ElementContext setCardExperiments(ElementContext context, boolean enabled) {
        return context.toBuilder()
                .setElementsExperiments(context.getElementsExperiments().toBuilder()
                        .setCardLayoutExperiments(CardLayoutExperiments.newBuilder()
                                .setEnableColorSampledBackground(enabled)
                                .setEnableMetadataBackground(enabled))
                        .clearLockupExperiments()
                        .clearLockupMetadataExperiments())
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
