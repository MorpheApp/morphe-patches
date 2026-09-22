/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 * https://github.com/MorpheApp/morphe-patches/pull/3033
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.ui.LyricsPanelView;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Puts the third party lyrics panel into the lyrics engagement panel.
 *
 * <p>It is laid over the built-in content rather than replacing it, so that a track
 * without third party lyrics still shows the built-in ones.
 */
public final class LyricsPanelInstaller {

    /** Container the built-in panel content lives in. */
    private static final String PANEL_CONTENT_ID = "panel_content";

    /** Panel heading, which tells the lyrics panel from the other engagement panels. */
    private static final String PANEL_TITLE_ID = "modern_title";

    /** Resource name of the app string used for the lyrics panel heading. */
    private static final String LYRICS_TITLE_RESOURCE = "lyrics_tab_title";

    /** Time given to the panel to attach its views after the component is built. */
    private static final long INSTALL_DELAY_MILLISECONDS = 150;

    /** Gap between attempts, about one frame. */
    private static final long INSTALL_RETRY_MILLISECONDS = 16;

    /** How long the panel is waited for before the attempt is given up. */
    private static final long INSTALL_TIMEOUT_MILLISECONDS = 2000;

    /** Collapses the many component callbacks of one panel opening into one attempt. */
    private static boolean installPending;

    @Nullable
    private static String lyricsTitle;

    private static WeakReference<LyricsPanelView> panelReference = new WeakReference<>(null);

    /** Panel the app currently has in the engagement panel container. */
    private static WeakReference<Object> currentPanelReference = new WeakReference<>(null);

    /** Panel the lyrics were last built into, kept to recognize it when it comes back. */
    private static WeakReference<Object> lyricsPanelReference = new WeakReference<>(null);

    /**
     * Whether this class added the window flag, so an existing app-owned flag is never cleared.
     */
    private static boolean keepScreenOnFlagAdded;

    private LyricsPanelInstaller() {
    }

    private static void updateKeepScreenOn(boolean lyricsPanelOpen) {
        Utils.runOnMainThreadNowOrLater(() -> {
            Activity activity = Utils.getActivity();
            if (activity == null) {
                return;
            }

            final int keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            final boolean shouldKeepScreenOn =
                    Settings.LYRICS_KEEP_SCREEN_ON.get() && lyricsPanelOpen;

            if (shouldKeepScreenOn) {
                if (!keepScreenOnFlagAdded
                        && (activity.getWindow().getAttributes().flags & keepScreenOnFlag) == 0) {
                    activity.getWindow().addFlags(keepScreenOnFlag);
                    keepScreenOnFlagAdded = true;
                }
            } else if (keepScreenOnFlagAdded) {
                activity.getWindow().clearFlags(keepScreenOnFlag);
                keepScreenOnFlagAdded = false;
            }
        });
    }

    /**
     * Injection point.
     *
     * <p>Some accounts get the panel without a heading, and then the app's own bookkeeping
     * is the only thing that tells the lyrics panel from the other panels that share the
     * same container.
     *
     * @param panel Panel put in the engagement panel container, or null when it was given up.
     */
    @SuppressWarnings("unused")
    public static void onEngagementPanelChanged(@Nullable Object panel) {
        try {
            currentPanelReference = new WeakReference<>(panel);

            final boolean isLyricsPanel = isCurrentPanelLyrics();
            Logger.printDebug(() -> "Engagement panel: "
                    + (panel == null ? "none" : panel.getClass().getName())
                    + (isLyricsPanel ? " (lyrics)" : ""));

            updateKeepScreenOn(isLyricsPanel);

            // Showing a panel again does not always rebuild its content, so there is no
            // component callback to install from when the lyrics panel comes back.
            if (isLyricsPanel) {
                onLyricsPanelDetected();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not track the engagement panel", ex);
        }
    }

    /**
     * Called by the litho filter when the lyrics panel is being built.
     */
    public static void onLyricsPanelDetected() {
        // Whichever panel holds the container while the lyrics component is built is the
        // lyrics panel, which keeps this working without knowing what the app calls it.
        Object panel = currentPanelReference.get();
        if (panel != null) {
            lyricsPanelReference = new WeakReference<>(panel);
        }

        updateKeepScreenOn(true);

        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }

        if (installPending) {
            return;
        }

        installPending = true;
        // With lyrics already loaded the panel can be covered on the next frame, which
        // is what keeps the built-in lyrics from being visible first. Otherwise, the app
        // is given time to attach its views, since there is nothing to show yet anyway.
        final long delay = LyricsManager.getInstance().hasLyrics()
                ? 0
                : INSTALL_DELAY_MILLISECONDS;

        scheduleInstall(SystemClock.uptimeMillis() + INSTALL_TIMEOUT_MILLISECONDS, delay);
    }

