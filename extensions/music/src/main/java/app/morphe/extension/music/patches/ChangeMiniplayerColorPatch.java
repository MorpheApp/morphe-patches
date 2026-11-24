package app.morphe.extension.music.patches;

import android.graphics.Color;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class ChangeMiniplayerColorPatch {

    /**
     * Implemented by the miniplayer during patching.
     */
    public interface MiniplayerAccess {
        /**
         * Sets the color field, which the miniplayer animates to when it resets its colors.
         */
        void patch_setColor(int color);

        /**
         * Animates the miniplayer to the color in its color field.
         */
        void patch_resetColors();
    }

    @Nullable
    private static volatile Integer lastMiniplayerColor;
    @Nullable
    private static volatile Integer defaultMiniplayerColor;
    private static volatile WeakReference<MiniplayerAccess> miniplayerRef = new WeakReference<>(null);
    private static volatile WeakReference<View> navigationBarRef = new WeakReference<>(null);
    @Nullable
    private static volatile Integer defaultNavigationBarColor;

    /**
     * Album color of the song that was dismissed, until another song is loaded.
     * The app still sends this color after a dismiss, which must not color the miniplayer again.
     */
    @Nullable
    private static volatile Integer dismissedSongColor;

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
     * Injection point. Remembers the miniplayer and the color it is made with, from the theme.
     */
    public static void setDefaultMiniplayerColor(MiniplayerAccess miniplayer, int color) {
        miniplayerRef = new WeakReference<>(miniplayer);
        defaultMiniplayerColor = color;
    }

    /**
     * Injection point. Stores the color of the album art, which is also the color of the open player.
     *
     * @return The color the miniplayer is given: the color of the album art if the miniplayer is colored,
     *         otherwise the color it has from the theme.
     */
    public static int setLastMiniplayerColor(int color) {
        final Integer dismissedColor = dismissedSongColor;
        if (dismissedColor == null || dismissedColor != color) {
            // Another color is another song.
            dismissedSongColor = null;

            lastMiniplayerColor = color;
            if (matchNavigationBarEnabled()) postNavigationBarColor(color);
            notifyGlass();

            if (changeColor()) return color;
        }

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
     * Injection point. Fires on watch-while dismiss (swipe or "Dismiss queue").
     */
    public static void onMiniplayerDismissed() {
        final Integer color = lastMiniplayerColor;
        if (color != null) dismissedSongColor = color;
        onMiniplayerEmpty();
    }

    /**
     * Injection point. A new song is loaded.
     */
    public static void newVideoLoaded(String videoId) {
        dismissedSongColor = null;
    }

    /**
     * Injection point. The miniplayer shows "Nothing is playing", or the queue was dismissed.
     * Drops the album color, and gives the miniplayer, the navigation bar and the glass their theme color.
     */
    public static void onMiniplayerEmpty() {
        lastMiniplayerColor = null;
        final Integer defaultColor = defaultNavigationBarColor;
        if (defaultColor != null) postNavigationBarColor(defaultColor);
        notifyGlass();

        Utils.runOnMainThreadNowOrLater(() -> {
            final MiniplayerAccess miniplayer = miniplayerRef.get();
            final Integer miniplayerColor = defaultMiniplayerColor;
            if (miniplayer == null || miniplayerColor == null) return;

            Logger.printDebug(() -> "Resetting miniplayer to the theme color");
            miniplayer.patch_setColor(miniplayerColor);
            miniplayer.patch_resetColors();
        });
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
