/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getContext;

import android.util.Pair;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface ProtocolBufferFieldInterface {
        // Method is added during patching.
        byte[] patch_getBuffer();
    }

    private static final String queueButtonName = "QUEUE_PLAY_NEXT";

    private static final byte[] VIDEO_ID_PREFIX_BYTES =
            "https://i.ytimg.com/vi/".getBytes(StandardCharsets.US_ASCII);

    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();
    private static String flyoutVideoId = "";
    private static String currentHandledButtonName = "";
    private static int currentHandledButtonIndex;

    // All methods are called on main thread.

    /**
     * Injection point.
     */
    public static boolean overrideFlyoutBufferDisabler(boolean originalValue) {
        if (Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return false;
        }
        return originalValue;
    }

    /**
     * Injection point.
     */
    public static void extractVideoIdFromFlyoutBuffer(Object bufferObject) {
        try {
            if (!(bufferObject instanceof ProtocolBufferFieldInterface bufferInterface)) {
                return;
            }

            visibleFlyoutButtons.clear();

            byte[] flyoutBuffer = bufferInterface.patch_getBuffer();
            if (flyoutBuffer == null) return;

            final int index = indexOf(flyoutBuffer, VIDEO_ID_PREFIX_BYTES);

            if (index >= 0) {
                final int videoIdStart = index + VIDEO_ID_PREFIX_BYTES.length;
                final int videoIdEnd = videoIdStart + 11; // YouTube video id is 11 characters.

                if (videoIdEnd <= flyoutBuffer.length) {
                    flyoutVideoId = new String(flyoutBuffer, videoIdStart, 11,
                            StandardCharsets.US_ASCII);
                    Logger.printDebug(() -> "Found flyout videoId: " + flyoutVideoId);
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
    public static void setCurrentHandledButtonInfo(Enum<?> buttonEnum, CharSequence buttonText) {
        if (buttonEnum == null || buttonText.toString().isEmpty()) {
            return;
        }
        currentHandledButtonName = buttonEnum.name();
        currentHandledButtonIndex++;

        visibleFlyoutButtons.add(new Pair<>(currentHandledButtonName, currentHandledButtonIndex));
    }

    /**
     * Injection point.
     */
    public static Runnable replaceButtonRunnable(Runnable original) {
        return Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get() &&
                currentHandledButtonName.equals(queueButtonName)
                ? invokeQueueFlyout(original)
                : original;
    }

    /**
     * Injection point.
     */
    public static boolean replaceOnItemClick(int index) {
        try {
            if (Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get() && flyoutVideoId.isEmpty()
                    && !visibleFlyoutButtons.isEmpty()
                    && queueButtonName.equals(visibleFlyoutButtons.get(index).first)) {
                invokeQueueFlyout(null).run();
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOnItemClick failure", ex);
        }
        return false;
    }

    private static Runnable invokeQueueFlyout(@Nullable Runnable original) {
        return () -> {
            if (flyoutVideoId.isEmpty()) {
                Logger.printDebug(() -> "Cannot opening custom queue flyout with an empty videoId");
                if (original != null) original.run();
                return;
            }
            Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + flyoutVideoId);
            PlaylistPatch.prepareDialogBuilder(getContext(), flyoutVideoId);
        };
    }
}
