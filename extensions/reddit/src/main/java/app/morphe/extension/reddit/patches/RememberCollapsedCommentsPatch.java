/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3517
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.SharedPreferences;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.reddit.domain.model.Comment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

/**
 * Remembers which comments the user collapsed, and shows them collapsed
 * again when the post is opened later.
 * <p>
 * Ids (t1_xxx) are kept in a dedicated SharedPreferences file, one key per comment,
 * with the time the comment was collapsed as the value. Only the {@link #MAX_ENTRIES}
 * most recently collapsed comments are kept, and none older than {@link #MAX_AGE_MILLIS}.
 */
@SuppressWarnings("unused")
public final class RememberCollapsedCommentsPatch {

    private static final int MAX_ENTRIES = 5000;
    private static final long MAX_AGE_MILLIS = 180L * 24 * 60 * 60 * 1000;

    private static final SharedPreferences prefs = new SharedPrefCategory(
            "morphe_collapsed_comments").preferences;

    /**
     * Comment id to the time it was collapsed. Same content as {@link #prefs}.
     */
    private static final Map<String, Long> collapsedComments = new ConcurrentHashMap<>();

    /**
     * Comments marked as collapsed by this patch, by id. Reddit caches loaded comments,
     * so these are reset when the comment is expanded and forgotten.
     */
    @GuardedBy("itself")
    private static final Map<String, List<WeakReference<Comment>>> markedComments = new HashMap<>();

    static {
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof Long) {
                collapsedComments.put(entry.getKey(), (Long) entry.getValue());
            }
        }

        SharedPreferences.Editor editor = prefs.edit();
        if (trim(editor, System.currentTimeMillis())) editor.apply();

        Logger.printDebug(() -> "Loaded " + collapsedComments.size() + " collapsed comments");
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Called by Comment.getCollapsed() with the collapsed flag Reddit set.
     */
    public static boolean isCollapsed(Comment comment, boolean collapsed) {
        if (collapsed) return true;

        try {
            if (!Settings.REMEMBER_COLLAPSED_COMMENTS.get()) return false;

            String id = comment.getKindWithId();
            if (id == null || !collapsedComments.containsKey(id)) return false;

            // Store it in the comment too, so copies made from it stay collapsed
            // and an expand can be told apart from other changes to the comment.
            comment.patch_setCollapsed(true);
            synchronized (markedComments) {
                List<WeakReference<Comment>> marked = markedComments.get(id);
                if (marked == null) {
                    marked = new ArrayList<>(1);
                    markedComments.put(id, marked);
                }
                marked.add(new WeakReference<>(comment));
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "isCollapsed failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the comment tree replaces one of its items. Collapse, expand and
     * collapse thread all replace the comment with a copy that has a different collapsed flag.
     */
    public static void onCommentTreeItemUpdated(@Nullable Object oldItem, @Nullable Object newItem) {
        try {
            if (!(oldItem instanceof Comment) || !(newItem instanceof Comment newComment)) return;

            final boolean wasCollapsed = ((Comment) oldItem).getCollapsed();
            // The raw flag, since the getter would still report a remembered comment as collapsed.
            final boolean collapsed = newComment.patch_getRawCollapsed();
            if (wasCollapsed == collapsed) return;
            if (!Settings.REMEMBER_COLLAPSED_COMMENTS.get()) return;

            String id = newComment.getKindWithId();
            if (id == null) return;

            if (collapsed) {
                remember(id);
            } else {
                forget(id);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCommentTreeItemUpdated failure", ex);
        }
    }

    private static synchronized void remember(String id) {
        final long now = System.currentTimeMillis();
        collapsedComments.put(id, now);
        SharedPreferences.Editor editor = prefs.edit().putLong(id, now);
        trim(editor, now);
        editor.apply();

        Logger.printDebug(() -> "Remembered collapsed comment: " + id);
    }

    private static synchronized void forget(String id) {
        if (collapsedComments.remove(id) == null) return;

        prefs.edit().remove(id).apply();

        List<WeakReference<Comment>> marked;
        synchronized (markedComments) {
            marked = markedComments.remove(id);
        }
        if (marked != null) {
            for (WeakReference<Comment> reference : marked) {
                Comment comment = reference.get();
                if (comment != null) comment.patch_setCollapsed(false);
            }
        }

        Logger.printDebug(() -> "Forgot collapsed comment: " + id);
    }

    /**
     * Removes expired entries, and the oldest ones above the limit.
     *
     * @return If any entry was removed.
     */
    private static boolean trim(SharedPreferences.Editor editor, long now) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(collapsedComments.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));

        boolean removed = false;
        int excess = entries.size() - MAX_ENTRIES;
        for (Map.Entry<String, Long> entry : entries) {
            if (excess <= 0 && now - entry.getValue() <= MAX_AGE_MILLIS) {
                // Sorted oldest first, so everything after this is kept.
                break;
            }
            collapsedComments.remove(entry.getKey());
            editor.remove(entry.getKey());
            excess--;
            removed = true;
        }
        return removed;
    }
}
