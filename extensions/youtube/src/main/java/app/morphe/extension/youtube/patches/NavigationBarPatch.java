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
import android.graphics.Paint;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Accessibility;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.AccessibilityData;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.BrowseEndpoint;
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
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.NavigationType;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemBytes;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRendererBytes;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.RendererAccessibilityData;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Runs;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Title;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.patches.spoof.SpoofOSNamePatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;

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

    /**
     * The flags of the translucent navigation bar are also overridden without this patch,
     * see {@link #isNavigationBarBehindSystemButtons(boolean)}. They are then left to the account.
     * <p>
     * The glass navigation bar floats over the pages, so with the default style it uses the translucent
     * style, which lays the pages out behind the navigation bar. Otherwise, for an account without the
     * translucent navigation bar, the pages end above the navigation bar, and the room around the capsule
     * and behind the system navigation buttons is an empty band.
     */
    private static final NavigationBarStyle NAVIGATION_BAR_STYLE = !isPatchIncluded()
            ? NavigationBarStyle.DEFAULT
            : Settings.NAVIGATION_BAR_STYLE.get() == NavigationBarStyle.DEFAULT
                    && Settings.NAVIGATION_BAR_GLASS.get() && !Settings.HIDE_NAVIGATION_BAR.get()
                    ? NavigationBarStyle.TRANSLUCENT
                    : Settings.NAVIGATION_BAR_STYLE.get();

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
        GlassNavigationBar.navigationTabCreated(button, tabView);

        if (button == NavigationBar.NavigationButton.NOTIFICATIONS) {
            notificationsTabCreated = true;
        }

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
     * <p>
     * The glass navigation bar sets the width of the buttons itself, from their content and spacing.
     */
    public static boolean enableNarrowNavigationButton(boolean original) {
        return (NARROW_NAVIGATION_BUTTONS && !GlassNavigationBar.isEnabled()) || original;
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
     * Value of the navigation bar covering the system navigation buttons flag first read by the app,
     * or null if not read yet.
     */
    @Nullable
    private static volatile Boolean accountCoversSystemButtons;

    /**
     * Value of the feed behind the navigation bar flag first read by the app, or null if not read yet.
     */
    @Nullable
    private static volatile Boolean accountFeedBehindNavigationBar;

    /**
     * The translucent navigation bar comes from feature flags that not every account gets, so they
     * are turned on to make the bar look the same on every account. With the navigation bar covering
     * the system navigation buttons and the feed laid out behind it, the navigation bar and the pages
     * make room for the system navigation buttons, which is only right when the window is drawn behind
     * them. That comes from the edge to edge top bar feature flag, so without it the room is added twice:
     * the navigation bar is raised and an empty bar is left at the bottom
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/3825">...</a>), or the pages and
     * the Shorts player end the height of the navigation bar above it
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/4159">...</a>,
     * <a href="https://github.com/MorpheApp/morphe-patches/issues/4173">...</a>).
     * <p>
     * The app draws the window behind the system navigation buttons only on Android 12 and later,
     * so on older versions the flags are always turned off, or the room is added twice
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/3864">...</a>).
     * <p>
     * Android 15 and later always draw the window of the app behind the system navigation buttons,
     * as the app targets Android 15 or later, so there the flags do not need the edge to edge top bar.
     * The Hide status bar patch turns that flag off, and the pages then ended above the navigation bar,
     * with an empty band behind the translucent navigation bar and the system navigation buttons.
     * <p>
     * With {@link NavigationBarStyle#DEFAULT} the flags are left to the account, but they are still
     * turned off if the window is not drawn behind the system navigation buttons.
     *
     * @param original The value the account first got, so all the classes that read the flag
     *                 use the same value even if the account value changes while the app starts,
     *                 the same as the edge to edge top bar flag.
     */
    private static boolean isNavigationBarBehindSystemButtons(boolean original) {
        try {
            // Some devices keep the window above the system navigation buttons also with the edge
            // to edge layout, as found at a previous app start by the Hide status bar patch.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || HideStatusBarPatch.isWindowAboveNavigationBar()) {
                return false;
            }

            final boolean windowBehindSystemButtons;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                windowBehindSystemButtons = true;
            } else {
                Boolean edgeToEdge = HideStatusBarPatch.isEdgeToEdgeTopBar();
                // Null if not known yet, or the Hide status bar patch is not included.
                // The edge to edge layout is then the one of the account, which the flags of the account follow.
                if (edgeToEdge == null) {
                    return original;
                }
                windowBehindSystemButtons = edgeToEdge;
            }

            return windowBehindSystemButtons && (original || NAVIGATION_BAR_STYLE != NavigationBarStyle.DEFAULT);
        } catch (Exception ex) {
            Logger.printException(() -> "isNavigationBarBehindSystemButtons failure", ex);
            return original;
        }
    }

    /**
     * Injection point.
     * <p>
     * The navigation bar covers the system navigation buttons, see
     * {@link #isNavigationBarBehindSystemButtons(boolean)}.
     */
    public static boolean useTranslucentNavigationBar(boolean original) {
        Boolean accountValue = accountCoversSystemButtons;
        final boolean firstRead = accountValue == null;
        if (firstRead) {
            accountValue = original;
            accountCoversSystemButtons = accountValue;
        }

        final boolean translucent = isNavigationBarBehindSystemButtons(accountValue);
        if (firstRead) {
            Logger.printDebug(() -> "Navigation bar covers system buttons: " + translucent + " (account: " + original
                    + ", edge to edge: " + HideStatusBarPatch.isEdgeToEdgeTopBar()
                    + ", window above navigation bar: " + HideStatusBarPatch.isWindowAboveNavigationBar()
                    + ", style: " + NAVIGATION_BAR_STYLE + ")");
        }
        translucentNavigationBarForced = NAVIGATION_BAR_STYLE != NavigationBarStyle.DEFAULT && translucent;
        navigationBarCoversSystemButtons = translucent;
        return translucent;
    }

    /**
     * If the app was last told the navigation bar covers the system navigation buttons,
     * with any navigation bar style.
     */
    private static volatile boolean navigationBarCoversSystemButtons;

    static boolean isNavigationBarCoveringSystemButtons() {
        return navigationBarCoversSystemButtons;
    }

    /**
     * Injection point.
     * <p>
     * The feed is laid out behind the navigation bar. It follows the navigation bar covering
     * the system navigation buttons: some pages add the navigation bar height below their content
     * when the navigation bar covers the system navigation buttons, as they are expected to be
     * laid out behind it, and the list padding for the navigation bar is only set with the edge
     * to edge layout. See {@link #isNavigationBarBehindSystemButtons(boolean)}.
     */
    public static boolean useFeedBehindNavigationBar(boolean original) {
        Boolean accountValue = accountFeedBehindNavigationBar;
        final boolean firstRead = accountValue == null;
        if (firstRead) {
            accountValue = original;
            accountFeedBehindNavigationBar = accountValue;
        }

        final boolean feedBehind = isNavigationBarBehindSystemButtons(accountValue);
        if (firstRead) {
            Logger.printDebug(() -> "Feed behind navigation bar: " + feedBehind + " (account: " + original + ")");
        }
        return feedBehind;
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
     * empty band is left between the two bars (<a href="https://github.com/MorpheApp/morphe-patches/issues/2300">...</a>).
     */
    public static boolean useFixedBottomBarRoom(boolean original) {
        return original && !translucentNavigationBarForced;
    }

    /**
     * Injection point.
     * <p>
     * The blurred, translucent background of the navigation bar only changes how the bar looks,
     * not the room left for the system navigation buttons, so it is turned on from Android 12 also
     * when the window is not drawn edge to edge. With {@link NavigationBarStyle#OPAQUE}, it follows
     * {@link #isNavigationBarBehindSystemButtons(boolean)} instead. With {@link NavigationBarStyle#DEFAULT}
     * it is left to the account.
     */
    public static boolean useTranslucentNavigationBarBackground(boolean original) {
        if (NAVIGATION_BAR_STYLE == NavigationBarStyle.DEFAULT) {
            return original;
        }

        final boolean translucent;
        if (NAVIGATION_BAR_STYLE != NavigationBarStyle.OPAQUE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            translucent = true;
        } else {
            translucent = isNavigationBarBehindSystemButtons(original);
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
     * (<a href="https://github.com/MorpheApp/morphe-patches/pull/3684#issuecomment-6322984709">...</a>).
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

    public static int getKeyboardMargin(int original) {
        return FixPagePaddingPatch.getKeyboardMargin(original);
    }

    static boolean isKeyboardMarginRemoved() {
        return FixPagePaddingPatch.isKeyboardMarginRemoved();
    }
    /**
     * Injection point.
     * <p>
     * When the navigation bar covers the system navigation buttons, the bottom window inset is
     * added to its height and bottom padding. With the keyboard open, that inset also holds
     * the keyboard, so the bar grows as tall as the keyboard and covers the page
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/3909">...</a>). The page above then ends
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
            padding = GlassNavigationBar.getFloatingRoom(navigationBar, padding);
            // The value is also stored as the room the bar adds to its height.
            GlassNavigationBar.setAppRoom(padding);
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

    static final int PIVOT_BAR = ResourceUtils.getIdentifier(
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

        /**
         * @return The room the navigation bar leaves below itself for the system navigation buttons,
         *         which is added to its height.
         */
        int patch_getSystemButtonsRoom();
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
                            final int navigationBarRoom = GlassNavigationBar.getFloatingRoom(
                                    navigationBar, setSlimStatusBarRoom(navigationBar, room));
                            access.patch_setSystemButtonsRoom(navigationBarRoom);
                            GlassNavigationBar.setAppRoom(navigationBarRoom);
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
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/3908">...</a>). The Shorts player then makes
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
     * <p>
     * Called whenever the navigation bar is measured.
     */
    public static int getNavigationBarHeightMeasureSpec(View navigationBar, int heightMeasureSpec) {
        return GlassNavigationBar.getHeightMeasureSpec(navigationBar, heightMeasureSpec);
    }

    /**
     * Injection point.
     * <p>
     * Called whenever the vertical translation of the navigation bar is set.
     */
    public static float getNavigationBarTranslationY(View navigationBar, float translationY) {
        return GlassNavigationBar.getTranslationY(navigationBar, translationY);
    }

    /**
     * Injection point.
     * <p>
     * Called whenever the padding of the navigation bar is set.
     *
     * @param bottom The bottom padding, the room below the buttons.
     */
    public static int getNavigationBarPadding(View navigationBar, int bottom) {
        return GlassNavigationBar.getFloatingRoom(navigationBar, bottom);
    }

    /**
     * Injection point.
     * <p>
     * Called when the app makes the tint paint drawn over the blurred background of the navigation bar.
     */
    public static void setNavigationBarBlurTint(Paint tint) {
        GlassNavigationBar.lightenBlurTint(tint);
    }

    /**
     * Injection point.
     *
     * @param bottomBarContainer The container of the navigation bar.
     */
    public static void setNavigationBarGlass(View bottomBarContainer) {
        if (!GlassNavigationBar.isEnabled()) return;

        try {
            GlassNavigationBar.apply(bottomBarContainer);
        } catch (Exception ex) {
            Logger.printException(() -> "setNavigationBarGlass failure", ex);
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

    private static volatile WeakReference<TextView> searchQueryRef = new WeakReference<>(null);

    public static final String SEARCH_PIVOT_IDENTIFIER = "morphe_search";
    private static Object pivotBarRenderer = null;
    private static View.OnClickListener openSearchBar = null;

    private static final boolean SHOW_SETTINGS_BUTTON = Settings.SHOW_SETTINGS_BUTTON.get();
    private static final boolean SHOW_SETTINGS_BUTTON_TYPE = Settings.SHOW_SETTINGS_BUTTON_TYPE.get();

    public static final String SETTINGS_PIVOT_IDENTIFIER = "morphe_settings";
    private static Object pivotBarSettingsRenderer = null;

    private static final boolean SHOW_WATCH_LATER_BUTTON = Settings.SHOW_WATCH_LATER_BUTTON.get();
    public static final String WATCH_LATER_PIVOT_IDENTIFIER = "morphe_watch_later";
    private static final String WATCH_LATER_BROWSE_ID = "VLWL";

    @Nullable
    private static Object pivotBarWatchLaterRenderer = null;

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
     * Order of the navigation buttons, as their pivot identifiers. Empty to keep the order of the server.
     */
    private static final List<String> NAVIGATION_BUTTONS_ORDER = Settings.NAVIGATION_BUTTONS_ORDER.get().isEmpty()
            ? Collections.emptyList()
            : Arrays.asList(Settings.NAVIGATION_BUTTONS_ORDER.get().split(","));

    /**
     * Navigation buttons seen in the navigation bar, as id and label, in the order they were seen.
     * Synchronized on itself.
     */
    public static final Map<String, String> discoveredNavigationButtons =
            loadDiscoveredButtons(Settings.NAVIGATION_BUTTONS_DISCOVERED);

    /**
     * Ids of the navigation buttons of the last navigation bar, in their order.
     */
    public static volatile List<String> navigationButtons = Collections.emptyList();

    private static Map<String, String> loadDiscoveredButtons(StringSetting setting) {
        Map<String, String> buttons = new LinkedHashMap<>();
        if (setting.get().isEmpty()) return buttons;

        try {
            JSONArray array = new JSONArray(setting.get());
            for (int i = 0, length = array.length(); i < length; i++) {
                JSONObject button = array.getJSONObject(i);
                buttons.put(button.getString("id"), button.getString("label"));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "loadDiscoveredButtons failure", ex);
        }
        return buttons;
    }

    /**
     * Saves a button that is seen for the first time, or whose label changed,
     * so the order setting can show it.
     */
    private static void discoverButton(StringSetting setting, Map<String, String> buttons,
                                       String id, @Nullable String label) {
        if (label == null || label.isEmpty()) label = id;

        synchronized (buttons) {
            if (label.equals(buttons.get(id))) return;
            buttons.put(id, label);

            try {
                JSONArray array = new JSONArray();
                for (Map.Entry<String, String> button : buttons.entrySet()) {
                    array.put(new JSONObject().put("id", button.getKey()).put("label", button.getValue()));
                }
                setting.save(array.toString());
            } catch (Exception ex) {
                Logger.printException(() -> "discoverButton failure", ex);
            }
        }
    }

    /**
     * Injection point.
     * Called after {@link #createPivotBarItems(MessageLite)}.
     *
     * @param list Proto list containing PivotBarRenderer.
     */
    public static List<Object> getPivotBarRendererList(List<Object> list) {
        if (list == null || list.isEmpty()) return list;

        List<Object> newList = new ArrayList<>(list);

        // The buttons added by Morphe are before You, unless a navigation buttons order is set.
        int index = ClassicSubscriptionsLayoutPatch.indexOfLibraryButton(newList);
        if (SHOW_WATCH_LATER_BUTTON && pivotBarWatchLaterRenderer != null) {
            newList.add(index++, pivotBarWatchLaterRenderer);
        }
        if (SHOW_SEARCH_BUTTON && pivotBarRenderer != null) {
            newList.add(index++, pivotBarRenderer);
        }
        if (SHOW_SETTINGS_BUTTON && pivotBarSettingsRenderer != null) {
            newList.add(index, pivotBarSettingsRenderer);
        }

        String[] ids = new String[newList.size()];
        for (int i = 0; i < ids.length; i++) {
            var info = ClassicSubscriptionsLayoutPatch.getPivotBarItemInfo(newList.get(i));
            if (info == null || info.id().isEmpty()) continue;

            ids[i] = info.id();
            discoverButton(Settings.NAVIGATION_BUTTONS_DISCOVERED, discoveredNavigationButtons, info.id(), info.label());
        }

        // The buttons in the order setting are sorted in the positions they take in the list.
        // Other buttons, such as the buttons of other patches, stay in their position.
        List<Integer> positions = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null && NAVIGATION_BUTTONS_ORDER.contains(ids[i])) {
                positions.add(i);
            }
        }
        if (!positions.isEmpty()) {
            List<Integer> sortedPositions = new ArrayList<>(positions);
            sortedPositions.sort(Comparator.comparingInt(i -> NAVIGATION_BUTTONS_ORDER.indexOf(ids[i])));

            List<Object> items = new ArrayList<>(newList);
            String[] originalIds = ids.clone();
            for (int i = 0, count = positions.size(); i < count; i++) {
                newList.set(positions.get(i), items.get(sortedPositions.get(i)));
                ids[positions.get(i)] = originalIds[sortedPositions.get(i)];
            }
        }

        List<String> buttons = new ArrayList<>(ids.length);
        for (String id : ids) {
            if (id != null) buttons.add(id);
        }
        navigationButtons = buttons;

        return newList;
    }

    /**
     * Injection point.
     * Creates the Search, Settings and Watch later buttons from the home button.
     * The buttons are created with the factory of the app, so the app reads their own pivot identifier.
     *
     * @param messageLite MessageLite class of PivotBarItemRenderer.
     */
    public static void createPivotBarItems(MessageLite messageLite) {
        if (!SHOW_SEARCH_BUTTON && !SHOW_SETTINGS_BUTTON && !SHOW_WATCH_LATER_BUTTON) return;

        try {
            var renderer = PivotBarItemRenderer.parseFrom(messageLite.toByteArray());
            var iconName = renderer.getIcon().getYtIconType().name();
            if (!NavigationBar.NavigationButton.HOME.ytEnumNames.contains(iconName)) return;

            if (SHOW_SEARCH_BUTTON) {
                var searchRenderer = renderer.toBuilder()
                        .setPivotIdentifier(SEARCH_PIVOT_IDENTIFIER)
                        .setAccessibility(Accessibility.newBuilder()
                                .setAccessibilityData(AccessibilityData.newBuilder().setLabel(str("menu_search"))))
                        .setIcon(Icon.newBuilder().setYtIconType(Utils.appIsUsingBoldIcons()
                                ? YTIconType.SEARCH_BOLD
                                : YTIconType.SEARCH_CAIRO))
                        .build().toByteString();
                pivotBarRenderer = buildPivotBarItem(PivotBarItemBytes.newBuilder()
                        .setPivotBarItemRenderer(searchRenderer)
                        .build().toByteArray());
            }

            if (SHOW_SETTINGS_BUTTON) {
                var settingsRenderer = renderer.toBuilder()
                        .setPivotIdentifier(SETTINGS_PIVOT_IDENTIFIER)
                        .setAccessibility(Accessibility.newBuilder()
                                .setAccessibilityData(AccessibilityData.newBuilder().setLabel(str("menu_settings"))))
                        .setIcon(Icon.newBuilder().setYtIconType(YTIconType.SETTINGS_CAIRO))
                        .build().toByteString();
                pivotBarSettingsRenderer = buildPivotBarItem(PivotBarItemBytes.newBuilder()
                        .setPivotBarItemRenderer(settingsRenderer)
                        .build().toByteArray());
            }

            if (SHOW_WATCH_LATER_BUTTON) {
                String label = str("morphe_watch_later_navigation_label");
                byte[] watchLaterRenderer = renderer.toBuilder()
                        .setPivotIdentifier(WATCH_LATER_PIVOT_IDENTIFIER)
                        .setNavigationEndpoint(NavigationEndpoint.newBuilder()
                                .setBrowseEndpoint(BrowseEndpoint.newBuilder().setBrowseId(WATCH_LATER_BROWSE_ID)))
                        .setAccessibility(Accessibility.newBuilder()
                                .setAccessibilityData(AccessibilityData.newBuilder().setLabel(label)))
                        .setTitle(Title.newBuilder().setRuns(Runs.newBuilder().setText(label)))
                        // The Cairo icon has no drawable in some app versions.
                        .setIcon(Icon.newBuilder().setYtIconType(YTIconType.WATCH_LATER))
                        .clearTargetId()
                        .build().toByteArray();

                // The tracking params and thumbnail belong to the home button.
                var watchLaterRendererBytes = PivotBarItemRendererBytes.parseFrom(watchLaterRenderer).toBuilder()
                        .clearTrackingParams()
                        .clearThumbnail()
                        .setNavigationType(NavigationType.PIVOT_BAR_NAVIGATION_TYPE_SELECT_TAB_VALUE)
                        .build().toByteString();

                pivotBarWatchLaterRenderer = buildPivotBarItem(PivotBarItemBytes.newBuilder()
                        .setPivotBarItemRenderer(watchLaterRendererBytes)
                        .build().toByteArray());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "createPivotBarItems failure", ex);
        }
    }

    /**
     * Method is modified during patching to create the navigation bar item with the factory of the app.
     *
     * @param pivotBarItem Navigation bar item proto.
     */
    @Nullable
    private static Object buildPivotBarItem(byte[] pivotBarItem) {
        return null;
    }

    /**
     * Injection point.
     * The app has no selected icon for the Watch later icon, so the outline icon is shown
     * when the Watch later button is selected.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void setWatchLaterSelectedIcon(EnumMap icons, Enum watchLaterIcon) {
        if (SHOW_WATCH_LATER_BUTTON) {
            icons.put(watchLaterIcon, ResourceUtils.getIdentifier(
                    ResourceType.DRAWABLE, "morphe_watch_later_selected"));
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

    /**
     * Icon of the Settings button of Morphe, which is also its id in the toolbar buttons order.
     */
    public static final String SETTING_BUTTON_ENUM_NAME = "SETTINGS_CAIRO";

    /**
     * The toolbar of some pages, such as the You tab, comes from their own response, which still has
     * the notifications button, so it is replaced with the create button of the toolbar of another page.
     */
    @Nullable
    private static volatile MessageLite toolbarCreateButton;

    /**
     * The swap has effect only if the server added the notifications tab to the navigation bar.
     * Otherwise the toolbar button is the only way to open the notifications.
     */
    private static volatile boolean notificationsTabCreated;

    private static final boolean HIDE_TOOLBAR_CHAT_BUTTON = Settings.HIDE_TOOLBAR_CHAT_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_CREATE_BUTTON = Settings.HIDE_TOOLBAR_CREATE_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_NOTIFICATION_BUTTON = Settings.HIDE_TOOLBAR_NOTIFICATION_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_SEARCH_BUTTON = Settings.HIDE_TOOLBAR_SEARCH_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_MICROPHONE_BUTTON = Settings.HIDE_TOOLBAR_MICROPHONE_BUTTON.get();

    private static final boolean SHOW_TOOLBAR_SETTINGS_BUTTON = Settings.SHOW_TOOLBAR_SETTINGS_BUTTON.get();

    /**
     * Order of the toolbar buttons, as their ids. Empty to keep the order of the server.
     */
    private static final List<String> TOOLBAR_BUTTONS_ORDER = Settings.TOOLBAR_BUTTONS_ORDER.get().isEmpty()
            ? Collections.emptyList()
            : Arrays.asList(Settings.TOOLBAR_BUTTONS_ORDER.get().split(","));

    /**
     * Toolbar buttons seen in the toolbars, as id and label, in the order they were seen.
     * Synchronized on itself.
     */
    public static final Map<String, String> discoveredToolbarButtons =
            loadDiscoveredButtons(Settings.TOOLBAR_BUTTONS_DISCOVERED);

    /**
     * Ids of the buttons of the last toolbar, in their order.
     */
    public static volatile List<String> toolbarButtons = Collections.emptyList();
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
     * Called when a toolbar button is created, on any page, also on the pages whose toolbar comes
     * from their own response, such as the channel pages and the You tab.
     * <p>
     * The toolbar create button of the Android Automotive layout, which swaps the create button with the
     * notifications button, opens a bottom sheet whose video button uses the upload endpoint, which the app
     * handles only in the creation modes. The upload endpoint is opened in the creation modes instead,
     * as the create button of the phone layout does.
     *
     * @param buttonRenderer The renderer of the button.
     * @return The renderer the button is created with.
     */
    public static MessageLite getToolbarButtonRenderer(MessageLite buttonRenderer) {
        if (buttonRenderer == null || (HIDE_TOOLBAR_CREATE_BUTTON && !SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON)) {
            return buttonRenderer;
        }

        try {
            ButtonRenderer renderer = ButtonRenderer.parseFrom(buttonRenderer.toByteArray());
            String iconName = renderer.getIcon().getYtIconType().name();

            if (Utils.equalsAny(iconName, CREATE_BUTTON_ENUMS)) {
                NavigationEndpoint endpoint = renderer.getNavigationEndpoint();
                if (!HIDE_TOOLBAR_CREATE_BUTTON && endpoint.hasCreationEntrySheetEndpoint()) {
                    CreationEntrySheetEndpoint sheetEndpoint = endpoint.getCreationEntrySheetEndpoint();
                    ButtonSheetRenderer sheet = sheetEndpoint.getButtonSheetSupportedRenderers().getButtonSheetRenderer();
                    ButtonSheetRenderer.Builder sheetBuilder = sheet.toBuilder();
                    boolean replaced = false;

                    for (int i = 0, count = sheet.getButtonsCount(); i < count; i++) {
                        Buttons button = sheet.getButtons(i);
                        ButtonRenderer buttonRendererOfSheet = button.getButtonRenderer();
                        NavigationEndpoint uploadEndpoint = buttonRendererOfSheet.getNavigationEndpoint();
                        if (!button.hasButtonRenderer() || !uploadEndpoint.hasShortsCreationEndpoint()) {
                            continue;
                        }

                        // The only mode button of the creation modes opens the upload endpoint.
                        ButtonRenderer modeButton = buttonRendererOfSheet.toBuilder()
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
                        sheetBuilder.setButtons(i, button.toBuilder().setButtonRenderer(
                                buttonRendererOfSheet.toBuilder().setNavigationEndpoint(creationModesEndpoint)));
                        replaced = true;
                    }

                    // Nothing is changed if the create button has no upload button.
                    if (replaced) {
                        CreationEntrySheetEndpoint creationModesSheetEndpoint = sheetEndpoint.toBuilder()
                                .setButtonSheetSupportedRenderers(sheetEndpoint.getButtonSheetSupportedRenderers().toBuilder()
                                        .setButtonSheetRenderer(sheetBuilder))
                                .build();
                        buttonRenderer = parseWithRegistry(buttonRenderer, renderer.toBuilder()
                                        .setNavigationEndpoint(endpoint.toBuilder().setCreationEntrySheetEndpoint(creationModesSheetEndpoint))
                                        .build().toByteArray(),
                                getGeneratedRegistry());
                        Logger.printDebug(() -> "Toolbar create button opens the upload in the creation modes");
                    }
                }

                if (SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON) {
                    toolbarCreateButton = buttonRenderer;
                }
            } else if (SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON && Utils.equalsAny(iconName, NOTIFICATION_BUTTON_ENUMS)
                    && notificationsTabCreated && !HIDE_NAVIGATION_BAR && !Settings.HIDE_NOTIFICATIONS_BUTTON.get()) {
                MessageLite createButton = toolbarCreateButton;
                if (createButton != null) {
                    return createButton;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getToolbarButtonRenderer failure", ex);
        }
        return buttonRenderer;
    }

    /**
     * Injection point.
     * The create button does not show the new content count of the notifications button it replaced.
     *
     * @param countIds The ids of the pages whose new content count the button shows.
     */
    public static List<String> getToolbarButtonCountIds(MessageLite buttonRenderer, List<String> countIds) {
        return buttonRenderer == toolbarCreateButton ? new ArrayList<>() : countIds;
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
     * @return Id of the toolbar button: the page it opens, or its icon.
     */
    private static String getToolbarButtonId(ButtonRenderer buttonRenderer) {
        String iconName = buttonRenderer.getIcon().getYtIconType().name();
        // The Settings button of Morphe is a copy of another button, with its navigation endpoint.
        if (SETTING_BUTTON_ENUM_NAME.equals(iconName)) return iconName;

        String browseId = buttonRenderer.getNavigationEndpoint().getBrowseEndpoint().getBrowseId();
        return browseId.isEmpty() ? iconName : browseId;
    }

    /**
     * Injection point.
     * Called after the hidden buttons are removed and the Settings button is added.
     */
    public static void applyToolbarButtonsOrder(List<MessageLite> rawButtonList) {
        if (rawButtonList == null || rawButtonList.isEmpty()) return;

        try {
            List<String> ids = new ArrayList<>(rawButtonList.size());
            for (MessageLite rawButton : rawButtonList) {
                Buttons buttons = Buttons.parseFrom(rawButton.toByteArray());
                // The chat and notifications buttons can be top bar buttons, which contain their button.
                if (buttons.hasTopbarButtonRenderer()) {
                    buttons = buttons.getTopbarButtonRenderer().getButtons();
                }
                ids.add(buttons.hasButtonRenderer() ? getToolbarButtonId(buttons.getButtonRenderer()) : null);
            }

            // Without an order, the Settings button is the third button, as it was before the order setting.
            // With an order, the toolbar buttons are sorted by getToolbarButtonOrder().
            final int settingsIndex = ids.indexOf(SETTING_BUTTON_ENUM_NAME);
            if (TOOLBAR_BUTTONS_ORDER.isEmpty() && settingsIndex >= 0) {
                final int targetIndex = Math.min(2, ids.size() - 1);
                rawButtonList.add(targetIndex, rawButtonList.remove(settingsIndex));
                ids.add(targetIndex, ids.remove(settingsIndex));
            }

            ids.removeIf(Objects::isNull);
            toolbarButtons = ids;
        } catch (Exception ex) {
            Logger.printException(() -> "applyToolbarButtonsOrder failure", ex);
        }
    }

    /**
     * Injection point.
     * The order of the menu items sets the order of the buttons of every toolbar,
     * including the toolbars that are not created from the toolbar of the guide, such as of the You tab.
     *
     * @param original       Order of the button, from the order the buttons were created in.
     * @param buttonRenderer ButtonRenderer of the toolbar button.
     */
    public static int getToolbarButtonOrder(int original, MessageLite buttonRenderer) {
        try {
            ButtonRenderer renderer = ButtonRenderer.parseFrom(buttonRenderer.toByteArray());
            String id = getToolbarButtonId(renderer);
            discoverButton(Settings.TOOLBAR_BUTTONS_DISCOVERED, discoveredToolbarButtons,
                    id, renderer.getButtonRendererAccessibilityData().getLabel());

            final int rank = TOOLBAR_BUTTONS_ORDER.indexOf(id);
            if (rank >= 0) {
                // The original order is at least 1000, so the buttons in the order setting
                // are before the other buttons.
                return 1000 - TOOLBAR_BUTTONS_ORDER.size() + rank;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getToolbarButtonOrder failure", ex);
        }
        return original;
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
