/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4338
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;
import app.morphe.extension.youtube.patches.NavigationBarPatch.NavigationBarStyle;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Draws the bottom navigation bar as a floating, rounded, translucent capsule.
 * <p>
 * No extra copy of the content behind the bar is made. If the app draws its own blurred background
 * (the translucent navigation bar style, Android 12 and later, blurred only from Android 14), that
 * background is kept and clipped to the capsule. The frosted look otherwise comes from a tinted fill,
 * a soft gradient, a gradient edge and a shadow, all drawn by one drawable that is set as the
 * background of the navigation bar.
 * The bar keeps its height and vertical layout, and everything outside the capsule is left transparent.
 */
@SuppressWarnings("unused")
public final class YouTubeGlassNavigationBar {

    private static final int PIVOT_BAR = NavigationBarPatch.PIVOT_BAR;

    /**
     * The opaque style wins if both are turned on.
     */
    private static final boolean ENABLED = Settings.NAVIGATION_BAR_GLASS.get()
            && Settings.NAVIGATION_BAR_STYLE.get() != NavigationBarStyle.OPAQUE
            && !Settings.HIDE_NAVIGATION_BAR.get()
            && PIVOT_BAR != 0;

    /**
     * The views of the navigation buttons, by button.
     */
    private static final Map<NavigationButton, WeakReference<View>> tabViews =
            new EnumMap<>(NavigationButton.class);

    @Nullable
    private static volatile NavigationButton selectedButton;

    /**
     * The drawable of the navigation bar currently on screen.
     */
    private static WeakReference<GlassCapsuleDrawable> currentDrawable = new WeakReference<>(null);

    /**
     * The navigation bar the glass was last applied to.
     */
    private static WeakReference<View> currentPivotBar = new WeakReference<>(null);

    static {
        if (ENABLED) {
            NavigationBar.addOnNavigationButtonChangedListener(button -> {
                selectedButton = button;
                Logger.printDebug(() -> "Glass selected tab: " + button);
                Utils.runOnMainThread(() -> {
                    GlassCapsuleDrawable drawable = currentDrawable.get();
                    if (drawable != null) {
                        drawable.onSelectedTabChanged();
                    }
                });
            });
        }
    }

    static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * The least room between the bottom of the capsule and the bottom of the bar,
     * so the capsule floats above the bottom of the screen.
     */
    private static final int FLOATING_ROOM = Dim.dp10;

    /**
     * The least room between the ends of the capsule and the edges of the screen.
     * Also used by the preview in the settings.
     */
    public static final int MARGIN_HORIZONTAL = Dim.dp12;

    /**
     * The corner radius of the capsule, in parts of its height: less round by default, with corners like the
     * glass miniplayer, or with round ends. Also used by the preview in the settings.
     */
    public static float getCornerRadiusFraction() {
        return Settings.NAVIGATION_BAR_GLASS_ROUND.get() ? 0.5f : 0.3f;
    }

    private static final float CORNER_RADIUS_FRACTION = getCornerRadiusFraction();

    /**
     * The most alpha of the tint the app draws over its blurred background.
     */
    private static final float LIGHT_BLUR_TINT_ALPHA = 0.50f;
    private static final float DARK_BLUR_TINT_ALPHA = 0.40f;

