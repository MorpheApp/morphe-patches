/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class CopyTextPatch {

    private static final boolean COPY_VIDEO_TITLE = Settings.COPY_VIDEO_TITLE.get();
    private static final boolean COPY_COMMENTS = Settings.COPY_COMMENTS.get();
    private static final boolean SELECT_TEXT = Settings.COPY_SELECT_TEXT.get();
    private static final boolean ENABLED = COPY_VIDEO_TITLE || COPY_COMMENTS || SELECT_TEXT;

    /**
     * Element of the description panel, which is a list of segments: texts, and attachments
     * such as channel cards that replace a link of the description.
     */
    private static final String DESCRIPTION_IDENTIFIER = "description_rich_text_list.e";
    /**
     * Description of the description panel of a Short, which is a single text.
     */
    private static final String SHORTS_DESCRIPTION_IDENTIFIER = "cell_description_body.e";
    /**
     * Included in the identifier of the post elements.
     */
    private static final String POST = "post";
    /**
     * Shorter texts of a post are the counts and the buttons, such as "12" or "Like".
     */
    private static final int MIN_POST_TEXT_LENGTH = 8;
    private static final int MAX_SELECTABLE_TEXTS = 300;

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
     * Texts of the comments laid out recently. Litho can lay out on other threads.
     */
    @GuardedBy("itself")
    private static final Map<String, Boolean> commentTexts =
            Utils.createSizeRestrictedMap(MAX_COMMENTS, true);

    /**
     * Normalized texts of the descriptions and posts laid out recently -> the texts.
     */
    @GuardedBy("itself")
    private static final Map<String, String> selectableTexts =
            Utils.createSizeRestrictedMap(MAX_SELECTABLE_TEXTS, true);

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
    /**
     * If the touched text is shown to select a part of it, instead of copying all of it.
     */
    private static boolean touchedTextSelectable;

    private static final View.OnLongClickListener LONG_CLICK_LISTENER = view -> {
        String text = touchedText;
        if (text.isEmpty()) {
            return false;
        }

        if (touchedTextSelectable) {
            showSelectTextDialog(view.getContext(), text);
            return true;
        }

        Utils.setClipboard(text);
        Utils.showSnackbarShort(str(touchedToastKey));
        return true;
    };

    /**
     * Injection point.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence text) {
        try {
            if (text == null || !ENABLED) {
                return text;
            }

            String trimmed = text.toString().trim();
            if (trimmed.isEmpty()) {
                return text;
            }

            if (SELECT_TEXT && isSelectableText(contextInterface, trimmed)) {
                String key = normalize(trimmed);
                synchronized (selectableTexts) {
                    selectableTexts.put(key, trimmed);
                }
            } else if (COPY_COMMENTS && isCommentText(contextInterface)) {
                synchronized (commentTexts) {
                    commentTexts.put(trimmed, Boolean.TRUE);
                }
            } else if (COPY_VIDEO_TITLE && isVideoTitle(contextInterface)) {
                shownTitle = removeTitleMarker(trimmed);
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
     * @return If the text is of a description or a post, which can be selected to copy a part of it.
     */
    private static boolean isSelectableText(ContextInterface contextInterface, String text) {
        String identifier = contextInterface.patch_getIdentifier();
        if (identifier == null) {
            return false;
        }
        if (identifier.startsWith(DESCRIPTION_IDENTIFIER) || identifier.startsWith(SHORTS_DESCRIPTION_IDENTIFIER)) {
            return true;
        }
        if (text.length() < MIN_POST_TEXT_LENGTH) {
            return false;
        }
        // Only the first element of the identifier is the post itself.
        final int postIndex = identifier.indexOf(POST);
        final int elementEnd = identifier.indexOf('|');
        if (postIndex < 0 || (elementEnd >= 0 && postIndex > elementEnd)) {
            return false;
        }
        // The element of a post can include comments, such as when the post is opened.
        return contextInterface.patch_getPathBuilder().indexOf("|comment.e") < 0;
    }

    /**
     * The shown text can join the lines of the text, or show invisible format characters.
     */
    private static String normalize(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0, length = text.length(); i < length; i++) {
            final char c = text.charAt(i);
            if (Character.getType(c) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = builder.length() > 0;
                continue;
            }
            if (space) {
                builder.append(' ');
                space = false;
            }
            builder.append(c);
        }
        return builder.toString();
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
        if (!ENABLED || event.getActionMasked() != MotionEvent.ACTION_DOWN) {
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
            if (clickableView == null && touchedTextSelectable) {
                // A description is not clickable, except its links.
                clickableView = host;
            }
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
                    setTouchedText(fullTitle, "morphe_copy_video_title_success", false);
                    return true;
                }
            }
            if (COPY_COMMENTS) {
                String comment = findComment(itemText);
                if (comment != null) {
                    setTouchedText(comment, "morphe_copy_comments_success", false);
                    return true;
                }
            }
            if (SELECT_TEXT) {
                String selectable = findSelectableText(itemText);
                if (selectable != null) {
                    setTouchedText(selectable, "", true);
                    return true;
                }
            }
        }
        return false;
    }

    private static void setTouchedText(String text, String toastKey, boolean selectable) {
        touchedText = text;
        touchedToastKey = toastKey;
        touchedTextSelectable = selectable;
    }

    /**
     * @return The full text of a description or a post, which can be the start of a collapsed post.
     */
    @Nullable
    private static String findSelectableText(String text) {
        String shown = normalize(text);
        synchronized (selectableTexts) {
            String key = findShownText(shown, selectableTexts.keySet());
            return key == null ? null : selectableTexts.get(key);
        }
    }

    /**
     * Shows the text in a dialog where any part of it can be selected and copied.
     */
    private static void showSelectTextDialog(Context context, String text) {
        try {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_copy_select_text_dialog_title"), // Title.
                    text, // Message.
                    null, // No EditText.
                    str("morphe_copy_select_text_copy_all"), // OK button text.
                    () -> {
                        Utils.setClipboard(text);
                        Utils.showSnackbarShort(str("morphe_copy_select_text_success"));
                    },
                    () -> {}, // Cancel button action (dismiss only).
                    null, // No neutral button.
                    null, // No neutral button action.
                    true  // Dismiss dialog when onNeutralClick.
            );
            TextView messageView = findTextView(dialogPair.second, text);
            if (messageView != null) {
                messageView.setTextIsSelectable(true);
            }
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showSelectTextDialog failure", ex);
        }
    }

    @Nullable
    private static TextView findTextView(View view, String text) {
        if (view instanceof TextView textView && text.contentEquals(textView.getText())) {
            return textView;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                TextView found = findTextView(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * @return The full comment of the text, which can be a collapsed comment.
     */
    @Nullable
    private static String findComment(String text) {
        synchronized (commentTexts) {
            return findShownText(text, commentTexts.keySet());
        }
    }

    /**
     * @return The full text that is shown, which can be truncated by Litho, or null if none is.
     */
    @Nullable
    private static String findShownText(String shownText, Set<String> fullTexts) {
        if (fullTexts.contains(shownText)) {
            return shownText;
        }
        if (shownText.length() < MIN_TRUNCATED_TEXT_LENGTH) {
            return null;
        }

        // A text can start with another text, such as a comment that quotes a comment,
        // but the text that is shown matches for longer.
        String bestText = null;
        int bestLength = -1;
        for (String fullText : fullTexts) {
            final int length = shownLength(shownText, fullText);
            if (length > bestLength) {
                bestText = fullText;
                bestLength = length;
            }
        }
        return bestText;
    }

    private static boolean isShownText(String shownText, String fullText) {
        return shownLength(shownText, fullText) >= 0;
    }

    /**
     * @return The length of the full text that is shown, if the shown text is the full text
     *         or the full text truncated by Litho, or -1 if not.
     */
    private static int shownLength(String shownText, String fullText) {
        if (fullText.isEmpty()) {
            return -1;
        }
        if (shownText.equals(fullText)) {
            return fullText.length();
        }
        final int prefixLength = commonPrefixLength(shownText, fullText);
        return prefixLength >= MIN_TRUNCATED_TEXT_LENGTH
                && prefixLength < fullText.length()
                && shownText.length() - prefixLength <= MAX_ELLIPSIS_LENGTH
                ? prefixLength
                : -1;
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
