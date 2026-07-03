package app.morphe.extension.music.patches;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class ChangeMiniplayerColorPatch {

    @Nullable
    private static volatile Integer lastMiniplayerColor;

    @Nullable
    private static volatile WeakReference<View> navigationBarRef;

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
        lastMiniplayerColor = color;
        applyToNavigationBar(color);
    }

    /**
     * Injection point. Captures the nav bar view once so its background can be refreshed whenever
     * the miniplayer color changes.
     */
    public static void registerNavigationBar(View view) {
        final WeakReference<View> current = navigationBarRef;
        if (current == null || current.get() != view) {
            navigationBarRef = new WeakReference<>(view);
        }
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

    private static void applyToNavigationBar(int color) {
        if (!matchNavigationBarEnabled()) return;
        final WeakReference<View> ref = navigationBarRef;
        final View view = ref != null ? ref.get() : null;
        if (view == null) return;
        view.post(() -> view.setBackgroundColor(color));
    }

    private static boolean matchNavigationBarEnabled() {
        return Settings.CHANGE_MINIPLAYER_COLOR.get()
                && Settings.MATCH_NAVIGATION_BAR_COLOR.get();
    }
}
