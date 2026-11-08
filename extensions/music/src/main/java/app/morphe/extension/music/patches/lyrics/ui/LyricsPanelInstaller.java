/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 * https://github.com/MorpheApp/morphe-patches/pull/3033
 * https://github.com/MorpheApp/morphe-patches/pull/4267
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.text.Normalizer;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.LyricsFilterDefaults;
import app.morphe.extension.music.patches.lyrics.session.LockScreenLyrics;
import app.morphe.extension.music.patches.lyrics.session.MiniplayerLyrics;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;

/**
 * Puts the third party lyrics panel into the lyrics engagement panel or the lyrics player tab.
 *
 * <p>It is laid over the built-in content rather than replacing it, so that a track
 * without third party lyrics still shows the built-in ones.
 */
public final class LyricsPanelInstaller {

    /** Container the built-in panel content lives in. */
    private static final String PANEL_CONTENT_ID = "panel_content";

    /** Panel heading, which tells the lyrics panel from the other engagement panels. */
    private static final String PANEL_TITLE_ID = "modern_title";

    /** Holds the tab strip and the pages of the player tabs. */
    private static final String TABBED_VIEW_ID = "bottom_sheet_tabbed_view";

    private static final int TAB_CONTENT_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "elements_component_holder");

    private static final String LYRICS_TITLE_RESOURCE = "lyrics_tab_title";

    /** Time given to the panel to attach its views after the component is built. */
    private static final long INSTALL_DELAY_MILLISECONDS = 150;

    /** Gap between attempts, about one frame. */
    private static final long INSTALL_RETRY_MILLISECONDS = 16;

    /** How long the panel is waited for before the attempt is given up. */
    private static final long INSTALL_TIMEOUT_MILLISECONDS = 2000;
    private static final long[] ENABLE_BUTTON_DELAYS_MS = {0, 150, 500, 1000, 2000};
    private static final long ENABLE_BUTTON_MIN_INTERVAL_MS = 2000;
    /** Collapses the many component callbacks of one panel opening into one attempt. */
    private static boolean installPending;
    private static WeakReference<LyricsPanelView> panelReference = new WeakReference<>(null);
    /** Panel the app currently has in the engagement panel container. */
    private static volatile WeakReference<Object> currentPanelReference = new WeakReference<>(null);
    /** Identifier of the current panel, or null when it has none or it could not be read. */
    @Nullable
    private static volatile String currentPanelIdentifier;
    /** Panel the lyrics were last built into, kept to recognize it when it comes back. */
    private static volatile WeakReference<Object> lyricsPanelReference = new WeakReference<>(null);
    /** Panel last found to be another one, such as the comments. */
    private static volatile WeakReference<Object> otherPanelReference = new WeakReference<>(null);
    private static final SharedPreferences.OnSharedPreferenceChangeListener SETTINGS_LISTENER =
            (prefs, key) -> {
                if (key == null || !key.startsWith("morphe_music_lyrics")) {
                    return;
                }
                Utils.runOnMainThreadNowOrLater(() -> onLyricsSettingChanged(key));
            };
    private static boolean settingsListenerRegistered;
    private static long lastEnableButtonWalkUptimeMs;
    private static boolean enableButtonWalkPending;

    private LyricsPanelInstaller() {
    }

    public static void registerSettingsListener() {
        if (settingsListenerRegistered) {
            return;
        }
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(
                SETTINGS_LISTENER);
        settingsListenerRegistered = true;
    }

    private static void onLyricsSettingChanged(String key) {
        try {
            if (Settings.LYRICS_ENABLED.key.equals(key)) {
                if (Settings.LYRICS_ENABLED.get()) {
                    if (isLyricsPanelOpen()) {
                        onLyricsPanelDetected();
                    }
                    LyricsManager.getInstance().reloadAfterSettingsChange();
                } else {
                    uninstallLyricsPanel();
                }
                MiniplayerLyrics.onSettingsChanged();
                return;
            }
            if (Settings.LYRICS_SOURCE.key.equals(key)
                    || Settings.LYRICS_SPLIT_BACKGROUND.key.equals(key)
                    || LyricsFilterDefaults.LYRICS_CUSTOM_REGEX.key.equals(key)
                    || LyricsFilterDefaults.LYRICS_TEXT_FILTER.key.equals(key)
                    || LyricsFilterDefaults.LYRICS_CREDIT_LINE_REGEX.key.equals(key)) {
                LyricsManager.getInstance().reloadAfterSettingsChange();
                return;
            }
            if (Settings.LYRICS_KEEP_SCREEN_ON.key.equals(key)) {
                updateKeepScreenOn(isLyricsPanelOpen());
                return;
            }
            final boolean formatting = Settings.LYRICS_FORCE_CAPITALIZE.key.equals(key)
                    || Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.key.equals(key)
                    || Settings.LYRICS_HAN_FORM.key.equals(key);
            if (formatting) {
                // Also shown in the miniplayer and the media session.
                MiniplayerLyrics.onSettingsChanged();
                LockScreenLyrics.onSettingsChanged();
            } else if (Settings.LYRICS_MINIPLAYER.key.equals(key)
                    || Settings.LYRICS_MEDIASESSION.key.equals(key)
                    || Settings.LYRICS_MINI_TRACK_FORMAT.key.equals(key)
                    || Settings.LYRICS_MINI_ENCAPSULATION.key.equals(key)
                    || Settings.LYRICS_MINI_CHAR_LIMIT.key.equals(key)
                    || Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.key.equals(key)
                    || Settings.LYRICS_MINI_ARTIST_MIN_MS.key.equals(key)
                    || Settings.LYRICS_MINI_ARTIST_MAX_MS.key.equals(key)
                    || Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.key.equals(key)
                    || Settings.LYRICS_MINI_INSTRUMENTAL.key.equals(key)
                    || Settings.LYRICS_MINI_NEXT_OFFSET_MS.key.equals(key)) {
                MiniplayerLyrics.onSettingsChanged();
                LockScreenLyrics.onSettingsChanged();
                return;
            }
            final LyricsPanelView panelView = panelReference.get();
            if (panelView == null) {
                return;
            }
            final boolean translateButtonOff =
                    Settings.LYRICS_SHOW_TRANSLATE_BUTTON.key.equals(key)
                            && !Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get();
            final boolean romanizeButtonOff =
                    Settings.LYRICS_SHOW_ROMANIZE_BUTTON.key.equals(key)
                            && !Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get();
            if (translateButtonOff || romanizeButtonOff) {
                panelView.releaseOnlyModesForHiddenButtons(translateButtonOff, romanizeButtonOff);
            }
            if (Settings.LYRICS_SHOW_COPY_BUTTON.key.equals(key)
                    || Settings.LYRICS_SHOW_TRANSLATE_BUTTON.key.equals(key)
                    || Settings.LYRICS_SHOW_ROMANIZE_BUTTON.key.equals(key)
                    || Settings.LYRICS_SHOW_REFRESH_BUTTON.key.equals(key)) {
                panelView.applyToolbarButtonSettings();
            } else if (Settings.LYRICS_TEXT_SIZE.key.equals(key)
                    || Settings.LYRICS_LINE_SCALE.key.equals(key)) {
                panelView.applyTextSize();
                panelView.applyLineScale();
            } else if (formatting || Settings.LYRICS_FORCE_CENTER.key.equals(key)
                    || Settings.LYRICS_SWAP_TRANS_ROMA.key.equals(key)
                    || Settings.LYRICS_TAP_TO_SEEK.key.equals(key)) {
                // These are read while the line is laid out, so the lines need rebuilding.
                panelView.applyLyricLayoutSettings();
            } else if (Settings.LYRICS_WORD_SYNC.key.equals(key)) {
                panelView.applyWordSyncSetting();
            } else if (Settings.LYRICS_HIDE_INFO.key.equals(key)) {
                panelView.applyHideInfo();
            } else if (Settings.LYRICS_SHOW_SECTION.key.equals(key)) {
                panelView.applyShowSection();
            } else if (Settings.LYRICS_HIDE_PLAYED.key.equals(key)
                    || Settings.LYRICS_HIDE_UNPLAYED.key.equals(key)) {
                panelView.applyLineOverlaySettings();
            } else if (Settings.LYRICS_DYNAMIC_BACKGROUND.key.equals(key)
                    || Settings.LYRICS_DYNAMIC_BACKGROUND_ANIMATED.key.equals(key)) {
                panelView.applyDynamicBackgroundSettings();
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not apply lyrics setting change", ex);
        }
    }

    private static void uninstallLyricsPanel() {
        installPending = false;
        final LyricsPanelView panelView = panelReference.get();
        if (panelView == null) {
            return;
        }
        panelView.dismissForOtherPanel();
        panelReference.clear();
    }

    private static void updateKeepScreenOn(boolean lyricsPanelOpen) {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                LyricsPanelView panelView = panelReference.get();
                if (panelView == null) {
                    return;
                }

                panelView.setKeepScreenOn(
                        Settings.LYRICS_KEEP_SCREEN_ON.get() && lyricsPanelOpen);
            } catch (Exception ex) {
                Logger.printException(() -> "updateKeepScreenOn failure", ex);
            }
        });
    }

    /**
     * Part of the identifier the app gives the lyrics engagement panel, which is
     * "music_watch_lyrics_panel". The other panels, such as "music-comment-panel", lack it.
     */
    private static final String LYRICS_PANEL_IDENTIFIER_PART = "lyrics";

    /**
     * Method is modified during patching to read the identifier through the app's own helper.
     *
     * @return Identifier of the panel, or null when it has none or the patch did not find the helper.
     */
    @Nullable
    private static String getPanelIdentifier(Object panel) {
        return null;
    }

    /**
     * Injection point.
     *
     * <p>All engagement panels share one container, and some accounts get them without a
     * heading, so the panel the app itself put in the container is what tells them apart.
     * Its identifier answers that in any language. Without one, the panel is recognized by
     * what was built into it, see {@link #onLyricsPanelDetected()} and
     * {@link #onOtherPanelDetected()}.
     *
     * @param panel Panel put in the engagement panel container, or null when it was given up.
     */
    @SuppressWarnings("unused")
    public static void onEngagementPanelChanged(@Nullable Object panel) {
        try {
            final String identifier = panel == null ? null : getPanelIdentifier(panel);
            if (identifier != null) {
                if (identifier.contains(LYRICS_PANEL_IDENTIFIER_PART)) {
                    markLyricsPanel(panel);
                } else {
                    markOtherPanel(panel);
                }
            }
            currentPanelIdentifier = identifier;
            currentPanelReference = new WeakReference<>(panel);

            final boolean isLyricsPanel = isCurrentPanelLyrics();
            Logger.printDebug(() -> "Engagement panel: "
                    + (panel == null ? "none" : panel.getClass().getName())
                    + (identifier == null ? "" : " identifier: " + identifier)
                    + (isLyricsPanel ? " (lyrics)" : ""));

            if (!isLyricsPanel) {
                installPending = false;
            }
            updateKeepScreenOn(isLyricsPanel);

            // Takes the panel out of the container when another panel took it over.
            LyricsPanelView panelView = panelReference.get();
            if (panelView != null) {
                panelView.syncOverlay();
            }

            // Showing a panel again does not always rebuild its content, so there is no
            // component callback to install from when the lyrics panel comes back.
            if (isLyricsPanel) {
                onLyricsPanelDetected();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not track the engagement panel", ex);
        }
    }

    private static void markLyricsPanel(Object panel) {
        lyricsPanelReference = new WeakReference<>(panel);
        if (otherPanelReference.get() == panel) {
            otherPanelReference.clear();
        }
    }

    private static void markOtherPanel(Object panel) {
        otherPanelReference = new WeakReference<>(panel);
        if (lyricsPanelReference.get() == panel) {
            lyricsPanelReference.clear();
        }
    }

    /**
     * Called by the litho filter when the timed lyrics component is built, and when lyrics
     * are loaded or turned on.
     */
    public static void onLyricsPanelDetected() {
        try {
            detectLyricsPanel();
        } catch (Exception ex) {
            Logger.printException(() -> "onLyricsPanelDetected failure", ex);
            installPending = false;
        }
    }

    /**
     * Called by the litho filter when comments are built. Only needed for a panel without an
     * identifier, so that the timed lyrics updates that keep coming while the comments are
     * open do not claim the comments panel as the lyrics one.
     */
    public static void onOtherPanelDetected() {
        try {
            Object panel = currentPanelReference.get();
            if (panel == null || currentPanelIdentifier != null
                    || panel == otherPanelReference.get()) {
                return;
            }
            markOtherPanel(panel);
            installPending = false;
            Logger.printDebug(() -> "Engagement panel is not the lyrics one: "
                    + panel.getClass().getName());

            updateKeepScreenOn(false);
            LyricsPanelView panelView = panelReference.get();
            if (panelView != null) {
                Utils.runOnMainThreadNowOrLater(panelView::syncOverlay);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onOtherPanelDetected failure", ex);
        }
    }

    private static void detectLyricsPanel() {
        // Not so with lyrics in a tab, where the build happens behind whichever panel is open.
        Object panel = currentPanelReference.get();
        if (panel != null && panel != lyricsPanelReference.get()
                && !isInTab(panelReference.get())) {
            // The timed lyrics are also updated while another panel is open over the lyrics.
            if (currentPanelIdentifier != null || panel == otherPanelReference.get()) {
                return;
            }
            // With nothing else to go by, whichever panel holds the container during the
            // lyrics build is the lyrics panel.
            markLyricsPanel(panel);
        }

        updateKeepScreenOn(true);

        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }

        if (installPending) {
            return;
        }

        installPending = true;
        // With lyrics loaded the panel can cover the next frame, which keeps the built-in
        // lyrics from showing first; otherwise the app is given time to attach its views.
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
                scheduleInstall(deadlineUptimeMs, retryDelay(deadlineUptimeMs));
            } catch (Exception ex) {
                installPending = false;
                Logger.printException(() -> "Could not install the lyrics panel", ex);
            }
        }, delay);
    }

    private static long retryDelay(long deadlineUptimeMs) {
        final long left = deadlineUptimeMs - SystemClock.uptimeMillis();
        if (left > 1700) {
            return INSTALL_RETRY_MILLISECONDS;
        }
        if (left > 1000) {
            return 50;
        }
        return 200;
    }

    /**
     * @return Whether the panel is in place, so that no further attempt is needed.
     */
    private static boolean install() {
        if (!Settings.LYRICS_ENABLED.get()) {
            return true;
        }
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return false;
        }

        View root = activity.getWindow().getDecorView();
        ViewGroup panel = findLyricsTabContent(root);
        if (panel == null) {
            panel = findLyricsPanelContent(root);
        }
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
            // Reopening makes the app restore its own content, so the overlay state is
            // reapplied rather than assumed still correct.
            existing.setKeepScreenOn(Settings.LYRICS_KEEP_SCREEN_ON.get());
            existing.syncOverlay();
            return true;
        }

        LyricsPanelView panelView = new LyricsPanelView(panel.getContext());
        panelView.setKeepScreenOn(Settings.LYRICS_KEEP_SCREEN_ON.get());
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
     * Injection point. Called for every tabbed view of the app.
     */
    @SuppressWarnings("unused")
    public static void onTabSelected(ViewGroup tabbedView) {
        try {
            if (!Settings.LYRICS_ENABLED.get()
                    || tabbedView.getId() != ResourceUtils.getIdentifier(ResourceType.ID, TABBED_VIEW_ID)
                    || !isLyricsTabSelected(tabbedView)) {
                return;
            }
            // Lyrics are in a tab, so an engagement panel tagged before this is not the lyrics one.
            lyricsPanelReference.clear();
            // The page of the previous tab fills the view until the pager starts to scroll,
            // so the first attempt is delayed to not install into that page.
            scheduleTabInstall(INSTALL_DELAY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "onTabSelected failure", ex);
        }
    }

    /**
     * A track change replaces the page of the lyrics tab while the tab stays selected,
     * and the replacement may not build anything that signals a lyrics panel.
     */
    static void onPanelDetached(LyricsPanelView panelView) {
        Activity activity = Utils.getActivity();
        if (activity != null && Settings.LYRICS_ENABLED.get() && isInTab(panelView)
                && findLyricsTabbedView(activity.getWindow().getDecorView()) != null) {
            scheduleTabInstall(0);
        }
    }

    private static void scheduleTabInstall(long delay) {
        if (!installPending) {
            installPending = true;
            scheduleInstall(SystemClock.uptimeMillis() + INSTALL_TIMEOUT_MILLISECONDS, delay);
        }
    }

    /**
     * Keeping the screen on follows the page being on screen, because the page stays attached
     * with the player sheet collapsed or another tab selected, and neither calls the panel.
     */
    static void updateKeepScreenOnInTab(LyricsPanelView panelView) {
        if (isInTab(panelView) && panelView.getParent() instanceof View content) {
            Rect visible = new Rect();
            panelView.setKeepScreenOn(Settings.LYRICS_KEEP_SCREEN_ON.get()
                    && content.getGlobalVisibleRect(visible)
                    && visible.height() * 2 >= content.getHeight());
        }
    }

    private static boolean isTabContent(View view) {
        return view.getId() == TAB_CONTENT_ID;
    }

    /**
     * Tab pages do not share a container, so a panel in one stays in place when another
     * tab or an engagement panel is shown, and scrolls away along with its page.
     */
    private static boolean isInTab(@Nullable LyricsPanelView panelView) {
        return panelView != null && panelView.getParent() instanceof View content
                && isTabContent(content);
    }

    /**
     * The tab strip is hidden while the player sheet is expanded, so its visibility says nothing.
     */
    private static boolean isLyricsTabSelected(ViewGroup tabbedView) {
        String title = lyricsTitle(tabbedView.getContext());
        return title != null && tabbedView.getChildCount() > 0
                && hasSelectedTitle(tabbedView.getChildAt(0), title);
    }

    private static boolean hasSelectedTitle(View view, String title) {
        if (view instanceof TextView text) {
            return text.isSelected() && isSameTitle(title, text.getText());
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (hasSelectedTitle(group.getChildAt(i), title)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return The player tabs, or null when the lyrics tab is not the selected one.
     */
    @Nullable
    private static ViewGroup findLyricsTabbedView(View root) {
        return root.findViewById(ResourceUtils.getIdentifier(ResourceType.ID, TABBED_VIEW_ID))
                instanceof ViewGroup tabbedView && isLyricsTabSelected(tabbedView)
                ? tabbedView : null;
    }

    /**
     * @return The container of the lyrics tab page, or null when the lyrics tab is not selected
     * or its page is not settled yet.
     */
    @Nullable
    private static ViewGroup findLyricsTabContent(View root) {
        ViewGroup tabbedView = findLyricsTabbedView(root);
        return tabbedView == null
                ? null
                : findFullWidthTabContent(tabbedView, tabbedView.getWidth(), new Rect());
    }

    /**
     * Pages next to the selected one can be attached too, so the page must fill the width.
     */
    @Nullable
    private static ViewGroup findFullWidthTabContent(View view, int width, Rect rect) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof ViewGroup group) {
            if (isTabContent(group)) {
                return group.getGlobalVisibleRect(rect) && rect.width() == width ? group : null;
            }
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                ViewGroup found = findFullWidthTabContent(group.getChildAt(i), width, rect);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * @return Whether the lyrics panel or the lyrics tab is the one on screen.
     */
    public static boolean isLyricsPanelOpen() {
        return isInTab(panelReference.get()) || isCurrentPanelLyrics();
    }

    /**
     * Answered by identity only, which keeps it cheap enough for the frequent overlay checks.
     *
     * @return Whether another engagement panel, such as the comments, holds the container.
     */
    public static boolean isOtherPanelForeground() {
        return !isInTab(panelReference.get())
                && currentPanelReference.get() != null
                && !isCurrentPanelLyrics();
    }

    /**
     * @return The container the lyrics belong in, or null when the panel on screen is
     * not the lyrics one.
     */
    @Nullable
    private static ViewGroup findLyricsPanelContent(View root) {
        if (!isCurrentPanelLyrics()) {
            return null;
        }
        // Heading and content share a panel, so the container comes from the heading's own
        // panel when there is one, rather than from a global lookup.
        TextView title = findForegroundTitle(root);
        if (title != null) {
            ViewGroup content = findPanelContent(title);
            if (content != null) {
                return content;
            }
        }
        return findForegroundPanelContent(root);
    }

    @Nullable
    private static ViewGroup findForegroundPanelContent(View root) {
        final int panelContentId = ResourceUtils.getIdentifier(ResourceType.ID, PANEL_CONTENT_ID);
        if (panelContentId == 0) {
            return null;
        }
        return findForegroundView(root, panelContentId, new Rect(), ViewGroup.class);
    }

    /**
     * Normalizes title text for locale-independent comparison across different cases,
     * accents/diacritics (e.g. Greek tonos Στίχοι vs ΣΤΙΧΟΙ), and dotted/dotless I
     * in Turkish/Azerbaijani.
     */
    public static String normalizeTitle(@Nullable CharSequence text) {
        if (text == null) {
            return "";
        }
        String s = text.toString().trim();
        if (s.isEmpty()) {
            return "";
        }
        // Normalize Turkish/Azerbaijani dotted/dotless I before generic decomposition
        s = s.replace('İ', 'I').replace('ı', 'i');
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().trim();
    }

    public static boolean isSameTitle(@Nullable CharSequence a, @Nullable CharSequence b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String normA = normalizeTitle(a);
        String normB = normalizeTitle(b);
        return !normA.isEmpty() && normA.equals(normB);
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

    /**
     * The heading the app gives the lyrics panel, in the language on screen. Read through the
     * resources that drew it rather than {@link ResourceUtils}: the app can be set to a language
     * of its own, and a string then comes back in whichever language its context was configured
     * with, which is not always the one being drawn. Not cached, since a language change would
     * leave a stale answer behind.
     */
    @Nullable
    private static String lyricsTitle(Context context) {
        final int id = ResourceUtils.getStringIdentifier(LYRICS_TITLE_RESOURCE);
        if (id == 0) {
            Logger.printException(() -> "App is missing: " + LYRICS_TITLE_RESOURCE);
            return null;
        }
        try {
            return context.getResources().getString(id);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read " + LYRICS_TITLE_RESOURCE, ex);
            return null;
        }
    }

    public static void enableLyricsButton() {
        try {
            walkForLyricsButton();
        } catch (Exception ex) {
            Logger.printException(() -> "enableLyricsButton failure", ex);
            enableButtonWalkPending = false;
        }
    }

    private static void walkForLyricsButton() {
        if (enableButtonWalkPending) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - lastEnableButtonWalkUptimeMs < ENABLE_BUTTON_MIN_INTERVAL_MS) {
            return;
        }
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return;
        }
        String title = lyricsTitle(activity);
        if (title == null) {
            return;
        }
        final View root = activity.getWindow().getDecorView();
        enableButtonWalkPending = true;
        lastEnableButtonWalkUptimeMs = now;
        scheduleEnableButtonPass(root, title, 0);
    }

    private static void scheduleEnableButtonPass(final View root, final String title, final int step) {
        final long delay = step == 0
                ? ENABLE_BUTTON_DELAYS_MS[0]
                : ENABLE_BUTTON_DELAYS_MS[step] - ENABLE_BUTTON_DELAYS_MS[step - 1];
        Utils.runOnMainThreadDelayed(() -> {
            boolean scheduledNext = false;
            try {
                final String normTitle = normalizeTitle(title);
                boolean matched = enableLyricsButtonPass(root, title, normTitle);
                if (!matched && step + 1 < ENABLE_BUTTON_DELAYS_MS.length) {
                    scheduleEnableButtonPass(root, title, step + 1);
                    scheduledNext = true;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "enableLyricsButton pass failure", ex);
            } finally {
                if (!scheduledNext) {
                    enableButtonWalkPending = false;
                }
            }
        }, delay);
    }

    private static boolean enableLyricsButtonPass(View view, String title, String normTitle) {
        if (view.getVisibility() != View.VISIBLE) {
            return false;
        }
        boolean matched = false;
        CharSequence description = view.getContentDescription();
        if (description != null) {
            String desc = description.toString();
            String normDesc = normalizeTitle(desc);
            final boolean matches = isSameTitle(title, desc)
                    || (!normTitle.isEmpty() && (normDesc.contains(normTitle) || normTitle.contains(normDesc)))
                    || normDesc.contains("lyric");
            if (matches) {
                view.setEnabled(true);
                view.setClickable(true);
                view.setAlpha(1.0f);
                Logger.printDebug(() -> "Enabling lyrics button: " + view.getClass().getSimpleName()
                        + " content description: '" + desc + "'");
                matched = true;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (enableLyricsButtonPass(group.getChildAt(i), title, normTitle)) {
                    matched = true;
                }
            }
        }
        return matched;
    }
}
