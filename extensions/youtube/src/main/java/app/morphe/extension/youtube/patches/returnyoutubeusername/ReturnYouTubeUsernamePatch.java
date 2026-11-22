/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5173
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.returnyoutubeusername;

import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the channel name of comment authors and channels instead of their handle.
 * <p>
 * Comments and some other layouts include only the handle of the channel, which is shown as
 * its own text. When the text is laid out, the channel name of the handle is fetched. Until it is
 * fetched, the handle is shown, and the text is laid out again once the name is fetched.
 */
@SuppressWarnings("unused")
public final class ReturnYouTubeUsernamePatch {

    public enum DisplayFormat {
        CHANNEL_NAME,
        CHANNEL_NAME_AND_HANDLE,
        HANDLE_AND_CHANNEL_NAME
    }

    private static final boolean ENABLED = Settings.RETURN_YOUTUBE_USERNAME.get();

    private static final DisplayFormat DISPLAY_FORMAT = Settings.RETURN_YOUTUBE_USERNAME_DISPLAY_FORMAT.get();

    private static final char HANDLE_PREFIX = '@';

    /**
     * Element of a comment, such as "comment.eml-js-fe". The element of a comment thread is "comment_thread".
     */
    private static final String COMMENT_PATH = "|comment.e";

    /**
     * Element of the text of the comment author.
     */
    private static final String AUTHOR_PATH = "|author_badge.e";

    /**
     * Elements of the line below the title of the video, which starts with the handle of the channel.
     */
    private static final String VIDEO_METADATA_PATH = "video_metadata.e";
    private static final String VIDEO_SUBTITLE_PATH = "|video_subtitle.e";

    /**
     * Element of the channel of a Short in the Shorts player.
     */
    private static final String SHORTS_CHANNEL_BAR_PATH = "|reel_channel_bar.e";

    /**
     * Element of the comment shown below the video, whose text is the handle of the author
     * followed by the comment.
     */
    private static final String COMMENT_TEASER_PATH = "|comments_entry_point_teaser.e";

    static {
        if (ENABLED) {
            // Names shown before are shown right away.
            ChannelNameRequest.loadSavedNames();
        }
    }

    /**
     * Marks the handles whose channel name is not yet fetched.
     */
    private record PendingNameSpan(String handle) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return !ChannelNameRequest.isPending(handle);
        }
    }

    private ReturnYouTubeUsernamePatch() {
    }

    /**
     * Injection point.
     * <p>
     * Called when a Litho text is created or reused, on the main thread or the layout thread.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence original) {
        if (!ENABLED) {
            return original;
        }
        try {
            final int handleLength = getHandleLength(original);
            if (handleLength == 0) {
                return original;
            }
            StringBuilder path = contextInterface.patch_getPathBuilder();
            if (!(isOnlyHandle(original, handleLength) ? showsHandle(path) : showsHandleAndText(path))) {
                return original;
            }

            String handle = original.subSequence(0, handleLength).toString();
            String name = ChannelNameRequest.fetch(handle).getNow(null);
            if (name != null) {
                return replaceHandle(original, handleLength, formatName(handle, name));
            }
            if (ChannelNameRequest.isPending(handle)) {
                SpannableStringBuilder pending = new SpannableStringBuilder(original);
                pending.setSpan(new PendingNameSpan(handle), 0, pending.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return pending;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    /**
     * @return The length of the handle the text starts with, such as "@handle", or 0 if the text
     *         does not start with a handle.
     */
    private static int getHandleLength(CharSequence text) {
        final int length = text.length();
        if (length < 2 || text.charAt(0) != HANDLE_PREFIX) {
            return 0;
        }
        int handleLength = 1;
        while (handleLength < length && isHandleCharacter(text.charAt(handleLength))) {
            handleLength++;
        }
        return handleLength > 1 ? handleLength : 0;
    }

    /**
     * Handles can have letters and digits of any language, underscores, hyphens and periods.
     */
    private static boolean isHandleCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }

    /**
     * @return If the text after the handle has no words, such as only the spaces before
     *         the verified badge of the channel.
     */
    private static boolean isOnlyHandle(CharSequence text, int handleLength) {
        final int length = text.length();
        for (int i = handleLength; i < length; i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return If the path is of a text that shows only the handle of a channel.
     */
    private static boolean showsHandle(StringBuilder path) {
        return (path.indexOf(AUTHOR_PATH) >= 0 && path.indexOf(COMMENT_PATH) >= 0)
                || (path.indexOf(VIDEO_SUBTITLE_PATH) >= 0 && path.indexOf(VIDEO_METADATA_PATH) >= 0)
                || path.indexOf(SHORTS_CHANNEL_BAR_PATH) >= 0;
    }

    /**
     * @return If the path is of a text that starts with the handle of a channel, followed by other text.
     */
    private static boolean showsHandleAndText(StringBuilder path) {
        return path.indexOf(COMMENT_TEASER_PATH) >= 0;
    }

    private static String formatName(String handle, String name) {
        // A name in a right-to-left language does not reorder the handle next to it.
        String isolatedName = BidiFormatter.getInstance().unicodeWrap(name);
        return switch (DISPLAY_FORMAT) {
            case CHANNEL_NAME -> name;
            case CHANNEL_NAME_AND_HANDLE -> isolatedName + " (" + handle + ")";
            case HANDLE_AND_CHANNEL_NAME -> handle + " (" + isolatedName + ")";
        };
    }

    /**
     * @return The text with the handle replaced. The styles of the handle apply to the new text,
     *         and the rest of the text keeps its styles, such as the verified badge after the handle.
     */
    private static CharSequence replaceHandle(CharSequence text, int handleLength, String newText) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        builder.replace(0, handleLength, newText);
        return builder;
    }
}
