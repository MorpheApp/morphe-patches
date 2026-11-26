/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.Comment;
import com.reddit.domain.model.IComment;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class AutoModeratorCommentsPatch {

    public enum AutoModeratorComments {
        SHOW,
        COLLAPSE,
        HIDE
    }

    private static final String AUTO_MODERATOR = "AutoModerator";

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Called with the comments loaded for a post, in tree order, before Reddit builds the comment tree.
     */
    public static List<?> filterComments(List<?> comments) {
        AutoModeratorComments setting = Settings.AUTOMODERATOR_COMMENTS.get();
        if (setting == AutoModeratorComments.SHOW) return comments;

        try {
            if (setting == AutoModeratorComments.COLLAPSE) {
                for (Object item : comments) {
                    if (isAutoModerator(item)) {
                        ((Comment) item).patch_setCollapsed(true);
                    }
                }
                return comments;
            }

            // Hide the comments with all their replies, which follow them with a greater depth.
            List<Object> filtered = new ArrayList<>(comments.size());
            int hiddenDepth = -1;
            for (Object item : comments) {
                if (item instanceof IComment comment) {
                    final int depth = comment.getDepth();
                    if (hiddenDepth >= 0 && depth > hiddenDepth) continue;
                    hiddenDepth = -1;

                    if (isAutoModerator(item)) {
                        hiddenDepth = depth;
                        continue;
                    }
                }
                filtered.add(item);
            }

            final int hiddenCount = comments.size() - filtered.size();
            if (hiddenCount == 0) return comments;

            Logger.printDebug(() -> "Hid " + hiddenCount + " AutoModerator comments and replies");
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterComments failure", ex);
            return comments;
        }
    }

    private static boolean isAutoModerator(Object item) {
        return item instanceof Comment comment && AUTO_MODERATOR.equalsIgnoreCase(comment.getAuthor());
    }
}
