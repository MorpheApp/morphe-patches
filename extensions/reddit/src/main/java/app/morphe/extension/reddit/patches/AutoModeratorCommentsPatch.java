/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.AccountType;
import com.reddit.domain.model.Comment;
import com.reddit.domain.model.IComment;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class AutoModeratorCommentsPatch {

    /**
     * Added to Reddit's CommentTree class during patching.
     */
    public interface CommentTree {
        ArrayList<Object> patch_getComments();
    }

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
        if (comments == null || comments.isEmpty()) return comments;

        AutoModeratorComments setting = Settings.AUTOMODERATOR_COMMENTS.get();
        if (setting == AutoModeratorComments.SHOW) return comments;

        try {
            if (setting == AutoModeratorComments.COLLAPSE) {
                collapseTargets(comments);
                return comments;
            }

            List<Object> filtered = filterHiddenComments(comments);
            final int hiddenCount = comments.size() - filtered.size();
            if (hiddenCount == 0) return comments;

            Logger.printDebug(() -> "Hid " + hiddenCount + " target comments and replies");
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterComments failure", ex);
            return comments;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the comment trees loaded or appended for a post.
     */
    public static List<?> filterCommentTrees(List<?> trees) {
        if (trees == null || trees.isEmpty()) return trees;

        AutoModeratorComments setting = Settings.AUTOMODERATOR_COMMENTS.get();
        if (setting == AutoModeratorComments.SHOW) return trees;

        try {
            if (setting == AutoModeratorComments.COLLAPSE) {
                for (Object item : trees) {
                    if (item instanceof CommentTree tree) {
                        ArrayList<Object> comments = tree.patch_getComments();
                        if (comments != null) {
                            collapseTargets(comments);
                        }
                    }
                }
                return trees;
            }

            List<Object> filteredTrees = null;
            int hiddenCount = 0;
            for (int i = 0, size = trees.size(); i < size; i++) {
                Object item = trees.get(i);
                if (item instanceof CommentTree tree) {
                    ArrayList<Object> comments = tree.patch_getComments();
                    if (comments != null && !comments.isEmpty()) {
                        List<Object> kept = filterHiddenComments(comments);
                        final int removed = comments.size() - kept.size();
                        if (removed > 0) {
                            hiddenCount += removed;
                            if (kept.isEmpty()) {
                                if (filteredTrees == null) {
                                    filteredTrees = new ArrayList<>(trees.subList(0, i));
                                }
                                continue;
                            }
                            comments.clear();
                            comments.addAll(kept);
                        }
                    }
                }
                if (filteredTrees != null) {
                    filteredTrees.add(item);
                }
            }

            if (hiddenCount > 0) {
                final int totalHidden = hiddenCount;
                Logger.printDebug(() -> "Hid " + totalHidden + " target comments and replies from comment trees");
            }
            return filteredTrees != null ? filteredTrees : trees;
        } catch (Exception ex) {
            Logger.printException(() -> "filterCommentTrees failure", ex);
            return trees;
        }
    }

    private static void collapseTargets(List<?> comments) {
        for (Object item : comments) {
            if (isTarget(item)) {
                ((Comment) item).patch_setCollapsed(true);
            }
        }
    }

    private static List<Object> filterHiddenComments(List<?> comments) {
        // Hide the comments with all their replies, which follow them with a greater depth.
        List<Object> filtered = new ArrayList<>(comments.size());
        int hiddenDepth = -1;
        for (Object item : comments) {
            if (item instanceof IComment comment) {
                final int depth = comment.getDepth();
                if (hiddenDepth >= 0 && depth > hiddenDepth) continue;
                hiddenDepth = -1;

                if (isTarget(item)) {
                    hiddenDepth = depth;
                    continue;
                }
            }
            filtered.add(item);
        }
        return filtered;
    }

    private static boolean isTarget(Object item) {
        if (!(item instanceof Comment comment)) return false;

        return AUTO_MODERATOR.equalsIgnoreCase(comment.getAuthor())
                || AccountType.APP == comment.getAccountType()
                || comment.getStickied();
    }
}
