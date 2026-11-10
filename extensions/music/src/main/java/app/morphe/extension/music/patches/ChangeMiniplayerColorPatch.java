package app.morphe.extension.music.patches;

import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class ChangeMiniplayerColorPatch {

    /**
     * Suppression window so YTM's trailing post-dismiss state-updates don't re-apply the last album color.
     */
    private static final long DISMISS_WINDOW_MS = 1500L;

    @Nullable
    private static volatile Integer lastMiniplayerColor;
    @Nullable
    private static volatile Integer defaultMiniplayerColor;
    private static volatile WeakReference<View> navigationBarRef = new WeakReference<>(null);
    @Nullable
    private static volatile Integer defaultNavigationBarColor;
    private static volatile long dismissWindowUntilMs;

    /**
     * Injection point.
     */
    public static boolean changeMiniplayerColor() {
        return Settings.CHANGE_MINIPLAYER_COLOR.get()
                || Settings.MINIPLAYER_GLASS.get()
                || Settings.NAVIGATION_BAR_GLASS.get();
    }

    /**
     * Injection point. Records the initial miniplayer/player-page color in the constructor.
     * Always preserve full alpha because YouTube Music uses this color field for the expanded
     * player page (song/lyrics/queue screen) as well.
     */
    public static int overrideInitialMiniplayerColor(int color) {
        defaultMiniplayerColor = color;
        return color;
    }

    /**
     * Injection point. Stores the dominant color extracted for the player and notifies the
     * glass miniplayer / navigation bar. Always returns an opaque color so the expanded
     * full-screen player page never becomes transparent.
     */
    public static int setLastMiniplayerColor(int color) {
        if (SystemClock.uptimeMillis() >= dismissWindowUntilMs) {
            lastMiniplayerColor = color;
            if (matchNavigationBarEnabled()) postNavigationBarColor(color);
            MusicGlassNavigationBar.onMiniplayerColorChanged();
        }
        if (Settings.CHANGE_MINIPLAYER_COLOR.get() && !Settings.MINIPLAYER_GLASS.get()) {
            return color;
        }
        final Integer fallback = defaultMiniplayerColor;
        return fallback != null ? fallback : color;
    }

    @Nullable
    public static Integer getLastMiniplayerColor() {
        return lastMiniplayerColor;
    }

    /**
     * Injection point. Remembers the nav bar view and its theme color for later repaints.
     */
    public static void registerNavigationBar(View view, int defaultColor) {
        navigationBarRef = new WeakReference<>(view);
        defaultNavigationBarColor = defaultColor;
    }

    /**
     * Injection point. Overrides the nav bar background color at draw time.
     */
    public static int overrideNavigationBarColor(int defaultColor) {
        // When glass navigation bar is active, clear solid background color so the translucent capsule renders cleanly.
        if (Settings.NAVIGATION_BAR_GLASS.get()) {
            return defaultColor & 0x00FFFFFF;
        }
        final Integer color = lastMiniplayerColor;
        if (color != null && matchNavigationBarEnabled()) {
            return color;
        }
        return defaultColor;
    }

    /**
     * Injection point. Fires on watch-while dismiss; drops the cached tint,
     * arms the suppression window and repaints the nav bar with the theme color.
     */
    public static void onMiniplayerDismissed() {
        lastMiniplayerColor = null;
        dismissWindowUntilMs = SystemClock.uptimeMillis() + DISMISS_WINDOW_MS;
        final Integer defaultColor = defaultNavigationBarColor;
        if (defaultColor != null) postNavigationBarColor(defaultColor);
        MusicGlassNavigationBar.onMiniplayerColorChanged();
    }

    private static void postNavigationBarColor(int color) {
        View view = navigationBarRef.get();
        if (view == null) return;
        view.post(() -> view.setBackgroundColor(color));
    }

    private static boolean matchNavigationBarEnabled() {
        return Settings.CHANGE_MINIPLAYER_COLOR.get()
                && Settings.CHANGE_NAVIGATION_BAR_COLOR.get()
                && !Settings.NAVIGATION_BAR_GLASS.get();
    }
}
