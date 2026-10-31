/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.settings.preference.ColorPickerPreference.DISABLED_ALPHA;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.function.Predicate;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.AddToQueuePatch;
import app.morphe.extension.youtube.patches.FeedVideoActionsPatch;
import app.morphe.extension.youtube.patches.LocalQueuePatch;
import app.morphe.extension.youtube.patches.PictureinPictureButtonPatch;
import app.morphe.extension.youtube.patches.PostImageDownloadPatch;
import app.morphe.extension.youtube.patches.PostTranslatePatch;
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;
import app.morphe.extension.youtube.patches.VideoDelayPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.components.PlayerFlyoutMenuComponentsFilter;
import app.morphe.extension.youtube.patches.playback.speed.ShortsPlaybackSpeedFlyoutPatch;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.videoplayer.ExportDownloadButton;
import app.morphe.extension.youtube.videoplayer.PictureinPictureButton;
import app.morphe.extension.youtube.videoplayer.SaveToWatchLaterButton;
import app.morphe.extension.youtube.videoplayer.ShortsDownloadButton;
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
     * Data a flyout button needs before it can be used. Until it is loaded, the button is disabled
     * and shows a loading text instead of its own.
     *
     * @param resource Loads in the background.
     * @param onLoaded Called on the main thread once the resource is loaded, with the text view of the button.
     *                 Returns if the button can be used.
     */
    public record FlyoutButtonResource(
            Future<?> resource,
            Predicate<TextView> onLoaded
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
    /**
     * The icon names of the Save to playlist item, by preference.
     * The item uses the icon of a playlist or of a bookmark, depending on the app version.
     */
    public static final String[] SAVE_TO_PLAYLIST_ICON_NAMES = {"PLAYLIST_ADD", "ADD_TO_PLAYLIST", "BOOKMARK", "SAVE"};
    private static final Pattern APP_SAVE_BUTTON_PATTERN =
            Pattern.compile("WATCH_LATER|" + String.join("|", SAVE_TO_PLAYLIST_ICON_NAMES));
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
    private static final StringRef LOADING_BUTTON_TEXT = StringRef.sfc("morphe_flyout_button_loading");

    private static final List<WeakReference<TextView>> customItemTextRefs = new ArrayList<>();

    private static String currentButtonName = "";
    private static int currentButtonIndex;
    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();
    /**
     * Text of the visible flyout buttons, by their name, to find the views of the buttons in the menu.
     */
    private static final Map<String, String> visibleFlyoutButtonTexts = new HashMap<>();
    /**
     * If the app menu has a save item, also if the item is hidden by a Morphe setting.
     */
    private static boolean flyoutHasAppSaveButton;
    /**
     * Injected save item. The app menu can bind its items after the injected items are added.
     */
    private static WeakReference<View> injectedSaveButtonRef = new WeakReference<>(null);

    private static WeakReference<View> senderViewRef = new WeakReference<>(null);


    /**
     * If the elements were added to the open menu. Menus opened on top of it stay as they are.
     */
    private static boolean flyoutElementsAdded;
    private static Dialog flyoutDialog;
    private static PopupWindow flyoutPopupWindow;
    private static String flyoutVideoId = "";
    private static String flyoutPlaylistId = "";
    /**
     * The buffer of the open menu, if the menu has one.
     */
    @Nullable
    private static byte[] flyoutBuffer;

    private static boolean isMyTabHistoryFlyout;
    private static boolean isShortFlyout;
    /**
     * If the open menu is the menu of the Shorts player, which has another layout
     * and only gets the playback speed item.
     */
    private static boolean isShortsPlayerFlyout;

    /**
     * Items of the menu of the Shorts player, to tell it apart from the other
     * bottom sheets opened in the Shorts player, such as the share sheet.
     */
    private static final List<String> SHORTS_PLAYER_MENU_BUTTONS = List.of(
            "ALIGN_LEFT", // Description.
            "NERD_STATS", // Stats for nerds.
            "FLAG",       // Report.
            "FEEDBACK"    // Send feedback.
    );
    /**
     * Channel of the video of the open menu. It loads in the background, so the menu never waits for it.
     */
    @Nullable
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

    /**
     * Some menus, such as the menus of the downloads list, have no description to find
     * the video by. Their buffer is of a single video, so its first thumbnail is of that video.
     *
     * @return The video of the first thumbnail of the open menu, or an empty string if there is none.
     */
    public static String getFlyoutThumbnailVideoId() {
        byte[] buffer = flyoutBuffer;
        if (buffer == null) {
            return "";
        }

        byte[] prefix = VIDEO_ID_PREFIXES_BYTES.get(0);
        final int index = byteIndexOf(buffer, prefix);
        final int videoIdStart = index + prefix.length;
        if (index < 0 || videoIdStart + VIDEO_ID_LENGTH > buffer.length) {
            return "";
        }
        return new String(buffer, videoIdStart, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
    }

    public static boolean hasVisibleFlyoutButton(String buttonName) {
        for (Pair<String, Integer> button : visibleFlyoutButtons) {
            if (button.first.equals(buttonName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The channel request of the video of the open menu, or null if the menu has no video,
     *         such as the player settings.
     */
    @Nullable
    public static ChannelIdRequest getFlyoutChannelIdRequest() {
        // The menus of the full history list are not litho elements, so their request starts here.
        if (flyoutChannelIdRequest == null && !flyoutVideoId.isEmpty()) {
            flyoutChannelIdRequest = ChannelIdRequest.fetchRequestIfNeeded(flyoutVideoId);
        }
        return flyoutChannelIdRequest;
    }

    /**
     * @return The channel name of the video of the open menu, or an empty string if it is not loaded yet.
     */
    public static String getFlyoutChannelName() {
        if (flyoutChannelIdRequest != null && flyoutChannelIdRequest.fetchIsDone()) {
            // The request is done, so this does not wait.
            Pair<String, String> channelInfo = flyoutChannelIdRequest.getChannelInfo();
            if (channelInfo != null) {
                return channelInfo.first;
            }
        }
        return "";
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

        Window window = dialog.getWindow();
        if (window != null) {
            addFlyoutElementsWhenShown(dialog, window.getDecorView());
        }
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

        // The app can set the content view after this call, in the same method that shows the popup.
        Utils.runOnMainThreadDelayed(() -> {
            View contentView = popupWindow.getContentView();
            if (contentView != null) {
                addFlyoutElementsWhenShown(popupWindow, contentView);
            }
        }, 0);
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

    /**
     * The menu is not shown yet when it is created. The show and dismiss listeners of the menu
     * are used by the app, while a view can have any number of attach listeners.
     *
     * @param flyoutView View that is attached to the window while the menu is open.
     */
    private static void addFlyoutElementsWhenShown(Object flyoutPanel, View flyoutView) {
        Runnable addElements = () -> {
            if (!flyoutElementsAdded) {
                flyoutElementsAdded = true;
                addFlyoutElements(flyoutPanel);
                onFlyoutListBound(flyoutPanel);
            }
        };

        flyoutView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
                // After the current layout pass, that binds the items of the menu.
                view.post(addElements);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                view.removeOnAttachStateChangeListener(this);
                // A dialog is still showing while its window is removed.
                Utils.runOnMainThreadDelayed(() -> {
                    if ((flyoutDialog == null || !flyoutDialog.isShowing())
                            && (flyoutPopupWindow == null || !flyoutPopupWindow.isShowing())) {
                        resetFlyoutIds();
                    }
                }, 0);
            }
        });

        // The app can show the menu before this call.
        if (flyoutView.isAttachedToWindow()) {
            flyoutView.post(addElements);
        }
    }

    private static void resetFlyoutIds() {
        flyoutElementsAdded = false;
        visibleFlyoutButtons.clear();
        visibleFlyoutButtonTexts.clear();
        flyoutHasAppSaveButton = false;
        injectedSaveButtonRef = new WeakReference<>(null);
        currentButtonIndex = 0;
        flyoutVideoId = "";
        flyoutPlaylistId = "";
        flyoutBuffer = null;
        if (flyoutChannelIdRequest != null) {
            // The buttons waiting for the channel are gone with the menu.
            flyoutChannelIdRequest.cancelIfNotDone();
            flyoutChannelIdRequest = null;
        }
        isShortsPlayerFlyout = false;
    }

    private static void addFlyoutElements(Object flyoutPanel) {
        int nextButtonIndex = 0;

        // The items of the menu that is closing are gone, and their typeface is copied
        // onto whatever this call adds instead.
        customItemTextRefs.clear();

        isShortsPlayerFlyout = (ShortsPlaybackSpeedFlyoutPatch.isPatchIncluded()
                || ShortsDownloadButton.isPatchIncluded())
                && isShortsPlayerMenu();
        if (isShortsPlayerFlyout) {
            nextButtonIndex = addShortsPlayerFlyoutButtons(flyoutPanel, nextButtonIndex);
        } else {
            nextButtonIndex = addVideoFlyoutButtons(flyoutPanel, nextButtonIndex);
        }

        if (nextButtonIndex > 0) {
            addFlyoutMenuItem(flyoutPanel, null, null, null, nextButtonIndex, true, null);
        }

        // Reset 'topFlyoutMenuVisible' field, once the buttons have been injected into the player's
        // overlay settings, to prevent them from also being added into nested menus.
        PlayerFlyoutMenuComponentsFilter.resetTopFlyoutMenuVisible();
    }

    /**
     * @return If the menu is the menu of the Shorts player, and not another bottom sheet
     *         opened in the Shorts player, such as the share sheet or the menu of a comment.
     */
    private static boolean isShortsPlayerMenu() {
        if (!ShortsPlayerState.isOpen()
                || isShortFlyout
                // Menus opened inside a panel, such as the menu of a comment.
                || EngagementPanel.getLastOpenedPanelId() != null) {
            return false;
        }
        for (Pair<String, Integer> button : visibleFlyoutButtons) {
            if (SHORTS_PLAYER_MENU_BUTTONS.contains(button.first)) {
                return true;
            }
        }
        return false;
    }

    private static int addShortsPlayerFlyoutButtons(Object flyoutPanel, int nextButtonIndex) {
        if (ShortsPlaybackSpeedFlyoutPatch.isPatchIncluded()
                && ShortsPlaybackSpeedFlyoutPatch.showFlyoutButton()) {
            nextButtonIndex = ShortsPlaybackSpeedFlyoutPatch.addFlyoutButton(flyoutPanel, nextButtonIndex);
        }

        if (ShortsDownloadButton.isPatchIncluded()) {
            final String flyoutVideoId = getFlyoutVideoId();
            nextButtonIndex = ShortsDownloadButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    flyoutVideoId.isEmpty() ? VideoInformation.getVideoId() : flyoutVideoId
            );
        }

        return nextButtonIndex;
    }

    private static int addVideoFlyoutButtons(Object flyoutPanel, int nextButtonIndex) {
        final String flyoutVideoId = getFlyoutVideoId();

        // Only added to the menus of posts with images.
        if (PostImageDownloadPatch.ENABLED) {
            nextButtonIndex = PostImageDownloadPatch.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    senderViewRef.get()
            );
        }

        // Only added to the menus of posts with a text.
        if (PostTranslatePatch.ENABLED) {
            nextButtonIndex = PostTranslatePatch.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    senderViewRef.get()
            );
        }

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
                    PlayerFlyoutMenuComponentsFilter.getTopFlyoutMenuVisible()
            );
        }

        // The menus of Shorts in the feed have no download option.
        if (ShortsDownloadButton.isPatchIncluded() && isShortFlyout) {
            nextButtonIndex = ShortsDownloadButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    flyoutVideoId
            );
        }

        if (ExportDownloadButton.isPatchIncluded()) {
            nextButtonIndex = ExportDownloadButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    allButtonsVideoId
            );
        }

        if (VideoDelayPatch.isPatchIncluded()) {
            nextButtonIndex = VideoDelayDialogButton.addFlyoutButton(
                    flyoutPanel,
                    nextButtonIndex,
                    PlayerFlyoutMenuComponentsFilter.getTopFlyoutMenuVisible()
            );
        }

        return nextButtonIndex;
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
        return addFlyoutMenuItem(flyoutPanel, icon, text, clickListener, index, false, null);
    }

    /**
     * Adds a save item, which is removed if the app menu binds its own save item afterwards.
     */
    public static int addFlyoutSaveButton(
            Object flyoutPanel,
            Drawable icon,
            String text,
            View.OnClickListener clickListener,
            int index
    ) {
        final int nextIndex = addFlyoutButton(flyoutPanel, icon, text, clickListener, index);
        if (nextIndex >= 0 && !customItemTextRefs.isEmpty()) {
            injectedSaveButtonRef = new WeakReference<>(customItemTextRefs.get(customItemTextRefs.size() - 1).get());
        }
        return nextIndex;
    }

    /**
     * @return If the app menu has its own item to save the video. The name of the item does not depend on the language.
     */
    public static boolean hasAppSaveButton() {
        return flyoutHasAppSaveButton;
    }

    /**
     * @param buttonResource Data the button needs. The button is disabled until it is loaded.
     */
    public static int addFlyoutButton(
            Object flyoutPanel,
            Drawable icon,
            String text,
            View.OnClickListener clickListener,
            int index,
            FlyoutButtonResource buttonResource
    ) {
        return addFlyoutMenuItem(flyoutPanel, icon, text, clickListener, index, false, buttonResource);
    }

    private static int addFlyoutMenuItem(
            Object flyoutPanel,
            @Nullable Drawable icon,
            @Nullable String text,
            @Nullable View.OnClickListener clickListener,
            int index,
            boolean isDivider,
            @Nullable FlyoutButtonResource buttonResource
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
                                clickListener,
                                buttonResource
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
        // Before the checks below, as a save item hidden by a Morphe setting must not be replaced by the injected one.
        if (buttonEnum != null && !flyoutHasAppSaveButton) {
            String name = buttonEnum.name();
            if (APP_SAVE_BUTTON_PATTERN.matcher(name).find() && !name.contains("REMOVE")) {
                flyoutHasAppSaveButton = true;

                View view = injectedSaveButtonRef.get();
                injectedSaveButtonRef = new WeakReference<>(null);
                while (view != null && view.getParent() instanceof ViewGroup parent) {
                    if (parent.getParent() instanceof InjectedItemsScrollView injectedItems) {
                        Utils.hideViewByRemovingFromParentUnderCondition(true, view);
                        if (parent.getChildCount() == 0) {
                            // The divider is right after the injected items.
                            ViewGroup menuContainer = injectedItems.menuContainer;
                            Utils.hideViewByRemovingFromParentUnderCondition(true,
                                    menuContainer.getChildAt(menuContainer.indexOfChild(injectedItems) + 1));
                            Utils.hideViewByRemovingFromParentUnderCondition(true, injectedItems);
                        }
                        if (flyoutPopupWindow != null && flyoutPopupWindow.isShowing()) {
                            flyoutPopupWindow.update();
                        }
                        break;
                    }
                    view = parent;
                }
            }
        }

        // The menu creates no row for an item without a text, such as an item hidden by a Morphe setting.
        // It is not counted, so the index of every button matches its row.
        // The popup menus always give the text, and keep the rows they hide.
        if (buttonEnum == null || buttonText == null) {
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
        //noinspection SizeReplaceableByIsEmpty
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
            return isShortsPlayerFlyout
                    ? getShortsPlayerMenuInfo(flyoutPanel, popupWindow)
                    : null;
        }

        return new FlyoutMenuInfo(menuContainer, adjustedIndex, popupWindow);
    }

    /**
     * The menu container of the Shorts player menu is the vertical layout that holds the list of the menu.
     * The items are injected above the list, so after the bottom sheet handle.
     */
    @Nullable
    private static FlyoutMenuInfo getShortsPlayerMenuInfo(Object flyoutPanel, @Nullable PopupWindow popupWindow) {
        View root = getFlyoutRootView(flyoutPanel);
        if (root instanceof ViewGroup rootGroup) {
            View child = Utils.getChildView(rootGroup, true,
                    view -> view instanceof RecyclerView || view instanceof AbsListView);
            ViewParent parent = child == null ? null : child.getParent();
            while (parent instanceof ViewGroup parentGroup && child != root) {
                if (parentGroup instanceof LinearLayout linearLayout
                        && linearLayout.getOrientation() == LinearLayout.VERTICAL) {
                    return new FlyoutMenuInfo(linearLayout, linearLayout.indexOfChild(child), popupWindow);
                }
                child = parentGroup;
                parent = parentGroup.getParent();
            }
        }

        Logger.printDebug(() -> "Shorts player menu container not found");
        return null;
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

    @Nullable
    private static View getFlyoutRootView(Object flyoutPanel) {
        if (flyoutPanel instanceof PopupWindow popupWindow) {
            return popupWindow.getContentView();
        }
        if (flyoutPanel instanceof Dialog dialog) {
            Window window = dialog.getWindow();
            return window == null ? null : window.getDecorView();
        }
        return null;
    }

    @SuppressLint("ResourceType")
    private static View addFlyoutButton(
            Context context,
            ViewGroup parent,
            @Nullable Drawable icon,
            String text,
            View.OnClickListener clickListener,
            @Nullable FlyoutButtonResource buttonResource
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

        if (buttonResource != null) {
            Future<?> resource = buttonResource.resource();
            if (resource.isDone()) {
                setFlyoutButtonLoaded(customButton, textView, text, buttonResource);
            } else {
                // The button is shown right away, so the menu does not change its size while the user taps it,
                // and the resource is waited for in the background.
                setFlyoutButtonEnabled(customButton, false);
                if (textView != null) {
                    // The loading text can take fewer lines than the button text, so it replaces the button text
                    // only once the button is laid out, and keeps the height of the button text.
                    textView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            textView.getViewTreeObserver().removeOnPreDrawListener(this);
                            if (resource.isDone()) {
                                // Loaded before the first draw, so the button is drawn ready to use.
                                setFlyoutButtonLoaded(customButton, textView, text, buttonResource);
                            } else {
                                textView.setMinHeight(textView.getHeight());
                                textView.setText(LOADING_BUTTON_TEXT.toString());
                            }
                            return false; // Skips the frame drawn before this change.
                        }
                    });
                }
                Utils.runOnBackgroundThread(() -> {
                    try {
                        resource.get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (CancellationException ex) {
                        return; // The menu was closed.
                    } catch (ExecutionException ex) {
                        // The button reads the failure from the resource itself.
                    }
                    Utils.runOnMainThread(() -> setFlyoutButtonLoaded(customButton, textView, text, buttonResource));
                });
            }
        }

        return customButton;
    }

    /**
     * Shows the button text again and enables the button if its resource can be used.
     * Can run more than once for the same button.
     */
    private static void setFlyoutButtonLoaded(
            View button,
            @Nullable TextView textView,
            String text,
            FlyoutButtonResource buttonResource
    ) {
        if (textView != null) {
            textView.setText(text);
        }
        setFlyoutButtonEnabled(button, buttonResource.onLoaded().test(textView));
    }

    private static void setFlyoutButtonEnabled(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : DISABLED_ALPHA);
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
                // An empty typed ID must clear the previous menu's video as well.
                flyoutVideoId = videoId;
                flyoutPlaylistId = "";
                isShortFlyout = false;
            }
            return;
        }

        byte[] flyoutBuffer = bufferObject instanceof ProtocolBufferFieldInterface bufferInterface
                ? bufferInterface.patch_getBuffer()
                : null;
        FlyoutUtils.flyoutBuffer = flyoutBuffer;
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
                        horizontalShelfHistoryBytesIndexes.get(0) <= 30;

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
                        // The request is never waited for here, so the menu opens right away.
                        if (!flyoutVideoId.isEmpty()) {
                            flyoutChannelIdRequest = ChannelIdRequest.fetchRequestIfNeeded(flyoutVideoId);
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
                if (!((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') ||
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
}
