/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;

@SuppressWarnings("unused")
public final class FlyoutUtils {

    public interface ProtocolBufferFieldInterface {
        byte[] patch_getBuffer();
    }

    public interface FlyoutMenuVideoIdInterface {
        String patch_getVideoId();
    }

    private static WeakReference<View> senderViewObjectRef = new WeakReference<>(null);

    private static Dialog flyoutDialog = null;
    private static PopupWindow flyoutPopupWindow = null;
    private static String flyoutVideoId = "";
    private static String flyoutCommentId = "";

    private static final List<byte[]> VIDEO_ID_PREFIXES_BYTES = List.of(
            ".ytimg.com/vi/".getBytes(StandardCharsets.US_ASCII),
            "youtube.com/watch?v=".getBytes(StandardCharsets.US_ASCII));

    private static final byte[] HORIZONTAL_SHELF_BYTES =
            "horizontal_shelf.e".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LIST_ITEM_BYTES =
            "list_item.e".getBytes(StandardCharsets.US_ASCII);

    public static String getFlyoutVideoId() {
        return flyoutVideoId;
    }

    public static void setBottomSheetFlyout(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        flyoutDialog = dialog;
        runFlyoutPanelVisibilityHandler(dialog);
    }

    public static void dismissBottomSheetFlyout() {
        if (flyoutDialog != null) {
            flyoutDialog.dismiss();
        }
    }

    public static void setPopupWindowFlyout(PopupWindow popupWindow) {
        if (popupWindow == null) {
            return;
        }
        flyoutPopupWindow = popupWindow;
        runFlyoutPanelVisibilityHandler(popupWindow);
    }

    public static void dismissPopupWindowFlyout() {
        if (flyoutPopupWindow != null) {
            flyoutPopupWindow.dismiss();
        }
    }

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
    public static void extractIdFromLithoButton(Map<?, ?> map) {
        if (Objects.equals(EngagementPanel.getId(), "comment-item-section")) {
            extractVideoId(map);
        }
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
                if (videoId != null) {
                    flyoutVideoId = videoId;
                }
                return;
            }

            if (!(bufferObject instanceof ProtocolBufferFieldInterface bufferInterface)) {
                return;
            }

            byte[] flyoutBuffer = bufferInterface.patch_getBuffer();
            if (flyoutBuffer == null) {
                return;
            }

            if (Settings.DEBUG_PROTOBUFFER.get()) {
                byte[] debugFlyoutBuffer = flyoutBuffer;
                Logger.printDebug(() -> "Flyout buffer: " + new BufferAsciiStrings(debugFlyoutBuffer).getStrings());
            }

