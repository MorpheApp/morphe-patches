/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getActivity;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.View;
import android.view.ViewParent;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
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

    private static WeakReference<View> senderViewObjectRef = new WeakReference<>(null);

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
        if (dialog == null) {
            return;
        }

        flyoutDialog = dialog;

        runFlyoutPanelVisibilityHandler(dialog);
    }

    public static void dismissBottomSheetFlyout() {
        if (flyoutDialog == null) {
            return;
        }

        flyoutDialog.dismiss();
    }

    /**
     * Injection point.
     */
    public static void setPopupWindowFlyout(PopupWindow popupWindow) {
        if (popupWindow == null) {
            return;
        }

        flyoutPopupWindow = popupWindow;

        runFlyoutPanelVisibilityHandler(popupWindow);
    }

    public static void dismissPopupWindowFlyout() {
        if (flyoutPopupWindow == null) {
            return;
        }
        flyoutPopupWindow.dismiss();
    }

    // Since we do not want to overwrite the object's original listener, we will
    // use a handler to check if the flyout panel is still visible
    // and, if not, reset the `flyoutVideoId` variable.
    private static void runFlyoutPanelVisibilityHandler(Object flyoutObject) {
        if (flyoutObject == null) {
            return;
        }

        final Handler visibilityHandler = new Handler(Looper.getMainLooper());
        visibilityHandler.post(new Runnable() {
            @Override
            public void run() {
                final boolean isShowing;
                if (flyoutObject instanceof Dialog flyoutDialogHandler) {
                    isShowing = flyoutDialogHandler.isShowing();
                } else if (flyoutObject instanceof PopupWindow flyoutPopupWindowHandler) {
                    isShowing = flyoutPopupWindowHandler.isShowing();
                } else {
                    isShowing = false;
                }

                if (isShowing) {
                    visibilityHandler.postDelayed(this, 100);
                } else {
                    // Apply a delay of 500ms to provide a time window for
                    // reading the video flyout ID in other patches.
                    Utils.runOnMainThreadDelayed(
                            () -> flyoutVideoId = "",
                            500
                    );
                }
            }
        });
    }

    /**
     * Injection point.
     */
    public static void extractVideoId(Map<?, ?> map) {
        senderViewObjectRef = new WeakReference<>(
                (View) map.get("com.google.android.libraries.youtube.rendering.elements.sender_view")
        );

        extractVideoId(map.get("com.google.android.libraries.youtube.innertube.endpoint.tag"));
    }

    /**
     * Injection point.
     */
    public static void extractVideoId(@Nullable Object bufferObject) {
        try {
            Logger.printDebug(() -> "FlyoutBuffer class: " + ((bufferObject == null) ? null : bufferObject.getClass()));

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
                byte[] debugFlyoutBuffer = flyoutBuffer;
                Logger.printDebug(() -> "Flyout buffer: " +
                        new BufferAsciiStrings(debugFlyoutBuffer).getStrings());
            }

            if (indexOf(flyoutBuffer, HORIZONTAL_SHELF_BYTES) >= 0) {
                View senderViewObject = senderViewObjectRef.get();

                if (senderViewObject != null) {
                    ViewParent viewObjectParent = senderViewObject.getParent();

                    while (viewObjectParent != null) {
                        if (viewObjectParent instanceof ComponentHost componentHost) {
                            CharSequence contentDescriptionChars = componentHost.getContentDescription();

                            if (contentDescriptionChars != null) {
                                String contentDescription = contentDescriptionChars.toString();

                                flyoutBuffer = getTrimmedHorizontalShelfBuffer(flyoutBuffer, contentDescription);
                            }
                        }

                        viewObjectParent = viewObjectParent.getParent();
                    }
                }
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
     * Sliding Window with Keyword Density Matching -
     * The description of the ComponentHost item is compared against the various 'horizontal shelf'
     * items listed in the buffer, until the matching index is found and a trimmed buffer
     * (that start from the matched index, in order to return the right video Id)
     * is returned. Otherwise, return the original buffer.
     */
    public static byte[] getTrimmedHorizontalShelfBuffer(byte[] buffer, String description) {
        if (description == null || buffer == null || description.isEmpty()) {
            return buffer;
        }

        String[] parts = description.split(" - ");
        if (parts.length == 0) {
            return buffer;
        }

        String title = parts[0].toLowerCase(Locale.ROOT).replaceAll("[^a-zA-Z0-9\\s]", "");
        String[] rawWords = title.split("\\s+");
        List<byte[]> words = new ArrayList<>();

        for (String w : rawWords) {
            if (w.length() > 2) {
                words.add(w.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (words.isEmpty()) {
            return buffer;
        }

        int bestIdx = -1;
        int maxScore = 0;
        int len = buffer.length;
        int windowSize = 200;

        for (int i = 0; i <= len - windowSize; i += 20) {
            int score = 0;
            for (byte[] w : words) {
                boolean found = false;
                int endLimit = i + windowSize - w.length;

                for (int j = i; j <= endLimit; j++) {
                    int k = 0;
                    while (k < w.length) {
                        byte b = buffer[j + k];
                        byte processedByte = (b >= 65 && b <= 90) ? (byte) (b + 32) : b;
                        if (processedByte != w[k]) {
                            break;
                        }
                        k++;
                    }
                    if (k == w.length) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    score++;
                }
            }
            if (score > maxScore) {
                maxScore = score;
                bestIdx = i;
            }
        }

        int requiredScore = Math.max(1, (int) Math.ceil(words.size() * 0.4));
        if (bestIdx != -1 && maxScore >= requiredScore) {
            return Arrays.copyOfRange(buffer, bestIdx, len);
        }

        return buffer;
    }

    /**
     * Injection point.
     */
    public static void setCurrentButtonInfo(@Nullable Enum<?> buttonEnum, @Nullable Object buttonInfo) {
        if (buttonEnum == null) {
            return;
        }

        if (buttonInfo instanceof CharSequence charSequence && charSequence.toString().isEmpty()) {
            return;
        }

        if (buttonInfo instanceof View view && view.getVisibility() == View.GONE) {
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

        return getNewRunnable(original, currentButtonName);
    }

    /**
     * Injection point.
     * -
     * 21.04 and older.
     */
    public static boolean replaceOnItemClick(Object object) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return false;
        }

        if (flyoutVideoId.isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            return false;
        }

        int buttonIndex = -1;
        String buttonName = "";

        if (object instanceof Integer index) {
            buttonIndex = index;
        } else if (object instanceof String name) {
            buttonName = name;
        }

        try {
            if (!visibleFlyoutButtons.isEmpty()) {
                if (buttonIndex >= 0) {
                    return flyoutButtonClickLogic(visibleFlyoutButtons.get(buttonIndex).first);
                } else if (!buttonName.isEmpty()) {
                    return flyoutButtonClickLogic(buttonName);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOnItemClick failure", ex);
        }
        return false;
    }

    private static Runnable getNewRunnable(@Nullable Runnable original, String buttonName) {
        return () -> {
            if (flyoutButtonClickLogic(buttonName)) {
                return;
            }

            if (original != null) {
                original.run();
            }
        };
    }

    private static boolean flyoutButtonClickLogic(String buttonName) {
        if (buttonName.equals(queueButtonName)) {
            Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + flyoutVideoId);

            Activity activity = getActivity();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                PlaylistPatch.prepareDialogBuilder(getActivity(), flyoutVideoId);
            }

            dismissBottomSheetFlyout(); // Must dismiss after showing dialog.
            dismissPopupWindowFlyout();
            return true;
        }

        return false;
    }

    public static String getFlyoutVideoId() {
        return flyoutVideoId;
    }
}
