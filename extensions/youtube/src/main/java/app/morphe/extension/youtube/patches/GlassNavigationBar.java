/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4338
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.NavigationBarPatch.NavigationBarStyle;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Draws the bottom navigation bar as a floating, rounded, translucent capsule.
 * <p>
 * No extra copy of the content behind the bar is made. If the app draws its own blurred background
 * (the translucent navigation bar style, Android 12 and later), that background is kept and clipped
 * to the capsule. The frosted look otherwise comes from a tinted fill, a soft gradient, a gradient
 * edge and a shadow, all drawn by one drawable that is set as the background of the navigation bar.
 * The bar keeps its height and vertical layout, and everything outside the capsule is left transparent.
 */
@SuppressWarnings("unused")
public final class GlassNavigationBar {

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
    private static WeakReference<GlassDrawable> currentDrawable = new WeakReference<>(null);

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
                    GlassDrawable drawable = currentDrawable.get();
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
     * The most alpha of the tint the app draws over its blurred background.
     */
    private static final float LIGHT_BLUR_TINT_ALPHA = 0.50f;
    private static final float DARK_BLUR_TINT_ALPHA = 0.40f;

    /**
     * How opaque the glass is, from the opacity slider. The slider is at 50 by default, which
     * keeps the default alphas, and every alpha is scaled by it: 0 is fully clear, 100 doubles them.
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
     * The least width of a button, the size of a touch target.
     */
    private static final int MIN_BUTTON_WIDTH = Dim.dp48;

    /**
     * The least room between the content of two buttons. With less room, the buttons that do not fit
     * in the width of the bar are hidden.
     */
    private static final int MIN_BUTTON_SPACING = Dim.dp8;

    /**
     * The least room above and below the content of the buttons in the capsule.
     */
    private static final int MIN_VERTICAL_PADDING = Dim.dp4;

    /**
     * Views of a button that hold its content, the icon and the label. Other views of the button,
     * such as the badge of the new content count, are left out.
     */
    private static final int BUTTON_ICON = ResourceUtils.getIdentifier(ResourceType.ID, "thumbnail_layout");
    private static final int BUTTON_LABEL = ResourceUtils.getIdentifier(ResourceType.ID, "text");

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

    /**
     * The usual content width (the median) and the content height of the buttons of the last bar, and the height
     * the app gives the bar, so the preview in the settings shows the buttons as they are.
     */
    public static volatile int lastContentWidth;
    public static volatile int lastContentHeight;
    public static volatile int lastBaseHeight;

    /**
     * @return The alpha scaled by the opacity slider, at most fully opaque.
     */
    private static float scaledAlpha(float alpha) {
        return Math.max(0, Math.min(1, alpha * OPACITY_SCALE));
    }

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
            if (width > 0 && !isHorizontalBottomBar(navigationBar)) return room;

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

        final int newAlpha = Math.round(255 * scaledAlpha(
                Utils.isDarkModeEnabled() ? DARK_BLUR_TINT_ALPHA : LIGHT_BLUR_TINT_ALPHA));
        if (alpha != newAlpha) {
            Logger.printDebug(() -> "Glass navigation bar blur tint alpha: " + alpha + " -> " + newAlpha);
            tint.setAlpha(newAlpha);
        }
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

        GlassDrawable glass = new GlassDrawable(pivotBar);
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
                if (isHorizontalBottomBar(pivotBar)) {
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
                        // The content of every button, also of the buttons hidden because they do not fit,
                        // so hiding them does not change how many buttons fit.
                        List<View> buttons = new ArrayList<>(tabs.getChildCount());
                        int[] contentWidths = new int[tabs.getChildCount()];
                        int contentTop = Integer.MAX_VALUE;
                        int contentBottom = 0;
                        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
                            View button = tabs.getChildAt(i);
                            if (button.getVisibility() == View.GONE && !hiddenButtons.contains(button)) continue;
                            if (!(button instanceof ViewGroup content)) continue;

                            final boolean laidOut = button.getVisibility() == View.VISIBLE;
                            int buttonContentWidth = 0;
                            for (int j = 0, contentCount = content.getChildCount(); j < contentCount; j++) {
                                View child = content.getChildAt(j);
                                final int id = child.getId();
                                if (child.getVisibility() != View.VISIBLE
                                        || (BUTTON_ICON != 0 && BUTTON_LABEL != 0 && id != BUTTON_ICON && id != BUTTON_LABEL)) {
                                    continue;
                                }
                                // The whole label, also if the button is narrower and the label is cut.
                                buttonContentWidth = Math.max(buttonContentWidth, child instanceof TextView label
                                        ? (int) Math.ceil(label.getPaint().measureText(label.getText().toString()))
                                                + label.getPaddingLeft() + label.getPaddingRight()
                                        : child.getWidth());
                                if (laidOut && child.getHeight() > 0) {
                                    contentTop = Math.min(contentTop, child.getTop());
                                    contentBottom = Math.max(contentBottom, child.getBottom());
                                }
                            }
                            contentWidths[buttons.size()] = buttonContentWidth;
                            buttons.add(button);
                        }

