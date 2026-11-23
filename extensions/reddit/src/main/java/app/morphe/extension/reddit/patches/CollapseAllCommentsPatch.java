/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5217
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.view.Choreographer;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Comment;
import com.reddit.domain.model.IComment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import kotlin.jvm.functions.Function0;

/**
 * Adds a button next to the comment count of a post that collapses or expands all comments.
 */
@SuppressWarnings("unused")
public final class CollapseAllCommentsPatch {

    /**
     * Added to the comment tree store during patching.
     */
    public interface CommentTree {
        /**
         * @return Ids of the comments in the order they are shown, or null if the comments did not load yet.
         */
        @Nullable
        List<String> patch_getVisibleCommentIds();

        @Nullable
        IComment patch_getComment(String id);

        void patch_collapse(String id);

        void patch_expand(String id);
    }

    public enum CollapseAllScope {
        TOP_COMMENTS(0),
        REPLIES(1),
        NESTED_REPLIES(2);

        /**
         * Depth of the collapsed comments, relative to the top comments.
         */
        final int depth;

        CollapseAllScope(int depth) {
            this.depth = depth;
        }
    }

    private static final int ICON_CARET_UP_DOWN =
            ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "icon_caret_up_down");

    /**
     * Comment click handlers of the post page action bar.
     */
    private static final Set<Function0<?>> postCommentClicks =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final Function0<Object> COLLAPSE_ALL_CLICK = () -> {
        onCollapseAllClick();
        return null;
    };

    private static volatile WeakReference<CommentTree> commentTreeRef = new WeakReference<>(null);

    /**
     * If comments are being collapsed or expanded. Only used on the main thread.
     */
    private static boolean updatingComments;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void registerPostCommentsClick(@Nullable Function0<?> onClick) {
        if (onClick != null && Settings.COLLAPSE_ALL_COMMENTS.get()) {
            postCommentClicks.add(onClick);
        }
    }

    /**
     * Injection point.
     *
     * @param onClick Click handler of an action bar comment button.
     */
    public static boolean showCollapseAllButton(@Nullable Function0<?> onClick) {
        return Settings.COLLAPSE_ALL_COMMENTS.get() && postCommentClicks.contains(onClick);
    }

    /**
     * Injection point.
     */
    public static int getCollapseAllIcon() {
        return ICON_CARET_UP_DOWN;
    }

    /**
     * Injection point.
     */
    public static String getCollapseAllDescription() {
        return str("morphe_collapse_all_comments_button");
    }

    /**
     * Injection point.
     */
    public static Function0<Object> getCollapseAllClick() {
        return COLLAPSE_ALL_CLICK;
    }

    /**
     * Injection point.
     * <p>
     * Called before the comment tree applies an operation.
     */
    public static void setCommentTree(CommentTree tree) {
        if (Settings.COLLAPSE_ALL_COMMENTS.get() && commentTreeRef.get() != tree) {
            commentTreeRef = new WeakReference<>(tree);
        }
    }

    /**
     * Collapses the comments at the depth of the scope setting.
     * If they are all collapsed already, expands all collapsed comments instead.
     */
    private static void onCollapseAllClick() {
        try {
            if (updatingComments) return;

            CommentTree tree = commentTreeRef.get();
            if (tree == null) return;

            List<String> ids = tree.patch_getVisibleCommentIds();
            if (ids == null) return;

            List<Comment> comments = new ArrayList<>(ids.size());
            int topDepth = Integer.MAX_VALUE;
            for (String id : ids) {
                // Can also be a "more replies" item.
                if (tree.patch_getComment(id) instanceof Comment comment) {
                    comments.add(comment);
                    topDepth = Math.min(topDepth, comment.getDepth());
                }
            }
            if (comments.isEmpty()) return;

            final int collapseDepth = topDepth + Settings.COLLAPSE_ALL_COMMENTS_SCOPE.get().depth;
            List<String> toCollapse = new ArrayList<>();
            List<String> toExpand = new ArrayList<>();
            for (Comment comment : comments) {
                String id = comment.getKindWithId();
                if (id == null) continue;

                if (comment.getCollapsed()) {
                    toExpand.add(id);
                } else if (comment.getDepth() == collapseDepth) {
                    toCollapse.add(id);
                }
            }

            final boolean collapse = !toCollapse.isEmpty();
            List<String> updateIds = collapse ? toCollapse : toExpand;
            if (updateIds.isEmpty()) return;

            Logger.printDebug(() -> (collapse ? "Collapsing " : "Expanding ") + updateIds.size() + " comments");

            // The tree rebuilds the list of shown comments after every change,
            // so change one comment per frame to keep the UI responsive.
            updatingComments = true;
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                private int index;

                @Override
                public void doFrame(long frameTimeNanos) {
                    try {
                        String id = updateIds.get(index++);
                        if (collapse) {
                            tree.patch_collapse(id);
                        } else {
                            tree.patch_expand(id);
                        }

                        if (index < updateIds.size()) {
                            Choreographer.getInstance().postFrameCallback(this);
                            return;
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "doFrame failure", ex);
                    }
                    updatingComments = false;
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onCollapseAllClick failure", ex);
        }
    }
}
