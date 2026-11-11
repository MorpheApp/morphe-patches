/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3966
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v7.widget.RecyclerView;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Downloads the images and GIFs of posts.
 * <p>
 * Posts are drawn by Litho, so a post has no view of its own and does not know its images.
 * The element of a post includes the urls of its images and the texts it shows. When the element
 * is parsed, its texts are mapped to its images. When a post is laid out, the texts it shows are
 * looked up, and the images most of the texts belong to are the images of the post.
 * <p>
 * A download button is drawn left of the Share button of the post, which downloads the image
 * the post shows, and a Download images item is added to the menu of the post, which downloads
 * all images of the post. Polls and posts with an embedded video are not used.
 */
@SuppressWarnings("unused")
public final class PostImageDownloadPatch {

    public static final boolean ENABLED = isPatchIncluded() && Settings.POST_IMAGE_DOWNLOAD.get();

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    private static final String DOWNLOAD_ICON = USE_EXPERIMENTAL_ICONS
            ? "yt_outline_experimental_download_vd_theme_24"
            : "yt_outline_download_black_24";

    /**
     * App texts of the Share button. The Share button of a post is described by a text
     * of the server, which is the same in the language of the app.
     */
    private static final String[] SHARE_LABEL_NAMES = {
            "reel_footer_share",
            "accessibility_send_share",
            "offline_share_button_text"
    };

    /** Folder of the saved images, inside the Pictures folder. */
    private static final String FOLDER = "YouTube";

    /** Size of the drawn icon, the same as the icons of the buttons of the post. */
    private static final int ICON_SIZE_DP = 24;
    private static final int MIN_BUTTON_DP = 36;
    private static final int MAX_BUTTON_DP = 48;
    /** Share buttons are small, which keeps other described views of the post apart. */
    private static final int MAX_SHARE_BUTTON_DP = 160;
    /** A button that ends this close to the Share button is left of it. */
    private static final int LEFT_NEIGHBOR_TOLERANCE_DP = 2;
    /** Space kept after the button, when it is placed after the last button of the row. */
    private static final int ROW_END_MARGIN_DP = 4;
    private static final int PRESSED_ALPHA = 0x1F;

    /** Smaller images of a post element are avatars, emojis and badges. */
    private static final int MIN_IMAGE_SIZE = 200;
    /** Options of an image url that ask for the image in its original size. */
    private static final String ORIGINAL_SIZE_OPTIONS = "=s0";
    private static final int MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024;
    private static final int DOWNLOAD_BUFFER_SIZE = 16 * 1024;
    private static final int TIMEOUT_MS = 15_000;

    private static final int MIN_TEXT_LENGTH = 4;
    private static final int LONG_TEXT_LENGTH = 20;
    private static final int MAX_TEXTS = 2000;
    private static final int MAX_POSTS = 300;

    /** Value of a text shown by more than one post. */
    private static final String AMBIGUOUS = "";

    /**
     * Value of a text of a post that is not used, such as a poll.
     * Never a key of post images, which are urls.
     */
    private static final String NOT_IMAGE_POST = "not_image_post";