                        // A long label, which the app cuts, does not make every button wider.
                        Arrays.sort(contentWidths, 0, buttons.size());
                        final int contentWidth = buttons.isEmpty() ? 0 : contentWidths[buttons.size() / 2];

                        if (contentWidth > 0 && contentBottom > contentTop) {
                            final int[] layout = getButtonsLayout(pivotBar.getWidth(), buttons.size(),
                                    contentWidth, WIDTH_PERCENT, BUTTON_SPACING);
                            final int shown = layout[0];

                            // The buttons that do not fit are hidden from the end, but never the selected one.
                            View selected = getSelectedTabView();
                            final int selectedIndex = buttons.indexOf(selected);
                            final int lastShown = selectedIndex >= shown ? shown - 2 : shown - 1;
                            for (int i = 0, count = buttons.size(); i < count; i++) {
                                View button = buttons.get(i);
                                if (i <= lastShown || i == selectedIndex) {
                                    if (hiddenButtons.remove(button)) {
                                        button.setVisibility(View.VISIBLE);
                                        layoutChanged = true;
                                    }
                                } else if (hiddenButtons.add(button)) {
                                    button.setVisibility(View.GONE);
                                    layoutChanged = true;
                                }
                            }
                            if (buttons.size() > shown && !logged[1]) {
                                logged[1] = true;
                                Logger.printDebug(() -> "Glass navigation bar buttons shown: " + shown
                                        + " of " + buttons.size());
                            }

                            // The content is in the middle of the capsule. The bar is laid out taller or lower,
                            // so the pages laid out behind it follow the height of the capsule.
                            final int contentHeight = contentBottom - contentTop;
                            final int capsuleHeight = getCapsuleHeight(baseHeight, contentHeight, HEIGHT_PERCENT);
                            final int shift = (capsuleHeight - contentHeight) / 2 - contentTop;
                            final int paddingTop = Math.max(0, shift);
                            glass.capsuleTop = Math.max(0, -shift);
                            final int delta = baseHeight > 0 ? capsuleHeight + glass.capsuleTop - baseHeight : 0;
                            if (delta != heightDelta) {
                                heightDelta = delta;
                                pivotBar.requestLayout();
                                layoutChanged = true;
                            }

                            final int horizontalPadding = Math.max(GlassDrawable.MARGIN_HORIZONTAL,
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

                            lastContentWidth = contentWidth;
                            lastContentHeight = contentHeight;
                            lastBaseHeight = baseHeight;
                        }
                    }
                } else if (background == glass) {
                    glass.setAppBackground(null);
                    pivotBar.setBackground(appBackground[0]);
                    pivotBar.setPadding(appPadding[0], appPadding[1],
                            appPadding[2], pivotBar.getPaddingBottom());
                    for (View button : hiddenButtons) {
                        button.setVisibility(View.VISIBLE);
                    }
                    hiddenButtons.clear();
                    heightDelta = 0;
                    glass.capsuleTop = 0;
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
     * The buttons that fit in the capsule and their width. Also used by the preview in the settings.
     *
     * @param barWidth     The width of the navigation bar.
     * @param buttons      The number of buttons.
     * @param contentWidth The usual width of the content of the buttons (the icon or the label), the median,
     *                     so a long label that the app cuts does not make every button wider.
     * @param widthPercent The most width of the capsule, in percent of the width of the bar.
     * @param spacing      The room between the content of two buttons.
     * @return The number of buttons shown, and the width of each button.
     */
    public static int[] getButtonsLayout(int barWidth, int buttons, int contentWidth, int widthPercent, int spacing) {
        final int maxWidth = Math.max(0, (barWidth - 2 * GlassDrawable.MARGIN_HORIZONTAL) * widthPercent / 100);
        final int minButtonWidth = Math.max(MIN_BUTTON_WIDTH, contentWidth + MIN_BUTTON_SPACING);
        final int shown = Math.max(1, Math.min(buttons, maxWidth / minButtonWidth));
        // With less room, the buttons share the most width of the capsule.
        final int buttonWidth = Math.min(Math.max(MIN_BUTTON_WIDTH, contentWidth + spacing), maxWidth / shown);
        return new int[]{shown, buttonWidth};
    }

    /**
     * The height of the capsule. Also used by the preview in the settings.
     *
     * @param baseHeight    The height the app gives the bar, without the room below the buttons.
     * @param contentHeight The height of the content of the buttons, the icon and the label.
     * @param heightPercent The height of the capsule, in percent of the height the app gives the bar,
     *                      or of the height the buttons need if they need more, such as with a larger font.
     */
    public static int getCapsuleHeight(int baseHeight, int contentHeight, int heightPercent) {
        final int minHeight = contentHeight + 2 * MIN_VERTICAL_PADDING;
        return Math.max(minHeight, Math.round(Math.max(baseHeight, minHeight) * heightPercent / 100f));
    }

    /**
     * @return If the navigation bar is the bar along the bottom of the screen,
     *         not a navigation rail along the side.
     */
    private static boolean isHorizontalBottomBar(View pivotBar) {
        final int width = pivotBar.getWidth();
        final int height = pivotBar.getHeight();
        return width > 0 && height > 0 && width > 2 * height;
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }

    @ColorInt
    private static int blend(@ColorInt int from, @ColorInt int to, float fraction) {
        final float inverse = 1 - fraction;
        return Color.rgb(
                Math.round(Color.red(from) * inverse + Color.red(to) * fraction),
                Math.round(Color.green(from) * inverse + Color.green(to) * fraction),
                Math.round(Color.blue(from) * inverse + Color.blue(to) * fraction)
        );
    }

    private static final class GlassDrawable extends Drawable {
        private static final int STYLE_UNKNOWN = -1;
        private static final int STYLE_LIGHT = 0;
        private static final int STYLE_DARK = 1;
        private static final int STYLE_SHORTS = 2;

        private static final int SHADOW_LAYERS = 3;

        private static final long INDICATOR_ANIMATION_MILLISECONDS = 200;

        private final View host;

        /**
         * The room between the top of the bar and the top of the capsule, if the capsule is lower than the
         * content of the buttons laid out by the app would need, as the content is not moved above the bar.
         */
        int capsuleTop;

        static final int MARGIN_HORIZONTAL = Dim.dp12;
        /**
         * How far the capsule reaches into the room below the buttons.
         */
        private static final int MAX_MARGIN_BOTTOM = Dim.dp4;
        private static final int INDICATOR_INSET = Dim.dp4;
        private static final int INDICATOR_MAX_WIDTH = Dim.dp(96);
        private static final float STROKE_WIDTH = Dim.dp1;
        /**
         * The rim of the capsule, lit from outside, and the dark line inside it, which give the glass its thickness.
         */
        private static final float RIM_WIDTH = Dim.dp(1.5f);
        private static final float BEVEL_WIDTH = Dim.dp1;

        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF capsule = new RectF();
        private final Path capsulePath = new Path();

        /**
         * The blurred background of the app, drawn inside the capsule, or null if the app
         * gave the bar a plain color.
         */
        @Nullable
        private Drawable appBackground;

        /**
         * If drawing the background of the app failed, so it is no longer drawn.
         */
        private static boolean appBackgroundFailed;

        private final Drawable.Callback appBackgroundCallback = new Drawable.Callback() {
            @Override
            public void invalidateDrawable(@NonNull Drawable who) {
                invalidateSelf();
            }

            @Override
            public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                scheduleSelf(what, when);
            }

            @Override
            public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
                unscheduleSelf(what);
            }
        };
        private final RectF scratch = new RectF();

        private int style = STYLE_UNKNOWN;
        private int shadowAlpha;
        @ColorInt
        private int sheenTopColor;
        @ColorInt
        private int strokeTopColor;
        @ColorInt
        private int strokeBottomColor;
        /**
         * The rim is brightest at the rounded ends of the capsule, where the light hits the edge.
         */
        @ColorInt
        private int rimEndColor;
        @ColorInt
        private int rimMiddleColor;

        /**
         * The vertical edges of the capsule the gradients were made for.
         */
        private float shaderTop = Float.NaN;
        private float shaderBottom = Float.NaN;
        private float shaderLeft = Float.NaN;
        private float shaderRight = Float.NaN;

        @ColorInt
        private int fillColor;
        /**
         * There is no fill over the blurred background of the app, which has its own tint.
         */
        private boolean fillOverBlur;

        private final ValueAnimator indicatorAnimator = ValueAnimator.ofFloat(0, 1);
        private float animationFromLeft;
        private float animationFromRight;
        private boolean animationFromValid;

        /**
         * The horizontal edges of the selected tab indicator in the last frame.
         */
        private float lastIndicatorLeft;
        private float lastIndicatorRight;
        private boolean lastIndicatorValid;

        GlassDrawable(View host) {
            this.host = host;

            fillPaint.setStyle(Paint.Style.FILL);
            sheenPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(Color.BLACK);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(STROKE_WIDTH);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(RIM_WIDTH);
            bevelPaint.setStyle(Paint.Style.STROKE);
            bevelPaint.setStrokeWidth(BEVEL_WIDTH);
            indicatorPaint.setStyle(Paint.Style.FILL);

            indicatorAnimator.setDuration(INDICATOR_ANIMATION_MILLISECONDS);
            indicatorAnimator.setInterpolator(new DecelerateInterpolator());
            indicatorAnimator.addUpdateListener(animation -> invalidateSelf());

            updateStyle();
        }

        /**
         * Updates the colors if the theme changed or the Shorts player was opened or closed.
         */
        void updateStyle() {
            final int newStyle = ShortsPlayerState.isOpen()
                    ? STYLE_SHORTS
                    : Utils.isDarkModeEnabled() ? STYLE_DARK : STYLE_LIGHT;
            if (newStyle == style) return;
            style = newStyle;

            switch (newStyle) {
                case STYLE_SHORTS -> {
                    // The Shorts player is always dark, whatever the app theme.
                    fillColor = withAlpha(Color.BLACK, scaledAlpha(0.45f));
                    sheenTopColor = withAlpha(Color.WHITE, 0.06f);
                    strokeTopColor = withAlpha(Color.WHITE, 0.20f);
                    strokeBottomColor = withAlpha(Color.WHITE, 0.05f);
                    rimEndColor = withAlpha(Color.WHITE, 0.45f);
                    rimMiddleColor = withAlpha(Color.WHITE, 0.10f);
                    bevelPaint.setColor(withAlpha(Color.BLACK, 0.35f));
                    indicatorPaint.setColor(withAlpha(Color.WHITE, 0.15f));
                    shadowAlpha = Math.round(0.10f * 255);
                }
                case STYLE_DARK -> {
                    final int background = ThemeUtils.getAppBackgroundColor();
                    fillColor = withAlpha(blend(background, Color.WHITE, 0.14f), scaledAlpha(0.50f));
                    sheenTopColor = withAlpha(Color.WHITE, 0.10f);
                    strokeTopColor = withAlpha(Color.WHITE, 0.30f);
                    strokeBottomColor = withAlpha(Color.WHITE, 0.06f);
                    rimEndColor = withAlpha(Color.WHITE, 0.50f);
                    rimMiddleColor = withAlpha(Color.WHITE, 0.12f);
                    bevelPaint.setColor(withAlpha(Color.BLACK, 0.35f));
                    indicatorPaint.setColor(withAlpha(Color.WHITE, 0.15f));
                    shadowAlpha = Math.round(0.10f * 255);
                }
                default -> {
                    final int background = ThemeUtils.getAppBackgroundColor();
                    fillColor = withAlpha(blend(background, Color.WHITE, 0.6f), scaledAlpha(0.50f));
                    sheenTopColor = withAlpha(Color.WHITE, 0.35f);
                    strokeTopColor = withAlpha(Color.WHITE, 0.95f);
                    strokeBottomColor = withAlpha(Color.BLACK, 0.07f);
                    rimEndColor = withAlpha(Color.WHITE, 0.95f);
                    rimMiddleColor = withAlpha(Color.WHITE, 0.50f);
                    bevelPaint.setColor(withAlpha(Color.BLACK, 0.08f));
                    indicatorPaint.setColor(withAlpha(Color.BLACK, 0.08f));
                    shadowAlpha = Math.round(0.05f * 255);
                }
            }

            fillPaint.setColor(fillOverBlur ? Color.TRANSPARENT : fillColor);

            // The gradients are made again with the new colors.
            shaderTop = Float.NaN;
            invalidateSelf();
        }

        /**
         * @param background The background the app gave the navigation bar.
         */
        void setAppBackground(@Nullable Drawable background) {
            if (appBackground != null && appBackground != background) {
                appBackground.setCallback(null);
            }

            // A plain color or an opaque background is the solid bar of the app, such as in
            // the Shorts player, and is left out so the capsule stays translucent.
            appBackground = background == null || appBackgroundFailed
                    || background instanceof ColorDrawable
                    || background.getOpacity() == PixelFormat.OPAQUE
                    ? null
                    : background;
            if (appBackground != null) {
                appBackground.setCallback(appBackgroundCallback);
                appBackground.setVisible(true, false);
                appBackground.setBounds(getBounds());
            }
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            if (appBackground != null) {
                appBackground.setBounds(bounds);
            }
        }

        void onSelectedTabChanged() {
            animationFromValid = lastIndicatorValid;
            animationFromLeft = lastIndicatorLeft;
            animationFromRight = lastIndicatorRight;

            indicatorAnimator.cancel();
            if (animationFromValid) {
                indicatorAnimator.start();
            } else {
                invalidateSelf();
            }
        }

        private void updateShaders() {
            if (capsule.top == shaderTop && capsule.bottom == shaderBottom
                    && capsule.left == shaderLeft && capsule.right == shaderRight) return;
            shaderTop = capsule.top;
            shaderBottom = capsule.bottom;
            shaderLeft = capsule.left;
            shaderRight = capsule.right;

            final float radius = capsule.height() / 2;
            capsulePath.reset();
            capsulePath.addRoundRect(capsule, radius, radius, Path.Direction.CW);

            // Only made again when the size of the capsule or the colors change, not on every frame.
            sheenPaint.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                    sheenTopColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            strokePaint.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                    strokeTopColor, strokeBottomColor, Shader.TileMode.CLAMP));
            final float end = Math.min(0.5f, radius / capsule.width());
            rimPaint.setShader(new LinearGradient(capsule.left, 0, capsule.right, 0,
                    new int[]{rimEndColor, rimMiddleColor, rimMiddleColor, rimEndColor},
                    new float[]{0, end, 1 - end, 1}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            try {
                Rect bounds = getBounds();
                // The bottom padding of the navigation bar is the room for the system navigation
                // buttons or gesture handle. The capsule holds the whole height of the buttons
                // and reaches only slightly into that room, so it floats above the gesture handle.
                final int bottomRoom = host.getPaddingBottom();
                final float bottom = bounds.bottom - bottomRoom
                        + Math.max(0, Math.min(bottomRoom - FLOATING_ROOM, MAX_MARGIN_BOTTOM));
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
                if (capsule.width() <= 0 || capsule.height() <= 0) return;

                final float radius = capsule.height() / 2;
                updateShaders();

                // The Shorts player ends above the navigation bar, so the background of the window
                // would show around the capsule, which is white with the light theme.
                // The Shorts player is always dark, so the bar is kept black around the capsule.
                if (style == STYLE_SHORTS) {
                    canvas.drawColor(Color.BLACK);
                }

                // Soft shadow below the capsule.
                for (int i = SHADOW_LAYERS; i >= 1; i--) {
                    scratch.set(capsule);
                    scratch.inset(-i * 0.5f * STROKE_WIDTH, -i * 0.5f * STROKE_WIDTH);
                    scratch.offset(0, i * STROKE_WIDTH);
                    shadowPaint.setAlpha(shadowAlpha / i);
                    final float shadowRadius = scratch.height() / 2;
                    canvas.drawRoundRect(scratch, shadowRadius, shadowRadius, shadowPaint);
                }

                final boolean overBlur = appBackground != null && style != STYLE_SHORTS;
                if (overBlur != fillOverBlur) {
                    fillOverBlur = overBlur;
                    fillPaint.setColor(overBlur ? Color.TRANSPARENT : fillColor);
                }
                if (overBlur) {
                    // The blurred background of the app, only inside the capsule.
                    final int saveCount = canvas.save();
                    try {
                        canvas.clipPath(capsulePath);
                        appBackground.draw(canvas);
                    } catch (Exception ex) {
                        // The background of the app may expect to be drawn by the bar itself.
                        Logger.printException(() -> "Glass navigation bar app background failure", ex);
                        appBackgroundFailed = true;
                        setAppBackground(null);
                    } finally {
                        canvas.restoreToCount(saveCount);
                    }
                }

                if (Color.alpha(fillPaint.getColor()) != 0) {
                    canvas.drawRoundRect(capsule, radius, radius, fillPaint);
                }
                canvas.drawRoundRect(capsule, radius, radius, sheenPaint);

                drawIndicator(canvas);

                // The dark line inside the rim.
                scratch.set(capsule);
                scratch.inset(RIM_WIDTH + BEVEL_WIDTH / 2, RIM_WIDTH + BEVEL_WIDTH / 2);
                final float bevelRadius = scratch.height() / 2;
                canvas.drawRoundRect(scratch, bevelRadius, bevelRadius, bevelPaint);

                // The rim lit from outside, brighter at the top.
                scratch.set(capsule);
                scratch.inset(RIM_WIDTH / 2, RIM_WIDTH / 2);
                final float rimRadius = scratch.height() / 2;
                canvas.drawRoundRect(scratch, rimRadius, rimRadius, rimPaint);
                canvas.drawRoundRect(scratch, rimRadius, rimRadius, strokePaint);
            } catch (Exception ex) {
                Logger.printException(() -> "Glass navigation bar draw failure", ex);
            }
        }

        private void drawIndicator(Canvas canvas) {
            lastIndicatorValid = false;

            View tab = getSelectedTabView();
            if (tab == null || tab.getVisibility() != View.VISIBLE || tab.getWidth() <= 0) return;

            // The position of the tab inside the navigation bar.
            float tabLeft = 0;
            View view = tab;
            while (view != host) {
                tabLeft += view.getLeft() + view.getTranslationX();
                ViewParent parent = view.getParent();
                if (!(parent instanceof View parentView) || view.getVisibility() != View.VISIBLE) {
                    return;
                }
                view = parentView;
            }

            final float minLeft = capsule.left + INDICATOR_INSET;
            final float maxRight = capsule.right - INDICATOR_INSET;
            float targetLeft = tabLeft + INDICATOR_INSET;
            float targetRight = tabLeft + tab.getWidth() - INDICATOR_INSET;
            if (targetRight - targetLeft > INDICATOR_MAX_WIDTH) {
                final float center = (targetLeft + targetRight) / 2;
                targetLeft = center - INDICATOR_MAX_WIDTH / 2f;
                targetRight = center + INDICATOR_MAX_WIDTH / 2f;
            }
            targetLeft = Math.max(targetLeft, minLeft);
            targetRight = Math.min(targetRight, maxRight);

            float left = targetLeft;
            float right = targetRight;
            if (animationFromValid && indicatorAnimator.isRunning()) {
                final float fraction = (float) indicatorAnimator.getAnimatedValue();
                left = animationFromLeft + (targetLeft - animationFromLeft) * fraction;
                right = animationFromRight + (targetRight - animationFromRight) * fraction;
            }
            if (right - left <= 0) return;

            scratch.set(left, capsule.top + INDICATOR_INSET, right, capsule.bottom - INDICATOR_INSET);
            if (scratch.height() <= 0) return;
            final float radius = scratch.height() / 2;
            canvas.drawRoundRect(scratch, radius, radius, indicatorPaint);

            lastIndicatorLeft = left;
            lastIndicatorRight = right;
            lastIndicatorValid = true;
        }

        @Override
        public void setAlpha(int alpha) {
            // The glass keeps its own translucency.
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            // The colors come from the theme.
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private GlassNavigationBar() {
    }
}
