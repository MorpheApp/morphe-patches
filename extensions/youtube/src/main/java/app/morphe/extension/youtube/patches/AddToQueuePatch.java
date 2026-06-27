/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getContext;
import static app.morphe.extension.youtube.patches.OpenSystemShareSheetPatch.enableIsFlyoutShareButton;

import android.app.Dialog;
import android.util.Pair;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.components.LithoFilterPatch;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    /**
     * Interface to use obfuscated fields.
     */
    public interface ProtocolBufferFieldInterface {
        // Method is added during patching.
        byte[] patch_getBuffer();
    }

    /**
     * Interface to use obfuscated fields.
     */
    public interface FlyoutMenuVideoIdInterface {
        // Method is added during patching.
        String patch_getVideoId();
    }

    private static Dialog flyoutDialog = null;
    private static PopupWindow flyoutPopupWindow = null;

    private static final String queueButtonName = "QUEUE_PLAY_NEXT";
    private static final String shareButtonName = "SHARE_ARROW";

    private static final List<byte[]> VIDEO_ID_PREFIXES_BYTES = List.of(
            // Can be i.ytimg.com, i2.ytimg.com, i3, etc.
            ".ytimg.com/vi/".getBytes(StandardCharsets.US_ASCII),
            "youtube.com/watch?v=".getBytes(StandardCharsets.US_ASCII));

    private static final byte[] HORIZONTAL_SHELF_BYTES =
            "horizontal_shelf.e".getBytes(StandardCharsets.US_ASCII);

    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();
    private static String flyoutVideoId = "";
    private static String currentButtonName = "";
    private static int currentButtonIndex;

    // All methods are called on main thread.

    /**
     * Injection point.
     */
    public static void setBottomSheetFlyout(Dialog dialog) {
        flyoutDialog = dialog;
    }

    public static void dismissBottomSheetFlyout() {
        if (flyoutDialog == null) {
            return;
        }
        flyoutVideoId = "";
        flyoutDialog.dismiss();
    }

    /**
     * Injection point.
     */
    public static void setPopupWindowFlyout(PopupWindow popupWindow) {
        flyoutPopupWindow = popupWindow;
    }

    public static void dismissPopupWindowFlyout() {
        if (flyoutPopupWindow == null) {
            return;
        }
        flyoutVideoId = "";
        flyoutPopupWindow.dismiss();
    }

    /**
     * Injection point.
     */
    public static void extractVideoId(Map<?, ?> map) {
        extractVideoId(map.get("com.google.android.libraries.youtube.innertube.endpoint.tag"));
    }

    /**
     * Injection point.
     */
    public static void extractVideoId(Object bufferObject) {
        try {
            Logger.printDebug(() -> "FlyoutBuffer class: " + bufferObject.getClass());

            if (bufferObject instanceof FlyoutMenuVideoIdInterface videoIdInterface) {
                String videoId = videoIdInterface.patch_getVideoId();
                if (videoId == null) {
                    Logger.printDebug(() -> "VideoId is null"); // Should never happen.
                }
                Logger.printDebug(() -> "Found flyout videoId: " + videoId);
                flyoutVideoId = videoId;
                visibleFlyoutButtons.clear();
                return;
            }

            if (!(bufferObject instanceof ProtocolBufferFieldInterface bufferInterface)) {
                return;
            }

            visibleFlyoutButtons.clear();

            byte[] flyoutBuffer = bufferInterface.patch_getBuffer();
            if (flyoutBuffer == null) {
                Logger.printDebug(() -> "FlyoutBuffer is null"); // Should never happen.
                return;
            }

            if (Settings.DEBUG_PROTOBUFFER.get()) {
                Logger.printDebug(() -> "Flyout buffer: " +
                        new LithoFilterPatch.BufferAsciiStrings(flyoutBuffer).getStrings());
            }

            if (indexOf(flyoutBuffer, HORIZONTAL_SHELF_BYTES) >= 0) {
                // The buffer contains the video id of all items in the shelf,
                // meaning when the flyout queue button is used it needs to figure out
                // which of those video id's the flyout belongs to.
                // The major place this is an issue is the 'You' tab horizontal history shelf.
                Logger.printDebug(() -> "Ignoring flyout buffer containing a horizontal shelf");
                return;
            }

            for (byte[] VIDEO_ID_PREFIX_BYTES : VIDEO_ID_PREFIXES_BYTES) {
                final int index = indexOf(flyoutBuffer, VIDEO_ID_PREFIX_BYTES);

                if (index >= 0) {
                    final int youTubeVideoIdLength = 11;
                    final int videoIdStart = index + VIDEO_ID_PREFIX_BYTES.length;
                    final int videoIdEnd = videoIdStart + youTubeVideoIdLength;

                    if (videoIdEnd <= flyoutBuffer.length) {
                        flyoutVideoId = new String(
                                flyoutBuffer,
                                videoIdStart,
                                youTubeVideoIdLength,
                                StandardCharsets.US_ASCII
                        );
                        Logger.printDebug(() -> "Found flyout videoId: " + flyoutVideoId);
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "extractVideoIdFromFlyoutBuffer failure", ex);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static int indexOf(byte[] haystack, byte[] needle) {
        final int needleLength = needle.length;
        for (int i = 0, lastIndex = haystack.length - needleLength; i <= lastIndex; i++) {
            boolean found = true;
            for (int j = 0; j < needleLength; j++) {
                if (haystack[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    /**
     * Injection point.
     */
    public static void setCurrentButtonInfo(@Nullable Enum<?> buttonEnum, @Nullable CharSequence buttonText) {
        if (buttonEnum == null || buttonText == null || buttonText.toString().isEmpty()) {
            return;
        }
        currentButtonName = buttonEnum.name();
        currentButtonIndex++;

        visibleFlyoutButtons.add(new Pair<>(currentButtonName, currentButtonIndex));
    }

    /**
     * Injection point.
     */
    public static Runnable replaceButtonRunnable(Runnable original) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return original;
        }

        if (flyoutVideoId.isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            return original;
        }

        return invokeQueueFlyout(original, currentButtonName);
    }

    /**
     * Injection point.
     * -
     * 21.04 and older.
     */
    public static boolean replaceOnItemClick(int index) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return false;
        }

        if (flyoutVideoId.isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            return false;
        }

        try {
            if (!visibleFlyoutButtons.isEmpty()) {
                String currentIndexedButtonName = visibleFlyoutButtons.get(index).first;

                invokeQueueFlyout(null, currentIndexedButtonName).run();
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOnItemClick failure", ex);
        }
        return false;
    }

    private static Runnable invokeQueueFlyout(@Nullable Runnable original, String buttonName) {
        return () -> {
            if (buttonName.equals(queueButtonName)) {
                Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + flyoutVideoId);
                PlaylistPatch.prepareDialogBuilder(getContext(), flyoutVideoId);
                dismissBottomSheetFlyout(); // Must dismiss after showing dialog.
                dismissPopupWindowFlyout();
                return;
            }
            if (buttonName.equals(shareButtonName)) {
                // It is necessary to check whether the Share
                // button is of type Flyout or Action.
                enableIsFlyoutShareButton();
            }

            if (original != null) {
                original.run();
            }
        };
    }

    public static String getFlyoutVideoId() {
        return flyoutVideoId;
    }
}
