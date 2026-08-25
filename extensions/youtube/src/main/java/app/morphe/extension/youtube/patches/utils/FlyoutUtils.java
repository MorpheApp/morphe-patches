/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.utils.PlaylistPatch.QueueManager.OPEN_QUEUE;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
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
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.AddToQueuePatch;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;
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

    public record FlyoutMenuInfo(
            LinearLayout menuContainer,
            int adjustedIndex,
            boolean isPopupWindow,
            @Nullable PopupWindow popupWindow
    ) {}

    public static final int CHANNEL_ID_LENGTH = 24;
    private static final byte[] PLAYLIST_ID_PREFIXES_BYTES =
            getAsciiBytes("playlist?list=");
    private static final List<byte[]> VIDEO_ID_PREFIXES_BYTES = List.of(
            getAsciiBytes(".ytimg.com/vi/"),
            getAsciiBytes("youtube.com/watch?v=")
    );
    private static final List<byte[]> KIDS_VIDEO_ELEMENTS_BYTES = List.of(
            getAsciiBytes("video_metadata_carousel.e"),
            getAsciiBytes("com.google.android.apps.youtube.kids")
    );
    private static final List<byte[]> VIDEO_ELEMENTS_BYTES = List.of(
            getAsciiBytes("compact_playlist.e"),
            getAsciiBytes("compact_video.e"),
            getAsciiBytes("grid_video.e"),
            getAsciiBytes("grid_video_wrapper.e"),
            getAsciiBytes("horizontal_shelf.e"),
            getAsciiBytes("rich_grid_row.e"),
            getAsciiBytes("shorts_pivot_item.e"),
            getAsciiBytes("shorts_shelf.e"),
            getAsciiBytes("shorts_video_cell.e"),
            getAsciiBytes("swipeable_row.e"),
            getAsciiBytes("video_lockup_with_attachment.e")
    );
    private static final List<byte[]> LIST_ITEM_SHARE_BYTES = List.of(
            getAsciiBytes("list_item.e"),
            getAsciiBytes("yt_outline_experimental_share")
    );

    private static final Pattern TITLE_CLEANUP_PATTERN = Pattern.compile("[^\\p{L}\\p{N}\\s_&.'+-]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern COMMENT_ID_CLEANUP_PATTERN = Pattern.compile("[^A-Za-z0-9_.-]");

    private static final int SECONDARY_CONTAINER_ID =
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_secondary_container");
    private static final int ITEM_TEXT_ID =
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_text");
    private static final Drawable queueButtonDrawable = Utils.getContext()
            .getDrawable(OPEN_QUEUE.drawableId);
    private static final String queueButtonName = str("morphe_queue_flyout_title");
    private static final Drawable saveToWatchLaterDrawable =
            ResourceUtils.getDrawable(
                    LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                            ? "yt_outline_clock_black_24"
                            : "yt_outline_experimental_clock_vd_theme_24"
            );
    private static final String saveToWatchLaterButtonName = str("morphe_save_to_watch_later_flyout_title");

    private static WeakReference<TextView> customItemTextRef = new WeakReference<>(null);

    private static String currentButtonName = "";
    private static int currentButtonIndex;
    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();

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

    private static boolean videoMarkedAsForKids;

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

    public static void setVideoMarkedAsForKids(byte[] bytes) {
        List<Integer> kidsVideoElementsBytesIndexes = byteIndexesOf(bytes, KIDS_VIDEO_ELEMENTS_BYTES);
        if (!kidsVideoElementsBytesIndexes.isEmpty() && kidsVideoElementsBytesIndexes.size() == KIDS_VIDEO_ELEMENTS_BYTES.size()) {
            videoMarkedAsForKids = true;
        }
    }

    /**
     * Injection point.
     */
    public static void setVideoMarkedAsForKids() {
        videoMarkedAsForKids = false;
    }

    /**
     * Injection point.
     */
    public static void setBottomSheetFlyout(Dialog dialog) {
        try {
            if (dialog == null) {
                return;
            }
            flyoutDialog = dialog;
            runFlyoutPanelVisibilityHandler(dialog);

            Window window = dialog.getWindow();
            if (window == null) {
                Logger.printDebug(() -> "Cannot set flyout, window is null: " + dialog);
                return;
            }

            WeakReference<Dialog> dialogRef = new WeakReference<>(dialog);

            ViewTreeObserver viewTreeObserver = window.getDecorView().getViewTreeObserver();
            viewTreeObserver.addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        private boolean alreadyInjectedButton;

                        @Override
                        public void onGlobalLayout() {
                            try {
                                Dialog dialog = dialogRef.get();
                                if (dialog == null) {
                                    Logger.printDebug(() -> "Removing flyout listener");
                                    viewTreeObserver.removeOnGlobalLayoutListener(this);
                                    return;
                                }

                                if (dialog.isShowing()) {
                                    if (!alreadyInjectedButton) {
                                        addFlyoutElements(dialog);
                                        alreadyInjectedButton = true;
                                    }
                                    onFlyoutListBound(dialog);
                                } else {
                                    alreadyInjectedButton = false;
                                }
                            } catch (Exception ex) {
                                Logger.printException(() -> "setBottomSheetFlyout onGlobalLayout failure", ex);
                            }
                        }
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "setBottomSheetFlyout failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setPopupWindowFlyout(PopupWindow popupWindow) {
        try {
            if (popupWindow == null) {
                return;
            }
            flyoutPopupWindow = popupWindow;
            runFlyoutPanelVisibilityHandler(popupWindow);

            addFlyoutElements(popupWindow);
            onFlyoutListBound(popupWindow);
        } catch (Exception ex) {
            Logger.printException(() -> "setPopupWindowFlyout failure", ex);
        }
    }

    public static void dismissFlyout() {
        visibleFlyoutButtons.clear();
        currentButtonIndex = 0;

        if (flyoutDialog != null) {
            flyoutDialog.dismiss();
            flyoutDialog = null;
        }

        if (flyoutPopupWindow != null) {
            flyoutPopupWindow.dismiss();
            flyoutPopupWindow = null;
        }
    }

    private static void addFlyoutElements(Object flyoutPanel) {
        int nextButtonIndex = 0;

        // TODO: Add playlists compatibility to Morphe's queue.
        if (Settings.QUEUE_ADD_FLYOUT_MENU.get() &&
                flyoutPlaylistId.isEmpty() &&
                !flyoutVideoId.isEmpty()) {
            nextButtonIndex = addFlyoutButton(
                    flyoutPanel,
                    queueButtonDrawable,
                    queueButtonName,
                    v -> AddToQueuePatch.flyoutButtonClickLogic(
                            AddToQueuePatch.queueButtonOriginalNames.get(0)
                    ),
                    nextButtonIndex
            );
        }

        if (Settings.KIDS_SAVE_TO_WATCH_LATER_BUTTON.get() &&
                PlayerType.getCurrent().isMaximizedOrFullscreen() &&
                videoMarkedAsForKids) {
            nextButtonIndex = addFlyoutButton(
                    flyoutPanel,
                    saveToWatchLaterDrawable,
                    saveToWatchLaterButtonName,
                    v -> {
                        SaveToWatchLaterPatch.saveVideo(getFlyoutVideoId());

                        dismissFlyout(); // Must dismiss after showing dialog.
                    },
                    nextButtonIndex
            );
        }

        if (nextButtonIndex > 0) {
            addDivider(flyoutPanel, nextButtonIndex);
        }
    }

    /**
     * Applies the changes that are only possible once the menu list has bound its items.
     * Idempotent, so it can run on every layout pass and reapply itself after the app
     * binds the list again.
     */
    private static void onFlyoutListBound(Object flyoutPanel) {
        try {
            FlyoutMenuInfo menuInfo = getFlyoutMenuInfo(flyoutPanel, 0);
            if (menuInfo == null) {
                return;
            }

            // The items are inside the list, which is the last view of the menu container.
            LinearLayout menuContainer = menuInfo.menuContainer();
            View lastChild = menuContainer.getChildAt(menuContainer.getChildCount() - 1);
            if (!(lastChild instanceof ViewGroup itemList) || itemList.getChildCount() == 0) {
                return;
            }

            copyListItemTypeface(itemList);
            hideItemSecondaryIcon(itemList);
        } catch (Exception ex) {
            Logger.printException(() -> "onFlyoutListBound failure", ex);
        }
    }

    /**
     * Hides menu secondary icon.
     */
    private static void hideItemSecondaryIcon(ViewGroup itemList) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get() || SECONDARY_CONTAINER_ID == 0) {
            return;
        }

        int itemIndex = -1;
        for (Pair<String, Integer> button : visibleFlyoutButtons) {
            if (AddToQueuePatch.queueButtonOriginalNames.contains(button.first)) {
                itemIndex = button.second - 1;
                break;
            }
        }
        if (itemIndex < 0 || itemIndex >= itemList.getChildCount()) {
            return;
        }

        View badge = itemList.getChildAt(itemIndex).findViewById(SECONDARY_CONTAINER_ID);
        if (badge != null && badge.getVisibility() != View.GONE) {
            Logger.printDebug(() -> "Hiding the menu item secondary icon");
            badge.setVisibility(View.GONE);
        }
    }

    /**
     * The app applies its own font weight to the menu items after they are bound,
     * so the custom item only matches them by taking the typeface of a bound item.
     */
    private static void copyListItemTypeface(ViewGroup itemList) {
        TextView customItemText = customItemTextRef.get();
        if (customItemText == null || ITEM_TEXT_ID == 0) {
            return;
        }

        if (itemList.getChildAt(0).findViewById(ITEM_TEXT_ID) instanceof TextView itemText) {
            // setTypeface always requests a layout, so only call it when the font really differs.
            Typeface itemTypeface = itemText.getTypeface();
            if (customItemText.getTypeface() != itemTypeface) {
                customItemText.setTypeface(itemTypeface);
            }
        }
    }

    /**
     * @return The height of the bottom sheet drag handle, or zero if the menu has no handle.
     * The handle is drawn over the top of the menu instead of being laid out in it,
     * so the first item has to be pushed down by its height.
     */
    private static int getDragHandleHeight(ViewGroup menuContainer) {
        for (int i = 0, count = menuContainer.getChildCount(); i < count; i++) {
            if (menuContainer.getChildAt(i) instanceof ImageView handle) {
                return handle.getHeight();
            }
        }

        return 0;
    }

    @SuppressWarnings("SameParameterValue")
    private static int addFlyoutButton(
            Object flyoutPanel,
            Drawable icon,
            String text,
            View.OnClickListener clickListener,
            int index
    ) {
        return addFlyoutMenuItem(flyoutPanel, icon, text, clickListener, index, false);
    }

    @SuppressWarnings("UnusedReturnValue")
    private static int addDivider(Object flyoutPanel, int index) {
        return addFlyoutMenuItem(flyoutPanel, null, null, null, index, true);
    }

    private static int addFlyoutMenuItem(
            Object flyoutPanel,
            @Nullable Drawable icon,
            @Nullable String text,
            @Nullable View.OnClickListener clickListener,
            int index,
            boolean isDivider
    ) {
        try {
            FlyoutMenuInfo menuInfo = getFlyoutMenuInfo(flyoutPanel, index);
            if (menuInfo == null) {
                return -1;
            }

            Context context = Utils.getActivity();
            if (context == null) {
                return -1;
            }

            View view = isDivider
                    ? createFlyoutDivider(context)
                    : addFlyoutButton(context, menuInfo.menuContainer(), icon, text, clickListener);

            int fixedIndex = menuInfo.adjustedIndex();
            menuInfo.menuContainer().addView(view, fixedIndex);

            PopupWindow popupWindow = menuInfo.popupWindow();
            if (popupWindow != null) {
                popupWindow.update();
            }

            // For new layout only:
            // Skip an index to inject the next element after the current button.
            if (menuInfo.isPopupWindow()) {
                fixedIndex++;
            }

            return fixedIndex;
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutMenuItem failure", ex);
        }

        return -1;
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
        Logger.printDebug(() ->
                "Current renderized flyout button {" +
                        "Name:" + currentButtonName + "; " +
                        "Index: " + currentButtonIndex +
                "}"
        );
        currentButtonIndex++;

        visibleFlyoutButtons.add(new Pair<>(currentButtonName, currentButtonIndex));
    }

    private static boolean containsFlyoutButton(String buttonName) {
        for (Pair<String, Integer> button : visibleFlyoutButtons) {
            if (button.first.equals(buttonName)) {
                return true;
            }
        }

        return false;
    }

    public static List<Pair<String, Integer>> getVisibleFlyoutButtons() {
        return visibleFlyoutButtons;
    }

    public static String getCurrentButtonName() {
        return currentButtonName;
    }

    public static void resetCurrentButtonIndex() {
        currentButtonIndex = 0;
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
    private static FlyoutMenuInfo getFlyoutMenuInfo(Object flyoutPanel, int initialIndex) {
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

    @SuppressLint("ResourceType")
    private static View addFlyoutButton(
            Context context,
            ViewGroup parent,
            @Nullable Drawable icon,
            String text,
            View.OnClickListener clickListener
    ) {
        // Inflating the same layout the app uses for its own items keeps the row height,
        // paddings, font and icon size identical to them.
        // 20.21 has no modern layout and uses the older one for its own items.
        int layoutId = ResourceUtils.getIdentifier(
                ResourceType.LAYOUT, "modern_bottom_sheet_enableable_list_item");
        if (layoutId == 0) {
            layoutId = ResourceUtils.getIdentifier(
                    ResourceType.LAYOUT, "bottom_sheet_enableable_list_item");
        }

        View customButton = LayoutInflater.from(context).inflate(layoutId, parent, false);

        TextView textView = customButton.findViewById(ITEM_TEXT_ID);
        if (textView != null) {
            textView.setText(text);
            customItemTextRef = new WeakReference<>(textView);
        }

        ImageView iconView = customButton.findViewById(
                ResourceUtils.getIdentifier(ResourceType.ID, "list_item_icon_primary"));
        if (iconView != null && icon != null) {
            iconView.setImageDrawable(icon);
            // The layout tints the icon with ytIconInactive, but the menu items themselves
            // are drawn with the text color.
            iconView.setImageTintList(ColorStateList.valueOf(textView != null
                    ? textView.getCurrentTextColor()
                    : ThemeUtils.getAppForegroundColor()));
        }

        if (customButton.getLayoutParams() instanceof ViewGroup.MarginLayoutParams marginParams) {
            marginParams.topMargin = getDragHandleHeight(parent);
        }

        // The layout reserves space for a secondary icon this item does not have.
        View secondaryContainer = customButton.findViewById(SECONDARY_CONTAINER_ID);
        if (secondaryContainer != null) {
            secondaryContainer.setVisibility(View.GONE);
        }

        int[] attrs = {android.R.attr.selectableItemBackground};
        try (TypedArray typedArray = context.obtainStyledAttributes(attrs)) {
            customButton.setForeground(typedArray.getDrawable(0));
        }

        customButton.setOnClickListener(clickListener);

        return customButton;
    }

    public static View createFlyoutDivider(Context context) {
        int height = ResourceUtils.getDimensionPixelSize("line_separator_height");
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height > 0 ? height : Dim.dp1
        );

        // A plain View measures to the full available width and stretches the whole menu
        // when the menu is not measured with a fixed width. An empty ViewGroup measures to zero.
        LinearLayout divider = new LinearLayout(context);
        divider.setLayoutParams(dividerParams);
        // Same 20% of the foreground the app draws its own separators with.
        divider.setBackgroundColor((ThemeUtils.getAppForegroundColor() & 0xFFFFFF) | 0x33000000);

        return divider;
    }

    /**
     * Injection point.
     */
    public static void extractFlyoutIdFromLithoButton(Map<?, ?> map) {
        try {
            if ((PlayerType.getCurrent().isMaximizedOrFullscreen() || ShortsPlayerState.isOpen()) &&
                    EngagementPanel.checkIdsInQueue(commentsPanelNames)) {
                extractFlyoutIdFromMap(map);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "extractFlyoutIdFromLithoButton failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void extractFlyoutIdFromMap(Map<?, ?> map) {
        try {
            senderViewRef = new WeakReference<>(
                    (View) map.get("com.google.android.libraries.youtube.rendering.elements.sender_view"));
            extractFlyoutIdFromObject(map.get("com.google.android.libraries.youtube.innertube.endpoint.tag"));
        } catch (Exception ex) {
            Logger.printException(() -> "extractFlyoutIdFromMap failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void extractFlyoutIdFromObject(@Nullable Object bufferObject) {
        Logger.printDebug(() -> "Flyout buffer class: " + ((bufferObject == null)
                                ? null : bufferObject.getClass()));

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
            Logger.printDebug(() -> "Flyout buffer: " + new BufferAsciiStrings(flyoutBuffer).getStrings());
        }

        // Check whether the buffer contains the specified IDs, within a certain initial
        // range of the buffer, to avoid matching with false positives.
        List<Integer> listItemShareBytesIndexes = byteIndexesOf(flyoutBuffer, LIST_ITEM_SHARE_BYTES);
        if (!listItemShareBytesIndexes.isEmpty() && listItemShareBytesIndexes.size() == LIST_ITEM_SHARE_BYTES.size()) {
            if (byteIndexInStartRange(listItemShareBytesIndexes.get(0))) {
                setFlyoutCommentId(flyoutBuffer);
            }
            return;
        }

        if (!byteIndexesOf(flyoutBuffer, VIDEO_ELEMENTS_BYTES).isEmpty()) {
            View senderView = senderViewRef.get();
            if (senderView != null) {
                ViewParent parent = senderView.getParent();
                while (parent != null) {
                    if (parent instanceof ComponentHost componentHost) {
                        CharSequence description = componentHost.getContentDescription();
                        if (description != null) {
                            setFlyoutPlaylistId(flyoutBuffer);

                            setFlyoutVideoId(flyoutBuffer, description.toString());
                        }
                    }
                    parent = parent.getParent();
                }
            }
        }
    }

    private static void setFlyoutVideoId(byte[] buffer, String description) {
        if (description == null || buffer == null || description.isEmpty()) {
            return;
        }

        final int separatorIndex = description.indexOf(" - ");
        String titlePart = separatorIndex == -1 ? description : description.substring(0, separatorIndex);
        if (titlePart.isEmpty()) {
            return;
        }
        String title = TITLE_CLEANUP_PATTERN.matcher(titlePart.toLowerCase(Locale.ROOT)).replaceAll("");
        List<byte[]> words = new ArrayList<>();
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
        if (bestIdx >= 0 && maxScore >= requiredScore) {
            for (byte[] VIDEO_ID_PREFIX_BYTES : VIDEO_ID_PREFIXES_BYTES) {
                // Search for the video ID prefix after the best title match.
                int index = byteIndexOf(buffer, VIDEO_ID_PREFIX_BYTES, bestIdx);

                if (index >= 0) {
                    final int videoIdStart = index + VIDEO_ID_PREFIX_BYTES.length;
                    final int videoIdEnd = videoIdStart + 11;
                    if (videoIdEnd <= buffer.length) {
                        flyoutVideoId = new String(buffer, videoIdStart, 11, StandardCharsets.US_ASCII);
                        return;
                    }
                }
            }
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
            byte[] byteBase64 = Base64.decode(Arrays.copyOfRange(buffer, bestStart, bestEnd), Base64.URL_SAFE);
            final int base64VideoIdIndex = byteIndexOf(
                    byteBase64,
                    VideoInformation.getVideoId().getBytes(StandardCharsets.UTF_8)
            );
            if (base64VideoIdIndex < 0) {
                Logger.printException(() -> "setCommentId failure: No videoId found in the decoded base64 string!");
                return;
            }

            byte[] rawCommentId = Arrays.copyOfRange(byteBase64, 0, base64VideoIdIndex);
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
        List<Integer> indices = byteIndexesOf(haystack, List.of(needle), startIndex);
        return indices.isEmpty() ? -1 : indices.get(0);
    }

    public static List<Integer> byteIndexesOf(byte[] haystack, List<byte[]> needles) {
        return byteIndexesOf(haystack, needles, 0);
    }

    public static List<Integer> byteIndexesOf(byte[] haystack, List<byte[]> needles, int startIndex) {
        List<Integer> indices = new ArrayList<>();
        if (haystack == null || needles == null) {
            return indices;
        }

        final int haystackLen = haystack.length;

        final boolean[] found = new boolean[needles.size()];
        for (int i = startIndex; i < haystackLen; i++) {
            for (int k = 0; k < needles.size(); k++) {
                byte[] needle = needles.get(k);
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
