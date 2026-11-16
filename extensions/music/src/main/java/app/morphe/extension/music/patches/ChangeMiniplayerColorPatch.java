package app.morphe.extension.music.patches;

import android.graphics.Color;
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
     *
     * @return If the color of the album art is taken, to color the miniplayer or to tint the glass with it.
     */
    public static boolean changeMiniplayerColor() {
        return changeColor()
                || (Settings.MINIPLAYER_GLASS.get() && Settings.MINIPLAYER_GLASS_ALBUM_TINT.get())
                || (Settings.NAVIGATION_BAR_GLASS.get() && Settings.NAVIGATION_BAR_GLASS_ALBUM_TINT.get());
    }

    /**
     * @return If the miniplayer gets the color of the album art. The glass miniplayer has a tint instead.
     */
    private static boolean changeColor() {
        return Settings.CHANGE_MINIPLAYER_COLOR.get() && !Settings.MINIPLAYER_GLASS.get();
    }

    /**
     * Injection point. Remembers the color the miniplayer is made with, from the theme.
     */
    public static void setDefaultMiniplayerColor(int color) {
        defaultMiniplayerColor = color;
    }

    /**
     * Injection point. Stores the color of the album art, which is also the color of the open player.
     *
     * @return The color the miniplayer is given: the color of the album art if the miniplayer is colored,
     *         otherwise the color it has from the theme.
     */
    public static int setLastMiniplayerColor(int color) {
        if (SystemClock.uptimeMillis() >= dismissWindowUntilMs) {
            lastMiniplayerColor = color;
            if (matchNavigationBarEnabled()) postNavigationBarColor(color);
            notifyGlass();
        }
        if (changeColor()) return color;

        final Integer defaultColor = defaultMiniplayerColor;
        return defaultColor != null ? defaultColor : color;
    }

    /**
     * @return The color of the album art of the song that plays, or null if none.
     */
    @Nullable
    public static Integer getLastMiniplayerColor() {
        return lastMiniplayerColor;
    }

    private static void notifyGlass() {
        MusicGlassNavigationBar.onAlbumColorChanged();
        MusicGlassMiniplayer.onAlbumColorChanged();
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
        // The glass is drawn instead, which is translucent.
        if (Settings.NAVIGATION_BAR_GLASS.get()) {
            return Color.TRANSPARENT;
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
        notifyGlass();
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
