/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.AddToQueuePatch;
import app.morphe.extension.youtube.patches.FeedVideoActionsPatch;
import app.morphe.extension.youtube.patches.LocalQueuePatch;
import app.morphe.extension.youtube.patches.PictureinPictureButtonPatch;
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;
import app.morphe.extension.youtube.patches.VideoDelayPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.components.PlayerFlyoutMenuComponentsFilter;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.PictureinPictureButton;
import app.morphe.extension.youtube.videoplayer.SaveToWatchLaterButton;
import app.morphe.extension.youtube.videoplayer.VideoDelayDialogButton;
import app.morphe.extension.youtube.whitelist.ChannelWhitelist;

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
            @Nullable PopupWindow popupWindow
    ) {}

    /**
     * Holds the injected items apart from the app list, and scrolls them on its own
     * when they do not all fit. Its height is limited by the space that the menu can use
     * in the window, which depends on the orientation and the window size, such as in split
     * screen, minus the space of the other items of the menu, so the whole menu fits.
     */
    private static final class InjectedItemsScrollView extends ScrollView {
        /**
         * Maximum part of the window that the injected items can use, so the app items stay visible.
         */
        private static final float MAX_WINDOW_HEIGHT_RATIO = 0.5f;
        /**
         * Minimum height in items, so a part of the next item is visible when the items scroll.
         */
        private static final float MIN_VISIBLE_ITEMS = 1.5f;

        private final LinearLayout itemsContainer;
        private final ViewGroup menuContainer;
        /**
         * Popup window of the menu, or null if the menu is a bottom sheet.
         */
        @Nullable
        private final PopupWindow popupWindow;
        /**
         * Width that the app gave to the menu before the items were injected, or 0 if it's not known.
         */
        private final int appMenuWidth;

        InjectedItemsScrollView(Context context, ViewGroup menuContainer, @Nullable PopupWindow popupWindow) {
            super(context);
            this.menuContainer = menuContainer;
            this.popupWindow = popupWindow;
            appMenuWidth = menuContainer.getWidth();

            setVerticalScrollBarEnabled(false);

            itemsContainer = new LinearLayout(context);
            itemsContainer.setOrientation(LinearLayout.VERTICAL);
            addView(itemsContainer, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            setNestedScrollingEnabled(false);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        /**
         * The visible frame of a popup window is not limited to the screen, so the visible frame
         * of the window of the app is used, which excludes the system bars and the keyboard,
         * and is the window of the app in split screen.
         *
         * @return The height of the window that the menu can use.
         */
        private int getWindowHeight() {
            Activity activity = Utils.getActivity();
            View frameView = activity == null ? this : activity.getWindow().getDecorView();
            Rect visibleFrame = new Rect();
            frameView.getWindowVisibleDisplayFrame(visibleFrame);
            return visibleFrame.height() > 0 ? visibleFrame.height() : Dim.getScreenHeight();
        }

        /**
         * @return The height of the other items of the menu as measured in the last layout,
         *         with the paddings of the menu and the margins of the injected items.
         */
        private int getOtherItemsHeight() {
            int height = menuContainer.getPaddingTop() + menuContainer.getPaddingBottom()
                    + getVerticalMargins(this);
            for (int i = 0, count = menuContainer.getChildCount(); i < count; i++) {
                View child = menuContainer.getChildAt(i);
                if (child != this && child.getVisibility() != GONE) {
                    height += child.getMeasuredHeight() + getVerticalMargins(child);
                }
            }
            return height;
        }

        /**
         * The list of the app is measured after the injected items, and is shrunk to the space they leave,
         * so the items after the injected items are measured without a height limit.
         *
         * @return The height of the items of the menu after the injected items.
         */
        private int getFollowingItemsHeight(int widthMeasureSpec) {
            final int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY);
            final int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int height = 0;
            for (int i = menuContainer.indexOfChild(this) + 1, count = menuContainer.getChildCount(); i < count; i++) {
                View child = menuContainer.getChildAt(i);
                if (child.getVisibility() != GONE) {
                    child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                    height += child.getMeasuredHeight() + getVerticalMargins(child);
                }
            }
            return height;
        }

        private static int getVerticalMargins(View view) {
            return view.getLayoutParams() instanceof MarginLayoutParams margins
                    ? margins.topMargin + margins.bottomMargin
                    : 0;
                    }

        /**
         * @return The height of a part of the second item, so the items can be seen to scroll.
         */
        private int getMinHeight() {
            View firstItem = itemsContainer.getChildAt(0);
            return firstItem == null ? 0 : (int) (firstItem.getMeasuredHeight() * MIN_VISIBLE_ITEMS);
        }

        /**
         * @return The maximum height: the space left in the window by the other items of the menu,
         *         up to a part of the window, and at least a part of the second item so the items
         *         can be seen to scroll. The menu is taller than the window only if the other items
         *         take almost all the space.
         */
        private int getMaxHeight() {
            final int windowHeight = getWindowHeight();
            final int maxHeight = Math.min(windowHeight - getOtherItemsHeight(),
                    (int) (windowHeight * MAX_WINDOW_HEIGHT_RATIO));
            return Math.max(maxHeight, getMinHeight());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // The menu can measure its items again with their measured height as a fixed height,
            // to give all the items the same width, so the measured height is kept.
            if (getLayoutParams() != null && getLayoutParams().height >= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }

            // The height given by a bottom sheet is ignored, as the bottom sheet can be measured
            // before the injected items are added, and it's resized afterward.
            int maxHeight = getMaxHeight();

            if (popupWindow != null) {
                // A popup window is as wide as the widest item of the menu, up to most of the window width,
                // so the injected items are not made wider than the width that the app gave to the menu,
                // and their long texts wrap as in the items of the app.
                // The injected items take all of that width, otherwise the menu is made narrower
                // than the app chose for its items, and their texts are cut.
                final int paddings = menuContainer.getPaddingLeft() + menuContainer.getPaddingRight();
                final int menuWidth = Math.max(menuContainer.getMinimumWidth(), appMenuWidth) - paddings;
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST
                        && menuWidth > 0 && menuWidth < MeasureSpec.getSize(widthMeasureSpec)) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(menuWidth,
                            appMenuWidth > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
                }

                // A popup window keeps the space that the app chose for the menu when it was shown,
                // before the injected items were added, so the injected items use the space left
                // by the other items, and the items of the app are not cut.
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
                    final int menuSpace = MeasureSpec.getSize(heightMeasureSpec)
                            - getFollowingItemsHeight(widthMeasureSpec);
                    maxHeight = Math.min(maxHeight, Math.max(menuSpace, getMinHeight()));
                }
            }

            if (maxHeight > 0) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN &&
                    (canScrollVertically(1)
                            || canScrollVertically(-1))) {
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    private static final int VIDEO_ID_LENGTH = 11;
    private static final byte[] PLAYLIST_ID_PREFIX_BYTES =
            getAsciiBytes("playlist?list=");
    private static final List<byte[]> VIDEO_ID_PREFIXES_BYTES = List.of(
            getAsciiBytes(".ytimg.com/vi/"),
            getAsciiBytes("youtube.com/watch?v=")
    );
    private static final List<byte[]> KIDS_VIDEO_ELEMENTS_BYTES = List.of(
            getAsciiBytes("video_metadata_carousel.e"),
            getAsciiBytes("com.google.android.apps.youtube.kids"),
            getAsciiBytes("https://www.youtube.com/myfamily/#mf-compare")
    );
    private static final List<byte[]> SHORTS_VIDEO_ELEMENT_BYTES = List.of(
            getAsciiBytes("history-shorts-shelf-item"),
            getAsciiBytes("shorts-shelf-item")
    );
    private static final List<byte[]> HORIZONTAL_SHELF_HISTORY_BYTES = List.of(
            getAsciiBytes("horizontal_shelf.e"),
            getAsciiBytes("FEhistory")
    );

    // 20.21 has no container, and its secondary views are direct children of the item.
    private static final List<Integer> SECONDARY_VIEW_IDS = List.of(
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_secondary_container"),
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_text_secondary"),
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_text_secondary_separator"),
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_icon_secondary")
    );
    private static final int ITEM_TEXT_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "list_item_text");

    private static final List<WeakReference<TextView>> customItemTextRefs = new ArrayList<>();

    private static String currentButtonName = "";
    private static int currentButtonIndex;
    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();
    /**
     * Text of the visible flyout buttons, by their name, to find the views of the buttons in the menu.
     */
    private static final Map<String, String> visibleFlyoutButtonTexts = new HashMap<>();

    private static WeakReference<View> senderViewRef = new WeakReference<>(null);

    private static final Handler flyoutVisibilityHandler = new Handler(Looper.getMainLooper());
    private static boolean flyoutVisibilityHandlerRunning;
    private static final Handler flyoutIdsResetHandler = new Handler(Looper.getMainLooper());
    private static boolean flyoutIdsResetHandlerRunning;

    private static Dialog flyoutDialog;
    private static PopupWindow flyoutPopupWindow;
    private static String flyoutVideoId = "";
    private static String flyoutPlaylistId = "";
    private static String flyoutChannelId = "";
    private static String flyoutChannelName = "";

    private static boolean videoMarkedAsForKids;
    private static boolean isMyTabHistoryFlyout;
    private static boolean isShortFlyout;
    private static ChannelIdRequest flyoutChannelIdRequest;

    public static byte[] getAsciiBytes(String string) {
        return string.getBytes(StandardCharsets.US_ASCII);
    }

    public static String getFlyoutVideoId() {
        return flyoutVideoId;
    }

    public static String getFlyoutPlaylistId() {
        return flyoutPlaylistId;
    }

    public static String getFlyoutChannelId() {
        return flyoutChannelId;
    }

    public static String getFlyoutChannelName() {
        return flyoutChannelName;
    }

    /**
     * Injection point.
     * Detect specific elements for videos marked as for kids, displayed
     * via the video player's comments button.
     */
    public static byte[] onCommentsLoaded(byte[] bytes) {
        List<Integer> kidsVideoElementsBytesIndexes = byteIndexesOf(bytes, KIDS_VIDEO_ELEMENTS_BYTES);
        if (!kidsVideoElementsBytesIndexes.isEmpty() &&
                kidsVideoElementsBytesIndexes.size() == KIDS_VIDEO_ELEMENTS_BYTES.size() - 1) {
            videoMarkedAsForKids = true;
        }

        return bytes;
    }

    /**
     * Injection point.
     */
    public static void resetVideoMarkedAsForKids() {
        videoMarkedAsForKids = false;
    }

    /**
     * Injection point.
     */
    public static void setBottomSheetFlyout(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        FeedVideoActionsPatch.onBottomSheetFlyout(dialog);
        flyoutDialog = dialog;

        runFlyoutVisibilityHandler();
    }

    /**
     * Injection point.
     */
    public static void setPopupWindowFlyout(PopupWindow popupWindow) {
        if (popupWindow == null) {
            return;
        }
        FeedVideoActionsPatch.onPopupWindowFlyout(popupWindow);
        flyoutPopupWindow = popupWindow;

        runFlyoutVisibilityHandler();
    }

    public static void dismissFlyout() {
        if (flyoutDialog != null) {
            flyoutDialog.dismiss();
            flyoutDialog = null;
        }
        if (flyoutPopupWindow != null) {
            flyoutPopupWindow.dismiss();
            flyoutPopupWindow = null;
        }
    }

    private static void runFlyoutVisibilityHandler() {
        if (flyoutVisibilityHandlerRunning) {
            return;
        }
        flyoutVisibilityHandlerRunning = true;

        flyoutVisibilityHandler.removeCallbacksAndMessages(null);
        flyoutVisibilityHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (flyoutDialog == null && flyoutPopupWindow == null) {
                            flyoutVisibilityHandlerRunning = false;
                            return;
                        }

                        final boolean isDialogShowing =
                                flyoutDialog != null && flyoutDialog.isShowing();
                        final boolean isPopupWindowShowing =
                                flyoutPopupWindow != null && flyoutPopupWindow.isShowing();
                        final boolean blockFlyoutVisibilityHandler =
                                isDialogShowing || isPopupWindowShowing;

                        if (blockFlyoutVisibilityHandler) {
                            final Object targetPanel = isDialogShowing ? flyoutDialog : flyoutPopupWindow;

                            // give a delay to ensure the flyout animation is finished.
                            Utils.runOnMainThreadDelayed(
                                    () -> {
                                        runFlyoutIdsResetHandler();
                                        addFlyoutElements(targetPanel);
                                        onFlyoutListBound(targetPanel);
                                    },
                                    30
                            );
                        } else {
                            flyoutVisibilityHandler.postDelayed(this, 10);
                        }
                    }
                }
        );
    }

    private static void runFlyoutIdsResetHandler() {
        if (flyoutIdsResetHandlerRunning) {
            return;
        }
        flyoutIdsResetHandlerRunning = true;

        flyoutIdsResetHandler.removeCallbacksAndMessages(null);
        flyoutIdsResetHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        final boolean isDialogClosed =
                                flyoutDialog == null || !flyoutDialog.isShowing();
                        final boolean isPopupWindowClosed =
                                flyoutPopupWindow == null || !flyoutPopupWindow.isShowing();
                        final boolean blockFlyoutIdsResetHandler =
                                isDialogClosed && isPopupWindowClosed;

                        if (blockFlyoutIdsResetHandler) {
                            resetFlyoutIds();

                            flyoutVisibilityHandlerRunning = false;
                            flyoutIdsResetHandlerRunning = false;
                        } else {
                            flyoutIdsResetHandler.postDelayed(this, 10);
                        }
                    }
                }
        );
    }

    private static void resetFlyoutIds() {
        visibleFlyoutButtons.clear();
        visibleFlyoutButtonTexts.clear();
        currentButtonIndex = 0;
        flyoutVideoId = "";
        flyoutPlaylistId = "";
        flyoutChannelId = "";
        flyoutChannelName = "";
    }

    private static void addFlyoutElements(Object flyoutPanel) {
        int nextButtonIndex = 0;

        // The items of the menu that is closing are gone, and their typeface is copied
        // onto whatever this call adds instead.
        customItemTextRefs.clear();

        final String flyoutVideoId = getFlyoutVideoId();

        // region to show the following buttons only for specific flyout menus.
        final String allButtonsVideoId;
        if (!flyoutVideoId.isEmpty()) {
            allButtonsVideoId = flyoutVideoId;
        } else if (PlayerFlyoutMenuComponentsFilter.getTopFlyoutMenuVisible()) {
            allButtonsVideoId = VideoInformation.getVideoId();
        } else {
            allButtonsVideoId = "";
        }

        if (!allButtonsVideoId.isEmpty()) {
            if (!(isMyTabHistoryFlyout && LocalQueuePatch.isEnabled())) {
                nextButtonIndex = AddToQueuePatch.addFlyoutButton(
                        flyoutPanel,
                        nextButtonIndex,
                        allButtonsVideoId
                );
            }

            nextButtonIndex = AiSListSubmitDialog.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    allButtonsVideoId
            );

            nextButtonIndex = ChannelWhitelist.addFlyoutButtons(flyoutPanel, nextButtonIndex, isMyTabHistoryFlyout);
        }

        // Check the patch first. Loading the button classes runs their static
        // initializer, which counts the overlay button even if the patch is excluded.
        if (PictureinPictureButtonPatch.isPatchIncluded()) {
            nextButtonIndex = PictureinPictureButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    PlayerFlyoutMenuComponentsFilter.getTopFlyoutMenuVisible(),
                    isShortFlyout
            );
        }

        if (SaveToWatchLaterPatch.isPatchIncluded()) {
            nextButtonIndex = SaveToWatchLaterButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    flyoutVideoId,
                    videoMarkedAsForKids,
                    isShortFlyout,
                    PlayerFlyoutMenuComponentsFilter.getTopFlyoutMenuVisible()
            );
        }

        if (VideoDelayPatch.isPatchIncluded()) {
            nextButtonIndex = VideoDelayDialogButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    PlayerFlyoutMenuComponentsFilter.getTopFlyoutMenuVisible()
            );
        }

        if (nextButtonIndex > 0) {
            addFlyoutMenuItem(flyoutPanel, null, null, null, nextButtonIndex, true);
        }

        // Reset 'topFlyoutMenuVisible' field, once the buttons have been injected into the player's
        // overlay settings, to prevent them from also being added into nested menus.
        PlayerFlyoutMenuComponentsFilter.resetTopFlyoutMenuVisible();
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
            AddToQueuePatch.onListBound(itemList);
            FeedVideoActionsPatch.onFlyoutListBound(itemList);

        } catch (Exception ex) {
            Logger.printException(() -> "onFlyoutListBound failure", ex);
        }
    }

    /**
     * The app applies its own font weight to the menu items after they are bound,
     * so the custom item only matches them by taking the typeface of a bound item.
     */
    private static void copyListItemTypeface(ViewGroup itemList) {
        if (customItemTextRefs.isEmpty() || ITEM_TEXT_ID == 0) {
            return;
        }

        if (itemList.getChildAt(0).findViewById(ITEM_TEXT_ID) instanceof TextView itemText) {
            Typeface itemTypeface = itemText.getTypeface();

            for (WeakReference<TextView> customItemTextRef : customItemTextRefs) {
                TextView customItemText = customItemTextRef.get();
                // setTypeface always requests a layout, so only call it when the font really differs.
                if (customItemText != null && customItemText.getTypeface() != itemTypeface) {
                    customItemText.setTypeface(itemTypeface);
                }
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
            // The handle is an ImageView, or a plain View on older app versions.
            View child = menuContainer.getChildAt(i);
            if (!(child instanceof ViewGroup)) {
                return child.getHeight();
            }
        }

        return 0;
    }

    @SuppressWarnings("SameParameterValue")
    public static int addFlyoutButton(
            Object flyoutPanel,
            Drawable icon,
            String text,
            View.OnClickListener clickListener,
            int index
    ) {
        return addFlyoutMenuItem(flyoutPanel, icon, text, clickListener, index, false);
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
            FlyoutMenuInfo menuInfo = getFlyoutMenuInfo(flyoutPanel, 0);
            if (menuInfo == null) {
                return -1;
            }

            Context context = Utils.getActivity();
            if (context == null) {
                return -1;
            }

            LinearLayout menuContainer = menuInfo.menuContainer();
            InjectedItemsScrollView currentInjectedItems = null;

            for (int i = 0,
                 injectedItemsCount = menuContainer.getChildCount();
                 i < injectedItemsCount; i++
            ) {
                if (menuContainer.getChildAt(i) instanceof InjectedItemsScrollView injectedItems) {
                    currentInjectedItems = injectedItems;
                }
            }

            if (isDivider) {
                if (currentInjectedItems == null) {
                    return -1;
                }

                // The divider separates the injected items from the app list,
                // so it stays outside the injected items scroll view.
                menuContainer.addView(
                        createFlyoutDivider(context),
                        menuContainer.indexOfChild(currentInjectedItems) + 1
                );
            } else {
                if (currentInjectedItems == null) {
                    currentInjectedItems = new InjectedItemsScrollView(context, menuContainer, menuInfo.popupWindow());
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    // The scroll view ends up under the drag handle, so it has to clear it.
                    params.topMargin = getDragHandleHeight(menuContainer);
                    menuContainer.addView(currentInjectedItems, menuInfo.adjustedIndex(), params);
                }

                currentInjectedItems.itemsContainer.addView(
                        addFlyoutButton(
                                context,
                                currentInjectedItems.itemsContainer,
                                icon,
                                text,
                                clickListener
                        )
                );
            }

            PopupWindow popupWindow = menuInfo.popupWindow();
            if (popupWindow != null) {
                popupWindow.update();
            }

            return index + 1;
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutMenuItem failure", ex);
        }

        return -1;
    }

    /**
     * Injection point.
     */
    public static void setCurrentButtonInfo(@Nullable Enum<?> buttonEnum, @Nullable Object buttonInfo) {
        // The popup menus give the text view of the button.
        setCurrentButtonInfo(buttonEnum, buttonInfo,
                buttonInfo instanceof TextView textView ? textView.getText() : null);
    }

    /**
     * Injection point.
     *
     * @param buttonText The text of the button, if it's known.
     */
    public static void setCurrentButtonInfo(@Nullable Enum<?> buttonEnum, @Nullable Object buttonInfo,
                                            @Nullable CharSequence buttonText) {
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
        if (buttonText != null && buttonText.length() > 0) {
            visibleFlyoutButtonTexts.putIfAbsent(currentButtonName, buttonText.toString());
        }
    }

    /**
     * @return The text of the visible flyout button, or null if it's not known.
     */
    @Nullable
    public static String getVisibleFlyoutButtonText(String buttonName) {
        return visibleFlyoutButtonTexts.get(buttonName);
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

    @SuppressWarnings("SameParameterValue")
    @Nullable
    private static FlyoutMenuInfo getFlyoutMenuInfo(Object flyoutPanel, int initialIndex) {
        LinearLayout menuContainer = null;
        PopupWindow popupWindow = null;
        int adjustedIndex = initialIndex;

        if (flyoutPanel instanceof PopupWindow checkedPopupWindow) {
            popupWindow = checkedPopupWindow;
            menuContainer = getPopupMenuContainer(checkedPopupWindow.getContentView());
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

        return new FlyoutMenuInfo(menuContainer, adjustedIndex, popupWindow);
    }

    /**
     * The menu container of a popup menu is the first LinearLayout found by following the first child
     * of the content view. Depending on the app version, the device and A/B tests, the content view is:
     * the container itself, a view that places the container next to its anchor (also wrapped
     * in a frame on phones), or a frame that sizes the popup window to the container.
     *
     * @return The menu container, or null if the content view is not a popup menu.
     */
    @Nullable
    private static LinearLayout getPopupMenuContainer(@Nullable View contentView) {
        View view = contentView;
        // The deepest container is in a frame, inside the view that places it.
        for (int depth = 0; depth < 3 && view != null; depth++) {
            if (view instanceof LinearLayout linearLayout) {
                return linearLayout;
            }
            view = view instanceof ViewGroup viewGroup ? viewGroup.getChildAt(0) : null;
        }
        return null;
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
        // Its multi line variant wraps long texts instead of cutting them.
        int layoutId = ResourceUtils.getIdentifier(
                ResourceType.LAYOUT, "modern_bottom_sheet_enableable_list_item");
        if (layoutId == 0) {
            layoutId = ResourceUtils.getIdentifier(
                    ResourceType.LAYOUT, "bottom_sheet_enableable_multi_line_list_item");
        }
        if (layoutId == 0) {
            layoutId = ResourceUtils.getIdentifier(
                    ResourceType.LAYOUT, "bottom_sheet_enableable_list_item");
        }

        View customButton = LayoutInflater.from(context).inflate(layoutId, parent, false);

        TextView textView = customButton.findViewById(ITEM_TEXT_ID);
        if (textView != null) {
            textView.setText(text);
            customItemTextRefs.add(new WeakReference<>(textView));
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

        // The layout reserves space for a secondary text and icon this item does not have.
        for (int secondaryViewId : SECONDARY_VIEW_IDS) {
            View secondaryView = customButton.findViewById(secondaryViewId);
            if (secondaryView != null) {
                secondaryView.setVisibility(View.GONE);
            }
        }

        final int ripple = ThemeUtils.getThemeResourceId(context, android.R.attr.selectableItemBackground);
        if (ripple != 0) {
            customButton.setForeground(context.getDrawable(ripple));
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
    public static void extractFlyoutIdFromMap(Map<?, ?> map) {
        try {
            senderViewRef = new WeakReference<>(
                    (View) map.get("com.google.android.libraries.youtube.rendering.elements.sender_view")
            );
            extractFlyoutIdFromObject(
                    map.get("com.google.android.libraries.youtube.innertube.endpoint.tag")
            );
            FeedVideoActionsPatch.onFlyoutSender(senderViewRef.get());
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
            isMyTabHistoryFlyout = false;
            String videoId = videoIdInterface.patch_getVideoId();
            if (videoId != null) {
                flyoutVideoId = videoId;
            }
            return;
        }

        byte[] flyoutBuffer = bufferObject instanceof ProtocolBufferFieldInterface bufferInterface
                ? bufferInterface.patch_getBuffer()
                : null;
        if (flyoutBuffer == null) {
            return;
        }

        if (Settings.DEBUG_PROTOBUFFER.get()) {
            Logger.printDebug(() -> "Flyout buffer: " + new BufferAsciiStrings(flyoutBuffer).getStrings());
        }

        List<Integer> horizontalShelfHistoryBytesIndexes = byteIndexesOf(flyoutBuffer, HORIZONTAL_SHELF_HISTORY_BYTES);
        isMyTabHistoryFlyout =
                !horizontalShelfHistoryBytesIndexes.isEmpty() &&
                        horizontalShelfHistoryBytesIndexes.size() == HORIZONTAL_SHELF_HISTORY_BYTES.size() &&
                        byteIndexInStartRange(horizontalShelfHistoryBytesIndexes.get(0));

        // Check if Shorts flyout is triggered only in the feed or on channels.
        List<Integer> shortsVideoElementsBytesIndexes = byteIndexesOf(flyoutBuffer, SHORTS_VIDEO_ELEMENT_BYTES);
        isShortFlyout = shortsVideoElementsBytesIndexes.size() == 1;

        setFlyoutPlaylistId(flyoutBuffer);

        View senderView = senderViewRef.get();
        Logger.printDebug(() -> "Flyout sender view object: " + (senderView != null));
        if (senderView != null) {
            // Long pressing an item, such as a Short in the Shorts tab of a channel, sends the flyout
            // from the item itself, which has the description. The item is still pressed, unlike
            // a button of the item that opens the flyout when released.
            ViewParent parent = senderView instanceof ViewGroup senderViewGroup
                    && senderView.isPressed() && senderView.getContentDescription() != null
                    ? senderViewGroup
                    : senderView.getParent();
            int parentCount = 0;
            while (parent != null && !parent.toString().contains("results")) {
                parentCount++;

                ViewParent loggingParent = parent;
                final int loggingParentCount = parentCount;
                Logger.printDebug(() -> "Flyout senderView parent " +
                        loggingParentCount +
                        ": " +
                        loggingParent
                );

                if (parent instanceof ViewGroup viewGroupParent) {
                    CharSequence description = viewGroupParent.getContentDescription();
                    boolean descriptionNull = description == null;

                    Logger.printDebug(() -> "Flyout viewGroupParent description is null: " +
                            descriptionNull
                    );

                    if (!descriptionNull) {
                        String stringDescription = description.toString();

                        Logger.printDebug(() -> "Flyout viewGroupParent description content: " +
                                stringDescription
                        );

                        setFlyoutVideoId(flyoutBuffer, stringDescription);

                        // Since not every element (especially in shelf) in feed have
                        // a channelId, we'll use YT API to safely retrieve it.
                        if (!flyoutVideoId.isEmpty()) {
                            // Prevent a new request until the previous (if exists) is not done.
                            if (flyoutChannelIdRequest != null && !flyoutChannelIdRequest.fetchIsDone()) {
                                return;
                            }

                            flyoutChannelIdRequest = ChannelIdRequest.fetchRequestIfNeeded(flyoutVideoId);

                            // Unfortunately must block main thread to ensure channel name is set.
                            Pair<String, String> remoteFlyoutChannelInfo = flyoutChannelIdRequest.getChannelInfo();
                            if (remoteFlyoutChannelInfo != null) {
                                String remoteFlyoutChannelName = remoteFlyoutChannelInfo.first;
                                if (!TextUtils.isEmpty(remoteFlyoutChannelName)) {
                                    flyoutChannelName = remoteFlyoutChannelName;
                                }

                                String remoteFlyoutChannelId = remoteFlyoutChannelInfo.second;
                                if (!TextUtils.isEmpty(remoteFlyoutChannelId)) {
                                    flyoutChannelId = remoteFlyoutChannelId;
                                }
                            }
                        }
                    }
                }
                parent = parent.getParent();
            }
        }
    }

    public static void setFlyoutVideoId(byte[] buffer, String rawDescription) {
        if (buffer == null || rawDescription == null || rawDescription.isEmpty()) {
            return;
        }

        String cleanDescription = rawDescription.replaceAll(
                "[^\\p{L}\\p{N}\\p{M}]",
                " "
        );
        String[] tokens = cleanDescription.split("\\s+");

        List<String> words = new ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }

        int targetWordCount = Math.min(words.size(), 3);
        if (targetWordCount == 0) {
            return;
        }

        byte[][] wordsBytes = new byte[targetWordCount][];
        for (int i = 0; i < targetWordCount; i++) {
            wordsBytes[i] = words.get(i).getBytes(StandardCharsets.UTF_8);
        }

        int byteTitleStartIndex = -1;
        byte[] firstWordBytes = wordsBytes[0];
        int fromIndex = 0;

        while ((fromIndex = byteIndexOf(buffer, firstWordBytes, fromIndex)) != -1) {
            int candidateStart = fromIndex;
            int currentPos = candidateStart + firstWordBytes.length;
            boolean fullMatch = true;

            for (int i = 1; i < wordsBytes.length; i++) {
                byte[] nextWordBytes = wordsBytes[i];
                int nextWordIndex = byteIndexOf(buffer, nextWordBytes, currentPos);

                if (nextWordIndex != -1 && (nextWordIndex - currentPos) <= 30) {
                    currentPos = nextWordIndex + nextWordBytes.length;
                } else {
                    fullMatch = false;
                    break;
                }
            }

            if (fullMatch) {
                byteTitleStartIndex = candidateStart;
                break;
            }

            fromIndex++;
        }

        if (byteTitleStartIndex == -1) {
            return;
        }

        for (byte[] VIDEO_ID_PREFIX_BYTES : VIDEO_ID_PREFIXES_BYTES) {
            int index = byteIndexOf(buffer, VIDEO_ID_PREFIX_BYTES, byteTitleStartIndex);

            if (index >= 0) {
                final int videoIdStart = index + VIDEO_ID_PREFIX_BYTES.length;
                final int videoIdEnd = videoIdStart + VIDEO_ID_LENGTH;
                if (videoIdEnd <= buffer.length) {
                    flyoutVideoId = new String(
                            buffer,
                            videoIdStart,
                            VIDEO_ID_LENGTH,
                            StandardCharsets.US_ASCII
                    );

                    Logger.printDebug(() -> "Flyout Video ID found: " +
                            flyoutVideoId
                    );
                }
                return;
            }
        }
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static void setFlyoutPlaylistId(byte[] flyoutBuffer) {
        final int index = byteIndexOf(flyoutBuffer, PLAYLIST_ID_PREFIX_BYTES);

        if (index >= 0) {
            final int playlistIdStart = index + PLAYLIST_ID_PREFIX_BYTES.length;

            int playlistIdEnd = playlistIdStart;
            while (playlistIdEnd < flyoutBuffer.length) {
                byte b = flyoutBuffer[playlistIdEnd];
                if (!(isByteAlphanumeric(b) ||
                        b == '-' ||
                        b == '_')) {
                    break;
                }
                playlistIdEnd++;
            }
            // "playlist?list=" always ends with a character extraneous to the
            // playlist ID, so verify that the current index of being
            // searched bytes does not belong to this element.
            final int playlistLength = playlistIdEnd - playlistIdStart - 1;

            if (playlistLength >= 18) {
                flyoutPlaylistId = new String(
                        flyoutBuffer,
                        playlistIdStart,
                        playlistLength,
                        StandardCharsets.US_ASCII
                );
                Logger.printDebug(() -> "Flyout Playlist ID found: " +
                        flyoutPlaylistId
                );
            }
        }
    }

    public static int byteIndexOf(byte[] haystack, byte[] needle) {
        return byteIndexOf(haystack, needle, 0);
    }

    public static int byteIndexOf(byte[] haystack, byte[] needle, int startIndex) {
        if (needle == null) {
            return -1;
        }
        List<Integer> indices = byteIndexesOf(haystack, Collections.singletonList(needle), startIndex);
        return indices.isEmpty() ? -1 : indices.get(0);
    }

    public static List<Integer> byteIndexesOf(byte[] haystack, List<byte[]> needles) {
        return byteIndexesOf(haystack, needles, 0);
    }

    public static List<Integer> byteIndexesOf(byte[] haystack, List<byte[]> needles, int startIndex) {
        List<Integer> indices = new ArrayList<>();
        if (haystack == null || needles == null || needles.isEmpty()) {
            return indices;
        }

        final int start = Math.max(0, startIndex);
        final int haystackLen = haystack.length;
        final int numNeedles = needles.size();

        final boolean[] found = new boolean[numNeedles];
        int foundCount = 0;

        for (int i = start; i < haystackLen; i++) {
            if (foundCount == numNeedles) {
                break;
            }

            for (int k = 0; k < numNeedles; k++) {
                if (found[k]) {
                    continue;
                }

                byte[] needle = needles.get(k);
                if (needle == null || needle.length == 0) {
                    continue;
                }

                final int needleLen = needle.length;
                if (i + needleLen > haystackLen) {
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
                    foundCount++;
                }
            }
        }
        return indices;
    }

    private static boolean byteIndexInStartRange(int index) {
        return index >= 0 && index <= 30;
    }

    private static boolean isByteAlphanumeric(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9');
    }
}
