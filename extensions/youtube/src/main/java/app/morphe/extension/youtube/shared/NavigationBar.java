/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.shared;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class NavigationBar {

    /**
     * Interface to call obfuscated methods in AppCompat Toolbar class.
     */
    public interface AppCompatToolbarPatchInterface {
        Drawable patch_getNavigationIcon();
    }

    /**
     * Interface to be notified when the navigation button changes.
     */
    public interface OnNavigationButtonChangedListener {
        /**
         * @param activeButton Currently selected button. Is null only if the navigation button
         *                     is a new and unidentified type.
         */
        void onNavigationButtonChanged(@Nullable NavigationButton activeButton);
    }

    private static final List<OnNavigationButtonChangedListener> onNavigationButtonChangedListeners
            = Collections.synchronizedList(new ArrayList<>());

    /**
     * Registers a listener to be notified when the navigation button changes.
     */
    public static void addOnNavigationButtonChangedListener(OnNavigationButtonChangedListener listener) {
        onNavigationButtonChangedListeners.add(listener);
    }

    /**
     * Unregisters a listener from being notified when the navigation button changes.
     */
    public static void removeOnNavigationButtonChangedListener(OnNavigationButtonChangedListener listener) {
        onNavigationButtonChangedListeners.remove(listener);
    }

    private static void notifyNavigationButtonChangedListeners(@Nullable NavigationButton button) {
        for (OnNavigationButtonChangedListener listener : onNavigationButtonChangedListeners) {
            listener.onNavigationButtonChanged(button);
        }
    }

    //
    // Search and toolbar.
    //

    private static volatile WeakReference<View> searchBarResultsRef = new WeakReference<>(null);

    private static volatile WeakReference<AppCompatToolbarPatchInterface> toolbarResultsRef
            = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void searchBarResultsViewLoaded(View searchbarResults) {
        searchBarResultsRef = new WeakReference<>(searchbarResults);
        isSearchBarAttached = searchbarResults.isAttachedToWindow();

        searchbarResults.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
                if (view == closingSearchBarResultsRef.get()) {
                    // Search was not closed, or was opened again.
                    closingSearchBarResultsRef = new WeakReference<>(null);
                }
                if (view == searchBarResultsRef.get()) {
                    isSearchBarAttached = true;
                }
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                if (view == closingSearchBarResultsRef.get()) {
                    Logger.printDebug(() -> "Search bar closed");
                    closingSearchBarResultsRef = new WeakReference<>(null);
                }
                if (view == searchBarResultsRef.get()) {
                    // View#isShown() is still true while the detach listeners are called.
                    isSearchBarAttached = false;
                }
            }
        });
    }

    /**
     * Injection point.
     */
    public static void setToolbar(FrameLayout layout) {
        AppCompatToolbarPatchInterface toolbar = Utils.getChildView(layout, false, (view) ->
                view instanceof AppCompatToolbarPatchInterface
        );

        if (toolbar == null) {
            Logger.printException(() -> "Could not find navigation toolbar");
            return;
        }

        toolbarResultsRef = new WeakReference<>(toolbar);
    }

    /**
     * @return If the search bar is on screen.  This includes if the player
     *         is on screen and the search results are behind the player (and not visible).
     *         Detecting the search is covered by the player can be done by checking {@link PlayerType#isMaximizedOrFullscreen()}.
     */
    public static boolean isSearchBarActive() {
        View searchbarResults = searchBarResultsRef.get();
        return searchbarResults != null
                && searchbarResults != closingSearchBarResultsRef.get()
                && isSearchBarAttached
                && searchbarResults.isShown();
    }

    /**
     * If the last loaded search bar is attached to the window.
     */
    private static volatile boolean isSearchBarAttached;

    /**
     * Search bar closed by the back button/gesture or by a navigation button.
     * <p>
     * Litho starts filtering the tab below the search before the search bar is detached,
     * and without this the tab is filtered as search results. Waiting on the Litho thread
     * for the detach is not possible, because the main thread can wait for the same Litho layout
     * before it detaches the search bar.
     * <p>
     * Only this search bar is considered closed, so a search bar loaded again
     * (such as going back to the previous search) is active as soon as it is shown.
     * Cleared when this search bar is detached (closed) or attached again.
     */
    private static volatile WeakReference<View> closingSearchBarResultsRef = new WeakReference<>(null);

    /**
     * Safety limit if a closed search bar is still attached, so a search that was unexpectedly
     * not closed is not filtered as the tab below it.
     * Normally the search bar is detached after the exit animation (300-500ms).
     */
    private static final long SEARCH_BAR_CLOSING_TIMEOUT_MILLISECONDS = 5000;

    /**
     * Must be called on the main thread.
     */
    private static void setSearchBarClosingIfShown() {
        View searchbarResults = searchBarResultsRef.get();
        // Search is not shown if the player is maximized over it,
        // and then the back button minimizes the player instead.
        if (searchbarResults == null || !isSearchBarAttached || !searchbarResults.isShown()) {
            return;
        }

        Logger.printDebug(() -> "Search bar closing");
        closingSearchBarResultsRef = new WeakReference<>(searchbarResults);

        Utils.runOnMainThreadDelayed(() -> {
            if (closingSearchBarResultsRef.get() == searchbarResults) {
                // Log as exception level, only if debug is enabled.
                if (Settings.DEBUG.get()) {
                    Logger.printException(() -> "Search bar was not closed");
                }
                closingSearchBarResultsRef = new WeakReference<>(null);
            }
        }, SEARCH_BAR_CLOSING_TIMEOUT_MILLISECONDS);
    }

    public static boolean isBackButtonVisible() {
        AppCompatToolbarPatchInterface toolbar = toolbarResultsRef.get();
        return toolbar != null && toolbar.patch_getNavigationIcon() != null;
    }

    //
    // Navigation bar buttons.
    //

    /**
     * How long to wait for the set nav button latch to be released.  Maximum wait time must
     * be as small as possible while still allowing enough time for the nav bar to update.
     * <p>
     * YT calls it's back button handlers out of order, and litho starts filtering before the
     * navigation bar is updated. Fixing this situation and not needlessly waiting requires
     * somehow detecting if a back button key/gesture will not change the active tab.
     * <p>
     * On average the time between pressing the back button and the first litho event is
     * about 10-20ms.  Waiting up to 75-150ms should be enough time to handle normal use cases
     * and not be noticeable, since YT typically takes 100-200ms (or more) to update the view.
     * <p>
     * This delay is only noticeable when the device back button/gesture will not
     * change the current navigation tab, such as backing out of the watch history.
     * <p>
     * This issue can also be avoided on a patch by patch basis, by avoiding calls to
     * {@link NavigationButton#getSelectedNavigationButton()} unless absolutely necessary.
     */
    private static final long LATCH_AWAIT_TIMEOUT_MILLISECONDS = 120;

    /**
     * Used as a workaround to fix the issue of YT calling back button handlers out of order.
     * Used to hold calls to {@link NavigationButton#getSelectedNavigationButton()}
     * until the current navigation button can be determined.
     * <p>
     * Only used when the hardware back button is pressed.
     */
    @Nullable
    private static volatile CountDownLatch navButtonLatch;

    /**
     * Map of nav button layout views to Enum type.
     * No synchronization is needed, and this is always accessed from the main thread.
     */
    private static final Map<View, NavigationButton> viewToButtonMap = new WeakHashMap<>();

    static {
        // On app startup litho can start before the navigation bar is initialized.
        // Force it to wait until the nav bar is updated.
        createNavButtonLatch();
    }

    private static void createNavButtonLatch() {
        navButtonLatch = new CountDownLatch(1);
    }

    private static void releaseNavButtonLatch() {
        CountDownLatch latch = navButtonLatch;
        if (latch != null) {
            navButtonLatch = null;
            latch.countDown();
        }
    }

    private static void waitForNavButtonLatchIfNeeded() {
        CountDownLatch latch = navButtonLatch;
        if (latch == null) {
            return;
        }

        if (Utils.isCurrentlyOnMainThread()) {
            // The latch is released from the main thread, and waiting from the main thread will always time out.
            // This situation has only been observed when navigating out of a submenu and not changing tabs.
            // and for that use case the nav bar does not change so it's safe to return here.
            Logger.printDebug(() -> "Cannot block main thread waiting for nav button. " +
                    "Using last known navbar button status.");
            return;
        }

        try {
            Logger.printDebug(() -> "Latch wait started");
            if (latch.await(LATCH_AWAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)) {
                // Back button changed the navigation tab.
                Logger.printDebug(() -> "Latch wait complete");
                return;
            }

            // Timeout occurred, and a normal event when pressing the physical back button
            // does not change navigation tabs.
            releaseNavButtonLatch(); // Prevent other threads from waiting for no reason.
            Logger.printDebug(() -> "Latch wait timed out");

        } catch (InterruptedException ex) {
            // Calling YouTube thread was interrupted.
            Logger.printException(() -> "Latch wait interrupted", ex);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        }
    }

    /**
     * Navigation button the selected button was opened from, if the selected button was selected
     * by tapping it. Going back from the root of a tab selects again the tab it was opened from.
     * Accessed only on the main thread.
     */
    @Nullable
    private static NavigationButton openedFromNavigationButton;

    /**
     * Navigation button tapped and not yet selected, and when it was tapped.
     * Accessed only on the main thread.
     */
    @Nullable
    private static NavigationButton tappedNavigationButton;
    private static long tappedNavigationButtonTime;

    /**
     * Maximum time between tapping a navigation button and selecting it.
     */
    private static final long TAPPED_NAVIGATION_BUTTON_TIMEOUT_MILLISECONDS = 1000;

    /**
     * Navigation button selected again by going back from the root of the You tab,
     * and when going back.
     * <p>
     * Litho filters the tab it goes back to before the navigation bar is updated (150-600ms later),
     * also on the main thread, so the filters cannot wait for the navigation bar.
     */
    @Nullable
    private static volatile NavigationButton goingBackNavigationButton;
    private static volatile long goingBackTime;

    /**
     * If going back again cancelled {@link #goingBackNavigationButton}, as the tab it goes back to
     * is then not known until the tab changes. Accessed only on the main thread.
     */
    private static boolean goingBackCancelled;

    /**
     * Maximum time going back to {@link #goingBackNavigationButton}, if going back does not change the tab.
     */
    private static final long GOING_BACK_TIMEOUT_MILLISECONDS = 1000;

    /**
     * Both back hooks can be called for the same back within this time.
     */
    private static final long SAME_BACK_EVENT_MILLISECONDS = 50;

    /**
     * Navigation button touched down, if the touch can be a tap on it.
     * Accessed only on the main thread.
     */
    @Nullable
    private static NavigationButton touchedNavigationButton;

    /**
     * Injection point.
     * <p>
     * Tapping a navigation tab while the search is on screen closes the search,
     * but does not select the tab again if it is already selected.
     */
    public static void navigationBarTouched(MotionEvent event) {
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> touchedNavigationButton = getNavigationButtonAt(event);
                case MotionEvent.ACTION_UP -> {
                    NavigationButton button = getNavigationButtonAt(event);
                    if (button != null && button == touchedNavigationButton) {
                        tappedNavigationButton = button;
                        tappedNavigationButtonTime = SystemClock.uptimeMillis();
                        if (button.closesSearch()) {
                            setSearchBarClosingIfShown();
                        }
                    }
                    touchedNavigationButton = null;
                }
                case MotionEvent.ACTION_CANCEL -> touchedNavigationButton = null;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "navigationBarTouched failure", ex);
        }
    }

    @Nullable
    private static NavigationButton getNavigationButtonAt(MotionEvent event) {
        final float x = event.getRawX();
        final float y = event.getRawY();
        int[] location = new int[2];

        for (Map.Entry<View, NavigationButton> entry : viewToButtonMap.entrySet()) {
            View view = entry.getKey();
            if (!view.isShown()) {
                continue;
            }
            view.getLocationOnScreen(location);
            if (x >= location[0] && x < location[0] + view.getWidth()
                    && y >= location[1] && y < location[1] + view.getHeight()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Last YT navigation enum loaded.  Not necessarily the active navigation tab.
     * Always accessed from the main thread.
     */
    @Nullable
    private static String lastYTNavigationEnumName;

    /**
     * Injection point.
     */
    public static void setLastAppNavigationEnum(@Nullable Enum<?> ytNavigationEnumName) {
        if (ytNavigationEnumName != null) {
            lastYTNavigationEnumName = ytNavigationEnumName.name();
        }
    }

    /**
     * Injection point.
     */
    public static void navigationTabLoaded(final View navigationButtonGroup) {
        try {
            // The navigation bar can be created again, such as if the activity is created again.
            openedFromNavigationButton = null;
            tappedNavigationButton = null;
            goingBackNavigationButton = null;
            goingBackCancelled = false;

            String lastEnumName = lastYTNavigationEnumName;

            for (NavigationButton buttonType : NavigationButton.values()) {
                if (buttonType.ytEnumNames.contains(lastEnumName)) {
                    Logger.printDebug(() -> "navigationTabLoaded: " + lastEnumName);
                    viewToButtonMap.put(navigationButtonGroup, buttonType);
                    navigationTabCreatedCallback(buttonType, navigationButtonGroup);
                    return;
                }
            }

            // Log the unknown tab as exception level, only if debug is enabled.
            // This is because unknown tabs do no harm, and it's only relevant to developers.
            if (Settings.DEBUG.get()) {
                Logger.printException(() -> "Unknown tab: " + lastEnumName
                        + " view: " + navigationButtonGroup.getClass());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "navigationTabLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Unique hook just for the 'Create' and 'You' tab.
     */
    public static void navigationImageResourceTabLoaded(View view) {
        // 'You' tab has no YT enum name and the enum hook is not called for it.
        // Compare the last enum to figure out which tab this actually is.
        if (NavigationBar.NavigationButton.CREATE.ytEnumNames.contains(lastYTNavigationEnumName)) {
            navigationTabLoaded(view);
        } else {
            lastYTNavigationEnumName = NavigationButton.LIBRARY.ytEnumNames.get(0);
            navigationTabLoaded(view);
        }
    }

    /**
     * Injection point.
     */
    public static void navigationTabSelected(View navButtonImageView, boolean isSelected) {
        try {
            if (!isSelected) {
                return;
            }

            NavigationButton button = viewToButtonMap.get(navButtonImageView);
            NavigationButton oldButton = NavigationButton.selectedNavigationButton;

            if (button == null) { // An unknown tab was selected.
                // Show a toast only if debug mode is enabled.
                if (BaseSettings.DEBUG.get()) {
                    Logger.printException(() -> "Unknown navigation view selected: " + navButtonImageView);
                }

                NavigationButton.selectedNavigationButton = null;
                openedFromNavigationButton = null;
                tappedNavigationButton = null;
                goingBackNavigationButton = null;
                goingBackCancelled = false;

                if (oldButton != null) {
                    notifyNavigationButtonChangedListeners(null);
                }
                return;
            }

            NavigationButton.selectedNavigationButton = button;

            // Release any threads waiting for the selected nav button.
            releaseNavButtonLatch();

            if (button != oldButton) {
                openedFromNavigationButton = button == tappedNavigationButton
                        && SystemClock.uptimeMillis() - tappedNavigationButtonTime <= TAPPED_NAVIGATION_BUTTON_TIMEOUT_MILLISECONDS
                        ? oldButton
                        : null;
                tappedNavigationButton = null;
                goingBackNavigationButton = null;
                goingBackCancelled = false;

                Logger.printDebug(() -> "Changed to navigation button: " + button);
                notifyNavigationButtonChangedListeners(button);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "navigationTabSelected failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onBackPressed() {
        Logger.printDebug(() -> "Back button pressed");
        createNavButtonLatch();
        setGoingBackNavigationButton();
        setSearchBarClosingIfShown();
    }

    /**
     * Injection point.
     * <p>
     * Predictive back gesture, which does not call {@link #onBackPressed()}.
     */
    public static void onBackInvoked() {
        Logger.printDebug(() -> "Back invoked");
        setGoingBackNavigationButton();
        setSearchBarClosingIfShown();
    }

    /**
     * Going back from the root of a tab selects again the tab it was opened from.
     * Only the You tab is used, as its root shows only horizontal collections, and the tabs it is
     * verified to select again: the Home and Subscriptions tabs. The Subscriptions feed is not used,
     * as its elements cannot be told apart from the elements of the Home feed.
     * <p>
     * Going back does not change the tab if a page is opened in the tab (the toolbar shows the back button),
     * if the search is on screen, or if a player is on screen (other than a video playing in the feed).
     * <p>
     * Must be called on the main thread, before the search bar is set as closing.
     */
    private static void setGoingBackNavigationButton() {
        // A tab selected after going back is not selected by a tap.
        tappedNavigationButton = null;

        try {
            final long now = SystemClock.uptimeMillis();
            if (getGoingBackNavigationButton() != null) {
                // Going back again before the navigation bar is updated goes back to a tab that is not known.
                if (now - goingBackTime > SAME_BACK_EVENT_MILLISECONDS) {
                    goingBackNavigationButton = null;
                    goingBackCancelled = true;
                }
                return;
            }
            goingBackNavigationButton = null;
            if (goingBackCancelled && now - goingBackTime <= GOING_BACK_TIMEOUT_MILLISECONDS) {
                return;
            }
            goingBackCancelled = false;

            NavigationButton selectedButton = NavigationButton.selectedNavigationButton;
            NavigationButton openedFromButton = openedFromNavigationButton;
            final boolean isVerifiedTab = openedFromButton == NavigationButton.HOME;
            if (!isVerifiedTab) {
                return;
            }

            AppCompatToolbarPatchInterface toolbar = toolbarResultsRef.get();
            PlayerType playerType = PlayerType.getCurrent();
            if (toolbar == null ||
                    toolbar.patch_getNavigationIcon() != null ||
                    // The search bar is set as closing by the back button, and both back hooks can be called.
                    isSearchBarActive() ||
                    closingSearchBarResultsRef.get() != null ||
                    !(playerType.isNoneOrHidden()
                            || playerType == PlayerType.WATCH_WHILE_MINIMIZED
                            || playerType == PlayerType.INLINE_MINIMAL) ||
                    ShortsPlayerState.isOpen()) {
                return;
            }

            goingBackTime = now;
            goingBackNavigationButton = openedFromButton;
        } catch (Exception ex) {
            goingBackNavigationButton = null;
            Logger.printException(() -> "setGoingBackNavigationButton failure", ex);
        }
    }

    /**
     * @return The navigation button selected again by going back from the root of the "You" tab,
     *         if the navigation bar is not yet updated, or null if not going back.
     */
    @Nullable
    private static NavigationButton getGoingBackNavigationButton() {
        NavigationButton button = goingBackNavigationButton;
        return button != null && SystemClock.uptimeMillis() - goingBackTime <= GOING_BACK_TIMEOUT_MILLISECONDS
                ? button
                : null;
    }

    /** @noinspection EmptyMethod*/
    private static void navigationTabCreatedCallback(NavigationButton button, View tabView) {
        // Code is added during patching.
    }

    /**
     * Custom cairo notification filled icon to fix unpatched app missing resource.
     */
    private static final int fillBellCairoBlack = ResourceUtils.getIdentifier(ResourceType.DRAWABLE,
            VersionCheckPatch.IS_20_31_OR_GREATER && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00")
                    ? "yt_fill_experimental_bell_vd_theme_24"
                    : "morphe_fill_bell_cairo_black_24"
    );

    /**
     * Injection point.
     * Fixes missing drawable.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void setCairoNotificationFilledIcon(EnumMap enumMap, Enum tabActivityCairo) {
        // Show a popup informing this fix is no longer needed to those who might care.
        if (BaseSettings.DEBUG.get() && enumMap.containsKey(tabActivityCairo)) {
            Logger.printException(() -> "YouTube fixed the notification icons");
        }

        enumMap.putIfAbsent(tabActivityCairo, fillBellCairoBlack);
    }

    public enum NavigationButton {
        HOME("PIVOT_HOME", "TAB_HOME_CAIRO"),
        SHORTS("TAB_SHORTS", "TAB_SHORTS_CAIRO"),
        /**
         * Create new video tab.
         * This tab will never be in a selected state, even if the Create video UI is on screen.
         */
        CREATE("CREATION_TAB_LARGE", "CREATION_TAB_LARGE_CAIRO"),
        /**
         * Only shown when 'Show Search' is turned on.
         */
        SEARCH("SEARCH", "SEARCH_BOLD", "SEARCH_CAIRO"),
        /**
         * Only shown when 'Show Settings' is turned on.
         */
        SETTINGS("SETTINGS", "SETTINGS_CAIRO"),
        SUBSCRIPTIONS("PIVOT_SUBSCRIPTIONS", "TAB_SUBSCRIPTIONS_CAIRO"),
        /**
         * Notifications tab.  Only present when
         * {@link Settings#SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON} is active.
         */
        NOTIFICATIONS("TAB_ACTIVITY", "TAB_ACTIVITY_CAIRO"),
        /**
         * Library tab, including if the user is in incognito mode or when logged out.
         */
        LIBRARY(
                // Modern library tab with 'You' layout.
                // The hooked YT code does not use an enum, and a dummy name is used here.
                "YOU_LIBRARY_DUMMY_PLACEHOLDER_NAME",
                // User is logged out.
                "ACCOUNT_CIRCLE",
                "ACCOUNT_CIRCLE_CAIRO",
                // User is logged in with incognito mode enabled.
                "INCOGNITO_CIRCLE",
                "INCOGNITO_CAIRO",
                // Old library tab (pre 'You' layout), only present when version spoofing.
                "VIDEO_LIBRARY_WHITE",
                // 'You' library tab that is sometimes momentarily loaded.
                // This might be a temporary tab while the user profile photo is loading,
                // but its exact purpose is not entirely clear.
                "PIVOT_LIBRARY"
        );

        @Nullable
        private static volatile NavigationButton selectedNavigationButton;

        /**
         * @return If tapping the button opens a tab, which closes the search if it is on screen.
         */
        boolean closesSearch() {
            return this == HOME || this == SHORTS || this == SUBSCRIPTIONS
                    || this == NOTIFICATIONS || this == LIBRARY;
        }

        /**
         * This will return null only if the currently selected tab is unknown.
         * This scenario will only happen if the UI has different tabs due to an A/B user test
         * or YT abruptly changes the navigation layout for some other reason.
         * <p>
         * All code calling this method should handle a null return value.
         * <p>
         * <b>Due to issues with how YT processes physical back button/gesture events,
         * this patch uses workarounds that can cause this method to take up to 120ms
         * if the device back button was recently pressed.</b>
         *
         * @return The active navigation tab.
         *         If the user is in the upload video UI, this returns tab that is still visually
         *         selected on screen (whatever tab the user was on before tapping the upload button).
         */
        @Nullable
        public static NavigationButton getSelectedNavigationButton() {
            waitForNavButtonLatchIfNeeded();
            return selectedNavigationButton;
        }

        /**
         * Same as {@link #getSelectedNavigationButton()}, but while going back from the root of the
         * You tab, the Litho elements that are not in a horizontal collection are of the tab it goes
         * back to, as the elements of the root of the You tab are in horizontal collections.
         *
         * @param contextInterface Context of the filtered Litho element.
         */
        @Nullable
        public static NavigationButton getSelectedNavigationButton(ContextInterface contextInterface) {
            if (getGoingBackNavigationButton() != null && contextInterface.isHomeFeedOrRelatedVideo()) {
                // The navigation bar can be updated while waiting for the back button latch.
                waitForNavButtonLatchIfNeeded();
                NavigationButton goingBackButton = getGoingBackNavigationButton();
                if (goingBackButton != null) {
                    return goingBackButton;
                }
            }

            return getSelectedNavigationButton();
        }

        /**
         * YouTube enum name for this tab.
         */
        public final List<String> ytEnumNames;

        NavigationButton(String... ytEnumNames) {
            this.ytEnumNames = Arrays.asList(ytEnumNames);
        }
    }
}