    /**
     * The panel content is built before the views it goes into are attached, so the first
     * attempt is usually too early. Retrying frame by frame covers the built-in lyrics as
     * soon as there is something to cover them in, instead of leaving them on screen until
     * the panel happens to be built again.
     */
    private static void scheduleInstall(long deadlineUptimeMs, long delay) {
        Utils.runOnMainThreadDelayed(() -> {
            try {
                if (install() || SystemClock.uptimeMillis() >= deadlineUptimeMs) {
                    installPending = false;
                    return;
                }
                scheduleInstall(deadlineUptimeMs, INSTALL_RETRY_MILLISECONDS);
            } catch (Exception ex) {
                installPending = false;
                Logger.printException(() -> "Could not install the lyrics panel", ex);
            }
        }, delay);
    }

    /**
     * @return Whether the panel is in place, so that no further attempt is needed.
     */
    private static boolean install() {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return false;
        }

        View root = activity.getWindow().getDecorView();
        ViewGroup panel = findLyricsPanelContent(root);
        if (panel == null) {
            return false;
        }

        LyricsPanelView existing = panelReference.get();

        if (existing != null && existing.getParent() instanceof ViewGroup previousParent
                && previousParent != panel) {
            previousParent.removeView(existing);
        }

        for (int i = panel.getChildCount() - 1; i >= 0; i--) {
            View child = panel.getChildAt(i);
            if (child instanceof LyricsPanelView && child != existing) {
                panel.removeViewAt(i);
            }
        }

        if (existing != null && existing.getParent() == panel) {
            // Reopening the panel makes the app restore its own content, so the
            // overlay state has to be reapplied rather than assumed still correct.
            existing.syncOverlay();
            return true;
        }

        LyricsPanelView panelView = new LyricsPanelView(panel.getContext());
        panel.addView(panelView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        panelReference = new WeakReference<>(panelView);
        return true;
    }

    private static boolean isCurrentPanelLyrics() {
        Object current = currentPanelReference.get();
        return current != null && current == lyricsPanelReference.get();
    }

