/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.youtube.patches.AddToQueuePatch.injectFlyoutElements;

import android.app.Dialog;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;
import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class FlyoutUtils {

    public interface ProtocolBufferFieldInterface {

        byte[] patch_getBuffer();
    }
    public interface FlyoutMenuVideoIdInterface {

        String patch_getVideoId();
    }
    public static byte[] getAsciiBytes(String string) {
        return string.getBytes(StandardCharsets.US_ASCII);
    }
    public static String getFlyoutVideoId() {
        return flyoutVideoId;
    }
    public static String getFlyoutPlaylistId() {
        return flyoutPlaylistId;
    }
    public static String getFlyoutCommentId() {
        return flyoutCommentId;
    }

    public static final int CHANNEL_ID_LENGTH = 24;
    private static final List<byte[]> VIDEO_ID_PREFIXES_BYTES = List.of(
            getAsciiBytes(".ytimg.com/vi/"),
            getAsciiBytes("youtube.com/watch?v=")
    );
    private static final byte[] PLAYLIST_ID_PREFIXES_BYTES = getAsciiBytes("youtube.com/playlist?list=");
    private static final byte[] COMPACT_PLAYLIST_BYTES = getAsciiBytes("compact_playlist.e");
    private static final List<byte[]> SHELFS_BYTES = List.of(
            getAsciiBytes("horizontal_shelf.e"),
            getAsciiBytes("shorts_shelf.e"),
            getAsciiBytes("grid_video_wrapper.e"),
            getAsciiBytes("rich_grid_row.e")
    );
    private static final List<byte[]> LIST_ITEM_SHARE_BYTES = List.of(
            getAsciiBytes("list_item.e"),
            getAsciiBytes("yt_outline_experimental_share")
    );

    private static final Pattern TITLE_CLEANUP_PATTERN = Pattern.compile("[^a-zA-Z0-9\\s]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern COMMENT_ID_CLEANUP_PATTERN = Pattern.compile("[^A-Za-z0-9_.-]");

    public static final int BLACK_COLOR = ResourceUtils.getColor("yt_black1");
    public static final int GREY_COLOR = ResourceUtils.getColor("yt_grey1");
    public static final int WHITE_COLOR = ResourceUtils.getColor("yt_white1");

    public record FlyoutMenuInfo(LinearLayout menuContainer, int adjustedIndex,
                                 boolean isPopupWindow, @Nullable PopupWindow popupWindow) {
    }

    private static WeakReference<View> senderViewRef = new WeakReference<>(null);

    private static Dialog flyoutDialog;
    private static PopupWindow flyoutPopupWindow;
    private static String flyoutVideoId = "";
    private static String flyoutPlaylistId = "";
    private static String flyoutCommentId = "";
    private static final List<String> commentsPanelNames = List.of(
            "comment-item-section",
            "shorts-comments-panel"
    );

    /**
     * Injection point.
     */
    public static void setBottomSheetFlyout(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        flyoutDialog = dialog;
        runFlyoutPanelVisibilityHandler(dialog);

        if (Settings.QUEUE_ADD_FLYOUT_MENU.get()) {
            dialog.setOnShowListener(dialogInterface -> {
                injectFlyoutElements(dialog);
            });
        }
    }

    public static void dismissBottomSheetFlyout() {
        if (flyoutDialog != null) {
            flyoutDialog.dismiss();
        }
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

        if (Settings.QUEUE_ADD_FLYOUT_MENU.get()) {
            injectFlyoutElements(popupWindow);
        }
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
                            () -> {
                                flyoutVideoId = "";
                                flyoutPlaylistId = "";
                            },
                            500
                    );
                }
            }
        });
    }

    @Nullable
    public static FlyoutMenuInfo getFlyoutMenuInfo(Object flyoutPanel, int initialIndex) {
        LinearLayout menuContainer = null;
        PopupWindow popupWindow = null;
        boolean isPopupWindow = false;
        int adjustedIndex = initialIndex;

        if (flyoutPanel instanceof PopupWindow checkedPopupWindow) {
            popupWindow = checkedPopupWindow;
            if (checkedPopupWindow.getContentView() instanceof FrameLayout frameLayout) {
                if (frameLayout.getChildAt(0) instanceof ViewGroup viewGroup &&
                        viewGroup.getChildAt(0) instanceof LinearLayout checkedMenuContainer) {
                    menuContainer = checkedMenuContainer;
                }
            }
            isPopupWindow = true;
        } else if (flyoutPanel instanceof Dialog checkedDialog) {
            Window window = checkedDialog.getWindow();
            if (window != null) {
                View decorView = window.getDecorView();
                final int containerId = ResourceUtils.getIdentifier(ResourceType.ID, "container");
                if (containerId != 0) {
                    View container = decorView.findViewById(containerId);
                    if (container instanceof FrameLayout frameLayout) {
                        if (frameLayout.getChildAt(0) instanceof ViewGroup coordinator &&
                                coordinator.getChildAt(1) instanceof ViewGroup nestedFrame) {
                            View menuRoot = nestedFrame.getChildAt(0);
                            if (menuRoot instanceof ViewGroup group &&
                                    group.getChildAt(0) instanceof LinearLayout linearLayout) {
                                menuContainer = linearLayout;
                                // Skip an index to inject the button after the bottom sheet handle.
                                adjustedIndex += 1;
                            }
                        }
                    }
                }
            }
        }

        if (menuContainer == null) {
            return null;
        }

        return new FlyoutMenuInfo(menuContainer, adjustedIndex, isPopupWindow, popupWindow);
    }

    public static View createFlyoutButton(Context context, @Nullable Drawable icon,
                                          String text, View.OnClickListener clickListener) {
        LinearLayout customButton = new LinearLayout(context);
        customButton.setOrientation(LinearLayout.HORIZONTAL);
        customButton.setGravity(Gravity.CENTER_VERTICAL);
        customButton.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
        customButton.setClickable(true);
        customButton.setBackgroundColor(Utils.isDarkModeEnabled()
                ? BLACK_COLOR
                : WHITE_COLOR
        );

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        layoutParams.rightMargin = Dim.dp16;

        if (icon != null) {
            ImageView iconView = new ImageView(context);
            iconView.setLayoutParams(layoutParams);

            Drawable mutableIcon = icon.mutate();
            mutableIcon.setTint(Utils.isDarkModeEnabled() ? WHITE_COLOR : BLACK_COLOR);
            mutableIcon.setTintMode(PorterDuff.Mode.SRC_IN);
            iconView.setImageDrawable(mutableIcon);

            customButton.addView(iconView);
        }

        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(16);
        textView.setTypeface(null, Typeface.BOLD);

        customButton.addView(textView);
        customButton.setOnClickListener(clickListener);

        return customButton;
    }

    public static View createFlyoutDivider(Context context) {
        final View divider = new View(context);
        final LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Dim.dp1
        );
        dividerParams.setMargins(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp4);
        divider.setLayoutParams(dividerParams);
        divider.setBackgroundColor(GREY_COLOR);

        return divider;
    }

    /**
     * Injection point.
     */
    public static void extractFlyoutIdFromLithoButton(Map<?, ?> map) {
        if ((PlayerType.getCurrent().isMaximizedOrFullscreen() || ShortsPlayerState.isOpen()) &&
                EngagementPanel.checkIdsInQueue(commentsPanelNames)) {
            extractFlyoutIdFromMap(map);
        }
    }

    /**
     * Injection point.
     */
    public static void extractFlyoutIdFromMap(Map<?, ?> map) {
        senderViewRef = new WeakReference<>(
                (View) map.get("com.google.android.libraries.youtube.rendering.elements.sender_view")
        );
        extractFlyoutIdFromObject(map.get("com.google.android.libraries.youtube.innertube.endpoint.tag"));
    }

    /**
     * Injection point.
     */
    public static void extractFlyoutIdFromObject(@Nullable Object bufferObject) {
        try {
            Logger.printDebug(() ->
                    "FlyoutBuffer class: " +
                    ((bufferObject == null)
                            ? null
                            : bufferObject.getClass())
            );

            if (bufferObject instanceof FlyoutMenuVideoIdInterface videoIdInterface) {
                final String videoId = videoIdInterface.patch_getVideoId();
                if (videoId != null) {
                    flyoutVideoId = videoId;
                }
                return;
            }

            if (!(bufferObject instanceof ProtocolBufferFieldInterface bufferInterface)) {
                return;
            }

            final byte[] flyoutBuffer = bufferInterface.patch_getBuffer();
            if (flyoutBuffer == null) {
                return;
            }

            if (Settings.DEBUG_PROTOBUFFER.get()) {
                Logger.printDebug(() -> "Flyout buffer: " + new BufferAsciiStrings(flyoutBuffer).getStrings());
            }

            // Check whether the buffer contains the specified IDs, within a certain initial
            // range of the buffer, to avoid matching with false positives.
            final List<Integer> listItemShareBytesIndexes = byteIndexesOf(flyoutBuffer, LIST_ITEM_SHARE_BYTES);
            if (!listItemShareBytesIndexes.isEmpty() && listItemShareBytesIndexes.size() == LIST_ITEM_SHARE_BYTES.size()) {
                if (byteIndexInStartRange(listItemShareBytesIndexes.get(0))) {
                    setFlyoutCommentId(flyoutBuffer);
                }
                return;
            }

            if (!byteIndexesOf(flyoutBuffer, SHELFS_BYTES).isEmpty()) {
                final View senderView = senderViewRef.get();
                if (senderView != null) {
                    ViewParent parent = senderView.getParent();
                    while (parent != null) {
                        if (parent instanceof ComponentHost componentHost) {
                            final CharSequence description = componentHost.getContentDescription();
                            if (description != null) {
                                setShelfFlyoutVideoId(flyoutBuffer, description.toString());
                            }
                        }
                        parent = parent.getParent();
                    }
                }
                return;
            }

            if (byteIndexInStartRange(byteIndexOf(flyoutBuffer, COMPACT_PLAYLIST_BYTES))) {
                setFlyoutPlaylistId(flyoutBuffer);
                return;
            }

            // Set 'flyoutVideoId' field, based on the rest of fetched litho elements.
            setFlyoutVideoId(flyoutBuffer);
        } catch (Exception ex) {
            Logger.printException(() -> "extractFlyoutId failure", ex);
        }
    }

    private static void setFlyoutVideoId(byte[] flyoutBuffer) {
        for (byte[] VIDEO_ID_PREFIX_BYTES : VIDEO_ID_PREFIXES_BYTES) {
            final int index = byteIndexOf(flyoutBuffer, VIDEO_ID_PREFIX_BYTES);
            if (index >= 0) {
                final int videoIdStart = index + VIDEO_ID_PREFIX_BYTES.length;
                final int videoIdEnd = videoIdStart + 11;
                if (videoIdEnd <= flyoutBuffer.length) {
                    flyoutVideoId = new String(flyoutBuffer, videoIdStart, 11, StandardCharsets.US_ASCII);
                    return;
                }
            }
        }
    }

    private static void setShelfFlyoutVideoId(byte[] buffer, String description) {
        if (description == null || buffer == null || description.isEmpty()) {
            return;
        }

        final int separatorIndex = description.indexOf(" - ");
        final String titlePart = separatorIndex == -1 ? description : description.substring(0, separatorIndex);
        if (titlePart.isEmpty()) {
            return;
        }
        final String title = TITLE_CLEANUP_PATTERN.matcher(titlePart.toLowerCase(Locale.ROOT)).replaceAll("");
        final List<byte[]> words = new ArrayList<>();
        for (String word : WHITESPACE_PATTERN.split(title)) {
            if (word.length() > 2) {
                words.add(word.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (words.isEmpty()) {
            return;
        }

        int bestIdx = -1;
        int maxScore = 0;
        final int len = buffer.length;
        final int windowSize = 200;
        for (int i = 0, iMaxIndex = len - windowSize; i <= iMaxIndex; i += 20) {
            int score = 0;

            for (byte[] word : words) {
                boolean found = false;

                final int wordLen = word.length;
                for (int j = i, jMaxIndex = i + windowSize - wordLen; j <= jMaxIndex; j++) {
                    int k = 0;
                    while (k < wordLen) {
                        final byte b = buffer[j + k];
                        if (((b >= 65 && b <= 90) ? (byte) (b + 32) : b) != word[k]) {
                            break;
                        }
                        k++;
                    }
                    if (k == wordLen) {
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

        final int requiredScore = Math.max(1, (int) Math.ceil(words.size() * 0.4));
        if (bestIdx != -1 && maxScore >= requiredScore) {
            setFlyoutVideoId(Arrays.copyOfRange(buffer, bestIdx, len));
        }
    }

    private static void setFlyoutPlaylistId(byte[] flyoutBuffer) {
        final int index = byteIndexOf(flyoutBuffer, PLAYLIST_ID_PREFIXES_BYTES);
        if (index >= 0) {
            final int playlistIdStart = index + PLAYLIST_ID_PREFIXES_BYTES.length;

            int playlistIdEnd = playlistIdStart;
            while (playlistIdEnd < flyoutBuffer.length) {
                byte b = flyoutBuffer[playlistIdEnd];
                if (!((b >= 'A' && b <= 'Z') ||
                        (b >= 'a' && b <= 'z') ||
                        (b >= '0' && b <= '9') ||
                        b == '-' ||
                        b == '_')) {
                    break;
                }
                playlistIdEnd++;
            }

            flyoutPlaylistId = new String(
                    flyoutBuffer,
                    playlistIdStart,
                    playlistIdEnd - playlistIdStart,
                    StandardCharsets.US_ASCII
            );
        }
    }

    private static void setFlyoutCommentId(byte[] buffer) {
        try {
            int bestStart = -1;
            int bestEnd = -1;
            int maxLen = 0;
            int curr = 0;

            final int bufferLength = buffer.length;
            // Ensure the string is a base64 value and not a false-positive.
            while (curr < bufferLength) {
                final int start = curr;

                while (curr < bufferLength) {
                    final byte b = buffer[curr];
                    final boolean isBase64 =
                            (b >= 'A' && b <= 'Z') ||
                            (b >= 'a' && b <= 'z') ||
                            (b >= '0' && b <= '9') ||
                            b == '+' ||
                            b == '/' ||
                            b == '=' ||
                            b == '-' ||
                            b == '_';

                    if (isBase64) {
                        curr++;
                    } else {
                        break;
                    }
                }

                final int len = curr - start;
                if (len > maxLen) {
                    maxLen = len;
                    bestStart = start;
                    bestEnd = curr;
                }
                if (len == 0) {
                    curr++;
                }
            }
            if (maxLen < 150) {
                Logger.printException(() -> "setCommentId failure: No base64 string found!");
                return;
            }

            // Get the Comment ID from the fetched base64 decoded buffer.
            final byte[] byteBase64 = Base64.decode(Arrays.copyOfRange(buffer, bestStart, bestEnd), Base64.URL_SAFE);
            final int base64VideoIdIndex = byteIndexOf(
                    byteBase64,
                    VideoInformation.getVideoId().getBytes(StandardCharsets.UTF_8)
            );
            if (base64VideoIdIndex < 0) {
                Logger.printException(() -> "setCommentId failure: No videoId found in the decoded base64 string!");
                return;
            }

            final byte[] rawCommentId = Arrays.copyOfRange(byteBase64, 0, base64VideoIdIndex);
            String cleanedCommentId = COMMENT_ID_CLEANUP_PATTERN.matcher(
                            new String(rawCommentId, StandardCharsets.UTF_8)
                    ).replaceAll(" ")
                    .trim();

            final int spaceIndex = cleanedCommentId.indexOf(' ');
            flyoutCommentId = spaceIndex == -1 ? cleanedCommentId : cleanedCommentId.substring(0, spaceIndex);

            // Reset 'flyoutCommentId' immediately after its fetching (when the comment
            // share flyout button is pressed), to prevent unintended usage.
            Utils.runOnMainThreadDelayed(() -> flyoutCommentId = "", 500);
        } catch (Exception ex) {
            Logger.printException(() -> "setCommentId failure", ex);
        }
    }

    public static int byteIndexOf(byte[] haystack, byte[] needle) {
        return byteIndexOf(haystack, needle, 0);
    }
    public static int byteIndexOf(byte[] haystack, byte[] needle, int startIndex) {
        if (needle == null) return -1;
        final List<Integer> indices = byteIndexesOf(haystack, List.of(needle), startIndex);
        return indices.isEmpty() ? -1 : indices.get(0);
    }
    public static List<Integer> byteIndexesOf(byte[] haystack, List<byte[]> needles) {
        return byteIndexesOf(haystack, needles, 0);
    }
    public static List<Integer> byteIndexesOf(byte[] haystack, List<byte[]> needles, int startIndex) {
        final List<Integer> indices = new ArrayList<>();
        if (haystack == null || needles == null) {
            return indices;
        }

        final int haystackLen = haystack.length;

        final boolean[] found = new boolean[needles.size()];
        for (int i = startIndex; i <= haystackLen; i++) {
            for (int k = 0; k < needles.size(); k++) {
                final byte[] needle = needles.get(k);
                if (found[k] || needle == null) {
                    continue;
                }

                final int needleLen = needle.length;
                if (needleLen == 0 || i + needleLen > haystackLen) {
                    continue;
                }

                boolean match = true;
                for (int j = 0; j < needleLen; j++) {
                    if (haystack[i + j] != needle[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    indices.add(i);
                    found[k] = true;
                }
            }
        }
        return indices;
    }

    private static boolean byteIndexInStartRange(int index) {
        return index >= 0 && index <= 30;
    }
}
