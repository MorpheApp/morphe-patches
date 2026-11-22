/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

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

    private static final String AUTOMODERATOR = "AutoModerator";

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
     * Filters comments from the SetComments class constructor.
     */
    @Nullable
    public static List<?> filterComments(@Nullable List<?> comments) {
        try {
            AutoModeratorComments setting = Settings.AUTOMODERATOR_COMMENTS.get();
            if (setting == AutoModeratorComments.SHOW || comments == null || comments.isEmpty()) {
                return comments;
            }

            if (setting == AutoModeratorComments.COLLAPSE) {
                for (Object item : comments) {
                    if (item instanceof Comment comment && AUTOMODERATOR.equalsIgnoreCase(comment.getAuthor())) {
                        comment.patch_setCollapsed(true);
                    }
                }
                return comments;
            }

            if (setting == AutoModeratorComments.HIDE) {
                List<Object> filteredComments = new ArrayList<>(comments.size());
                int skippingDepth = -1;

                for (Object item : comments) {
                    if (item instanceof IComment iComment) {
                        if (skippingDepth >= 0) {
                            if (iComment.getDepth() > skippingDepth) {
                                continue;
                            }
                            skippingDepth = -1;
                        }

                        if (item instanceof Comment comment && AUTOMODERATOR.equalsIgnoreCase(comment.getAuthor())) {
                            skippingDepth = iComment.getDepth();
                            continue;
                        }
                    }

                    filteredComments.add(item);
                }

                return filteredComments.size() == comments.size() ? comments : filteredComments;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "filterComments failure", ex);
        }

        return comments;
    }
}