    /**
     * All engagement panels are built into the same content container, so the heading
     * is what tells the lyrics panel from the comments or the live chat one. A panel
     * shown without a heading falls back to the panel the app itself put there.
     *
     * @return Whether the engagement panel currently on screen is the lyrics panel.
     */
    public static boolean isLyricsPanelOpen() {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return false;
        }
        TextView title = findForegroundTitle(activity.getWindow().getDecorView());
        if (title != null) {
            return isLyricsTitle(title);
        }
        return isCurrentPanelLyrics();
    }

    public static boolean isOtherPanelForeground() {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return false;
        }
        TextView title = findForegroundTitle(activity.getWindow().getDecorView());
        if (title != null) {
            return !isLyricsTitle(title);
        }
        Object current = currentPanelReference.get();
        return current != null && !isCurrentPanelLyrics();
    }

    /**
     * @return The container the lyrics belong in, or null when the panel on screen is
     * not the lyrics one.
     */
    @Nullable
    private static ViewGroup findLyricsPanelContent(View root) {
        TextView title = findVisibleTitle(root);
        if (title != null) {
            // The heading and the content live in the same panel, so the container is
            // looked up from the panel the heading belongs to rather than globally.
            return isLyricsTitle(title) ? findPanelContent(title) : null;
        }

        // Without a heading there is nothing to look the panel up from, so the container
        // on screen is taken as is, once the app agrees the lyrics panel is the one in it.
        if (!isCurrentPanelLyrics()) {
            return null;
        }
        return findForegroundPanelContent(root);
    }

    /**
     * @return The content container of the engagement panel currently on screen, if any.
     */
    @Nullable
    private static ViewGroup findForegroundPanelContent(View root) {
        final int panelContentId = ResourceUtils.getIdentifier(ResourceType.ID, PANEL_CONTENT_ID);
        if (panelContentId == 0) {
            return null;
        }
        return findForegroundView(root, panelContentId, new Rect(), ViewGroup.class);
    }

    private static boolean isLyricsTitle(@Nullable TextView title) {
        if (title == null) {
            return false;
        }
        String expectedTitle = lyricsTitle();
        return expectedTitle != null
                && expectedTitle.equalsIgnoreCase(String.valueOf(title.getText()));
    }

    /**
     * Walks up from the heading to the panel, then back down to its content container.
     */
    @Nullable
    private static ViewGroup findPanelContent(View title) {
        final int panelContentId = ResourceUtils.getIdentifier(ResourceType.ID, PANEL_CONTENT_ID);
        if (panelContentId == 0) {
            return null;
        }

        View node = title;
        while (node.getParent() instanceof ViewGroup parent) {
            if (parent.findViewById(panelContentId) instanceof ViewGroup content) {
                return content;
            }
            node = parent;
        }
        return null;
    }

    /**
     * @return The heading of the engagement panel currently on screen, if any.
     */
    @Nullable
    private static TextView findVisibleTitle(View root) {
        final int titleId = ResourceUtils.getIdentifier(ResourceType.ID, PANEL_TITLE_ID);
        if (titleId == 0) {
            Logger.printException(() -> "App is missing " + PANEL_TITLE_ID);
            return null;
        }
        return findVisibleTitle(root, titleId);
    }

    @Nullable
    private static TextView findVisibleTitle(View view, int titleId) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }

        if (view.getId() == titleId && view instanceof TextView title) {
            return title;
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                TextView found = findVisibleTitle(group.getChildAt(i), titleId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private static TextView findForegroundTitle(View root) {
        final int titleId = ResourceUtils.getIdentifier(ResourceType.ID, PANEL_TITLE_ID);
        if (titleId == 0) {
            Logger.printException(() -> "App is missing " + PANEL_TITLE_ID);
            return null;
        }
        Rect rect = new Rect();
        return findForegroundView(root, titleId, rect, TextView.class);
    }

    @Nullable
    private static <T extends View> T findForegroundView(View view, int targetId, Rect rect, Class<T> clazz) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }

        T result = null;
        if (view.getId() == targetId && clazz.isInstance(view)
                && view.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
            result = clazz.cast(view);
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                T found = findForegroundView(group.getChildAt(i), targetId, rect, clazz);
                if (found != null) {
                    result = found;
                }
            }
        }
        return result;
    }

    @Nullable
    private static String lyricsTitle() {
        if (lyricsTitle == null) {
            if (ResourceUtils.getStringIdentifier(LYRICS_TITLE_RESOURCE) == 0) {
                Logger.printException(() -> "App is missing: " + LYRICS_TITLE_RESOURCE);
                return null;
            }
            lyricsTitle = ResourceUtils.getString(LYRICS_TITLE_RESOURCE);
        }
        return lyricsTitle;
    }

    public static void enableLyricsButton() {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return;
        }
        String title = lyricsTitle();
        if (title == null) {
            return;
        }
        View root = activity.getWindow().getDecorView();
        for (long delay : ENABLE_BUTTON_DELAYS_MS) {
            Utils.runOnMainThreadDelayed(() -> enableLyricsButtonPass(root, title), delay);
        }
    }

    private static final long[] ENABLE_BUTTON_DELAYS_MS = {0, 150, 500, 1000, 2000};

    private static void enableLyricsButtonPass(@Nullable View root, String title) {
        if (root == null) {
            return;
        }
        String titleLower = title.toLowerCase(Locale.ROOT);
        enableLyricsButtonPass(root, title, titleLower);
    }

    private static boolean enableLyricsButtonPass(View view, String title, String titleLower) {
        if (view.getVisibility() != View.VISIBLE) {
            return false;
        }
        boolean matched = false;
        CharSequence description = view.getContentDescription();
        if (description != null) {
            String desc = description.toString();
            String descLower = desc.toLowerCase(Locale.ROOT);
            final boolean matches = title.equalsIgnoreCase(desc)
                    || descLower.contains(titleLower)
                    || descLower.contains("lyric");
            if (matches) {
                enableAndClick(view, desc);
                matched = true;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (enableLyricsButtonPass(group.getChildAt(i), title, titleLower)) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static void enableAndClick(View view, String desc) {
        view.setEnabled(true);
        view.setClickable(true);
        view.setAlpha(1.0f);
        Logger.printDebug(() -> "Enabling lyrics button: " + view.getClass().getSimpleName()
                + " content description: '" + desc + "'");
    }
}