    /**
     * How opaque the glass is, from the opacity slider. Every alpha is scaled by it: 50 keeps the
     * default alphas, 0 is fully clear and 100 doubles them. The slider is at 20 by default, a clear glass.
     */
    private static final float OPACITY_SCALE = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_OPACITY) / 50f;

    /**
     * Height of the capsule, in percent of the height the app gives the bar
     * or of the height its buttons need, if they need more.
     */
    private static final int HEIGHT_PERCENT = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_HEIGHT);

    /**
     * The most width of the capsule, in percent of the width of the bar.
     */
    private static final int WIDTH_PERCENT = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_WIDTH);

    /**
     * Room between the content (icon and label) of two buttons.
     */
    private static final int BUTTON_SPACING = Dim.dp(SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING));

    /**
     * Views of a button that hold its content, the icon and the label. Other views of the button,
     * such as the badge of the new content count, are left out. Also used by the preview in the settings.
     */
    public static final int BUTTON_ICON = ResourceUtils.getIdentifier(ResourceType.ID, "thumbnail_layout");
    public static final int BUTTON_LABEL = ResourceUtils.getIdentifier(ResourceType.ID, "text");

    /**
     * Buttons hidden because they do not fit in the width of the bar, shown again when they fit,
     * such as after a rotation.
     */
    private static final Set<View> hiddenButtons = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * How much taller the bar is than the height the app gives it, to hold the capsule.
     * Less than zero if the capsule is lower.
     */
    private static volatile int heightDelta;

    private static boolean floatingRoomLogged;

    /**
     * The room below the buttons the app last added to the height of the bar itself,
     * the room for the system navigation buttons.
     */
    private static volatile int appRoom;

    /**
     * The full height the app gives the bar, without the room below the buttons.
     */
    private static volatile int baseHeight;

    static void setAppRoom(int room) {
        appRoom = room;
    }

    /**
     * The room below the buttons the app was seen adding to the height of the bar,
     * and the bottom padding it was seen with.
     */
    private static volatile int seenAppRoom;
    private static volatile int seenAppRoomPadding;

    /**
     * How far the room the app adds to the height may differ from the bottom padding.
     */
    private static final int SEEN_ROOM_TOLERANCE = Dim.dp2;

    /**
     * Some configurations, such as with the layout updates of the app disabled, add the room
     * for the system navigation buttons to the height of the bar another way, so it is not known
     * as the room the app added, and it would be added twice. The bar is then given its full
     * height plus exactly its bottom padding, which is remembered for as long as the padding
     * stays the same, also while the app animates the height to hide the bar.
     */
    private static void updateSeenAppRoom(View navigationBar, int height) {
        final int padding = navigationBar.getPaddingBottom();
        if (padding != seenAppRoomPadding) {
            seenAppRoom = 0;
            seenAppRoomPadding = padding;
        }

        // The app can round the room it adds differently from the padding, such as 109 for a padding of 110.
        final int base = baseHeight;
        final int room = height - base;
        if (base > 0 && padding > 0 && Math.abs(room - padding) <= SEEN_ROOM_TOLERANCE && seenAppRoom != room) {
            seenAppRoom = room;
            Logger.printDebug(() -> "Glass navigation bar room added by the app: " + room + " padding: " + padding);
        }
    }

    /**
     * @return The room below the buttons the app added to the height of the bar itself.
     */
    private static int getAppRoom(View navigationBar) {
        final int padding = navigationBar.getPaddingBottom();
        final int seenRoom = padding == seenAppRoomPadding ? seenAppRoom : 0;
        // The stored room can still be the room of a previous bar, such as after switching between
        // gesture and 3-button navigation, and the app never adds more room than the padding.
        return Math.max(0, Math.min(Math.max(padding, seenRoom), Math.max(appRoom, seenRoom)));
    }

    /**
     * Injection point helper.
     * <p>
     * The room below the buttons that the app did not add to the height of the bar, such as the
     * floating room without system navigation buttons, is added when the bar is measured.
     * The app also animates the height of the bar to hide it, so the room is added in proportion,
     * and the animation is left as it is.
     */
    static int getHeightMeasureSpec(View navigationBar, int heightMeasureSpec) {
        if (!ENABLED) return heightMeasureSpec;

        try {
            if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.EXACTLY) {
                return heightMeasureSpec;
            }

            final int height = View.MeasureSpec.getSize(heightMeasureSpec);
            updateSeenAppRoom(navigationBar, height);
            final int added = getAddedRoom(navigationBar, height);
            if (added == 0) return heightMeasureSpec;

            return View.MeasureSpec.makeMeasureSpec(Math.max(0, height + added), View.MeasureSpec.EXACTLY);
        } catch (Exception ex) {
            Logger.printException(() -> "getHeightMeasureSpec failure", ex);
            return heightMeasureSpec;
        }
    }

    /**
     * Injection point helper.
     * <p>
     * The app moves the bar out of the screen by the height it gives the bar: by translation while
     * the player is dragged, and by a negative bottom margin when the bar is hidden on scroll.
     * The room added to the height is not part of it, so the top of the capsule would stay on screen.
     * The bar is moved down by the same part of that room, and is not moved while it is shown.
     */
    static float getTranslationY(View navigationBar, float translationY) {
        if (!ENABLED) return translationY;

        try {
            ViewGroup.LayoutParams params = navigationBar.getLayoutParams();
            if (params == null || params.height <= 0) return translationY;

            final int added = getAddedRoom(navigationBar, params.height);
            if (added == 0) return translationY;

            final int marginOffset = params instanceof ViewGroup.MarginLayoutParams margins
                    ? Math.max(0, -margins.bottomMargin)
                    : 0;
            final float hidden = Math.min(1, Math.max(0, translationY + marginOffset) / params.height);
            return translationY + added * hidden;
        } catch (Exception ex) {
            Logger.printException(() -> "getTranslationY failure", ex);
            return translationY;
        }
    }

    /**
     * @param height The height the app gives the bar.
     * @return The room below the buttons that the app did not add to the height of the bar, and the
     *         height the capsule adds to the bar (less than zero if the capsule is lower), in proportion
     *         to that height, as the app also animates the height of the bar to hide it.
     */
    private static int getAddedRoom(View navigationBar, int height) {
        final int base = baseHeight;
        final int extra = navigationBar.getPaddingBottom() - getAppRoom(navigationBar) + heightDelta;
        if (base <= 0 || extra == 0 || height <= 0) return 0;

        // A navigation rail along the side.
        if (navigationBar.getWidth() > 0 && navigationBar.getWidth() <= 2 * Math.min(height, base)) {
            return 0;
        }

        return height >= base ? extra : Math.round(extra * (float) height / base);
    }

    /**
     * Called from {@link NativeSnackbarPatch}. The app lays the snackbars out above the height it
     * gives the navigation bar, so they would overlap the room added to it.
     *
     * @return The room added to the height of the navigation bar on screen.
     */
    static int getAddedRoomOnScreen() {
        if (!ENABLED) return 0;

        View pivotBar = currentPivotBar.get();
        if (pivotBar == null || !pivotBar.isShown()) return 0;

        ViewGroup.LayoutParams params = pivotBar.getLayoutParams();
        return params != null && params.height > 0 ? getAddedRoom(pivotBar, params.height) : 0;
    }

    /**
     * Injection point helper.
     * <p>
     * The room below the buttons is the room the app leaves for the system navigation buttons.
     * Without them, such as with the gesture handle hidden, the capsule would touch the bottom
     * of the screen, so some room is added, the same way the app adds the room for the system
     * navigation buttons to the height of the bar. Pages laid out behind the bar follow its height.
     *
     * @param room The room the app leaves below the buttons.
     * @return The room below the buttons.
     */
    static int getFloatingRoom(View navigationBar, int room) {
        if (!ENABLED || room >= FLOATING_ROOM) return room;

        try {
            final int width = navigationBar.getWidth();
            if (width > 0 && !GlassNavigationBarLayout.isHorizontalBottomBar(navigationBar)) return room;

            if (!floatingRoomLogged) {
                floatingRoomLogged = true;
                Logger.printDebug(() -> "Glass navigation bar floating room: " + room + " -> " + FLOATING_ROOM);
            }
            return FLOATING_ROOM;
        } catch (Exception ex) {
            Logger.printException(() -> "getFloatingRoom failure", ex);
            return room;
        }
    }

    /**
     * Injection point helper.
     * <p>
     * The tint the app draws over its blurred background makes the capsule dark, so its alpha
     * is set from the opacity slider, and by default more of the blurred content shows.
     */
    static void lightenBlurTint(Paint tint) {
        if (!ENABLED) return;

        final int alpha = tint.getAlpha();
        // Only the translucent tint, not a solid bar color.
        if (alpha <= 0 || alpha >= 255) return;

        final int newAlpha = Math.round(255 * GlassCapsuleDrawable.scaledAlpha(
                Utils.isDarkModeEnabled() ? DARK_BLUR_TINT_ALPHA : LIGHT_BLUR_TINT_ALPHA, OPACITY_SCALE));
        if (alpha != newAlpha) {
            Logger.printDebug(() -> "Glass navigation bar blur tint alpha: " + alpha + " -> " + newAlpha);
            tint.setAlpha(newAlpha);
        }
    }

    private static final int[] capsuleLocation = new int[2];

    /**
     * Called from {@link MinimalMiniplayerPatch}, which keeps the minimal miniplayer as wide as the capsule
     * in landscape.
     *
     * @param out Set to the left and the right of the capsule in the window.
     * @return If the navigation bar is drawn as a capsule. It still is while the app slides the bar away.
     */
    static boolean getCapsuleSpanInWindow(int[] out) {
        if (!ENABLED) return false;

        View pivotBar = currentPivotBar.get();
        if (pivotBar == null || !pivotBar.isAttachedToWindow() || pivotBar.getWidth() <= 0
                || pivotBar.getVisibility() != View.VISIBLE
                || !(pivotBar.getBackground() instanceof GlassCapsuleDrawable)) {
            return false;
        }

        // The same edges as the capsule host.
        float left = pivotBar.getPaddingLeft();
        float right = pivotBar.getWidth() - pivotBar.getPaddingRight();
        if (pivotBar instanceof ViewGroup bar && bar.getChildCount() > 0
                && bar.getChildAt(0) instanceof ViewGroup tabs && tabs.getWidth() > 0) {
            left = Math.max(left, tabs.getLeft() + tabs.getTranslationX());
            right = Math.min(right, tabs.getRight() + tabs.getTranslationX());
        }
        if (right <= left) return false;

        pivotBar.getLocationInWindow(capsuleLocation);
        out[0] = capsuleLocation[0] + Math.round(left);
        out[1] = capsuleLocation[0] + Math.round(right);
        return true;
    }

    public static final class GlassAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.NAVIGATION_BAR_STYLE.get() != NavigationBarStyle.OPAQUE;
        }
    }

    static void navigationTabCreated(NavigationButton button, View tabView) {
        if (!ENABLED) return;

        synchronized (tabViews) {
            tabViews.put(button, new WeakReference<>(tabView));
        }
    }

    @Nullable
    private static View getSelectedTabView() {
        NavigationButton button = selectedButton;
        if (button == null) return null;

        WeakReference<View> reference;
        synchronized (tabViews) {
            reference = tabViews.get(button);
        }
        return reference == null ? null : reference.get();
    }

    static void apply(View bottomBarContainer) {
        View pivotBar = bottomBarContainer.findViewById(PIVOT_BAR);
        if (pivotBar == null || pivotBar == currentPivotBar.get()) return;
        currentPivotBar = new WeakReference<>(pivotBar);

        CapsuleHost capsuleHost = new CapsuleHost(pivotBar);
        GlassCapsuleDrawable glass = new GlassCapsuleDrawable(pivotBar, capsuleHost, OPACITY_SCALE);
        currentDrawable = new WeakReference<>(glass);

        // The height the app gives the bar, without the room below the buttons.
        ViewGroup.LayoutParams params = pivotBar.getLayoutParams();
        // The room of this bar, not the stored room, which can be the room of a previous bar,
        // such as after switching between gesture and 3-button navigation.
        final int barRoom = pivotBar instanceof NavigationBarPatch.PivotBarAccess access
                ? Math.max(0, access.patch_getSystemButtonsRoom())
                : appRoom;
        baseHeight = params != null && params.height > 0 ? params.height - barRoom : 0;
        Logger.printDebug(() -> "Glass navigation bar base height: " + baseHeight + " app room: " + barRoom);

        // The background the app gave the navigation bar, which is put back if the bar
        // is not a horizontal bar at the bottom, such as a navigation rail on large screens.
        Drawable[] appBackground = {pivotBar.getBackground()};
        boolean[] logged = {false, false};
        int[] skippedFrames = {0};
        // The left, top and right padding the app gave the navigation bar.
        int[] appPadding = {pivotBar.getPaddingLeft(), pivotBar.getPaddingTop(), pivotBar.getPaddingRight()};

        // The app hands the navigation bar its own background again, such as on a configuration
        // change or in the Shorts player, so the glass is put back whenever that happens.
        pivotBar.getViewTreeObserver().addOnPreDrawListener(() -> {
            boolean layoutChanged = false;
            try {
                Drawable background = pivotBar.getBackground();
                if (GlassNavigationBarLayout.isHorizontalBottomBar(pivotBar)) {
                    if (background != glass) {
                        appBackground[0] = background;
                        pivotBar.setBackground(glass);
                        // Set after the bar let go of its old background.
                        glass.setAppBackground(background);
                        if (!logged[0]) {
                            logged[0] = true;
                            Logger.printDebug(() -> {
                                int[] location = new int[2];
                                pivotBar.getLocationInWindow(location);
                                View parent = (View) pivotBar.getParent();
                                return "Glass navigation bar applied, height: " + pivotBar.getHeight()
                                        + " bottom padding: " + pivotBar.getPaddingBottom()
                                        + " room below: " + (pivotBar.getRootView().getHeight() - location[1] - pivotBar.getHeight())
                                        + " container height: " + parent.getHeight()
                                        + " container bottom padding: " + parent.getPaddingBottom()
                                        + " app background: " + (background == null ? null : background.getClass().getName());
                            });
                        }
                    }
                    glass.updateStyle();

                    // Without system navigation buttons, the app never sets the room below the buttons,
                    // so it is set here, for the capsule to float above the bottom of the screen.
                    // The padding is kept by the patched setPadding of the bar. If it is still reset,
                    // it is put back right before the frame is drawn, so no frame is drawn with the
                    // buttons moved down.
                    // The room is added to the height of the bar when it is measured.
                    final int room = pivotBar.getPaddingBottom();
                    if (room < FLOATING_ROOM) {
                        Logger.printDebug(() -> "Glass navigation bar floating room set: " + room + " -> " + FLOATING_ROOM);
                        pivotBar.setPadding(pivotBar.getPaddingLeft(), pivotBar.getPaddingTop(),
                                pivotBar.getPaddingRight(), FLOATING_ROOM);
                        layoutChanged = true;
                    }

                    // The capsule is as wide as the buttons, so the indicator of the first and the last button
                    // has the same room from the ends of the capsule as from its top and bottom.
                    // The size of the buttons comes from their content, the icon and the label, so it follows
                    // the density, the font size and the language, and the buttons that do not fit in the width
                    // of the bar are hidden.
                    if (pivotBar instanceof ViewGroup bar && bar.getChildCount() > 0
                            && bar.getChildAt(0) instanceof ViewGroup tabs) {
                        GlassNavigationBarLayout.Buttons buttons = GlassNavigationBarLayout.measureButtons(
                                tabs, BUTTON_ICON, BUTTON_LABEL, hiddenButtons);
                        final int contentWidth = buttons.contentWidth;
                        final int contentTop = buttons.contentTop;

                        if (buttons.isValid()) {
                            final int[] layout = GlassNavigationBarLayout.getButtonsLayout(pivotBar.getWidth(),
                                    buttons.views.size(), contentWidth, buttons.minWidth, WIDTH_PERCENT, BUTTON_SPACING,
                                    MARGIN_HORIZONTAL);
                            final int shown = layout[0];

                            // The buttons that do not fit are hidden from the end, but never the selected one.
                            if (GlassNavigationBarLayout.fitButtons(buttons.views, shown,
                                    getSelectedTabView(), hiddenButtons)) {
                                layoutChanged = true;
                            }
                            if (buttons.views.size() > shown && !logged[1]) {
                                logged[1] = true;
                                Logger.printDebug(() -> "Glass navigation bar buttons shown: " + shown
                                        + " of " + buttons.views.size());
                            }

                            // The content is in the middle of the capsule. The bar is laid out taller or lower,
                            // so the pages laid out behind it follow the height of the capsule.
                            final int contentHeight = buttons.contentHeight();
                            final int capsuleHeight = GlassNavigationBarLayout.getCapsuleHeight(
                                    baseHeight, contentHeight, HEIGHT_PERCENT);
                            final int shift = (capsuleHeight - contentHeight) / 2 - contentTop;
                            final int paddingTop = Math.max(0, shift);
                            capsuleHost.capsuleTop = Math.max(0, -shift);
                            final int delta = baseHeight > 0 ? capsuleHeight + capsuleHost.capsuleTop - baseHeight : 0;
                            if (delta != heightDelta) {
                                heightDelta = delta;
                                pivotBar.requestLayout();
                                layoutChanged = true;
                            }

                            final int horizontalPadding = Math.max(MARGIN_HORIZONTAL,
                                    (pivotBar.getWidth() - shown * layout[1]) / 2);
                            if (pivotBar.getPaddingLeft() != horizontalPadding || pivotBar.getPaddingRight() != horizontalPadding
                                    || pivotBar.getPaddingTop() != paddingTop) {
                                if (pivotBar.getPaddingLeft() != horizontalPadding) appPadding[0] = pivotBar.getPaddingLeft();
                                if (pivotBar.getPaddingTop() != paddingTop) appPadding[1] = pivotBar.getPaddingTop();
                                if (pivotBar.getPaddingRight() != horizontalPadding) appPadding[2] = pivotBar.getPaddingRight();
                                pivotBar.setPadding(horizontalPadding, paddingTop,
                                        horizontalPadding, pivotBar.getPaddingBottom());
                                layoutChanged = true;
                            }

                            GlassNavigationBarLayout.lastContentWidth = contentWidth;
                            GlassNavigationBarLayout.lastMinButtonWidth = buttons.minWidth;
                            GlassNavigationBarLayout.lastContentHeight = contentHeight;
                            GlassNavigationBarLayout.lastBaseHeight = baseHeight;
                            GlassNavigationBarLayout.setLastButtons(buttons.views);
                        }
                    }
                } else if (background == glass) {
                    glass.setAppBackground(null);
                    pivotBar.setBackground(appBackground[0]);
                    pivotBar.setPadding(appPadding[0], appPadding[1],
                            appPadding[2], pivotBar.getPaddingBottom());
                    GlassNavigationBarLayout.showHiddenButtons(hiddenButtons);
                    heightDelta = 0;
                    capsuleHost.capsuleTop = 0;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Glass navigation bar pre draw failure", ex);
            }

            // The frame is drawn again after the new layout, instead of once with the old one.
            // Only a few frames in a row are skipped, so the bar can never stop drawing.
            if (layoutChanged && skippedFrames[0] < 2) {
                skippedFrames[0]++;
                return false;
            }
            skippedFrames[0] = 0;
            return true;
        });
    }

    /**
     * Where the capsule is drawn in the navigation bar.
     */
    private static final class CapsuleHost implements GlassCapsuleDrawable.Host {
        /**
         * How far the capsule reaches into the room below the buttons.
         */
        private static final int MAX_MARGIN_BOTTOM = Dim.dp4;

        private final View host;

        /**
         * The room between the top of the bar and the top of the capsule, if the capsule is lower than the
         * content of the buttons laid out by the app would need, as the content is not moved above the bar.
         */
        int capsuleTop;

        CapsuleHost(View host) {
            this.host = host;
        }

        @Override
        public void getCapsule(Rect bounds, RectF capsule) {
            // The bottom padding of the navigation bar is the room for the system navigation
            // buttons or gesture handle. The capsule holds the whole height of the buttons
            // and reaches only slightly into that room, so it floats above the gesture handle.
            final int bottomRoom = host.getPaddingBottom();
            float bottom = bounds.bottom - bottomRoom
                    + Math.max(0, Math.min(bottomRoom - FLOATING_ROOM, MAX_MARGIN_BOTTOM));
            // Some accounts lay the slim status bar ("No connection", "You're incognito") out over the room
            // below the buttons, and it is drawn over the bar. The capsule then does not reach into the room
            // the slim status bar covers, or its edge is cut. The bar's own translation is left out,
            // so the capsule keeps its size while the bar is moved behind the slim status bar to hide it.
            View slimStatusBar = host.getParent() instanceof ViewGroup container
                    ? container.findViewById(NavigationBarPatch.SLIM_STATUS_BAR)
                    : null;
            if (slimStatusBar != null && slimStatusBar.getParent() == host.getParent()
                    && slimStatusBar.getVisibility() == View.VISIBLE) {
                final float slimStatusBarTop = bounds.top + slimStatusBar.getTop()
                        + slimStatusBar.getTranslationY() - host.getTop();
                bottom = Math.min(bottom, Math.max(bounds.bottom - bottomRoom, slimStatusBarTop));
            }
            // The capsule ends at the edges of the buttons. The narrow buttons don't fill the bar,
            // and are in the middle of it.
            float left = bounds.left + host.getPaddingLeft();
            float right = bounds.right - host.getPaddingRight();
            View tab = getSelectedTabView();
            if (tab != null && tab.getParent() instanceof View tabs && tabs.getParent() == host
                    && tabs.getWidth() > 0) {
                left = Math.max(left, tabs.getLeft() + tabs.getTranslationX());
                right = Math.min(right, tabs.getRight() + tabs.getTranslationX());
            }
            capsule.set(left, bounds.top + capsuleTop, right, bottom);
        }

        @Nullable
        @Override
        public View getSelectedTabView() {
            return YouTubeGlassNavigationBar.getSelectedTabView();
        }

        @Override
        public boolean isOverDarkPlayer() {
            // The Shorts player is always dark, whatever the app theme.
            return ShortsPlayerState.isOpen();
        }

        @Override
        public float getCornerRadius(RectF capsule) {
            return capsule.height() * CORNER_RADIUS_FRACTION;
        }
    }

    private YouTubeGlassNavigationBar() {
    }
}
