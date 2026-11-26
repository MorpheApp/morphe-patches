/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.Menu.MenuIcon;
import app.morphe.extension.shared.innertube.Menu.MenuItem;
import app.morphe.extension.shared.innertube.Menu.MenuNavigationItemRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuText;
import app.morphe.extension.shared.innertube.Menu.MenuTextRun;
import app.morphe.extension.shared.patches.VideoEffectDialog;

/**
 * Adds Video effect to the player menu while a video plays.
 */
@SuppressWarnings("unused")
public final class VideoEffectMenuPatch {

    /**
     * Icon of the Quality item. The app adds it to the player menu only while a video plays
     * that has more than one quality, and not while only the audio plays.
     */
    private static final int ICON_TYPE_SETTINGS_MATERIAL = 189;

    /**
     * Icon of an image with a sparkle.
     */
    private static final int ICON_TYPE_SPARK_IMAGE = 1100;

    /**
     * Color of the sheets of the app in dark mode.
     */
    private static final int SHEET_DARK_COLOR = 0xFF212121;

    private static final int SHEET_ANIMATION_DURATION = 200;

    private VideoEffectMenuPatch() {
    }

    /**
     * @return The index of the Quality item, or -1 if the items have none.
     */
    private static int indexOfQualityItem(List<?> items) throws InvalidProtocolBufferException {
        for (int i = 0, size = items.size(); i < size; i++) {
            if (items.get(i) instanceof MessageLite message
                    && MenuItem.parseFrom(message.toByteArray()).getMenuServiceItemRenderer()
                    .getIcon().getIconType() == ICON_TYPE_SETTINGS_MATERIAL) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Injection point.
     *
     * @param items The items that the app adds to the end of the player menu.
     * @return The menu item renderer of the video effect item, or null if the menu gets none.
     */
    @Nullable
    public static byte[] getVideoEffectMenuItem(List<?> items) {
        try {
            if (!Settings.VIDEO_EFFECT_MENU.get() || indexOfQualityItem(items) < 0) {
                return null;
            }

            // No command, so the app only closes the menu when the item is clicked.
            return MenuItem.newBuilder()
                    .setMenuNavigationItemRenderer(MenuNavigationItemRenderer.newBuilder()
                            .setText(MenuText.newBuilder()
                                    .addRuns(MenuTextRun.newBuilder().setText(str("morphe_video_effect_title"))))
                            .setIcon(MenuIcon.newBuilder().setIconType(ICON_TYPE_SPARK_IMAGE))
                            .setMorpheVideoEffect(true))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "getVideoEffectMenuItem failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     *
     * @param item The item of {@link #getVideoEffectMenuItem(List)}.
     * @return The items with the video effect item after the Quality item.
     */
    public static List<?> addVideoEffectMenuItem(List<?> items, Object item) {
        try {
            List<Object> newItems = new ArrayList<>(items);
            newItems.add(indexOfQualityItem(items) + 1, item);
            return newItems;
        } catch (Exception ex) {
            Logger.printException(() -> "addVideoEffectMenuItem failure", ex);
            return items;
        }
    }

    /**
     * Injection point.
     * Called on the main thread when an item of a menu is clicked, before the app closes the menu.
     */
    public static void onMenuItemClick(@Nullable Object item) {
        try {
            if (!(item instanceof MessageLite message)
                    || !MenuItem.parseFrom(message.toByteArray()).getMenuNavigationItemRenderer()
                    .getMorpheVideoEffect()) {
                return;
            }

            // After the menu is closed.
            Utils.runOnMainThread(VideoEffectMenuPatch::showDialog);
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuItemClick failure", ex);
        }
    }

    private static void showDialog() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null) {
                Logger.printException(() -> "No activity to show the video effects in");
                return;
            }

            VideoEffectDialog.create(
                    activity,
                    Utils.isDarkModeEnabled() ? SHEET_DARK_COLOR : null,
                    SHEET_ANIMATION_DURATION,
                    // The app has no way to load the video again.
                    dialog -> Utils.showToastLong(str("morphe_music_video_effect_next_video"))
            ).show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
