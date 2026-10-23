/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

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
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.shared.theme.ThemeUtils;
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

    /** Folder of the saved images, inside the Pictures folder. */
    private static final String FOLDER = "YouTube";

    /** Size of the drawn icon, the same as the icons of the buttons of the post. */
    private static final int ICON_SIZE_DP = 24;
    private static final int MIN_BUTTON_DP = 36;
    private static final int MAX_BUTTON_DP = 48;
    /** Share buttons are small, which keeps other described views of the post apart. */
    private static final int MAX_SHARE_BUTTON_DP = 160;
    private static final int PRESSED_ALPHA = 0x1F;

    /** Smaller images of a post element are avatars, emojis and badges. */
    private static final int MIN_IMAGE_SIZE = 200;
    private static final int MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024;
    private static final int TIMEOUT_MS = 15_000;

    private static final int MIN_TEXT_LENGTH = 4;
    private static final int LONG_TEXT_LENGTH = 20;
    private static final int MAX_TEXTS = 2000;
    private static final int MAX_POSTS = 300;

    /** Value of a text shown by more than one post. */
    private static final String AMBIGUOUS = "";

    /** Value of a text of a post that is not used, such as a poll. */
    private static final String NOT_IMAGE_POST = "\u0000";

    private static final byte[] POST_BYTES = "post".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GGPHT_BYTES = "ggpht.com/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GOOGLE_USER_CONTENT_BYTES =
            "googleusercontent.com/".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(?:https?:)?//(?:yt\\d\\.ggpht\\.com|[a-z0-9]+\\.googleusercontent\\.com)/[^\\s\"'<>]+");
    /** Size of an image url, such as =s1080 or =w640-h480. */
    private static final Pattern IMAGE_SIZE_PATTERN = Pattern.compile("(?:^|-)[swh](\\d+)(?=-|$)");

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

    private static final int MAX_SHARE_LABEL_LENGTH = 40;

    @Nullable
    private static PostCard pressedCard;

    private record PostImage(String originalUrl, String shownUrl) {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private PostImageDownloadPatch() {
    }

    // region Elements

    private static boolean contains(byte[] bytes, byte[] search) {
        final int last = bytes.length - search.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < search.length; j++) {
                if (bytes[i + j] != search[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isPostText(String text) {
        return text.length() >= MIN_TEXT_LENGTH
                && !text.contains(".eml")
                && !text.startsWith("http");
    }

    /** Collapses the whitespace, as the shown text can join the lines of the post text. */
    private static String normalize(String text) {
        return text.replaceAll("[\\s\u200B]+", " ").trim();
    }

    /**
     * @return The start of a text cut off with an ellipsis, such as a long post text
     *         followed by Read more, or null if the text is not cut off.
     */
    @Nullable
    private static String getCutOffStart(String text) {
        int index = text.indexOf("...");
        if (index < 0) {
            index = text.indexOf('\u2026');
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
            try {
                final int value = Integer.parseInt(matcher.group(1));
                if (value > 0) {
                    size = Math.min(size, value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return size;
    }

    /**
     * Injection point.
     * <p>
     * Called when an element is parsed. The element is not changed.
     */
    public static byte[] onElementLoaded(byte[] bytes) {
        if (!ENABLED || bytes == null || !contains(bytes, POST_BYTES)
                || !(contains(bytes, GGPHT_BYTES) || contains(bytes, GOOGLE_USER_CONTENT_BYTES))) {
            return bytes;
        }
        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }
            List<ProtoNode> textNodes = ProtoNode.textNodes(root);

            String identifier = null;
            for (ProtoNode node : textNodes) {
                if (node.getText().contains(".eml")) {
                    identifier = node.getText();
                    break;
                }
            }
            final String elementIdentifier = identifier;
            if (elementIdentifier == null || !elementIdentifier.contains("post")) {
                return bytes;
            }

            getShareLabels();

            if (elementIdentifier.contains("shelf")) {
                // A shelf of many posts. Each post starts with the avatar of its channel.
                List<List<ProtoNode>> posts = new ArrayList<>();
                List<ProtoNode> current = new ArrayList<>();
                boolean currentHasImage = false;
                for (ProtoNode node : textNodes) {
                    final int kind = getUrlKind(node.getText());
                    if (kind == URL_AVATAR && currentHasImage) {
                        posts.add(current);
                        current = new ArrayList<>();
                        currentHasImage = false;
                    }
                    if (kind == URL_IMAGE) {
                        currentHasImage = true;
                    }
                    current.add(node);
                }
                if (currentHasImage) {
                    posts.add(current);
                }
                Logger.printDebug(() -> "Post shelf " + elementIdentifier + " with " + posts.size() + " posts");
                for (List<ProtoNode> post : posts) {
                    if (isImagePost(post, null)) {
                        registerPost(post, elementIdentifier);
                    } else {
                        registerNotImagePost(post);
                    }
                }
                return bytes;
            }

            if (!isImagePost(textNodes, elementIdentifier)) {
                registerNotImagePost(textNodes);
                return bytes;
            }
            registerPost(textNodes, elementIdentifier);
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
        }
        return bytes;
    }

    /** Thumbnail of a video, which an embedded video shows. */
    private static final Pattern VIDEO_THUMBNAIL_PATTERN = Pattern.compile("/vi(?:_webp)?/[A-Za-z0-9_-]{11}/");

    /**
     * Only posts of images. Polls with images and posts with an embedded video are not used,
     * as their images are not the images of the post.
     *
     * @param identifier The identifier of the element of the post, or null for a post of a shelf.
     */
    private static boolean isImagePost(List<ProtoNode> textNodes, @Nullable String identifier) {
        if (identifier != null && !identifier.startsWith("images_post")) {
            Logger.printDebug(() -> "Not an image post: " + identifier);
            return false;
        }
        for (ProtoNode node : textNodes) {
            String text = node.getText();
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

    private static final int URL_NONE = 0;
    private static final int URL_AVATAR = 1;
    private static final int URL_IMAGE = 2;

    /**
     * @return If the text has the url of an avatar or an emoji, of a post image, or none.
     */
    private static int getUrlKind(String text) {
        int kind = URL_NONE;
        Matcher matcher = IMAGE_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String options = getOptions(matcher.group());
            if (isAvatarOptions(options)) {
                if (kind == URL_NONE) {
                    kind = URL_AVATAR;
                }
            } else if (getImageSize(options) >= MIN_IMAGE_SIZE) {
                return URL_IMAGE;
            }
        }
        return kind;
    }

    /** Avatars and emojis are cropped to a circle or to a square without a border. */
    private static boolean isAvatarOptions(String options) {
        return options.contains("-k-") || options.contains("no-rj");
    }

    /**
     * Maps the texts of a post that is not used, such as a poll, so its card
     * is not matched to another post that shares some of its texts.
     */
    private static void registerNotImagePost(List<ProtoNode> textNodes) {
        synchronized (textPostKeys) {
            for (ProtoNode node : textNodes) {
                String text = normalize(node.getText());
                if (!isPostText(text)) {
                    continue;
                }
                String previous = textPostKeys.get(text);
                if (previous == null) {
                    textPostKeys.put(text, NOT_IMAGE_POST);
                } else if (!previous.equals(NOT_IMAGE_POST)) {
                    textPostKeys.put(text, AMBIGUOUS);
                }
            }
        }
    }

    /**
     * Maps the texts of a post to its images.
     */
    private static void registerPost(List<ProtoNode> textNodes, String elementIdentifier) {
        // Base url -> image, in the order of the post.
        Map<String, PostImage> images = new LinkedHashMap<>();
        for (ProtoNode node : textNodes) {
            Matcher matcher = IMAGE_URL_PATTERN.matcher(node.getText());
            while (matcher.find()) {
                String url = matcher.group();
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
                final int optionsIndex = url.indexOf('=');
                String base = optionsIndex < 0 ? url : url.substring(0, optionsIndex);
                String options = getOptions(url);
                if (isAvatarOptions(options) || getImageSize(options) < MIN_IMAGE_SIZE) {
                    continue;
                }
                PostImage previous = images.get(base);
                if (previous == null || getImageSize(options)
                        > getImageSize(getOptions(previous.shownUrl()))) {
                    images.put(base, new PostImage(base + "=s0", url));
                }
            }
        }

        if (images.isEmpty()) {
            return;
        }

        String key = String.join("\n", images.keySet());
        synchronized (postImages) {
            postImages.put(key, new ArrayList<>(images.values()));
        }
        int mappedTexts = 0;
        synchronized (textPostKeys) {
            for (ProtoNode node : textNodes) {
                String text = normalize(node.getText());
                if (!isPostText(text)) {
                    continue;
                }
                String previous = textPostKeys.get(text);
                if (previous == null) {
                    textPostKeys.put(text, key);
                } else if (!previous.equals(key)) {
                    textPostKeys.put(text, AMBIGUOUS);
                }
                mappedTexts++;
            }
        }
        final int texts = mappedTexts;
        Logger.printDebug(() -> "Post element " + elementIdentifier + " with "
                + images.size() + " images and " + texts + " texts");
    }

    // endregion

    // region Cards

    private static int dp(View view, int dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    @Nullable
    private static ComponentHost findRootHost(@Nullable View view) {
        ComponentHost root = null;
        ViewParent parent = view instanceof ComponentHost ? (ViewParent) view
                : view == null ? null : view.getParent();
        while (parent instanceof ComponentHost host) {
            root = host;
            parent = host.getParent();
        }
        return root;
    }

    private static void collectTexts(ComponentHost host, List<String> texts) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null) {
                    String item = text.toString().trim();
                    if (isPostText(item) && !texts.contains(item)) {
                        texts.add(item);
                    }
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            View view = host.getChildAt(i);
            CharSequence description = view.getContentDescription();
            if (description != null) {
                String item = description.toString().trim();
                if (isPostText(item) && !texts.contains(item)) {
                    texts.add(item);
                }
            }
            if (view instanceof ComponentHost child) {
                collectTexts(child, texts);
            }
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
                if (key != null && key.equals(NOT_IMAGE_POST)) {
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

        String best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            final int score = entry.getValue();
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        return tie || bestScore < 2 ? null : best;
    }

    /**
     * @return The key of the post with a text that starts with the text, or null if none or many.
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

    /**
     * @return The texts of the app whose resource names include share, such as Share
     *         in the language of the app. The names of the texts are not known.
     */
    private static List<String> findShareTexts() {
        List<String> texts = new ArrayList<>();
        try {
            android.content.res.Resources resources = Utils.getContext().getResources();
            // Any text of the app gives the package and the type of the texts.
            final int knownId = ResourceUtils.getIdentifier(ResourceType.STRING, "morphe_post_image_download_title");
            if (knownId == 0) {
                return texts;
            }
            final int typeBase = knownId & 0xFFFF0000;
            int misses = 0;
            for (int entry = 0; entry <= 0xFFFF && misses < 1000; entry++) {
                final int id = typeBase | entry;
                String name;
                try {
                    name = resources.getResourceEntryName(id);
                } catch (android.content.res.Resources.NotFoundException ex) {
                    misses++;
                    continue;
                }
                misses = 0;
                if (!name.contains("share") || name.startsWith("morphe")) {
                    continue;
                }
                try {
                    String text = resources.getString(id).trim();
                    if (!text.isEmpty() && text.length() <= MAX_SHARE_LABEL_LENGTH && !texts.contains(text)) {
                        texts.add(text);
                    }
                } catch (Exception ignored) {
                    // A plural or a text with format arguments.
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "findShareTexts failure", ex);
        }
        return texts;
    }

    private static synchronized List<String> getShareLabels() {
        if (shareLabels == null) {
            List<String> labels = new ArrayList<>(findShareTexts());
            for (String name : new String[]{"share", "menu_share", "menu_item_share", "share_button", "accessibility_share", "accessibility_share_button"}) {
                try {
                    if (ResourceUtils.getIdentifier(ResourceType.STRING, name) != 0) {
                        String label = ResourceUtils.getString(name).trim();
                        if (!label.isEmpty() && !labels.contains(label)) {
                            labels.add(label);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            try {
                android.content.res.Resources system = android.content.res.Resources.getSystem();
                final int id = system.getIdentifier("share", "string", "android");
                if (id != 0) {
                    // Framework texts are given in the language of the app.
                    String label = Utils.getContext().getResources().getString(id).trim();
                    if (!label.isEmpty() && !labels.contains(label)) {
                        labels.add(label);
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not get the share text of the system: " + ex);
            }
            if (!labels.contains("Share")) {
                labels.add("Share");
            }
            Logger.printDebug(() -> "Share button labels: " + labels.size());
            shareLabels = labels;
        }
        return shareLabels;
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
                if (rect.width() > 0 && rect.width() <= dp(root, MAX_SHARE_BUTTON_DP)) {
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
            String key = FeedVideoCardsPatch.isInDialog(root) ? null : findPostKey(root);
            if (key == null) {
                if (card != null) {
                    card.removeButton(root);
                    cards.remove(root);
                }
                // The element of the post can be parsed after the post was laid out.
                if (findShareButton(root, root, new Rect()) != null) {
                    FeedVideoCardsPatch.scheduleRetry(root);
                }
                return;
            }
            if (card == null) {
                card = new PostCard();
                cards.put(root, card);
            }
            card.key = key;

            Rect shareRect = new Rect();
            View share = findShareButton(root, root, shareRect);
            if (share == null) {
                card.removeButton(root);
                FeedVideoCardsPatch.scheduleRetry(root);
                return;
            }
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
        final Rect touchBounds = new Rect();
        boolean buttonAdded;

        /**
         * Places the button left of the Share button if there is room for it,
         * or else after the last button of the row.
         *
         * @return If the button was placed.
         */
        boolean placeButton(ComponentHost root, View shareView, Rect share) {
            final int size = Math.max(dp(root, MIN_BUTTON_DP),
                    Math.min(dp(root, MAX_BUTTON_DP), share.height()));
            final int centerY = share.centerY();

            List<Rect> row = new ArrayList<>();
            collectRowButtons(root, root, shareView, share, row);
            int leftNeighbor = 0;
            int rowRight = share.right;
            for (Rect rect : row) {
                if (rect.right <= share.left + dp(root, 2)) {
                    leftNeighbor = Math.max(leftNeighbor, rect.right);
                }
                rowRight = Math.max(rowRight, rect.right);
            }

            final int left;
            if (share.left - leftNeighbor >= size) {
                left = share.left - size;
            } else if (root.getWidth() - rowRight >= size + dp(root, 4)) {
                left = rowRight;
            } else {
                Logger.printDebug(() -> "No room for the download button");
                return false;
            }
            touchBounds.set(left, centerY - size / 2, left + size, centerY - size / 2 + size);
            button.setBounds(touchBounds);
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
            return buttonAdded && touchBounds.contains(Math.round(x), Math.round(y));
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
            Drawable icon = getDownloadIcon();
            if (icon != null) {
                final float density = Utils.getContext().getResources().getDisplayMetrics().density;
                final int iconSize = Math.round(ICON_SIZE_DP * density);
                final int left = bounds.centerX() - iconSize / 2;
                final int top = bounds.centerY() - iconSize / 2;
                icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
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

    @Nullable
    private static Drawable getDownloadIcon() {
        if (downloadIcon == null) {
            Drawable icon = null;
            String[] names = USE_EXPERIMENTAL_ICONS
                    ? new String[]{"yt_outline_experimental_download_vd_theme_24",
                    "yt_outline_download_black_24", "morphe_post_image_download"}
                    : new String[]{"yt_outline_download_black_24", "morphe_post_image_download"};
            for (String name : names) {
                if (ResourceUtils.getIdentifier(ResourceType.DRAWABLE, name) != 0) {
                    icon = ResourceUtils.getDrawable(name);
                    if (icon != null) {
                        break;
                    }
                }
            }
            if (icon != null) {
                icon = icon.mutate();
            }
            downloadIcon = icon;
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

    /**
     * @return The image the post shows now, or all images if it is not known.
     */
    @Nullable
    private static List<PostImage> getShownImages(ComponentHost root, @Nullable List<PostImage> images) {
        if (images == null || images.size() < 2) {
            return images;
        }
        try {
            ViewGroup carousel = findImageCarousel(root, null);
            final int index = carousel == null ? -1 : getShownIndex(carousel);
            Logger.printDebug(() -> "Shown image " + index + " of " + images.size());
            if (index >= 0 && index < images.size()) {
                return List.of(images.get(index));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getShownImages failure", ex);
        }
        return images;
    }

    /**
     * @return The tallest horizontal list of the post, which is the carousel of its images
     *         and not the carousel of its comments.
     */
    @Nullable
    private static ViewGroup findImageCarousel(ViewGroup group, @Nullable ViewGroup best) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || !(child instanceof ViewGroup childGroup)) {
                continue;
            }
            if (FeedVideoCardsPatch.isHorizontalList(child)) {
                if (childGroup.getChildCount() > 0
                        && (best == null || child.getHeight() > best.getHeight())) {
                    best = childGroup;
                }
            } else {
                best = findImageCarousel(childGroup, best);
            }
        }
        return best;
    }

    /**
     * @return The position of the item of the carousel that is shown the most.
     */
    private static int getShownIndex(ViewGroup carousel) throws Exception {
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
        java.lang.reflect.Method method = View.class.getDeclaredMethod("computeHorizontalScrollOffset");
        method.setAccessible(true);
        final int offset = (int) method.invoke(carousel);
        final int position = offset + shown.getLeft() - carousel.getPaddingLeft();
        return Math.max(0, Math.round(position / (float) pitch));
    }

    private static void clearPressed() {
        if (pressedCard != null) {
            pressedCard.button.setPressed(false);
            pressedCard = null;
        }
    }

    // endregion

    // region Menu

    /**
     * Adds Download images to the menu of a post.
     *
     * @param sender The view that opened the menu.
     */
    public static int addFlyoutButton(Object flyoutPanel, int index, @Nullable View sender) {
        if (!ENABLED) {
            return index;
        }
        try {
            ComponentHost root = findRootHost(sender);
            if (root == null) {
                Logger.printDebug(() -> "Menu sender has no root host: " + sender);
                return index;
            }
            PostCard card = cards.get(root);
            String key = card != null ? card.key : findPostKey(root);
            List<PostImage> images = getImages(key);
            if (images == null) {
                return index;
            }

            Drawable icon = getDownloadIcon();
            final int nextIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    icon == null ? null : icon.getConstantState() == null
                            ? icon : icon.getConstantState().newDrawable().mutate(),
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
            Utils.showToastShort(str("morphe_post_image_download_not_found"));
            return;
        }
        Utils.showToastShort(images.size() == 1
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
            Utils.runOnMainThread(() -> {
                if (savedCount == 0) {
                    Utils.showToastShort(str("morphe_post_image_download_failed"));
                } else if (savedCount == 1) {
                    Utils.showToastShort(str("morphe_post_image_download_saved_single", FOLDER));
                } else {
                    Utils.showToastShort(str("morphe_post_image_download_saved", savedCount, FOLDER));
                }
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
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    if (output.size() > MAX_DOWNLOAD_BYTES) {
                        return null;
                    }
                }
                return output.size() == 0 ? null : output.toByteArray();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch post image: " + url + " " + ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * @return The file type of the image, found from its first bytes.
     */
    private static String[] getType(byte[] data) {
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return new String[]{"gif", "image/gif"};
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return new String[]{"webp", "image/webp"};
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return new String[]{"png", "image/png"};
        }
        return new String[]{"jpg", "image/jpeg"};
    }

    private static boolean save(byte[] data, String name) throws Exception {
        Context context = Utils.getContext();
        String[] type = getType(data);
        String fileName = name + "." + type[0];

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, type[1]);
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + FOLDER);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return false;
            }
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    resolver.delete(uri, null, null);
                    return false;
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

        // Older versions need a storage permission for the shared Pictures folder.
        File folder = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER);
        if (!folder.exists() && !folder.mkdirs()) {
            return false;
        }
        try (OutputStream output = new FileOutputStream(new File(folder, fileName))) {
            output.write(data);
        }
        return true;
    }

    // endregion
}
