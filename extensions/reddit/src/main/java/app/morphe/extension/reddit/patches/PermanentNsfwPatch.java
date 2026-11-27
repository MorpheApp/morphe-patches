/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4869
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.view.View;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Subreddit;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

@SuppressWarnings("unused")
public final class PermanentNsfwPatch {
    private static final int BLOCKED_TITLE = ResourceUtils.getStringIdentifier("morphe_nsfw_blocked_title");
    private static final int BLOCKED_MESSAGE = ResourceUtils.getStringIdentifier("morphe_nsfw_blocked_message");
    private static final int GO_BACK_LABEL = ResourceUtils.getStringIdentifier("action_go_back");

    /**
     * Settings section and adult content switches of the anonymous browsing profile drawer.
     */
    private static final int[] ANONYMOUS_CONTROL_IDS = {
            ResourceUtils.getIdIdentifier("toggle_over18"),
            ResourceUtils.getIdIdentifier("toggle_blur_nsfw"),
            ResourceUtils.getIdIdentifier("title_settings"),
            ResourceUtils.getIdIdentifier("divider")
    };

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return If NSFW content is permanently disabled.
     */
    public static boolean isEnabled() {
        return isPatchIncluded() && Settings.PERMANENTLY_DISABLE_NSFW.get();
    }

    /**
     * Injection point.
     */
    public static boolean allowNsfw(boolean enabled) {
        return enabled && !isEnabled();
    }

    /**
     * Injection point.
     */
    public static boolean enforceSafeSearch(boolean safeSearch) {
        return safeSearch || isEnabled();
    }

    /**
     * Injection point.
     */
    public static int getWarningTitle(int original) {
        return isEnabled() ? BLOCKED_TITLE : original;
    }

    /**
     * Injection point.
     */
    public static int getWarningMessage(int original) {
        return isEnabled() ? BLOCKED_MESSAGE : original;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Integer getWarningSubMessage(Integer original) {
        return isEnabled() ? null : original;
    }

    /**
     * Injection point.
     */
    public static boolean allowNsfwBottomSheet(boolean original) {
        return original && !isEnabled();
    }

    /**
     * Injection point.
     */
    public static int getWarningDismissLabel(int original) {
        return isEnabled() ? GO_BACK_LABEL : original;
    }

    /**
     * Injection point.
     * The anonymous browsing profile drawer uses views instead of the Compose settings rows.
     */
    public static void hideAnonymousControls(View drawer) {
        try {
            if (!isEnabled()) return;

            for (int id : ANONYMOUS_CONTROL_IDS) {
                View view = drawer.findViewById(id);
                if (view != null) {
                    view.setVisibility(View.GONE);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideAnonymousControls failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @return If the community must not be saved as recently visited.
     */
    public static boolean skipRecentSubreddit(@Nullable Subreddit subreddit) {
        return subreddit != null && isEnabled() && Boolean.TRUE.equals(subreddit.getOver18());
    }

    /**
     * Injection point.
     * Removes NSFW communities that were saved as recently visited before NSFW content was disabled.
     */
    @Nullable
    public static List<Subreddit> filterRecentSubreddits(@Nullable List<Subreddit> subreddits) {
        try {
            if (subreddits == null || !isEnabled()) return subreddits;

            List<Subreddit> filtered = new ArrayList<>(subreddits.size());
            for (Subreddit subreddit : subreddits) {
                if (!Boolean.TRUE.equals(subreddit.getOver18())) {
                    filtered.add(subreddit);
                }
            }
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterRecentSubreddits failure", ex);
            return subreddits;
        }
    }
}