    /**
     * Included in the identifier of the post elements.
     */
    private static final String POST = "post";
    private static final byte[] GGPHT_BYTES = "ggpht.com/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GOOGLE_USER_CONTENT_BYTES =
            "googleusercontent.com/".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(?:https?:)?//(?:yt\\d\\.ggpht\\.com|[a-z0-9]+\\.googleusercontent\\.com)/[^\\s\"'<>]+");
    /** Size of an image url, such as =s1080 or =w640-h480. */
    private static final Pattern IMAGE_SIZE_PATTERN = Pattern.compile("(?:^|-)[swh](\\d{1,5})(?=-|$)");
    /** Thumbnail of a video, which an embedded video shows. */
    private static final Pattern VIDEO_THUMBNAIL_PATTERN = Pattern.compile("/vi(?:_webp)?/[A-Za-z0-9_-]{11}/");
    /** The shown text can join the lines of the post text, or show invisible format characters. */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s\\p{Cf}]+");

    /** Text -> key of the post images, or {@link #AMBIGUOUS}. Oldest texts are removed first. */
    @GuardedBy("itself")
    private static final Map<String, String> textPostKeys = Utils
            .createSizeRestrictedMap(MAX_TEXTS, true);

    /** Key of the post images -> images of the post. */
    @GuardedBy("itself")
    private static final Map<String, List<PostImage>> postImages = Utils
            .createSizeRestrictedMap(MAX_POSTS, true);

    /** Root host -> the post it shows. Used only on the main thread. */
    private static final Map<ComponentHost, PostCard> cards = new WeakHashMap<>();

    @Nullable
    private static Drawable downloadIcon;
    @Nullable
    private static List<String> shareLabels;
    @Nullable
    private static PostCard pressedCard;

    /**
     * @param shownSize Size of the shown image, from the options of its url.
     */
    private record PostImage(String originalUrl, String shownUrl, int shownSize) {
    }

    private enum UrlKind {
        NONE,
        /** An avatar or an emoji. */
        AVATAR,
        IMAGE
    }

    private enum ImageType {
        GIF("gif", "image/gif"),
        WEBP("webp", "image/webp"),
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg");

        final String extension;
        final String mimeType;

        ImageType(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private PostImageDownloadPatch() {
    }

    // region Elements

    private static boolean isPostText(String text) {
        return (text.length() >= MIN_TEXT_LENGTH || isEmojiText(text))
                && !text.contains(".eml")
                && !text.startsWith("http");
    }

    /**
     * @return If the text has only emojis or symbols, such as the text of a post that is a single emoji.
     *         Short texts with letters or numbers, such as counts, are shown by many posts.
     */
    private static boolean isEmojiText(String text) {
        boolean hasSymbol = false;
        for (int i = 0, length = text.length(); i < length; ) {
            final int codePoint = text.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) {
                return false;
            }
            if (Character.getType(codePoint) == Character.OTHER_SYMBOL) {
                hasSymbol = true;
            }
            i += Character.charCount(codePoint);
        }
        return hasSymbol;
    }

    private static String normalize(String text) {
        return WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    /**
     * @return The start of a text cut off with an ellipsis, such as a long post text
     *         followed by Read more, or null if the text is not cut off.
     */
    @Nullable
    private static String getCutOffStart(String text) {
        int index = text.indexOf("...");
        if (index < 0) {
            index = text.indexOf('…');
        }
        return index >= LONG_TEXT_LENGTH ? normalize(text.substring(0, index)) : null;
    }

    private static String getOptions(String url) {
        final int optionsIndex = url.indexOf('=');
        return optionsIndex < 0 ? "" : url.substring(optionsIndex + 1);
    }

    /**
     * @return The size the url asks for, or {@link Integer#MAX_VALUE} if it does not ask for one.
     */
    private static int getImageSize(String options) {
        int size = Integer.MAX_VALUE;
        Matcher matcher = IMAGE_SIZE_PATTERN.matcher(options);
        while (matcher.find()) {
            //noinspection ConstantConditions
            final int value = Integer.parseInt(matcher.group(1));
            if (value > 0) {
                size = Math.min(size, value);
            }
        }
        return size;
    }

    /** Avatars and emojis are cropped to a circle or to a square without a border. */
    private static boolean isAvatarOptions(String options) {
        return options.contains("-k-") || options.contains("no-rj");
    }

    private static UrlKind getUrlKind(String text) {
        UrlKind kind = UrlKind.NONE;
        Matcher matcher = IMAGE_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String options = getOptions(matcher.group());
            if (isAvatarOptions(options)) {
                kind = UrlKind.AVATAR;
            } else if (getImageSize(options) >= MIN_IMAGE_SIZE) {
                return UrlKind.IMAGE;
            }
        }
        return kind;
    }

    /**
     * Injection point.
     * <p>
     * Called when an element is parsed. The element is not changed.
     */
    public static byte[] onElementLoaded(byte[] bytes) {
        if (!ENABLED || bytes == null || !FeedVideoCardsPatch.emlTextMayContain(bytes, POST)
                || !(FeedVideoCardsPatch.contains(bytes, GGPHT_BYTES)
                || FeedVideoCardsPatch.contains(bytes, GOOGLE_USER_CONTENT_BYTES))) {
            return bytes;
        }
        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }
            // Post texts with line breaks are not parsed as text fields.
            List<String> texts = ProtoNode.texts(root);

            String identifier = null;
            for (String text : texts) {
                if (text.contains(".eml")) {
                    identifier = text;
                    break;
                }
            }
            final String elementIdentifier = identifier;
            if (elementIdentifier == null || !elementIdentifier.contains(POST)) {
                return bytes;
            }

            if (!elementIdentifier.contains("shelf")) {
                registerPost(texts, elementIdentifier, isImagePost(texts, elementIdentifier));
                return bytes;
            }

            // A shelf of many posts. Each post starts with the avatar of its channel.
            List<List<String>> posts = new ArrayList<>();
            List<String> current = new ArrayList<>();
            boolean currentHasImage = false;
            for (String text : texts) {
                final UrlKind kind = getUrlKind(text);
                if (kind == UrlKind.AVATAR && currentHasImage) {
                    posts.add(current);
                    current = new ArrayList<>();
                    currentHasImage = false;
                } else if (kind == UrlKind.IMAGE) {
                    currentHasImage = true;
                }
                current.add(text);
            }
            if (currentHasImage) {
                posts.add(current);
            }
            Logger.printDebug(() -> "Post shelf " + elementIdentifier + " with " + posts.size() + " posts");
            for (List<String> post : posts) {
                registerPost(post, elementIdentifier, isImagePost(post, null));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
        }
        return bytes;
    }

    /**
     * Only posts of images. Polls with images and posts with an embedded video are not used,
     * as their images are not the images of the post.
     *
     * @param identifier The identifier of the element of the post, or null for a post of a shelf.
     */
    private static boolean isImagePost(List<String> texts, @Nullable String identifier) {
        if (identifier != null && !identifier.startsWith("images_post")) {
            Logger.printDebug(() -> "Not an image post: " + identifier);
            return false;
        }
        for (String text : texts) {
            if (text.contains(".eml") && (text.contains("options_post") || text.contains("poll_post")
                    || text.contains("videos_post") || text.contains("shared_post"))) {
                Logger.printDebug(() -> "Not an image post: " + text);
                return false;
            }
            if (text.contains("/vi") && VIDEO_THUMBNAIL_PATTERN.matcher(text).find()) {
                Logger.printDebug(() -> "Post with an embedded video: " + identifier);
                return false;
            }
        }
        return true;
    }

    /**
     * Maps the texts of a post to its images. The texts of a post that is not used, such as
     * a poll, are mapped too, so its card is not matched to another post that shares some
     * of its texts.
     */
    private static void registerPost(List<String> texts, String elementIdentifier,
                                     boolean isImagePost) {
        String key = NOT_IMAGE_POST;
        if (isImagePost) {
            Map<String, PostImage> images = findImages(texts);
            if (images.isEmpty()) {
                return;
            }
            key = String.join("\n", images.keySet());
            synchronized (postImages) {
                postImages.put(key, new ArrayList<>(images.values()));
            }
            final int imageCount = images.size();
            Logger.printDebug(() -> "Post element " + elementIdentifier + " with " + imageCount + " images");
        }

        synchronized (textPostKeys) {
            for (String postText : texts) {
                String text = normalize(postText);
                if (!isPostText(text)) {
                    continue;
                }
                String previous = textPostKeys.get(text);
                if (previous == null) {
                    textPostKeys.put(text, key);
                } else if (!previous.equals(key)) {
                    textPostKeys.put(text, AMBIGUOUS);
                }
            }
        }
    }

    /**
     * @return Base url -> image, in the order of the post.
     */
    private static Map<String, PostImage> findImages(List<String> texts) {
        Map<String, PostImage> images = new LinkedHashMap<>();
        for (String text : texts) {
            Matcher matcher = IMAGE_URL_PATTERN.matcher(text);
            while (matcher.find()) {
                String url = matcher.group();
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
                final int optionsIndex = url.indexOf('=');
                String base = optionsIndex < 0 ? url : url.substring(0, optionsIndex);
                String options = optionsIndex < 0 ? "" : url.substring(optionsIndex + 1);
                if (isAvatarOptions(options)) {
                    continue;
                }
                final int size = getImageSize(options);
                if (size < MIN_IMAGE_SIZE) {
                    continue;
                }
                // The largest shown size of each image.
                PostImage previous = images.get(base);
                if (previous == null || size > previous.shownSize()) {
                    images.put(base, new PostImage(base + ORIGINAL_SIZE_OPTIONS, url, size));
                }
            }
        }
        return images;
    }

    // endregion

    // region Cards

    private static void collectTexts(ComponentHost host, List<String> texts) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null) {
                    addPostText(texts, text);
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            View view = host.getChildAt(i);
            CharSequence description = view.getContentDescription();
            if (description != null) {
                addPostText(texts, description);
            }
            if (view instanceof ComponentHost child) {
                collectTexts(child, texts);
            }
        }
    }

    private static void addPostText(List<String> texts, CharSequence text) {
        String item = text.toString().trim();
        if (isPostText(item) && !texts.contains(item)) {
            texts.add(item);
        }
    }

    /**
     * @return The key of the images of the post the root host shows, or null if it is not a post.
     */
    @Nullable
    private static String findPostKey(ComponentHost root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        if (texts.isEmpty()) {
            return null;
        }

        Map<String, Integer> scores = new HashMap<>();
        synchronized (textPostKeys) {
            for (String shown : texts) {
                String text = normalize(shown);
                String key = textPostKeys.get(text);
                if (key == null) {
                    // A long post text is cut off and shows only its start,
                    // or is shown with other line breaks or links.
                    String start = getCutOffStart(shown);
                    if (start != null) {
                        key = findKeyByStart(start);
                    } else if (text.length() >= LONG_TEXT_LENGTH) {
                        key = findKeyByStart(text);
                    }
                }
                if (NOT_IMAGE_POST.equals(key)) {
                    if (text.length() >= LONG_TEXT_LENGTH) {
                        // The card shows a post that is not used, such as a poll.
                        return null;
                    }
                    continue;
                }
                if (key == null || key.equals(AMBIGUOUS)) {
                    continue;
                }
                final int score = text.length() >= LONG_TEXT_LENGTH ? 2 : 1;
                scores.merge(key, score, Integer::sum);
            }
        }
        return FeedVideoCardsPatch.findBestMatch(scores);
    }

    /**
     * @return The key of the post with a text that starts with the text,
     *         {@link #AMBIGUOUS} if many posts do, or null if none.
     */
    @GuardedBy("textPostKeys")
    @Nullable
    private static String findKeyByStart(String start) {
        String found = null;
        for (Map.Entry<String, String> entry : textPostKeys.entrySet()) {
            String key = entry.getValue();
            String text = entry.getKey();
            if (key.equals(AMBIGUOUS) || !(text.startsWith(start)
                    || (text.length() >= LONG_TEXT_LENGTH && start.startsWith(text)))) {
                continue;
            }
            if (found != null && !found.equals(key)) {
                return AMBIGUOUS;
            }
            found = key;
        }
        return found;
    }

    @Nullable
    private static List<PostImage> getImages(@Nullable String key) {
        if (key == null) {
            return null;
        }
        synchronized (postImages) {
            List<PostImage> images = postImages.get(key);
            return images == null || images.isEmpty() ? null : new ArrayList<>(images);
        }
    }

    private static List<String> getShareLabels() {
        if (shareLabels == null) {
            List<String> labels = new ArrayList<>();
            for (String name : SHARE_LABEL_NAMES) {
                addShareLabel(labels, ResourceUtils.getString(name));
            }
            // Framework texts are given in the language of the app.
            final int systemShareId = ResourceUtils.getSystemStringIdentifier("share");
            if (systemShareId != 0) {
                addShareLabel(labels, Utils.getContext().getString(systemShareId));
            }
            Logger.printDebug(() -> "Share button labels: " + labels);
            shareLabels = labels;
        }
        return shareLabels;
    }

    private static void addShareLabel(List<String> labels, @Nullable String label) {
        if (label == null) {
            return;
        }
        label = label.trim();
        if (!label.isEmpty() && !labels.contains(label)) {
            labels.add(label);
        }
    }

    private static boolean isShareDescription(CharSequence description) {
        String text = description.toString().trim();
        for (String label : getShareLabels()) {
            if (text.equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The Share button of the post, or null if it is not shown.
     */
    @Nullable
    private static View findShareButton(ComponentHost root, ViewGroup group, Rect rect) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            CharSequence description = child.getContentDescription();
            if (description != null && isShareDescription(description)) {
                rect.set(0, 0, child.getWidth(), child.getHeight());
                root.offsetDescendantRectToMyCoords(child, rect);
                if (rect.width() > 0 && rect.width() <= Dim.dp(MAX_SHARE_BUTTON_DP)) {
                    return child;
                }
            }
            if (child instanceof ComponentHost host) {
                View found = findShareButton(root, host, rect);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Collects the bounds of the other buttons in the row of the Share button,
     * such as the like and comment buttons.
     */
    private static void collectRowButtons(ComponentHost root, ViewGroup group, View share,
                                          Rect shareRect, List<Rect> row) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child == share) {
                continue;
            }
            if (child.getContentDescription() != null) {
                Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
                root.offsetDescendantRectToMyCoords(child, rect);
                if (rect.width() < root.getWidth() / 2
                        && rect.centerY() >= shareRect.top && rect.centerY() <= shareRect.bottom) {
                    row.add(rect);
                }
            }
            if (child instanceof ComponentHost host) {
                collectRowButtons(root, host, share, shareRect, row);
            }
        }
    }

    /**
     * Called by {@link FeedVideoCardsPatch} once a root host was laid out.
     * Must be called on the main thread.
     */
    public static void onRootLaidOut(ComponentHost root) {
        if (!ENABLED) {
            return;
        }
        try {
            PostCard card = cards.get(root);
            // Most root hosts are not posts and have no Share button,
            // so the texts are only looked up for the ones that do.
            Rect shareRect = new Rect();
            View share = FeedVideoCardsPatch.isInDialog(root) ? null : findShareButton(root, root, shareRect);
            String key = share == null ? null : findPostKey(root);
            if (key == null) {
                if (card != null) {
                    card.removeButton(root);
                    cards.remove(root);
                }
                if (share != null) {
                    // The element of the post can be parsed after the post was laid out.
                    FeedVideoCardsPatch.scheduleRetry(root);
                }
                return;
            }
            if (card == null) {
                card = new PostCard();
                cards.put(root, card);
            }
            card.key = key;
            if (!card.placeButton(root, share, shareRect)) {
                card.removeButton(root);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onRootLaidOut failure", ex);
        }
    }

    private static final class PostCard {
        String key;
        final DownloadButton button = new DownloadButton();
        boolean buttonAdded;

        /**
         * Places the button left of the Share button if there is room for it,
         * or else after the last button of the row.
         *
         * @return If the button was placed.
         */
        boolean placeButton(ComponentHost root, View shareView, Rect share) {
            final int size = Math.max(Dim.dp(MIN_BUTTON_DP), Math.min(Dim.dp(MAX_BUTTON_DP), share.height()));

            List<Rect> row = new ArrayList<>();
            collectRowButtons(root, root, shareView, share, row);
            int leftNeighbor = 0;
            int rowRight = share.right;
            for (Rect rect : row) {
                if (rect.right <= share.left + Dim.dp(LEFT_NEIGHBOR_TOLERANCE_DP)) {
                    leftNeighbor = Math.max(leftNeighbor, rect.right);
                }
                rowRight = Math.max(rowRight, rect.right);
            }

            final int left;
            if (share.left - leftNeighbor >= size) {
                left = share.left - size;
            } else if (root.getWidth() - rowRight >= size + Dim.dp(ROW_END_MARGIN_DP)) {
                left = rowRight;
            } else {
                Logger.printDebug(() -> "No room for the download button");
                return false;
            }
            final int top = share.centerY() - size / 2;
            button.setBounds(left, top, left + size, top + size);
            if (!buttonAdded) {
                root.getOverlay().add(button);
                buttonAdded = true;
            }
            button.invalidateSelf();
            return true;
        }

        void removeButton(ComponentHost root) {
            if (buttonAdded) {
                root.getOverlay().remove(button);
                buttonAdded = false;
            }
        }

        boolean isHit(float x, float y) {
            return buttonAdded && button.getBounds().contains(Math.round(x), Math.round(y));
        }
    }

    /**
     * Download icon of the app, drawn like the other buttons of the post.
     */
    private static final class DownloadButton extends Drawable {
        private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean pressed;

        void setPressed(boolean pressed) {
            if (this.pressed != pressed) {
                this.pressed = pressed;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            final int color = ThemeUtils.getAppForegroundColor();
            if (pressed) {
                pressedPaint.setColor(color);
                pressedPaint.setAlpha(PRESSED_ALPHA);
                canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(),
                        bounds.width() / 2f, pressedPaint);
            }
            Drawable icon = getDownloadIcon(color);
            if (icon != null) {
                final int iconSize = Dim.dp(ICON_SIZE_DP);
                final int left = bounds.centerX() - iconSize / 2;
                final int top = bounds.centerY() - iconSize / 2;
                icon.setBounds(left, top, left + iconSize, top + iconSize);
                icon.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static int downloadIconColor;

    /**
     * @return The download icon of the buttons drawn on posts, in the color.
     */
    @Nullable
    private static Drawable getDownloadIcon(int color) {
        if (downloadIcon == null) {
            Drawable icon = ResourceUtils.getDrawable(DOWNLOAD_ICON);
            if (icon == null) {
                return null;
            }
            downloadIcon = icon.mutate();
            downloadIconColor = ~color;
        }
        if (downloadIconColor != color) {
            downloadIconColor = color;
            downloadIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
        return downloadIcon;
    }

    /**
     * Injection point.
     *
     * @return If the touch was handled and must not be given to the children.
     */
    public static boolean onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (!ENABLED || host.getParent() instanceof ComponentHost) {
            return false;
        }
        try {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                clearPressed();
                PostCard card = cards.get(host);
                if (card == null || !card.isHit(event.getX(), event.getY())) {
                    return false;
                }
                pressedCard = card;
                card.button.setPressed(true);
                return true;
            }

            PostCard card = pressedCard;
            if (card == null || cards.get(host) != card) {
                return false;
            }
            switch (action) {
                case MotionEvent.ACTION_MOVE -> card.button.setPressed(card.isHit(event.getX(), event.getY()));
                case MotionEvent.ACTION_UP -> {
                    final boolean hit = card.isHit(event.getX(), event.getY());
                    clearPressed();
                    if (hit) {
                        host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        download(getShownImages(host, getImages(card.key)));
                    }
                }
                case MotionEvent.ACTION_CANCEL -> clearPressed();
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostTouch failure", ex);
            clearPressed();
        }
        return false;
    }

    private static void clearPressed() {
        if (pressedCard != null) {
            pressedCard.button.setPressed(false);
            pressedCard = null;
        }
    }

    /**
     * @return The image the post shows now, or all images if it is not known.
     */
    @Nullable
    private static List<PostImage> getShownImages(ComponentHost root, @Nullable List<PostImage> images) {
        if (images == null || images.size() < 2) {
            return images;
        }
        View carousel = findImageCarousel(root, null);
        final int index = carousel instanceof RecyclerView list ? getShownIndex(list) : -1;
        Logger.printDebug(() -> "Shown image " + index + " of " + images.size());
        return index >= 0 && index < images.size() ? List.of(images.get(index)) : images;
    }

    /**
     * @return The tallest horizontal list of the post, which is the carousel of its images
     *         and not the carousel of its comments.
     */
    @Nullable
    private static View findImageCarousel(ViewGroup group, @Nullable View best) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || !(child instanceof ViewGroup childGroup)) {
                continue;
            }
            if (FeedVideoCardsPatch.isHorizontalList(child)) {
                if (childGroup.getChildCount() > 0
                        && (best == null || child.getHeight() > best.getHeight())) {
                    best = child;
                }
            } else {
                best = findImageCarousel(childGroup, best);
            }
        }
        return best;
    }

    /**
     * @return The position of the item of the carousel that is shown the most, or -1 if none.
     */
    private static int getShownIndex(RecyclerView carousel) {
        View shown = null;
        int shownWidth = 0;
        int firstLeft = Integer.MAX_VALUE;
        int secondLeft = Integer.MAX_VALUE;
        for (int i = 0, count = carousel.getChildCount(); i < count; i++) {
            View child = carousel.getChildAt(i);
            final int left = child.getLeft();
            if (left < firstLeft) {
                secondLeft = firstLeft;
                firstLeft = left;
            } else if (left < secondLeft) {
                secondLeft = left;
            }
            final int visible = Math.min(child.getRight(), carousel.getWidth()) - Math.max(left, 0);
            if (visible > shownWidth) {
                shownWidth = visible;
                shown = child;
            }
        }
        if (shown == null || shown.getWidth() <= 0) {
            return -1;
        }
        final int pitch = secondLeft != Integer.MAX_VALUE && secondLeft > firstLeft
                ? secondLeft - firstLeft
                : shown.getWidth();

        // The scroll offset is the distance of the start of the list to its first item,
        // and is the same for any adapter.
        final int position = carousel.computeHorizontalScrollOffset() + shown.getLeft()
                - carousel.getPaddingLeft();
        return Math.max(0, Math.round(position / (float) pitch));
    }

    // endregion

    // region Menu

    /**
     * Adds Download images to the menu of a post.
     *
     * @param sender The view that opened the menu.
     */
    public static int addFlyoutButton(Object flyoutPanel, int index, @Nullable View sender) {
        if (!ENABLED || sender == null) {
            return index;
        }
        try {
            ComponentHost root = FeedVideoCardsPatch.findTappedRootHost(sender);
            if (root == null) {
                Logger.printDebug(() -> "Menu sender has no root host: " + sender);
                return index;
            }
            PostCard card = cards.get(root);
            List<PostImage> images = getImages(card != null ? card.key : findPostKey(root));
            if (images == null) {
                return index;
            }

            final int nextIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable(DOWNLOAD_ICON),
                    str(images.size() == 1
                            ? "morphe_post_image_download_flyout_single"
                            : "morphe_post_image_download_flyout"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        download(images);
                    },
                    index
            );
            return nextIndex < 0 ? index : nextIndex;
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
        }
        return index;
    }

    // endregion

    // region Download

    private static void download(@Nullable List<PostImage> images) {
        if (images == null || images.isEmpty()) {
            Utils.showSnackbarShort(str("morphe_post_image_download_not_found"));
            return;
        }
        Utils.showSnackbarShort(images.size() == 1
                ? str("morphe_post_image_download_started_single")
                : str("morphe_post_image_download_started", images.size()));

        Utils.runOnBackgroundThread(() -> {
            final long time = System.currentTimeMillis();
            int saved = 0;
            for (int i = 0; i < images.size(); i++) {
                PostImage image = images.get(i);
                try {
                    byte[] data = fetch(image.originalUrl());
                    if (data == null) {
                        data = fetch(image.shownUrl());
                    }
                    if (data == null) {
                        continue;
                    }
                    String name = images.size() == 1
                            ? "YouTube_post_" + time
                            : String.format(Locale.US, "YouTube_post_%d_%d", time, i + 1);
                    if (save(data, name)) {
                        saved++;
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not download post image: " + image.originalUrl(), ex);
                }
            }

            final int savedCount = saved;
            final String folder = getSaveFolderName();
            Utils.runOnMainThread(() -> {
                String toast;
                if (savedCount == 0) {
                    toast = str("morphe_post_image_download_failed");
                } else if (savedCount == 1) {
                    toast = str("morphe_post_image_download_saved_single", folder);
                } else {
                    toast = str("morphe_post_image_download_saved", savedCount, folder);
                }
                Utils.showSnackbarShort(toast);
            });
        });
    }

    @Nullable
    private static byte[] fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Logger.printDebug(() -> "Post image response " + code + ": " + url);
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    if (output.size() > MAX_DOWNLOAD_BYTES) {
                        Logger.printDebug(() -> "Post image is too large: " + url);
                        return null;
                    }
                }
                return output.size() == 0 ? null : output.toByteArray();
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not fetch post image: " + url, ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * @return The type of the image, found from its first bytes.
     */
    private static ImageType getType(byte[] data) {
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return ImageType.GIF;
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return ImageType.WEBP;
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return ImageType.PNG;
        }
        return ImageType.JPEG;
    }

    /**
     * Before Android 10, the shared Pictures folder needs a storage permission,
     * so the images are saved to the Pictures folder of the app instead.
     */
    private static File getLegacySaveFolder() {
        return new File(Utils.getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER);
    }

    /**
     * @return The folder the images are saved to, as shown to the user.
     */
    private static String getSaveFolderName() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? Environment.DIRECTORY_PICTURES + File.separator + FOLDER
                : getLegacySaveFolder().getPath();
    }

    private static boolean save(byte[] data, String name) throws Exception {
        ImageType type = getType(data);
        String fileName = name + '.' + type.extension;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File folder = getLegacySaveFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                Logger.printInfo(() -> "Could not create folder: " + folder);
                return false;
            }
            try (OutputStream output = new FileOutputStream(new File(folder, fileName))) {
                output.write(data);
            }
            return true;
        }

        Context context = Utils.getContext();
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, type.mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, getSaveFolderName());
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Logger.printInfo(() -> "Could not create the image: " + fileName);
            return false;
        }
        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("No output stream for: " + uri);
            }
            output.write(data);
        } catch (Exception ex) {
            resolver.delete(uri, null, null);
            throw ex;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return true;
    }

    // endregion
}