            if (indexOf(flyoutBuffer, LIST_ITEM_BYTES) == -1) {
                if (indexOf(flyoutBuffer, HORIZONTAL_SHELF_BYTES) >= 0) {
                    View senderViewObject = senderViewObjectRef.get();
                    if (senderViewObject != null) {
                        ViewParent viewObjectParent = senderViewObject.getParent();
                        while (viewObjectParent != null) {
                            if (viewObjectParent instanceof ComponentHost componentHost) {
                                CharSequence contentDescriptionChars = componentHost.getContentDescription();
                                if (contentDescriptionChars != null) {
                                    flyoutBuffer = getTrimmedHorizontalShelfBuffer(flyoutBuffer, contentDescriptionChars.toString());
                                }
                            }
                            viewObjectParent = viewObjectParent.getParent();
                        }
                    }
                }

                for (byte[] VIDEO_ID_PREFIX_BYTES : VIDEO_ID_PREFIXES_BYTES) {
                    final int index = indexOf(flyoutBuffer, VIDEO_ID_PREFIX_BYTES);
                    if (index >= 0) {
                        final int videoIdStart = index + VIDEO_ID_PREFIX_BYTES.length;
                        final int videoIdEnd = videoIdStart + 11;
                        if (videoIdEnd <= flyoutBuffer.length) {
                            flyoutVideoId = new String(flyoutBuffer, videoIdStart, 11, StandardCharsets.US_ASCII);
                            break;
                        }
                    }
                }
            } else {
                setCommentId(flyoutBuffer);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "extractVideoId failure", ex);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }
    private static int indexOf(byte[] haystack, byte[] needle, int startIndex) {
        if (startIndex < 0) {
            startIndex = 0;
        }
        final int needleLength = needle.length;
        for (int i = startIndex, lastIndex = haystack.length - needleLength; i <= lastIndex; i++) {
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

    public static byte[] getTrimmedHorizontalShelfBuffer(byte[] buffer, String description) {
        if (description == null || buffer == null || description.isEmpty()) return buffer;

        String[] parts = description.split(" - ");
        if (parts.length == 0) return buffer;

        String title = parts[0].toLowerCase(Locale.ROOT).replaceAll("[^a-zA-Z0-9\\s]", "");
        List<byte[]> words = new ArrayList<>();
        for (String w : title.split("\\s+")) {
            if (w.length() > 2) {
                words.add(w.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (words.isEmpty()) return buffer;

        int bestIdx = -1;
        int maxScore = 0;
        int len = buffer.length;
        int windowSize = 200;

        for (int i = 0; i <= len - windowSize; i += 20) {
            int score = 0;
            for (byte[] w : words) {
                boolean found = false;
                for (int j = i; j <= i + windowSize - w.length; j++) {
                    int k = 0;
                    while (k < w.length) {
                        byte b = buffer[j + k];
                        if (((b >= 65 && b <= 90) ? (byte) (b + 32) : b) != w[k]) break;
                        k++;
                    }
                    if (k == w.length) {
                        found = true;
                        break;
                    }
                }
                if (found) score++;
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

    private static void setCommentId(byte[] buffer) {
        try {
            int bestStart = -1, bestEnd = -1, maxLen = 0, curr = 0;

            // Ensure the string is a base64 value and not a false-positive.
            while (curr < buffer.length) {
                int start = curr;
                while (curr < buffer.length) {
                    byte b = buffer[curr];
                    if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                            || b == '+' || b == '/' || b == '=' || b == '-' || b == '_') {
                        curr++;
                    } else {
                        break;
                    }
                }
                int len = curr - start;
                if (len > maxLen) {
                    maxLen = len;
                    bestStart = start;
                    bestEnd = curr;
                }
                if (len == 0) curr++;
            }
            if (maxLen < 150) {
                Logger.printException(() -> "extractCommentId failure: No base64 string found!");
                return;
            }

            // Extract the Comment ID from the fetched base64 decoded buffer.
            byte[] byteBase64 = Base64.decode(Arrays.copyOfRange(buffer, bestStart, bestEnd), Base64.URL_SAFE);
            int base64VideoIdIndex = indexOf(byteBase64, VideoInformation.getVideoId().getBytes(StandardCharsets.UTF_8));

            if (base64VideoIdIndex == -1) {
                Logger.printException(() -> "extractCommentId failure: No videoId found in the decoded base64 string!");
                return;
            }

            byte[] rawCommentId = Arrays.copyOfRange(byteBase64, 0, base64VideoIdIndex);

            flyoutCommentId = new String(rawCommentId, StandardCharsets.UTF_8)
                    .replaceAll("[^A-Za-z0-9_.-]", " ")
                    .trim()
                    .split(" ")[0];

            // Reset 'flyoutCommentId' immediately after its fetching (when the comment
            // share flyout button is pressed), to prevent unintended usage.
            Utils.runOnMainThreadDelayed(() -> flyoutCommentId = "", 500);
        } catch (Exception ex) {
            Logger.printException(() -> "extractCommentId failure", ex);
        }
    }

    public static String getFlyoutCommentId() {
        return flyoutCommentId;
    }
}
