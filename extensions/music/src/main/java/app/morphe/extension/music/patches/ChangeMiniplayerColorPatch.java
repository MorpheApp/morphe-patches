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

    /**
     * Baseline color from YTM's empty-state callback at app start; every later
     *  call carrying this value is treated as "back to empty".
     */
    @Nullable
    private static volatile Integer initialCapturedColor;

    private static volatile WeakReference<View> navigationBarRef = new WeakReference<>(null);

    @Nullable
    private static volatile Integer defaultNavigationBarColor;

    private static volatile long dismissWindowUntilMs;

    /**
     * Injection point.
     */
    public static boolean changeMiniplayerColor() {
        return Settings.CHANGE_MINIPLAYER_COLOR.get();
    }

    /**
     * Injection point. Stores the color applied to the miniplayer and, if the setting is enabled,
     * forwards it to the navigation bar so both surfaces update together instead of waiting for a UI relayout.
     */
    public static void setLastMiniplayerColor(int color) {
        if (SystemClock.uptimeMillis() < dismissWindowUntilMs) return;

        final Integer initial = initialCapturedColor;
        if (initial == null) {
            initialCapturedColor = color;
            return;
        }

        if (color == initial) {
            if (lastMiniplayerColor == null) return;
            lastMiniplayerColor = null;
            final Integer defaultColor = defaultNavigationBarColor;
            if (defaultColor != null) postNavigationBarColor(defaultColor);
            return;
        }

        lastMiniplayerColor = color;
        applyToNavigationBar(color);
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
    }

    private static void applyToNavigationBar(int color) {
        if (!Settings.CHANGE_NAVIGATION_BAR_COLOR.get()) return;
        postNavigationBarColor(color);
    }

    private static void postNavigationBarColor(int color) {
        View view = navigationBarRef.get();
        if (view == null) return;
        view.post(() -> view.setBackgroundColor(color));
    }

    private static boolean matchNavigationBarEnabled() {
        return Settings.CHANGE_MINIPLAYER_COLOR.get()
                && Settings.CHANGE_NAVIGATION_BAR_COLOR.get();
    }
}
