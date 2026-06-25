/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getContext;

import android.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.components.LithoFilterPatch;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OverrideFeedFlyoutButtonRunnablePatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface ProtocolBufferFieldInterface {
        // Method is added during patching.
        byte[] patch_getBuffer();
    }

    private static final String queueButtonName = "QUEUE_PLAY_NEXT";

    private static String flyoutVideoId = "";
    private static String currentHandledButtonName = "";
    private static int currentHandledButtonIndex = 0;
    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();

    public static String getFlyoutVideoId() {
        return flyoutVideoId;
    }

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

            Matcher matcher = Pattern.compile(
                    "https://i\\.ytimg\\.com/vi/([a-zA-Z0-9_-]{11})"
            ).matcher(new LithoFilterPatch.BufferAsciiStrings(flyoutBuffer).getStrings());

            if (matcher.find()) {
                flyoutVideoId = matcher.group(1);
                Logger.printDebug(() -> "extractVideoIdFromFlyout: VideoId extracted: " + flyoutVideoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "extractVideoIdFromFlyoutBuffer failure", ex);
        }
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
                Objects.equals(currentHandledButtonName, queueButtonName)
                ? invokeQueueFlyout()
                : original;
    }

    /**
     * Injection point.
     */
    public static boolean replaceOnItemClick(int index) {
        if (Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            if (Objects.equals(visibleFlyoutButtons.get(index).first, queueButtonName)) {
                invokeQueueFlyout().run();
                return true;
            }
        }
        return false;
    }

    private static Runnable invokeQueueFlyout() {
        return () -> {
            if (flyoutVideoId.isEmpty()) {
                Logger.printDebug(() -> "invokeQueueFlyout: Cannot opening custom queue flyout with an empty videoId.");
                return;
            }
            Logger.printDebug(() -> "invokeQueueFlyout: Opening custom queue flyout with videoId: " + flyoutVideoId);
            PlaylistPatch.prepareDialogBuilder(getContext(), flyoutVideoId);
        };
    }
}
