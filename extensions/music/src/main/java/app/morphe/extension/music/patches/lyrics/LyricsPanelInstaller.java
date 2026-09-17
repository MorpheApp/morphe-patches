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
import android.view.View;
import android.view.ViewGroup;
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

    @Nullable
    private static WeakReference<LyricsPanelView> panelReference;

    /** Collapses the many component callbacks of one panel opening into one attempt. */
    private static boolean installPending;

    @Nullable
    private static String lyricsTitle;

    /** Panel the app currently has in the engagement panel container. */
    @Nullable
    private static WeakReference<Object> currentPanelReference;

    /** Panel the lyrics were last built into, kept to recognize it when it comes back. */
    @Nullable
    private static WeakReference<Object> lyricsPanelReference;

    private LyricsPanelInstaller() {
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
            currentPanelReference = panel == null ? null : new WeakReference<>(panel);

            final boolean isLyricsPanel = panel != null && panel == lyricsPanel();
            Logger.printDebug(() -> "Engagement panel: "
                    + (panel == null ? "none" : panel.getClass().getName())
                    + (isLyricsPanel ? " (lyrics)" : ""));

            // Showing a panel again does not always rebuild its content, so there is no
            // component callback to install from when the lyrics panel comes back.
            if (isLyricsPanel) {
                onLyricsPanelDetected();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not track the engagement panel", ex);
        }
    }

    @Nullable
    private static Object currentPanel() {
        return currentPanelReference == null ? null : currentPanelReference.get();
    }

    @Nullable
    private static Object lyricsPanel() {
        return lyricsPanelReference == null ? null : lyricsPanelReference.get();
    }

    /**
     * Called by the litho filter when the lyrics panel is being built.
     */
    public static void onLyricsPanelDetected() {
        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }

        // Whichever panel holds the container while the lyrics component is built is the
        // lyrics panel, which keeps this working without knowing what the app calls it.
        Object panel = currentPanel();
        if (panel != null) {
            lyricsPanelReference = new WeakReference<>(panel);
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

        Utils.runOnMainThreadDelayed(() -> {
            installPending = false;
            try {
                install();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not install the lyrics panel", ex);
            }
        }, delay);
    }

    private static void install() {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return;
        }

        View root = activity.getWindow().getDecorView();
        ViewGroup panel = findLyricsPanelContent(root);
        if (panel == null) {
            return;
        }

        LyricsPanelView existing = panelReference == null ? null : panelReference.get();

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
            return;
        }

        LyricsPanelView panelView = new LyricsPanelView(panel.getContext());
        panel.addView(panelView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        panelReference = new WeakReference<>(panelView);
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
        Object current = currentPanel();
        return current != null && current == lyricsPanel();
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
        Object current = currentPanel();
        return current != null && current != lyricsPanel();
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
        Object current = currentPanel();
        if (current == null || current != lyricsPanel()) {
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
        return findForegroundPanelContent(root, panelContentId, new Rect());
    }

    @Nullable
    private static ViewGroup findForegroundPanelContent(View view, int panelContentId, Rect rect) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }

        ViewGroup result = null;
        if (view.getId() == panelContentId && view instanceof ViewGroup content
                && content.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
            result = content;
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findForegroundPanelContent(
                        group.getChildAt(i), panelContentId, rect);
                if (found != null) {
                    result = found;
                }
            }
        }
        return result;
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
            for (int i = 0; i < group.getChildCount(); i++) {
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
        return findForegroundTitle(root, titleId, rect);
    }

    @Nullable
    private static TextView findForegroundTitle(View view, int titleId, Rect rect) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }

        TextView result = null;
        if (view.getId() == titleId && view instanceof TextView title) {
            if (title.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
                result = title;
            }
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findForegroundTitle(group.getChildAt(i), titleId, rect);
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
            for (int i = 0; i < group.getChildCount(); i++) {
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
        Logger.printInfo(() -> "Enabling lyrics button: " + view.getClass().getSimpleName()
                + " content description: '" + desc + "'");
    }
}
