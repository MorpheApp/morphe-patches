/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.apps.youtube.app.application.Shell_SettingsActivity;
import com.google.android.gms.common.api.GoogleApiActivity;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Accessibility;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.AccessibilityData;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.ButtonRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.ButtonRendererAccessibilityData;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.ButtonSheetRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Buttons;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationEntrySheetEndpoint;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationMode;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationModesCommand;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationModesControlsRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationModesControlsRendererHolder;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationModesSwitcherRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.CreationModesSwitcherRendererHolder;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.RendererAccessibilityData;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.patches.spoof.SpoofOSNamePatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class NavigationBarPatch {

    private static final Map<NavigationBar.NavigationButton, Boolean> shouldHideMap =
            new EnumMap<>(NavigationBar.NavigationButton.class) {
        {
            put(NavigationBar.NavigationButton.HOME, Settings.HIDE_HOME_BUTTON.get());
            put(NavigationBar.NavigationButton.CREATE, Settings.HIDE_CREATE_BUTTON.get());
            put(NavigationBar.NavigationButton.NOTIFICATIONS, Settings.HIDE_NOTIFICATIONS_BUTTON.get());
            put(NavigationBar.NavigationButton.SHORTS, Settings.HIDE_SHORTS_BUTTON.get());
            put(NavigationBar.NavigationButton.SUBSCRIPTIONS, Settings.HIDE_SUBSCRIPTIONS_BUTTON.get());
        }
    };

    private static final boolean SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON = Settings.SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON.get();

    public enum NavigationBarStyle {
        /**
         * The feature flags of the translucent navigation bar are left to the account,
         * and the fixes for what they break are off.
         */
        DEFAULT,
        TRANSLUCENT,
        /**
         * Translucent, without the blurred background.
         */
        TRANSPARENT,
        /**
         * Transparent, painted over with the app background.
         */
        OPAQUE
    }

    private static final NavigationBarStyle NAVIGATION_BAR_STYLE = Settings.NAVIGATION_BAR_STYLE.get();

    private static final boolean NARROW_NAVIGATION_BUTTONS = Settings.NARROW_NAVIGATION_BUTTONS.get();

    private static final boolean DISABLE_AUTO_HIDE_NAVIGATION_BAR = Settings.DISABLE_AUTO_HIDE_NAVIGATION_BAR.get();

    private static final boolean HIDE_NAVIGATION_BAR = Settings.HIDE_NAVIGATION_BAR.get();

    private static final boolean HIDE_SHORTS_NAVIGATION_BAR = Settings.HIDE_SHORTS_NAVIGATION_BAR.get();

    /**
     * The view the Shorts player is in, inside the layout of the Shorts player fragment.
     */
    private static final int shortsPlayerContainerId = HIDE_SHORTS_NAVIGATION_BAR
            ? ResourceUtils.getIdentifier(ResourceType.ID, "fragment_container_view")
            : 0;

    /**
     * If the room below the Shorts player is set by {@link #setShortsPlayerBottomInset(View)}.
     * The system navigation buttons are read from the window insets, available from Android 11.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private static final boolean SETS_SHORTS_PLAYER_BOTTOM_ROOM = HIDE_SHORTS_NAVIGATION_BAR
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

    private static final boolean HIDE_NAVIGATION_NEW_CONTENT_DOT = Settings.HIDE_NAVIGATION_NEW_CONTENT_DOT.get();

    private static final int newContentDotId = HIDE_NAVIGATION_NEW_CONTENT_DOT
            ? ResourceUtils.getIdentifier(ResourceType.ID, "new_content_dot")
            : 0;

    public static boolean isPatchIncluded() {
        return false;
    }

    /**
     * Injection point.
     */
    public static String swapCreateWithNotificationButton(String osName) {
        return SpoofOSNamePatch.getOSName(SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON);
    }

    /**
     * Injection point.
     */
    public static void navigationTabCreated(NavigationBar.NavigationButton button, View tabView) {
        if (SHOW_SEARCH_BUTTON && button == NavigationBar.NavigationButton.SEARCH) {
            Utils.runOnMainThread(() -> tabView.setOnClickListener(openSearchBarOnClickListener));
            return;
        }

        if (SHOW_SETTINGS_BUTTON && button == NavigationBar.NavigationButton.SETTINGS) {
            Utils.runOnMainThread(() -> tabView.setOnClickListener(v -> {
                if (SHOW_SETTINGS_BUTTON_TYPE) {
                    openMorpheSettings(v);
                } else {
                    openYouTubeSettings(v);
                }
            }));
            return;
        }

        // The dot is a stub that is inflated when the tab has new content, and the app shows and
        // hides it again later. It is hidden right before every draw, so no frame shows it. The
        // theme patch keeps the color of the dot the same way.
        if (HIDE_NAVIGATION_NEW_CONTENT_DOT && newContentDotId != 0) {
            try {
                tabView.getViewTreeObserver().addOnPreDrawListener(() -> {
                    View dot = tabView.findViewById(newContentDotId);
                    if (dot != null && !(dot instanceof ViewStub) && dot.getVisibility() == View.VISIBLE) {
                        dot.setVisibility(View.INVISIBLE);
                    }
                    return true;
                });
            } catch (Exception ex) {
                Logger.printException(() -> "navigationTabCreated new content dot failure", ex);
            }
        }

        if (Boolean.TRUE.equals(shouldHideMap.get(button))) {
            tabView.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     */
    public static void hideNavigationButtonLabels(TextView navigationLabelsView) {
        Utils.hideViewUnderCondition(Settings.HIDE_NAVIGATION_BUTTON_LABELS, navigationLabelsView);
    }

    /**
     * Injection point.
     */
    public static boolean useAnimatedNavigationButtons(boolean original) {
        return Settings.NAVIGATION_BAR_ANIMATIONS.get();
    }

    /**
     * Injection point.
     */
    public static boolean enableNarrowNavigationButton(boolean original) {
        return NARROW_NAVIGATION_BUTTONS || original;
    }

    /**
     * Injection point.
     */
    public static boolean disableAutoHidingNavigationBar() {
        return DISABLE_AUTO_HIDE_NAVIGATION_BAR;
    }

    /**
     * Injection point.
     */
    public static void hideNavigationBar(View view) {
        Utils.hideViewUnderCondition(HIDE_NAVIGATION_BAR, view);
    }

    /**
     * Injection point.
     * <p>
     * The translucent navigation bar comes from feature flags that not every account gets, so they
     * are turned on to make the bar look the same on every account. With them, the navigation bar
     * and the pages make room for the system navigation buttons, which is only right when
     * the window is drawn behind them. That comes from the edge to edge top bar feature flag,
     * so without it the room is added twice: the navigation bar is raised and an empty bar is left
     * at the bottom (https://github.com/MorpheApp/morphe-patches/issues/3825).
     * <p>
     * The app draws the window behind the system navigation buttons only on Android 12 and later,
     * so on older versions the flags are always turned off, or the room is added twice
     * (https://github.com/MorpheApp/morphe-patches/issues/3864).
     * <p>
     * With {@link NavigationBarStyle#DEFAULT} the flags are left to the account.
     */
    public static boolean useTranslucentNavigationBar(boolean original) {
        if (NAVIGATION_BAR_STYLE == NavigationBarStyle.DEFAULT) {
            return original;
        }

        boolean translucent = original;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                translucent = false;
            } else {
                Boolean edgeToEdge = HideStatusBarPatch.isEdgeToEdgeTopBar();
                // Null if not known yet, or the Hide status bar patch is not included.
                if (edgeToEdge != null) {
                    translucent = edgeToEdge;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "useTranslucentNavigationBar failure", ex);
        }

        translucentNavigationBarForced = translucent;
        return translucent;
    }

    /**
     * If {@link #useTranslucentNavigationBar(boolean)} turned the translucent navigation bar on.
     * The fixes for what its feature flags break are only needed then, and stay off if the flags
     * are no longer turned on by this patch.
     */
    private static volatile boolean translucentNavigationBarForced;

    /**
     * Injection point.
     */
    public static boolean isTranslucentNavigationBarForced() {
        return translucentNavigationBarForced;
    }

    /**
     * Injection point.
     * <p>
     * With the navigation bar covering the system navigation buttons, the room below the bar fixed
     * at the bottom of some pages, such as the reply bar of the comment replies, already holds
     * the system navigation buttons. The flag adds the navigation bar height to it as well, so an
     * empty band is left between the two bars (https://github.com/MorpheApp/morphe-patches/issues/2300).
     */
    public static boolean useFixedBottomBarRoom(boolean original) {
        return original && !translucentNavigationBarForced;
    }

    /**
     * Injection point.
     * <p>
     * The blurred, translucent background of the navigation bar and the feed laid out behind it
     * only change how the bar looks, not the room left for the system navigation buttons, so they
     * are turned on from Android 12 also when the window is not drawn edge to edge. With
     * {@link NavigationBarStyle#OPAQUE}, they follow {@link #useTranslucentNavigationBar(boolean)}
     * instead. With {@link NavigationBarStyle#DEFAULT} they are left to the account.
     */
    public static boolean useTranslucentNavigationBarBackground(boolean original) {
        if (NAVIGATION_BAR_STYLE == NavigationBarStyle.DEFAULT) {
            return original;
        }

        final boolean translucent;
        if (NAVIGATION_BAR_STYLE != NavigationBarStyle.OPAQUE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            translucent = true;
        } else {
            translucent = useTranslucentNavigationBar(original);
        }

        translucentNavigationBarBackgroundForced = translucent;
        return translucent;
    }

    /**
     * If {@link #useTranslucentNavigationBarBackground(boolean)} turned the translucent background
     * of the navigation bar on. The fixes for what the blur and the feed behind the navigation bar
     * break are only needed then.
     */
    private static volatile boolean translucentNavigationBarBackgroundForced;

    /**
     * Injection point.
     */
    public static boolean isTranslucentNavigationBarBackgroundForced() {
        return translucentNavigationBarBackgroundForced;
    }

    /**
     * Injection point.
     * <p>
     * The blurred background of the navigation bar draws a copy of the watch layout, so the player
     * surface is drawn twice in every frame, at two positions. Android 12 and 13 send the two
     * positions to the surface from two threads at the same time, and the app crashes while
     * the player moves, such as when it is slowly dragged down to the miniplayer
     * (https://github.com/MorpheApp/morphe-patches/pull/3684#issuecomment-6322984709).
     * With {@link NavigationBarStyle#TRANSPARENT} and {@link NavigationBarStyle#OPAQUE} the copy
     * is not drawn, so the bar keeps its translucent color, without the blur.
     *
     * @param hardwareAccelerated If the canvas is hardware accelerated, which the blur is drawn for.
     */
    public static boolean useNavigationBarBlur(boolean hardwareAccelerated) {
        return hardwareAccelerated && !(translucentNavigationBarBackgroundForced
                && (NAVIGATION_BAR_STYLE == NavigationBarStyle.TRANSPARENT
                || NAVIGATION_BAR_STYLE == NavigationBarStyle.OPAQUE));
    }

    /**
     * If the keyboard height was ever removed from the bottom margin of the layout that holds
     * the page, the player and the navigation bar, so the keyboard covers them.
     */
    private static volatile boolean keyboardMarginRemoved;

    /**
     * Injection point.
     * <p>
     * The bottom margin of the layout that holds the page, the player and the navigation bar while
     * the keyboard is open. With the feature flags turned on by
     * {@link #useTranslucentNavigationBar(boolean)}, the keyboard height is removed from it, so
     * the keyboard covers them again.
     * <p>
     * The legacy miniplayer, such as the minimal type before 21.29, rests at the bottom of that
     * layout, so while it is shown the layout ends at the top of the keyboard instead
     * (https://github.com/MorpheApp/morphe-patches/issues/4003). The margin of the app leaves
     * the height of the system navigation buttons for the navigation bar, which is hidden while
     * the keyboard is open, so the full keyboard height is used. The other miniplayers are moved
     * above the keyboard by {@link MinimalMiniplayerPatch}.
     *
     * @param original The keyboard height the app uses as the margin.
     */
    public static int getKeyboardMargin(int original) {
        if (!translucentNavigationBarForced) {
            return original;
        }

        try {
            if (PlayerType.getCurrent() == PlayerType.WATCH_WHILE_MINIMIZED
                    && !MiniplayerPatch.getCurrentMiniplayerType().isModern()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Activity activity = Utils.getActivity();
                WindowInsets insets = activity == null
                        ? null
                        : activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    return Math.max(original, insets.getInsets(WindowInsets.Type.ime()).bottom);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getKeyboardMargin failure", ex);
        }

        keyboardMarginRemoved = true;
        return 0;
    }

    static boolean isKeyboardMarginRemoved() {
        return keyboardMarginRemoved;
    }

    /**
     * Injection point.
     * <p>
     * When the navigation bar covers the system navigation buttons, the bottom window inset is
     * added to its height and bottom padding. With the keyboard open, that inset also holds
     * the keyboard, so the bar grows as tall as the keyboard and covers the page
     * (https://github.com/MorpheApp/morphe-patches/issues/3909). The page above then ends
     * the height of the system bars below the top of the keyboard, so only that much room is left.
     * Only with the feature flags turned on by {@link #useTranslucentNavigationBar(boolean)}.
     */
    public static int getNavigationBarBottomPadding(View navigationBar, int original) {
        int padding = original;
        try {
            if (translucentNavigationBarForced && original != 0
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsets insets = navigationBar.getRootWindowInsets();
                if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                    padding = Math.min(original, insets.getInsets(WindowInsets.Type.systemBars()).bottom);
                }
            }

            padding = setSlimStatusBarRoom(navigationBar, padding);
        } catch (Exception ex) {
            Logger.printException(() -> "getNavigationBarBottomPadding failure", ex);
        }
        return padding;
    }

    /**
     * The bar shown below the navigation bar when the connection is lost or comes back
     * ("No connection", "Back online").
     */
    private static final int SLIM_STATUS_BAR = ResourceUtils.getIdentifier(
            ResourceType.ID, "slim_status_bar");

    private static final int PIVOT_BAR = ResourceUtils.getIdentifier(
            ResourceType.ID, "pivot_bar");

    /**
     * The navigation bar, with a method added during patching.
     */
    public interface PivotBarAccess {
        /**
         * Sets the room the navigation bar leaves below itself for the system navigation buttons,
         * which is added to its height.
         */
        void patch_setSystemButtonsRoom(int room);
    }

    /**
     * If the slim status bar is shown below the navigation bar.
     */
    private static volatile boolean slimStatusBarShown;

    /**
     * The room for the system navigation buttons the app last gave the navigation bar.
     */
    private static volatile int systemButtonsRoom;

    /**
     * When the navigation bar covers the system navigation buttons, the slim status bar is laid out
     * below it, behind the buttons, which cover it. While the slim status bar is shown, the room for
     * the buttons is left below the slim status bar instead of below the navigation bar.
     * Only with the feature flags turned on by {@link #useTranslucentNavigationBar(boolean)}.
     *
     * @return The room the navigation bar leaves for the system navigation buttons.
     */
    private static int setSlimStatusBarRoom(View navigationBar, int room) {
        if (!translucentNavigationBarForced || !(navigationBar instanceof PivotBarAccess)) {
            return room;
        }

        systemButtonsRoom = room;
        View slimStatusBar = navigationBar.getParent() instanceof ViewGroup container
                ? container.findViewById(SLIM_STATUS_BAR)
                : null;
        final boolean moveRoom = slimStatusBarShown && slimStatusBar != null && room > 0;
        if (slimStatusBar != null) {
            // The top padding is the padding of the slim status bar itself.
            final int bottomPadding = slimStatusBar.getPaddingTop() + (moveRoom ? room : 0);
            if (slimStatusBar.getPaddingBottom() != bottomPadding) {
                slimStatusBar.setPadding(slimStatusBar.getPaddingLeft(), slimStatusBar.getPaddingTop(),
                        slimStatusBar.getPaddingRight(), bottomPadding);
            }
        }
        return moveRoom ? 0 : room;
    }

    /**
     * Injection point.
     * <p>
     * The window insets are not sent again when the slim status bar is shown or hidden,
     * so the room for the system navigation buttons is moved whenever that happens.
     *
     * @param bottomBarContainer The container of the navigation bar and the slim status bar.
     */
    public static void watchSlimStatusBar(View bottomBarContainer) {
        if (SLIM_STATUS_BAR == 0 || PIVOT_BAR == 0) return;

        try {
            bottomBarContainer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                          oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    View slimStatusBar = view.findViewById(SLIM_STATUS_BAR);
                    final boolean shown = slimStatusBar != null && slimStatusBar.getVisibility() == View.VISIBLE;
                    if (shown == slimStatusBarShown) return;
                    slimStatusBarShown = shown;

                    if (translucentNavigationBarForced
                            && view.findViewById(PIVOT_BAR) instanceof PivotBarAccess access) {
                        View navigationBar = (View) access;
                        final int room = systemButtonsRoom;
                        view.post(() -> {
                            final int navigationBarRoom = setSlimStatusBarRoom(navigationBar, room);
                            access.patch_setSystemButtonsRoom(navigationBarRoom);
                            navigationBar.setPadding(navigationBar.getPaddingLeft(), navigationBar.getPaddingTop(),
                                    navigationBar.getPaddingRight(), navigationBarRoom);
                        });
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "watchSlimStatusBar layout failure", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "watchSlimStatusBar failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * With the translucent navigation bar turned on, the window is drawn behind the system
     * navigation buttons, the navigation bar makes room for them, and the pages end above
     * the navigation bar. With the navigation bar hidden in the Shorts player, nothing makes that
     * room, so the Shorts player is drawn behind the system navigation buttons
     * (https://github.com/MorpheApp/morphe-patches/issues/3908). The Shorts player then makes
     * the room itself.
     * <p>
     * The room the Shorts player reserves for the navigation bar depends on how it's opened,
     * so with the navigation bar hidden that room is left out ({@link #getShortsPlayerBottomRoom(int)}),
     * and the Shorts player always ends a slight room above the system navigation buttons,
     * or the seekbar thumb at the bottom of the Shorts player overlaps them.
     *
     * @param shortsFragmentLayout The layout of the Shorts player fragment.
     */
    public static void setShortsPlayerBottomInset(View shortsFragmentLayout) {
        if (!SETS_SHORTS_PLAYER_BOTTOM_ROOM) {
            return;
        }

        try {
            shortsFragmentLayout.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    WindowInsets insets = view.getRootWindowInsets();
                    View shortsPlayerContainer = view.findViewById(shortsPlayerContainerId);
                    if (insets == null || shortsPlayerContainer == null) return;

                    // The window can already end above the system navigation buttons, such as
                    // without the translucent navigation bar, so only the room still missing
                    // is added. The padding added here is left out, or the room would be counted twice.
                    final int navigationButtonsHeight = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    int[] location = new int[2];
                    shortsPlayerContainer.getLocationInWindow(location);
                    final int roomBelow = view.getRootView().getHeight()
                            - (location[1] + shortsPlayerContainer.getHeight())
                            - view.getPaddingBottom();
                    final int padding = Math.max(0, navigationButtonsHeight + Dim.dp12 - roomBelow);

                    if (view.getPaddingBottom() != padding) {
                        view.post(() -> view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                                view.getPaddingRight(), padding));
                    }

                    // The room is drawn with the background of the layout, which follows the app theme.
                    // The system navigation buttons over the Shorts player are light in both themes,
                    // so with the light theme they cannot be seen. The room is black like the Shorts player.
                    if (!(view.getBackground() instanceof ColorDrawable background)
                            || background.getColor() != Color.BLACK) {
                        view.setBackgroundColor(Color.BLACK);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "setShortsPlayerBottomInset layout failure", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "setShortsPlayerBottomInset failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param original The room the Shorts player reserves below itself for the navigation bar.
     */
    public static int getShortsPlayerBottomRoom(int original) {
        return SETS_SHORTS_PLAYER_BOTTOM_ROOM ? 0 : original;
    }

    /**
     * Injection point.
     *
     * @param bottomBarContainer The container of the navigation bar, which also covers the area
     *                           behind the system navigation buttons or gesture handle.
     */
    public static void setNavigationBarOpaque(View bottomBarContainer) {
        if (NAVIGATION_BAR_STYLE != NavigationBarStyle.OPAQUE) return;

        try {
            OpaqueSystemBars.apply(bottomBarContainer);
        } catch (Exception ex) {
            Logger.printException(() -> "setNavigationBarOpaque failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param original The color the app theme gives the system navigation buttons.
     */
    public static int getSystemNavigationBarColor(int original) {
        if (NAVIGATION_BAR_STYLE != NavigationBarStyle.OPAQUE) return original;

        try {
            return OpaqueSystemBars.systemNavigationBarColor(original);
        } catch (Exception ex) {
            Logger.printException(() -> "getSystemNavigationBarColor failure", ex);
            return original;
        }
    }

    // Navigation search and settings button
    private static final boolean SHOW_SEARCH_BUTTON = Settings.SHOW_SEARCH_BUTTON.get();
    private static final IntegerSetting SHOW_SEARCH_BUTTON_INDEX = Settings.SHOW_SEARCH_BUTTON_INDEX;

    private static volatile WeakReference<TextView> searchQueryRef = new WeakReference<>(null);

    private static Object pivotBarRenderer = null;
    private static View.OnClickListener openSearchBar = null;

    private static final boolean SHOW_SETTINGS_BUTTON = Settings.SHOW_SETTINGS_BUTTON.get();
    private static final IntegerSetting SHOW_SETTINGS_BUTTON_INDEX = Settings.SHOW_SETTINGS_BUTTON_INDEX;
    private static final boolean SHOW_SETTINGS_BUTTON_TYPE = Settings.SHOW_SETTINGS_BUTTON_TYPE.get();

    private static Object pivotBarSettingsRenderer = null;

    private static final View.OnClickListener openSearchBarOnClickListener = v -> {
        if (NavigationBar.isSearchBarActive() && searchQueryRef.get() != null) {
            // If the search bar is active, simply click on the search query view.
            searchQueryRef.get().callOnClick();
        } else if (openSearchBar != null) {
            // If the search bar is not active, click the OnClickListener of the search button.
            openSearchBar.onClick(v);
        } else {
            // If the OnClickListener of the search button is not initialized, execute the shortcut.
            Context context = v.getContext();
            Intent intent = new Intent();
            intent.setAction("com.google.android.youtube.action.open.search");
            intent.setPackage(context.getPackageName());
            context.startActivity(intent);
        }
    };

    /**
     * Injection point.
     *
     * @param searchQuery The text view of the search query shown in the search results.
     */
    public static void searchQueryViewLoaded(TextView searchQuery) {
        if (SHOW_SEARCH_BUTTON) {
            searchQueryRef = new WeakReference<>(searchQuery);
        }
    }

    /**
     * Injection point.
     *
     * @param messageLite MessageLite class of ButtonRenderer in topBarRenderer.
     * @param listener    OnClickListener for topBar buttons (Create, Notification, Search, Settings).
     */
    public static void setSearchBarOnClickListener(MessageLite messageLite, View.OnClickListener listener) {
        if (SHOW_SEARCH_BUTTON) {
            try {
                var buttonRenderer = ButtonRenderer.parseFrom(messageLite.toByteArray());
                if (buttonRenderer.hasIcon()) {
                    var iconName = buttonRenderer.getIcon().getYtIconType().name();

                    // Check the icon name to see if it is the OnClickListener of the search button.
                    if (NavigationBar.NavigationButton.SEARCH.ytEnumNames.contains(iconName)) {
                        openSearchBar = listener;
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to set search bar OnClickListener", ex);
            }
        }
    }

    /**
     * Injection point.
     *
     * @param messageLite MessageLite class of PivotBarItemRenderer.
     */
    @Nullable
    public static byte[] parsePivotBarItemRenderer(MessageLite messageLite) {
        if (SHOW_SEARCH_BUTTON) {
            try {
                var pivotBarItemBuilder = PivotBarItemRenderer.parseFrom(messageLite.toByteArray()).toBuilder();
                var iconName = pivotBarItemBuilder.getIcon().getYtIconType().name();

                // Check the icon name to see if it is the PivotBarItemRenderer of the home button.
                // If other buttons besides the home button are used, the code becomes more complex.
                if (NavigationBar.NavigationButton.HOME.ytEnumNames.contains(iconName)) {
                    // Change the label and icon of the navigation button.
                    var newAccessibilityData = AccessibilityData.newBuilder().setLabel(str("menu_search")).build();
                    var newAccessibility = Accessibility.newBuilder().setAccessibilityData(newAccessibilityData).build();
                    var ytIconType = Utils.appIsUsingBoldIcons() ? YTIconType.SEARCH_BOLD : YTIconType.SEARCH_CAIRO;
                    var newIcon = Icon.newBuilder().setYtIconType(ytIconType).build();

                    pivotBarItemBuilder.clearAccessibility();
                    pivotBarItemBuilder.setAccessibility(newAccessibility);
                    pivotBarItemBuilder.clearIcon();
                    pivotBarItemBuilder.setIcon(newIcon);

                    return pivotBarItemBuilder.build().toByteArray();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse PivotBarItemRenderer", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     * Called after {@link #parsePivotBarItemRenderer(MessageLite)}.
     *
     * @param object Classes used in YouTube with MessageLite.
     */
    public static void setPivotBarRenderer(Object object) {
        if (SHOW_SEARCH_BUTTON) {
            pivotBarRenderer = object;
        }
    }

    /**
     * Injection point.
     * Called after {@link #setPivotBarRenderer(Object)}.
     *
     * @param list Proto list containing PivotBarRenderer.
     */
    public static List<Object> getPivotBarRendererList(List<Object> list) {
        if (list == null || list.isEmpty()) return list;

        boolean addSearch = SHOW_SEARCH_BUTTON && pivotBarRenderer != null;
        boolean addSettings = SHOW_SETTINGS_BUTTON && pivotBarSettingsRenderer != null;

        if (!addSearch && !addSettings) return list;

        List<Object> newList = new ArrayList<>(list);

        if (addSearch) {
            int preferredIndex = SHOW_SEARCH_BUTTON_INDEX.get();
            preferredIndex = Math.max(0, Math.min(preferredIndex, newList.size()));

            newList.add(preferredIndex, pivotBarRenderer);
        }

        if (addSettings) {
            int preferredIndex = SHOW_SETTINGS_BUTTON_INDEX.get();
            preferredIndex = Math.max(0, Math.min(preferredIndex, newList.size()));

            newList.add(preferredIndex, pivotBarSettingsRenderer);
        }

        return newList;
    }

    @Nullable
    public static byte[] parseSettingsPivotBarItemRenderer(MessageLite messageLite) {
        if (SHOW_SETTINGS_BUTTON) {
            try {
                var pivotBarItemBuilder = PivotBarItemRenderer.parseFrom(messageLite.toByteArray()).toBuilder();
                var iconName = pivotBarItemBuilder.getIcon().getYtIconType().name();

                if (NavigationBar.NavigationButton.HOME.ytEnumNames.contains(iconName)) {
                    var newAccessibilityData = AccessibilityData.newBuilder().setLabel(str("menu_settings")).build();
                    var newAccessibility = Accessibility.newBuilder().setAccessibilityData(newAccessibilityData).build();

                    var newIcon = Icon.newBuilder().setYtIconType(YTIconType.SETTINGS_CAIRO).build();

                    pivotBarItemBuilder.clearAccessibility();
                    pivotBarItemBuilder.setAccessibility(newAccessibility);
                    pivotBarItemBuilder.clearIcon();
                    pivotBarItemBuilder.setIcon(newIcon);

                    return pivotBarItemBuilder.build().toByteArray();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse Settings PivotBarItemRenderer", ex);
            }
        }
        return null;
    }

    public static void setPivotBarSettingsRenderer(Object object) {
        if (SHOW_SETTINGS_BUTTON) {
            pivotBarSettingsRenderer = object;
        }
    }

    // Toolbar
    private static final String CHAT_BUTTON_ENUMS = "MESSAGE_BUBBLE_OVERLAP";

    private static final String[] CREATE_BUTTON_ENUMS = {
            "CREATION_ENTRY", // Phone layout.
            "FAB_CAMERA" // Tablet layout.
    };

    private static final String[] NOTIFICATION_BUTTON_ENUMS = {
            "TAB_ACTIVITY_CAIRO", // New layout.
            "TAB_ACTIVITY" // Old layout.
    };

    private static final String SETTING_BUTTON_ENUM_NAME = "SETTINGS_CAIRO";

    private static final boolean HIDE_TOOLBAR_CAST_BUTTON = Settings.HIDE_TOOLBAR_CAST_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_CHAT_BUTTON = Settings.HIDE_TOOLBAR_CHAT_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_CREATE_BUTTON = Settings.HIDE_TOOLBAR_CREATE_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_NOTIFICATION_BUTTON = Settings.HIDE_TOOLBAR_NOTIFICATION_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_SEARCH_BUTTON = Settings.HIDE_TOOLBAR_SEARCH_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_MICROPHONE_BUTTON = Settings.HIDE_TOOLBAR_MICROPHONE_BUTTON.get();

    private static final boolean SHOW_TOOLBAR_SETTINGS_BUTTON = Settings.SHOW_TOOLBAR_SETTINGS_BUTTON.get();
    private static final IntegerSetting SHOW_TOOLBAR_SETTINGS_BUTTON_INDEX = Settings.SHOW_TOOLBAR_SETTINGS_BUTTON_INDEX;
    private static final boolean SHOW_TOOLBAR_SETTINGS_BUTTON_TYPE = Settings.SHOW_TOOLBAR_SETTINGS_BUTTON_TYPE.get();

    /**
     * Interface to use obfuscated methods.
     */
    public interface SettingsController {
        // Methods are added during patching.
        void patch_openYouTubeSettings();
    }

    private static WeakReference<SettingsController> settingsControllerRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static boolean hideCastButton(boolean original) {
        return !HIDE_TOOLBAR_CAST_BUTTON && original;
    }

    /**
     * Injection point.
     */
    public static void hideCastButton(MenuItem menuItem) {
        if (HIDE_TOOLBAR_CAST_BUTTON) {
            menuItem.setVisible(false);
            menuItem.setEnabled(false);
        }
    }

    /**
     * Injection point.
     */
    public static void hideChatButton(String enumName, View parentView, ImageView imageView) {
        final boolean shouldHide = HIDE_TOOLBAR_CHAT_BUTTON && Utils.equalsAny(enumName, CHAT_BUTTON_ENUMS);
        Utils.hideViewUnderCondition(shouldHide, parentView);
    }

    /**
     * Injection point.
     */
    public static void hideCreateButton(String enumName, View parentView, ImageView imageView) {
        final boolean shouldHide = HIDE_TOOLBAR_CREATE_BUTTON && Utils.equalsAny(enumName, CREATE_BUTTON_ENUMS);
        Utils.hideViewUnderCondition(shouldHide, parentView);
    }

    /**
     * Injection point.
     */
    public static void hideNotificationButton(String enumName, View parentView, ImageView imageView) {
        boolean shouldHide = HIDE_TOOLBAR_NOTIFICATION_BUTTON && Utils.equalsAny(enumName, NOTIFICATION_BUTTON_ENUMS);
        Utils.hideViewUnderCondition(shouldHide, parentView);
    }

    /**
     * Injection point.
     */
    public static void hideSearchButton(String enumName, View parentView, ImageView imageView) {
        boolean shouldHide = HIDE_TOOLBAR_SEARCH_BUTTON && NavigationBar.NavigationButton.SEARCH.ytEnumNames.contains(enumName);
        Utils.hideViewUnderCondition(shouldHide, parentView);
    }

    /**
     * Injection point.
     */
    public static void hideOldSearchButton(MenuItem menuItem, int original) {
        int actionEnum = HIDE_TOOLBAR_SEARCH_BUTTON ? MenuItem.SHOW_AS_ACTION_NEVER : original;
        menuItem.setShowAsAction(actionEnum);
    }

    /**
     * Injection point.
     */
    public static void hideMicrophoneButton(View view) {
        Utils.hideViewUnderCondition(HIDE_TOOLBAR_MICROPHONE_BUTTON, view);
    }

    /**
     * Injection point.
     */
    public static void hideMicrophoneButton(View view, int visibility) {
        view.setVisibility(HIDE_TOOLBAR_MICROPHONE_BUTTON ? View.GONE : visibility);
    }

    /**
     * Injection point.
     */
    public static void setSettingsController(@NonNull SettingsController settingsController) {
        if (SHOW_TOOLBAR_SETTINGS_BUTTON || SHOW_SETTINGS_BUTTON) {
            settingsControllerRef = new WeakReference<>(settingsController);
        }
    }

    /**
     * Injection point.
     * <p>
     * The toolbar create button of the Android Automotive layout, which swaps the create button with the
     * notifications button, opens a bottom sheet whose video button uses the upload endpoint, which the app
     * handles only in the creation modes. The upload endpoint is opened in the creation modes instead,
     * as the create button of the phone layout does.
     */
    public static void fixToolbarCreateButtonUpload(List<MessageLite> rawButtonList) {
        if (HIDE_TOOLBAR_CREATE_BUTTON || rawButtonList == null || rawButtonList.isEmpty()) return;

        try {
            for (int i = 0, size = rawButtonList.size(); i < size; i++) {
                MessageLite rawButtons = rawButtonList.get(i);
                Buttons buttons = Buttons.parseFrom(rawButtons.toByteArray());
                if (!buttons.hasButtonRenderer() || !buttons.getButtonRenderer().hasIcon()
                        || !Utils.equalsAny(buttons.getButtonRenderer().getIcon().getYtIconType().name(),
                        CREATE_BUTTON_ENUMS)) {
                    continue;
                }

                // The upload buttons of the create button open the upload endpoint in the creation modes.
                // Nothing is changed if the create button has no upload button.
                ButtonRenderer createButton = buttons.getButtonRenderer();
                NavigationEndpoint endpoint = createButton.getNavigationEndpoint();
                if (!endpoint.hasCreationEntrySheetEndpoint()) {
                    return;
                }
                CreationEntrySheetEndpoint sheetEndpoint = endpoint.getCreationEntrySheetEndpoint();
                ButtonSheetRenderer sheet = sheetEndpoint.getButtonSheetSupportedRenderers().getButtonSheetRenderer();
                ButtonSheetRenderer.Builder sheetBuilder = sheet.toBuilder();
                boolean replaced = false;

                for (int j = 0, count = sheet.getButtonsCount(); j < count; j++) {
                    Buttons button = sheet.getButtons(j);
                    ButtonRenderer buttonRenderer = button.getButtonRenderer();
                    NavigationEndpoint uploadEndpoint = buttonRenderer.getNavigationEndpoint();
                    if (!button.hasButtonRenderer() || !uploadEndpoint.hasShortsCreationEndpoint()) {
                        continue;
                    }

                    // The only mode button of the creation modes opens the upload endpoint.
                    ButtonRenderer modeButton = buttonRenderer.toBuilder()
                            .clearNavigationEndpoint()
                            .setCommand(uploadEndpoint)
                            .build();
                    CreationModesSwitcherRenderer switcher = CreationModesSwitcherRenderer.newBuilder()
                            .addModeButtons(Buttons.newBuilder().setButtonRenderer(modeButton))
                            .addModes(CreationMode.CREATION_MODE_UPLOADS)
                            .setDefaultMode(CreationMode.CREATION_MODE_UPLOADS)
                            .build();
                    CreationModesCommand creationModesCommand = CreationModesCommand.newBuilder()
                            .setCreationModesControlsRenderer(CreationModesControlsRendererHolder.newBuilder()
                                    .setCreationModesControlsRenderer(CreationModesControlsRenderer.newBuilder()
                                            .setCreationModesSwitcherRenderer(CreationModesSwitcherRendererHolder.newBuilder()
                                                    .setCreationModesSwitcherRenderer(switcher))))
                            .build();

                    NavigationEndpoint creationModesEndpoint = uploadEndpoint.toBuilder()
                            .clearShortsCreationEndpoint()
                            .setCreationModesCommand(creationModesCommand)
                            .build();
                    sheetBuilder.setButtons(j, button.toBuilder().setButtonRenderer(
                            buttonRenderer.toBuilder().setNavigationEndpoint(creationModesEndpoint)));
                    replaced = true;
                }
                if (!replaced) {
                    return;
                }

                CreationEntrySheetEndpoint creationModesSheetEndpoint = sheetEndpoint.toBuilder()
                        .setButtonSheetSupportedRenderers(sheetEndpoint.getButtonSheetSupportedRenderers().toBuilder()
                                .setButtonSheetRenderer(sheetBuilder))
                        .build();
                ButtonRenderer creationModesCreateButton = createButton.toBuilder()
                        .setNavigationEndpoint(endpoint.toBuilder().setCreationEntrySheetEndpoint(creationModesSheetEndpoint))
                        .build();
                rawButtonList.set(i, parseWithRegistry(rawButtons,
                        buttons.toBuilder().setButtonRenderer(creationModesCreateButton).build().toByteArray(),
                        getGeneratedRegistry()));
                Logger.printDebug(() -> "Toolbar create button opens the upload in the creation modes");
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fixToolbarCreateButtonUpload failure", ex);
        }
    }

    /**
     * Parses a message with the extension registry. The commands of the buttons are extensions,
     * which are lost if the message is parsed without the registry.
     *
     * @param message Message of the class to parse.
     */
    private static MessageLite parseWithRegistry(MessageLite message, byte[] bytes, ExtensionRegistryLite registry) {
        return null; // Modified during patching.
    }

    /**
     * @return The extension registry of the app.
     */
    private static ExtensionRegistryLite getGeneratedRegistry() {
        return null; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void modifyToolbarButtons(List<MessageLite> rawButtonList) {
        if (rawButtonList == null || rawButtonList.isEmpty()) return;

        try {
            for (int i = rawButtonList.size() - 1; i >= 0; i--) {
                MessageLite msg = rawButtonList.get(i);
                Buttons buttons = Buttons.parseFrom(msg.toByteArray());

                if (buttons.hasButtonRenderer() && buttons.getButtonRenderer().hasIcon()) {
                    String iconName = buttons.getButtonRenderer().getIcon().getYtIconType().name();

                    boolean isCreate = Utils.equalsAny(iconName, CREATE_BUTTON_ENUMS);
                    boolean isNotification = Utils.equalsAny(iconName, NOTIFICATION_BUTTON_ENUMS);
                    boolean isSearch = NavigationBar.NavigationButton.SEARCH.ytEnumNames.contains(iconName);

                    if ((HIDE_TOOLBAR_CREATE_BUTTON && isCreate) ||
                            (HIDE_TOOLBAR_NOTIFICATION_BUTTON && isNotification) ||
                            (HIDE_TOOLBAR_SEARCH_BUTTON && isSearch)) {
                        rawButtonList.remove(i);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to modify toolbar buttons", ex);
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static byte[] createToolbarSettingsButton(List<MessageLite> rawButtonList) {
        if (!SHOW_TOOLBAR_SETTINGS_BUTTON || rawButtonList == null || rawButtonList.isEmpty()) return null;
        try {
            for (MessageLite msg : rawButtonList) {
                try {
                    Buttons originalButtons = Buttons.parseFrom(msg.toByteArray());

                    if (originalButtons.hasButtonRenderer() && originalButtons.getButtonRenderer().hasIcon()) {
                        ButtonRenderer.Builder renderer = originalButtons.getButtonRenderer().toBuilder();

                        // Replace the accessibility label of the copied button.
                        ButtonRendererAccessibilityData accessibilityData = ButtonRendererAccessibilityData
                                .newBuilder().setLabel(str("morphe_settings_submenu_title")).build();
                        renderer.setButtonRendererAccessibilityData(accessibilityData);
                        renderer.setRendererAccessibilityData(RendererAccessibilityData.newBuilder()
                                .setButtonRendererAccessibilityData(accessibilityData).build());

                        renderer.clearIcon();
                        renderer.setIcon(Icon.newBuilder().setYtIconType(YTIconType.SETTINGS_CAIRO).build());

                        return originalButtons.toBuilder().setButtonRenderer(renderer.build()).build().toByteArray();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "Failed to create toolbar settings button", e);
        }
        return null;
    }

    /**
     * Injection point.
     */
    public static void applyToolbarSettingsButtonIndex(List<MessageLite> rawButtonList) {
        if (!SHOW_TOOLBAR_SETTINGS_BUTTON || rawButtonList == null || rawButtonList.isEmpty()) return;

        int targetIndex = SHOW_TOOLBAR_SETTINGS_BUTTON_INDEX.get() - 1;

        targetIndex = Math.max(0, Math.min(targetIndex, rawButtonList.size() - 1));

        MessageLite settingsButton = rawButtonList.remove(rawButtonList.size() - 1);
        rawButtonList.add(targetIndex, settingsButton);
    }

    /**
     * Injection point.
     */
    public static void setToolbarSettingsOnClickListener(String enumName, View parentView, ImageView imageView) {
        if (SHOW_TOOLBAR_SETTINGS_BUTTON && SETTING_BUTTON_ENUM_NAME.equals(enumName)) {
            Utils.runOnMainThreadDelayed(() -> {
                if (imageView != null) {
                    imageView.setClickable(true);

                    if (SHOW_TOOLBAR_SETTINGS_BUTTON_TYPE) {
                        imageView.setOnClickListener(NavigationBarPatch::openMorpheSettings);
                        imageView.setOnLongClickListener(button -> {
                            openYouTubeSettings(button);
                            return true;
                        });
                    } else {
                        imageView.setOnClickListener(NavigationBarPatch::openYouTubeSettings);
                        imageView.setOnLongClickListener(button -> {
                            openMorpheSettings(button);
                            return true;
                        });
                    }
                }
            }, 100);
        }
    }

    private static void openYouTubeSettings(View view) {
        SettingsController settingsController = settingsControllerRef.get();
        if (settingsController != null) {
            settingsController.patch_openYouTubeSettings();
            return;
        }

        Activity activity = Utils.getActivity();
        Context context = activity != null ? activity : view.getContext();

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setPackage(context.getPackageName());
            intent.setClass(context, Shell_SettingsActivity.class);

            if (activity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            context.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to open YouTube settings", e);
        }
    }

    private static void openMorpheSettings(View view) {
        Activity activity = Utils.getActivity();
        Context context = activity != null ? activity : view.getContext();

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setData(Uri.parse(BaseActivityHook.MORPHE_SETTINGS_INTENT));
            intent.setPackage(context.getPackageName());
            intent.setClass(context, GoogleApiActivity.class);

            if (activity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            context.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to open Morphe settings", e);
        }
    }
}
