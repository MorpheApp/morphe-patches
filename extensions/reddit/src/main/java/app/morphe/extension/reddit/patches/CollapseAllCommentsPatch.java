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

@SuppressWarnings("unused")
public final class CollapseAllCommentsPatch {

    public interface CommentTree {
        List<?> patch_getVisibleCommentIds();
        IComment patch_getComment(String id);
        void patch_collapse(String id);
        void patch_expand(String id);
    }

    public enum CollapseAllScope {
        ALL,
        REPLIES,
        DEEP
    }

    private static final int ICON_CARET_UP_DOWN =
            ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "icon_caret_up_down");

    private static final Set<Function0<?>> POST_COMMENTS_CLICKS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static WeakReference<CommentTree> commentTreeRef = new WeakReference<>(null);

    private static boolean isRunning;

    private static final Function0<Object> COLLAPSE_ALL_CLICK = new Function0<>() {
        @Override
        public Object invoke() {
            onCollapseAllClick();
            return null;
        }
    };

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;
    }

    public static void registerPostCommentsClick(@Nullable Function0<?> onClick) {
        try {
            if (onClick != null) {
                POST_COMMENTS_CLICKS.add(onClick);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "registerPostCommentsClick failure", ex);
        }
    }

    public static boolean showCollapseAllButton(@Nullable Function0<?> onClick) {
        try {
            return Settings.COLLAPSE_ALL_COMMENTS.get() && onClick != null && POST_COMMENTS_CLICKS.contains(onClick);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static int getCollapseAllIcon() {
        return ICON_CARET_UP_DOWN;
    }

    public static String getCollapseAllDescription() {
        return str("morphe_collapse_all_comments_button");
    }

    public static Function0<Object> getCollapseAllClick() {
        return COLLAPSE_ALL_CLICK;
    }

    public static void setCommentTree(Object tree) {
        try {
            if (tree instanceof CommentTree commentTree) {
                commentTreeRef = new WeakReference<>(commentTree);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setCommentTree failure", ex);
        }
    }

    public static void onCollapseAllClick() {
        if (isRunning) {
            return;
        }

        try {
            CommentTree tree = commentTreeRef.get();
            if (tree == null) {
                return;
            }

            List<?> ids = tree.patch_getVisibleCommentIds();
            if (ids == null || ids.isEmpty()) {
                return;
            }

            List<Comment> comments = new ArrayList<>(ids.size());
            for (Object idObj : ids) {
                if (idObj instanceof String id) {
                    IComment comment = tree.patch_getComment(id);
                    if (comment instanceof Comment c) {
                        comments.add(c);
                    }
                }
            }

            if (comments.isEmpty()) {
                return;
            }

            int top = Integer.MAX_VALUE;
            for (Comment comment : comments) {
                final int depth = comment.getDepth();
                if (depth < top) {
                    top = depth;
                }
            }

            final CollapseAllScope scope = Settings.COLLAPSE_ALL_COMMENTS_SCOPE.get();
            final int offset = switch (scope) {
                case REPLIES -> 1;
                case DEEP -> 2;
                case ALL -> 0;
            };
            final int targetDepth = top + offset;

            boolean hasUncollapsedAtTarget = false;
            for (Comment comment : comments) {
                if (comment.getDepth() == targetDepth && !comment.getCollapsed()) {
                    hasUncollapsedAtTarget = true;
                    break;
                }
            }

            List<String> targetIds = new ArrayList<>();
            final boolean collapse = hasUncollapsedAtTarget;
            if (collapse) {
                for (Comment comment : comments) {
                    if (comment.getDepth() == targetDepth && !comment.getCollapsed()) {
                        String kindWithId = comment.getKindWithId();
                        if (kindWithId != null) {
                            targetIds.add(kindWithId);
                        }
                    }
                }
            } else {
                for (Comment comment : comments) {
                    if (comment.getCollapsed()) {
                        String kindWithId = comment.getKindWithId();
                        if (kindWithId != null) {
                            targetIds.add(kindWithId);
                        }
                    }
                }
            }

            if (targetIds.isEmpty()) {
                return;
            }

            isRunning = true;
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                private int index;

                @Override
                public void doFrame(long frameTimeNanos) {
                    try {
                        if (index < targetIds.size()) {
                            String kindWithId = targetIds.get(index);
                            if (collapse) {
                                tree.patch_collapse(kindWithId);
                            } else {
                                tree.patch_expand(kindWithId);
                            }
                            index++;
                        }

                        if (index < targetIds.size()) {
                            Choreographer.getInstance().postFrameCallback(this);
                        } else {
                            isRunning = false;
                        }
                    } catch (Exception ex) {
                        isRunning = false;
                        Logger.printException(() -> "Collapse all comments op failure", ex);
                    }
                }
            });
        } catch (Exception ex) {
            isRunning = false;
            Logger.printException(() -> "onCollapseAllClick failure", ex);
        }
    }
}
