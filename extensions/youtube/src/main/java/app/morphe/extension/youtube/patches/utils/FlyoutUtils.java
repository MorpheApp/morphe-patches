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
import java.util.regex.Pattern;

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

    public static final int CHANNEL_ID_LENGTH = 24;
    private static final List<byte[]> VIDEO_ID_PREFIXES_BYTES = List.of(
            getAsciiBytes(".ytimg.com/vi/"),
            getAsciiBytes("youtube.com/watch?v="));
    private static final byte[] HORIZONTAL_SHELF_BYTES = getAsciiBytes("horizontal_shelf.e");
    private static final byte[] LIST_ITEM_BYTES = getAsciiBytes("list_item.e");

    private static final Pattern TITLE_CLEANUP_PATTERN = Pattern.compile("[^a-zA-Z0-9\\s]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern COMMENT_ID_CLEANUP_PATTERN = Pattern.compile("[^A-Za-z0-9_.-]");

    private static WeakReference<View> senderViewRef = new WeakReference<>(null);

    private static Dialog flyoutDialog;
    private static PopupWindow flyoutPopupWindow;
    private static String flyoutVideoId = "";
    private static String flyoutCommentId = "";

    public static byte[] getAsciiBytes(String string) {
        return string.getBytes(StandardCharsets.US_ASCII);
    }

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
        senderViewRef = new WeakReference<>(
                (View) map.get("com.google.android.libraries.youtube.rendering.elements.sender_view")
        );
        extractVideoId(map.get("com.google.android.libraries.youtube.innertube.endpoint.tag"));
    }

    /**
     * Injection point.
     */
    public static void extractVideoId(@Nullable Object bufferObject) {
        try {
            Logger.printDebug(() -> "FlyoutBuffer class: " + ((bufferObject == null) ? null
                    : bufferObject.getClass()));

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
                final byte[] flyoutBufferLog = flyoutBuffer;
                Logger.printDebug(() -> "Flyout buffer: " + new BufferAsciiStrings(flyoutBufferLog).getStrings());
            }

            if (indexOf(flyoutBuffer, LIST_ITEM_BYTES) < 0) {
                if (indexOf(flyoutBuffer, HORIZONTAL_SHELF_BYTES) >= 0) {
                    View senderView = senderViewRef.get();
                    if (senderView != null) {
                        ViewParent parent = senderView.getParent();
                        while (parent != null) {
                            if (parent instanceof ComponentHost componentHost) {
                                final CharSequence description = componentHost.getContentDescription();
                                if (description != null) {
                                    flyoutBuffer = getTrimmedHorizontalShelfBuffer(flyoutBuffer, description.toString());
                                }
                            }
                            parent = parent.getParent();
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

    public static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }
    public static int indexOf(byte[] haystack, byte[] needle, int startIndex) {
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

    /**
     * Validates if the buffer contains a valid YouTube channel ID at the given index.
     * YouTube channel IDs are always 24 characters long, starting with the prefix "UC".
     * The remaining 22 characters are Base64 URL-safe: A-Z, a-z, 0-9, hyphen (-), and underscore (_).
     *
     * @param buffer The buffer to check.
     * @param index  The start index of the "UC" prefix.
     * @return True if it is a valid channel ID.
     */
    public static boolean isValidChannelId(byte[] buffer, int index) {
        final int lastIndex = index + CHANNEL_ID_LENGTH;
        if (index < 0 || lastIndex > buffer.length) {
            return false;
        }

        if (buffer[index] != 'U' || buffer[index + 1] != 'C') {
            return false;
        }

        for (int i = index + 2; i < lastIndex; i++) {
            final byte b = buffer[i];
            final boolean isValid = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') ||
                    (b >= '0' && b <= '9') || b == '-' || b == '_';
            if (!isValid) {
                return false;
            }
        }
        return true;
    }

    public static byte[] getTrimmedHorizontalShelfBuffer(byte[] buffer, String description) {
        if (description == null || buffer == null || description.isEmpty()) return buffer;

        final int separatorIndex = description.indexOf(" - ");
        String titlePart = separatorIndex == -1 ? description : description.substring(0, separatorIndex);
        if (titlePart.isEmpty()) return buffer;

        String title = TITLE_CLEANUP_PATTERN.matcher(titlePart.toLowerCase(Locale.ROOT)).replaceAll("");
        List<byte[]> words = new ArrayList<>();
        for (String w : WHITESPACE_PATTERN.split(title)) {
            if (w.length() > 2) {
                words.add(w.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (words.isEmpty()) return buffer;

        int bestIdx = -1;
        int maxScore = 0;
        final int len = buffer.length;
        final int windowSize = 200;

        for (int i = 0, iMaxIndex = len - windowSize; i <= iMaxIndex; i += 20) {
            int score = 0;
            for (byte[] w : words) {
                boolean found = false;
                final int wLength = w.length;
                for (int j = i, jMaxIndex = i + windowSize - wLength; j <= jMaxIndex; j++) {
                    int k = 0;
                    while (k < wLength) {
                        byte b = buffer[j + k];
                        if (((b >= 65 && b <= 90) ? (byte) (b + 32) : b) != w[k]) break;
                        k++;
                    }
                    if (k == wLength) {
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

        final int requiredScore = Math.max(1, (int) Math.ceil(words.size() * 0.4));
        if (bestIdx != -1 && maxScore >= requiredScore) {
            return Arrays.copyOfRange(buffer, bestIdx, len);
        }

        return buffer;
    }

    private static void setCommentId(byte[] buffer) {
        try {
            int bestStart = -1, bestEnd = -1, maxLen = 0, curr = 0;
            final int bufferLength = buffer.length;

            // Ensure the string is a base64 value and not a false-positive.
            while (curr < bufferLength) {
                int start = curr;
                while (curr < bufferLength) {
                    final byte b = buffer[curr];
                    final boolean isBase64 = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') ||
                            (b >= '0' && b <= '9') || b == '+' || b == '/' || b == '=' ||
                            b == '-' || b == '_';
                    if (isBase64) {
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
            final int base64VideoIdIndex = indexOf(byteBase64,
                    VideoInformation.getVideoId().getBytes(StandardCharsets.UTF_8));

            if (base64VideoIdIndex < 0) {
                Logger.printException(() -> "extractCommentId failure: No videoId found in the decoded base64 string!");
                return;
            }

            byte[] rawCommentId = Arrays.copyOfRange(byteBase64, 0, base64VideoIdIndex);

            String cleanedCommentId = COMMENT_ID_CLEANUP_PATTERN.matcher(
                            new String(rawCommentId, StandardCharsets.UTF_8))
                    .replaceAll(" ")
                    .trim();

            final int spaceIndex = cleanedCommentId.indexOf(' ');
            flyoutCommentId = spaceIndex == -1 ? cleanedCommentId : cleanedCommentId.substring(0, spaceIndex);

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
