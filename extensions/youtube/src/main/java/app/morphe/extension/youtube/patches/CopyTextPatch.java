/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class CopyTextPatch {

    private static final boolean COPY_VIDEO_TITLE = Settings.COPY_VIDEO_TITLE.get();
    private static final boolean COPY_COMMENTS = Settings.COPY_COMMENTS.get();

    /**
     * The comment text is nested deeper in the comment than the time and the "Pinned by" text.
     */
    private static final Pattern COMMENT_TEXT_PATH_PATTERN =
            Pattern.compile("^(ContainerType\\|){7,}TextType\\|$");

    private static final int MAX_COMMENTS = 200;

    /**
     * Litho draws a truncated text as its start followed by an ellipsis text,
     * such as "Read more" of a collapsed comment, or nothing for a title that fades out.
     */
    private static final int MAX_ELLIPSIS_LENGTH = 40;
    private static final int MIN_TRUNCATED_TEXT_LENGTH = 20;

    /**
     * Texts of the comments laid out recently. Litho can lay out on other threads,
     * so it is only used while synchronized on itself.
     */
    private static final Map<String, Boolean> commentTexts =
            new LinkedHashMap<>(MAX_COMMENTS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_COMMENTS;
                }
            };

    /**
     * The view made long clickable, so the long click is removed again
     * when the recycled view shows something else.
     */
    private static WeakReference<View> longClickViewRef = new WeakReference<>(null);

    /**
     * Title of the watch page as drawn, which can be auto-translated
     * and then differs from the title of the video.
     */
    private static volatile String shownTitle = "";

    /**
     * Text of the host that was touched last, so the copied text is the one on the screen.
     */
    private static String touchedText = "";
    private static String touchedToastKey = "";

    private static final View.OnLongClickListener LONG_CLICK_LISTENER = view -> {
        String text = touchedText;
        if (text.isEmpty()) {
            return false;
        }

        Utils.setClipboard(text);
        Utils.showToastShort(str(touchedToastKey));
        return true;
    };

    /**
     * Injection point.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence text) {
        try {
            if (text == null || !(COPY_VIDEO_TITLE || COPY_COMMENTS)) {
                return text;
            }

            if (COPY_COMMENTS && isCommentText(contextInterface)) {
                String comment = text.toString().trim();
                if (!comment.isEmpty()) {
                    synchronized (commentTexts) {
                        commentTexts.put(comment, Boolean.TRUE);
                    }
                }
            } else if (COPY_VIDEO_TITLE && isVideoTitle(contextInterface)) {
                shownTitle = removeTitleMarker(text.toString().trim());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return text;
    }

    private static boolean isVideoTitle(ContextInterface contextInterface) {
        String identifier = contextInterface.patch_getIdentifier();
        if (identifier == null || !identifier.startsWith("video_metadata.e")) {
            return false;
        }
        // The subtitle with the channel and the views is in the same element.
        StringBuilder path = contextInterface.patch_getPathBuilder();
        return path.indexOf("video_metadata_inner.e") >= 0
                && path.indexOf("video_subtitle.e") < 0
                && Utils.endsWith(path, "|TextType|");
    }

    /**
     * Comments, replies and the comment above the replies all nest the same comment component.
     */
    private static boolean isCommentText(ContextInterface contextInterface) {
        StringBuilder path = contextInterface.patch_getPathBuilder();
        final int commentStart = path.lastIndexOf("|comment.e");
        if (commentStart < 0) {
            return false;
        }
        // Skip the name and the hash of the comment component.
        final int nameEnd = path.indexOf("|", commentStart + 1);
        final int hashEnd = nameEnd < 0 ? -1 : path.indexOf("|", nameEnd + 1);
        return hashEnd >= 0 && COMMENT_TEXT_PATH_PATTERN.matcher(path.substring(hashEnd + 1)).matches();
    }

    /**
     * Restore original titles marks the titles with invisible characters at the end,
     * and removes them only when its own text hook runs after this one.
     */
    private static String removeTitleMarker(String title) {
        int end = title.length();
        while (end > 0 && title.charAt(end - 1) >= '⁠' && title.charAt(end - 1) <= '⁤') {
            end--;
        }
        return title.substring(0, end);
    }

    /**
     * Injection point.
     */
    public static void onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (!(COPY_VIDEO_TITLE || COPY_COMMENTS) || event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return;
        }

        try {
            // Parents get the touch before their children, so a stale long click is
            // removed before the host that draws the text can add it again.
            View longClickView = longClickViewRef.get();
            if (longClickView == host) {
                longClickView.setOnLongClickListener(null);
                longClickView.setLongClickable(false);
                longClickViewRef = new WeakReference<>(null);
            }

            if (!findCopyableText(host)) {
                return;
            }

            // The text is drawn by a host that is not clickable itself,
            // and the tap is handled by a parent.
            View clickableView = findClickableView(host);
            if (clickableView == null || clickableView.isLongClickable()) {
                return;
            }

            clickableView.setOnLongClickListener(LONG_CLICK_LISTENER);
            longClickViewRef = new WeakReference<>(clickableView);
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostTouch failure", ex);
        }
    }

    private static boolean findCopyableText(ComponentHost host) {
        TextContent textContent = host.getTextContent();
        if (textContent == null) {
            return false;
        }

        String shown = shownTitle;
        // Restore original titles replaces the shown title when its text hook runs after this one.
        String title = COPY_VIDEO_TITLE ? VideoInformation.getVideoTitle().trim() : "";

        for (CharSequence text : textContent.getTextItems()) {
            if (text == null) {
                continue;
            }
            String itemText = text.toString().trim();
            if (itemText.isEmpty()) {
                continue;
            }
            if (COPY_VIDEO_TITLE) {
                String fullTitle = isShownText(itemText, shown) ? shown
                        : isShownText(itemText, title) ? title
                        : null;
                if (fullTitle != null) {
                    touchedText = fullTitle;
                    touchedToastKey = "morphe_copy_video_title_success";
                    return true;
                }
            }
            if (COPY_COMMENTS) {
                String comment = findComment(itemText);
                if (comment != null) {
                    touchedText = comment;
                    touchedToastKey = "morphe_copy_comments_success";
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return The full comment of the text, which can be a collapsed comment.
     */
    @Nullable
    private static String findComment(String text) {
        synchronized (commentTexts) {
            if (commentTexts.containsKey(text)) {
                return text;
            }
            if (text.length() < MIN_TRUNCATED_TEXT_LENGTH) {
                return null;
            }

            for (String comment : commentTexts.keySet()) {
                if (isShownText(text, comment)) {
                    return comment;
                }
            }
            return null;
        }
    }

    /**
     * @return If the shown text is the full text, or the full text truncated by Litho.
     */
    private static boolean isShownText(String shownText, String fullText) {
        if (fullText.isEmpty()) {
            return false;
        }
        if (shownText.equals(fullText)) {
            return true;
        }
        final int prefixLength = commonPrefixLength(shownText, fullText);
        return prefixLength >= MIN_TRUNCATED_TEXT_LENGTH
                && prefixLength < fullText.length()
                && shownText.length() - prefixLength <= MAX_ELLIPSIS_LENGTH;
    }

    private static int commonPrefixLength(String first, String second) {
        final int maxLength = Math.min(first.length(), second.length());
        int length = 0;
        while (length < maxLength && first.charAt(length) == second.charAt(length)) {
            length++;
        }
        return length;
    }

    @Nullable
    private static View findClickableView(View view) {
        while (true) {
            if (view.isClickable()) {
                return view;
            }
            ViewParent parent = view.getParent();
            if (!(parent instanceof ComponentHost)) {
                return null;
            }
            view = (View) parent;
        }
    }
}
