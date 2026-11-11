/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4542
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassBackdrop;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;

/**
 * Draws the bottom navigation bar of YouTube Music as a floating, rounded, translucent capsule,
 * the same way as the glass navigation bar of YouTube ({@link GlassCapsuleDrawable}).
 * <p>
 * The navigation bar is a tab layout at the bottom of the screen. The miniplayer is laid out right
 * above it, and the pages end at its top, both from the height of the bar. The capsule is drawn inside
 * the bar, and the room it needs above and below is added to the padding of the bar, so the bar grows
 * or shrinks with the capsule and the miniplayer and the pages follow it, without overlapping it.
 * The buttons keep the width the capsule gives them, so the first and the last button
 * end at the ends of the capsule.
 * <p>
 * Everything outside the capsule is left clear, so the page shows around the capsule: the page is laid out
 * down to the bottom of the screen, behind the bar, and the lists at the bottom of the page get room
 * below their last item, so it can still be scrolled above the bar. The player is laid out from the top
 * of the miniplayer to the bottom of the screen, so it is cut at the top of the bar, and the background
 * of the miniplayer ends there. The page behind the capsule is blurred on Android 14 and later, like the
 * blur of the YouTube navigation bar, and is covered by the background of the page before that.
 * The floating miniplayer of the app (9.39+) is used, with the same room on its sides as the capsule
 * and the page.
 */
@SuppressWarnings("unused")
public final class MusicGlassNavigationBar {

    private static final int PIVOT_BAR = ResourceUtils.getIdentifier(ResourceType.ID, "pivot_bar");

    /**
     * Views of a button that hold its content, the icon and the label. Also used by the preview in the settings.
     */
    public static final int BUTTON_ICON = ResourceUtils.getIdentifier(ResourceType.ID, "icon");
    public static final int BUTTON_LABEL = ResourceUtils.getIdentifier(ResourceType.ID, "text1");

    private static final boolean ENABLED = Settings.NAVIGATION_BAR_GLASS.get()
            && !Settings.HIDE_NAVIGATION_BAR.get()
            && PIVOT_BAR != 0;

    /**
     * How opaque the glass is, from the opacity slider. At 50 the default alphas of the glass are kept,
     * and every alpha is scaled by it: 0 is fully clear, 100 doubles them. The slider is at 20 by default,
     * so the blurred page shows clearly through the glass.
     * <p>
     * The style and the size are read again when the app comes back from the settings, see {@link #readSettings()}.
     */
    private static float opacityScale;

    /**
     * How bright the rim is, from the slider: 50 keeps the default alphas, 0 draws no rim.
     */
    private static float rimScale;

    /**
     * How much the page behind the capsule is blurred, on Android 14 and later. 0 shows the page sharp
     * through the glass.
     */
    private static float blurRadius;

    /**
     * If the glass is tinted white, a lighter and clearer glass.
     */
    private static boolean lightTint;

    /**
     * If the glass is tinted with the color of the album art.
     */
    private static boolean albumTint;

    /**
     * If the selected button has a rounded indicator.
     */
    private static boolean indicatorShown;

    /**
     * Height of the capsule, in percent of the height of the buttons the app lays out,
     * or of the height their content needs, if it needs more.
     */
    private static int heightPercent;

    /**
     * The most width of the capsule, in percent of the width of the bar.
     */
    private static int widthPercent;

    /**
     * Room between the content (icon and label) of two buttons.
     */
    private static int buttonSpacing;

    static {
        readSettings();
    }

    private static void readSettings() {
        opacityScale = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_OPACITY) / 50f;
        rimScale = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_RIM) / 50f;
        blurRadius = Dim.dp(SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_BLUR));
        lightTint = Settings.NAVIGATION_BAR_GLASS_LIGHT.get();
        albumTint = Settings.NAVIGATION_BAR_GLASS_ALBUM_TINT.get();
        indicatorShown = !Settings.NAVIGATION_BAR_GLASS_HIDE_INDICATOR.get();
        heightPercent = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_HEIGHT);
        widthPercent = SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_WIDTH);
        buttonSpacing = Dim.dp(SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING));
    }

    /**
     * The least room between the bottom of the capsule and the bottom of the screen,
     * so the capsule floats above it.
     */
    private static final int FLOATING_ROOM = Dim.dp10;

    /**
     * How far the capsule may reach into the room for the system navigation buttons or gesture handle.
     */
    private static final int MAX_SYSTEM_ROOM_OVERLAP = Dim.dp4;

    /**
     * Room between the top of the bar and the top of the capsule. The floating miniplayer
     * keeps its own room above the bar.
     */
    private static final int TOP_ROOM = Dim.dp2;

    /**
     * The least room between the ends of the capsule and the edges of the screen, the same as the room
     * of the pages ({@code item_large_spacing}), so the capsule lines up with the content of the page
     * and with the floating miniplayer.
     */
    public static final int MARGIN_HORIZONTAL = Dim.dp(16);

    /**
     * Buttons hidden because they do not fit in the width of the bar, shown again when they fit,
     * such as after a rotation.
     */
    private static final Set<View> hiddenButtons = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The navigation bar the glass was last applied to.
     */
    private static WeakReference<View> currentPivotBar = new WeakReference<>(null);
    private static WeakReference<GlassCapsuleDrawable> currentGlass = new WeakReference<>(null);

    /**
     * If the page is being recorded for a blurred backdrop. The page can hold the glass that is
     * recording it, such as the glass miniplayer, which then draws no backdrop of its own.
     */
    private static boolean recordingBackdrop;

    private static final int PLAYER_PAGE = ResourceUtils.getIdentifier(ResourceType.ID, "player_page");

    /**
     * The gradient the app draws behind the floating miniplayer, across the whole width of the screen.
     */
    private static final int MINIPLAYER_SCRIM = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player_scrim");

    /**
     * If the glass is drawn, so the page is laid out behind the bar.
     */
    private static boolean glassActive;

    /**
     * The view the pages are in, laid out behind the bar.
     */
    private static WeakReference<View> currentContent = new WeakReference<>(null);

    /**
     * The player, cut at the top of the bar.
     */
    private static WeakReference<View> currentPlayerPage = new WeakReference<>(null);
    private static final Rect playerClip = new Rect();

    /**
     * The views at the bottom of the page, with the bottom padding or margin the app gave them
     * and the bottom padding or margin they were given here.
     */
    private static final Map<View, int[]> bottomRoom = new WeakHashMap<>();

    /**
     * The floating button at the bottom of a page, such as New in the library.
     */
    private static final int FLOATING_ACTION_BUTTON = ResourceUtils.getIdentifier(ResourceType.ID, "floating_action_button");

    /**
     * The title and the buttons at the bottom of a video of Samples.
     */
    private static final int OVERLAY_ELEMENTS_CONTAINER = ResourceUtils.getIdentifier(ResourceType.ID, "overlay_elements_container");

    /**
     * If the class of a view is a RecyclerView, by class.
     */
    private static final Map<Class<?>, Boolean> listClasses = new WeakHashMap<>();

    /**
     * Lists inside the page are looked for at most this deep.
     */
    private static final int MAX_LIST_DEPTH = 12;

    private MusicGlassNavigationBar() {
    }

    /**
     * Injection point.
     * <p>
     * The floating miniplayer of the app, a rounded card with room on its sides and below it,
     * is used with the glass navigation bar and the glass miniplayer.
     */
    public static boolean useFloatingMiniplayer(boolean original) {
        return ENABLED || MusicGlassMiniplayer.ENABLED || original;
    }

    /**
     * @return If the glass navigation bar is drawn, and the page is laid out behind it.
     */
    static boolean isGlassActive() {
        return glassActive;
    }

    /**
     * Called when the color of the album art changes, or the player is closed.
     */
    public static void onAlbumColorChanged() {
        GlassCapsuleDrawable glass = currentGlass.get();
        if (glass != null) {
            Utils.runOnMainThread(() -> glass.setTintColor(getTintColor()));
        }
    }

    private static int getTintColor() {
        return albumTint ? getAlbumColor() : 0;
    }

    /**
     * @return The color of the album art of the song that plays, or 0 if none.
     */
    public static int getAlbumColor() {
        Integer color = ChangeMiniplayerColorPatch.getLastMiniplayerColor();
        return color == null ? 0 : color;
    }


    /**
     * Injection point.
     * <p>
     * Called for every button added to the navigation bar.
     *
     * @param tabView The view of the button.
     */
    public static void navigationTabCreated(View tabView) {
        if (!ENABLED && !MusicGlassMiniplayer.ENABLED) return;

        try {
            if (tabView.getParent() instanceof ViewGroup tabs
                    && tabs.getParent() instanceof ViewGroup pivotBar
                    && pivotBar.getId() == PIVOT_BAR) {
                if (ENABLED) {
                    apply(pivotBar);
                }
                MusicGlassMiniplayer.findMiniplayer(pivotBar);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "navigationTabCreated failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the view, the pages are in, is given its height, which ends at the top of the bar.
     *
     * @param content The view the pages are in.
     * @param height  The height of the view, without the height of the bar.
     * @return The height of the view, down to the bottom of the screen if the glass is drawn.
     */
    public static int getContentHeight(View content, int height) {
        if (!ENABLED && !MusicGlassMiniplayer.ENABLED) return height;

        try {
            if (currentContent.get() != content) {
                currentContent = new WeakReference<>(content);
                if (ENABLED) watchContent(content);
            }
            // The glass miniplayer only blurs the page, which is not laid out behind it.
            if (!ENABLED) return height;

            final int room = getContentRoom(content);
            // The keyboard covers the bottom of the page, and the app ends the page above it.
            if (room <= 0 || isKeyboardShown(content)) return height;
            return height + room;
        } catch (Exception ex) {
            Logger.printException(() -> "getContentHeight failure", ex);
            return height;
        }
    }

    /**
     * @return How much of the page is behind the bar.
     */
    private static int getContentRoom() {
        return getContentRoom(currentContent.get());
    }

    /**
     * The page is laid out down to the bottom of the screen from the first time it is laid out, also before
     * the glass is drawn, so its height does not change afterward. Some pages only read their height once,
     * such as Samples for the size of the video.
     *
     * @return How much of the page is behind the bar, the height of the bar if it is shown.
     */
    private static int getContentRoom(@Nullable View content) {
        View pivotBar = currentPivotBar.get();
        if (pivotBar == null && content != null && PIVOT_BAR != 0) {
            pivotBar = content.getRootView().findViewById(PIVOT_BAR);
        }
        if (pivotBar == null || pivotBar.getVisibility() != View.VISIBLE) return 0;
        return pivotBar.getMeasuredHeight();
    }

    private static boolean isKeyboardShown(View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        WindowInsets insets = view.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private static void setGlassActive(boolean active) {
        if (glassActive == active) return;
        glassActive = active;
        Logger.printDebug(() -> "Glass navigation bar page behind the bar: " + active);

        // The page is laid out again with or without the room behind the bar.
        View content = currentContent.get();
        if (content != null) {
            content.post(content::requestLayout);
        }
        if (!active) {
            View playerPage = currentPlayerPage.get();
            if (playerPage != null) playerPage.setClipBounds(null);
        }
    }

    /**
     * Gives the lists at the bottom of the page room below their last item whenever the page is laid out,
     * so the last item can be scrolled above the bar.
     */
    private static void watchContent(View content) {
        if (!(content instanceof ViewGroup group)) return;

        ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    if (currentContent.get() != content) {
                        content.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        return;
                    }
                    final int room = isKeyboardShown(content) ? 0 : getContentRoom();
                    updateListPadding(group, group, room, 0);
                } catch (Exception ex) {
                    Logger.printException(() -> "Glass navigation bar list padding failure", ex);
                }
            }
        };
        content.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    private static boolean isList(View view) {
        final Class<?> viewClass = view.getClass();
        Boolean list = listClasses.get(viewClass);
        if (list == null) {
            list = false;
            for (Class<?> type = viewClass; type != null && type != View.class; type = type.getSuperclass()) {
                if (type.getSimpleName().equals("RecyclerView")) {
                    list = true;
                    break;
                }
            }
            listClasses.put(viewClass, list);
        }
        return list;
    }

    private static void updateListPadding(ViewGroup content, View view, int room, int depth) {
        if (view.getVisibility() == View.GONE) return;

        final int id = view.getId();
        if (id != View.NO_ID && id == FLOATING_ACTION_BUTTON) {
            // The floating button at the bottom of the page, such as New in the library,
            // is laid out above the miniplayer with its bottom margin.
            setBottomRoom(view, room, true, true);
            return;
        }
        if (id != View.NO_ID && id == OVERLAY_ELEMENTS_CONTAINER) {
            // The title and the buttons at the bottom of a video of Samples. Their layer shades the video
            // and ends below them, so the shade is continued below them, behind the bar.
            setBottomRoom(view, room, true, false);
            final int shadeHeight = bottomRoom.containsKey(view) ? view.getPaddingBottom()
                    - Objects.requireNonNull(bottomRoom.get(view))[0] : 0;
            if (view.getBackground() instanceof BottomShadeDrawable shade) {
                shade.setHeight(shadeHeight);
            } else if (shadeHeight > 0 && view.getBackground() == null) {
                view.setBackground(new BottomShadeDrawable(shadeHeight));
            }
            return;
        }

        if (view != content && isList(view)) {
            // Only the lists that end at the bottom of the page and draw into their padding,
            // as the app makes them for the room of the miniplayer.
            final boolean atBottom = getBottomIn(view, content) >= content.getHeight() - Dim.dp2
                    && view.getHeight() > content.getHeight() / 3;
            final ViewGroup list = (ViewGroup) view;
            // A list of items as tall as the page, such as the videos of Samples, pages through them.
            // Its items are looked into instead, as the room is left inside each item.
            View first = list.getChildCount() > 0 ? list.getChildAt(0) : null;
            final boolean pager = first != null && first.getHeight() >= view.getHeight() * 9 / 10;
            setBottomRoom(view, room, atBottom && !pager && !list.getClipToPadding(), false);
            if (!pager || depth >= MAX_LIST_DEPTH) return;
        }

        if (depth >= MAX_LIST_DEPTH || !(view instanceof ViewGroup group)) return;
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            updateListPadding(content, group.getChildAt(i), room, depth + 1);
        }
    }

    /**
     * Shades the bottom of a view, as dark as the end of the shade the layer of the title and the buttons
     * of a video of Samples draws over the video.
     */
    private static final class BottomShadeDrawable extends Drawable {
        private static final int SHADE_COLOR = 0x66000000;

        private final Paint paint = new Paint();
        private int height;

        BottomShadeDrawable(int height) {
            this.height = height;
            paint.setColor(SHADE_COLOR);
        }

        void setHeight(int height) {
            if (this.height != height) {
                this.height = height;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (height <= 0) return;
            Rect bounds = getBounds();
            canvas.drawRect(bounds.left, bounds.bottom - height, bounds.right, bounds.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Adds the room behind the bar to the bottom padding or the bottom margin of a view, or takes it away.
     * The padding or the margin the app gave the view is kept, also if the app sets it again.
     *
     * @param applies If the view gets the room.
     * @param margin  If the room is added to the bottom margin instead of the bottom padding.
     */
    private static void setBottomRoom(View view, int room, boolean applies, boolean margin) {
        ViewGroup.MarginLayoutParams params = null;
        if (margin) {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams marginParams)) return;
            params = marginParams;
        }
        final int current = margin ? params.bottomMargin : view.getPaddingBottom();

        int[] state = bottomRoom.get(view);
        if (state == null) {
            if (!applies || room <= 0) return;
            state = new int[]{current, current};
            bottomRoom.put(view, state);
        } else if (current != state[1]) {
            // The app set the padding or the margin again.
            state[0] = current;
        }

        final int target = state[0] + (applies ? room : 0);
        state[1] = target;
        if (current == target) return;
        if (margin) {
            params.bottomMargin = target;
            view.setLayoutParams(params);
        } else {
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), target);
        }
    }

    /**
     * @return The bottom of the view in the coordinates of the ancestor.
     */
    private static int getBottomIn(View view, View ancestor) {
        int top = 0;
        View current = view;
        while (current != ancestor) {
            top += current.getTop();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View parentView)) break;
            current = parentView;
        }
        return top + view.getHeight();
    }

    /**
     * The player is laid out from the top of the miniplayer down to the bottom of the screen, and the bar
     * covered the part behind it. The player is cut at the top of the bar, which moves down when the player
     * is opened, so the background of the miniplayer ends at the top of the bar.
     */
    private static void clipPlayer(View pivotBar) {
        View playerPage = currentPlayerPage.get();
        if (playerPage == null || !playerPage.isAttachedToWindow()) {
            playerPage = PLAYER_PAGE == 0 ? null : pivotBar.getRootView().findViewById(PLAYER_PAGE);
            if (playerPage == null) return;
            currentPlayerPage = new WeakReference<>(playerPage);
        }

        int[] location = new int[2];
        pivotBar.getLocationInWindow(location);
        final int barTop = location[1];
        playerPage.getLocationInWindow(location);
        // The glass miniplayer is a card with room below it, where the page shows as well.
        final int bottom = Math.min(barTop - location[1], MusicGlassMiniplayer.getCollapsedBottom(playerPage));

        if (bottom >= playerPage.getHeight() || pivotBar.getVisibility() != View.VISIBLE) {
            if (playerPage.getClipBounds() != null) playerPage.setClipBounds(null);
            return;
        }

        final int clipBottom = Math.max(0, bottom);
        Rect current = playerPage.getClipBounds();
        if (current == null || current.bottom != clipBottom || current.right != playerPage.getWidth()) {
            playerClip.set(0, 0, playerPage.getWidth(), clipBottom);
            playerPage.setClipBounds(playerClip);
        }
    }

    /**
     * @return The opaque background color of the layout the page is in, or of the app theme.
     */
    private static int getPageBackgroundColor(View source) {
        for (ViewParent parent = source.getParent(); parent instanceof View view; parent = view.getParent()) {
            if (view.getBackground() instanceof ColorDrawable color && Color.alpha(color.getColor()) == 255) {
                return color.getColor();
            }
        }
        return ThemeUtils.getAppBackgroundColor() | 0xFF000000;
    }

    /**
     * The floating miniplayer is a card with room around it, so the gradient the app draws behind it,
     * across the whole width of the screen, is hidden.
     */
    private static void hideMiniplayerScrim(View pivotBar) {
        if (MINIPLAYER_SCRIM == 0) return;
        View scrim = pivotBar.getRootView().findViewById(MINIPLAYER_SCRIM);
        if (scrim != null && scrim.getVisibility() == View.VISIBLE) {
            scrim.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * @return The parent of the buttons, or null if the bar has none.
     */
    @Nullable
    private static ViewGroup getTabs(ViewGroup pivotBar) {
        return pivotBar.getChildCount() > 0 && pivotBar.getChildAt(0) instanceof ViewGroup tabs ? tabs : null;
    }

    /**
     * @return The selected button, or null if none is selected.
     */
    @Nullable
    private static View getSelectedTab(@Nullable ViewGroup tabs) {
        if (tabs == null) return null;
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View button = tabs.getChildAt(i);
            if (button.isSelected()) return button;
        }
        return null;
    }

    /**
     * @return If the bar holds buttons, unlike the empty bar the app shows on some pages, such as the onboarding.
     */
    private static boolean hasButtons(@Nullable ViewGroup tabs) {
        if (tabs == null || tabs.getVisibility() != View.VISIBLE) return false;
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View button = tabs.getChildAt(i);
            if (button.getVisibility() == View.VISIBLE || hiddenButtons.contains(button)) return true;
        }
        return false;
    }

    /**
     * @return The room for the system navigation buttons or the gesture handle at the bottom of the screen.
     */
    @SuppressWarnings("deprecation")
    private static int getSystemBottomRoom(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /**
     * @return The room between the bottom of the bar and the bottom of the window,
     *         without the translation the app animates the bar with.
     */
    private static int getRoomBelow(View pivotBar) {
        int[] location = new int[2];
        pivotBar.getLocationInWindow(location);
        final int bottom = Math.round(location[1] - pivotBar.getTranslationY()) + pivotBar.getHeight();
        return Math.max(0, pivotBar.getRootView().getHeight() - bottom);
    }

    private static void apply(ViewGroup pivotBar) {
        if (pivotBar == currentPivotBar.get()) return;
        currentPivotBar = new WeakReference<>(pivotBar);

        CapsuleHost capsuleHost = new CapsuleHost(pivotBar);
        GlassCapsuleDrawable glass = new GlassCapsuleDrawable(pivotBar, capsuleHost, opacityScale, lightTint, rimScale);
        glass.setTintColor(getTintColor());
        currentGlass = new WeakReference<>(glass);

        // The background and the padding the app gave the navigation bar and its buttons,
        // put back if the bar holds no buttons or is not a horizontal bar at the bottom.
        Drawable[] appBackground = {pivotBar.getBackground()};
        int[] appBarPadding = {pivotBar.getPaddingTop(), pivotBar.getPaddingBottom()};
        int[] appTabsPadding = {-1, -1};
        boolean[] logged = {false, false};
        int[] skippedFrames = {0};
        View[] lastSelected = {null};
        // The position of the bar and of the player in the last frame.
        float[] lastPositions = {Float.NaN, Float.NaN};

        // The page behind the capsule is blurred, so the glass is drawn again when the page scrolls.
        pivotBar.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (glassActive && currentPivotBar.get() == pivotBar && pivotBar.getBackground() == glass) {
                pivotBar.invalidate();
            }
        });

        // The settings may have changed while the app was in the background or showed the settings.
        pivotBar.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus || currentPivotBar.get() != pivotBar) return;
            readSettings();
            glass.setStyle(opacityScale, lightTint, rimScale);
            glass.setTintColor(getTintColor());
            pivotBar.requestLayout();
        });

        // The app hands the navigation bar its own background again, such as when the miniplayer
        // color changes, so the glass is put back whenever that happens.
        pivotBar.getViewTreeObserver().addOnPreDrawListener(() -> {
            boolean layoutChanged = false;
            try {
                if (currentPivotBar.get() != pivotBar) return true;

                Drawable background = pivotBar.getBackground();
                ViewGroup tabs = getTabs(pivotBar);
                if (GlassNavigationBarLayout.isHorizontalBottomBar(pivotBar) && hasButtons(tabs)) {
                    if (background != glass) {
                        appBackground[0] = background;
                        pivotBar.setBackground(glass);
                        // Set after the bar let go of its old background.
                        glass.setAppBackground(background);
                        if (!logged[0]) {
                            logged[0] = true;
                            Logger.printDebug(() -> "Glass navigation bar applied, height: " + pivotBar.getHeight()
                                    + " padding: " + pivotBar.getPaddingTop() + "/" + pivotBar.getPaddingBottom()
                                    + " buttons height: " + tabs.getHeight()
                                    + " room below: " + getRoomBelow(pivotBar)
                                    + " system room: " + getSystemBottomRoom(pivotBar));
                        }
                    }
                    glass.updateStyle();
                    setGlassActive(true);

                    View selected = getSelectedTab(tabs);
                    if (selected != lastSelected[0]) {
                        lastSelected[0] = selected;
                        glass.onSelectedTabChanged();
                    }

                    GlassNavigationBarLayout.Buttons buttons = GlassNavigationBarLayout.measureButtons(
                            tabs, BUTTON_ICON, BUTTON_LABEL, hiddenButtons);
                    View laidOutButton = null;
                    for (View button : buttons.views) {
                        if (button.getVisibility() == View.VISIBLE && button.getHeight() > 0) {
                            laidOutButton = button;
                            break;
                        }
                    }

                    if (buttons.isValid() && laidOutButton != null) {
                        // The width of the buttons comes from their content, and the buttons that do not fit
                        // in the width of the bar are hidden from the end, but never the selected one.
                        final int[] layout = GlassNavigationBarLayout.getButtonsLayout(pivotBar.getWidth(),
                                buttons.views.size(), buttons.contentWidth, buttons.minWidth, widthPercent, buttonSpacing,
                                MARGIN_HORIZONTAL);
                        final int shown = layout[0];
                        if (GlassNavigationBarLayout.fitButtons(buttons.views, shown, selected, hiddenButtons)) {
                            layoutChanged = true;
                        }
                        if (buttons.views.size() > shown && !logged[1]) {
                            logged[1] = true;
                            Logger.printDebug(() -> "Glass navigation bar buttons shown: " + shown
                                    + " of " + buttons.views.size());
                        }

                        // The buttons share the width between the padding of their parent, so the capsule
                        // is as wide as the buttons, and the indicator of the first and the last button
                        // has the same room from the ends of the capsule as from its top and bottom.
                        final int horizontalPadding = Math.max(MARGIN_HORIZONTAL,
                                (pivotBar.getWidth() - shown * layout[1]) / 2);
                        if (tabs.getPaddingLeft() != horizontalPadding || tabs.getPaddingRight() != horizontalPadding) {
                            if (appTabsPadding[0] < 0 || tabs.getPaddingLeft() != horizontalPadding) {
                                appTabsPadding[0] = tabs.getPaddingLeft();
                            }
                            if (appTabsPadding[1] < 0 || tabs.getPaddingRight() != horizontalPadding) {
                                appTabsPadding[1] = tabs.getPaddingRight();
                            }
                            tabs.setPadding(horizontalPadding, tabs.getPaddingTop(),
                                    horizontalPadding, tabs.getPaddingBottom());
                            layoutChanged = true;
                        }

                        // The content is in the middle of the capsule, with some room above the capsule,
                        // below the miniplayer, and the capsule floats above the bottom of the screen.
                        // The height of the bar follows from its padding.
                        final int buttonsHeight = tabs.getHeight();
                        final int contentHeight = buttons.contentHeight();
                        final int contentTop = laidOutButton.getTop() + buttons.contentTop;
                        final int capsuleHeight = GlassNavigationBarLayout.getCapsuleHeight(
                                buttonsHeight, contentHeight, heightPercent);
                        final int capsuleTopInButtons = contentTop - (capsuleHeight - contentHeight) / 2;
                        final int paddingTop = Math.max(0, TOP_ROOM - capsuleTopInButtons);
                        final int capsuleTop = paddingTop + capsuleTopInButtons;
                        final int capsuleBottom = capsuleTop + capsuleHeight;

                        // The room needed between the bottom of the capsule and the bottom of the screen.
                        final int neededRoom = Math.max(FLOATING_ROOM,
                                getSystemBottomRoom(pivotBar) - MAX_SYSTEM_ROOM_OVERLAP);
                        final int roomBelowButtons = paddingTop + buttonsHeight - capsuleBottom;
                        final int roomBelowBar = getRoomBelow(pivotBar);
                        final int paddingBottom = Math.max(0, neededRoom - roomBelowButtons - roomBelowBar);

                        capsuleHost.set(tabs.getLeft() + horizontalPadding, capsuleTop,
                                tabs.getRight() - horizontalPadding, capsuleBottom);

                        if (pivotBar.getPaddingTop() != paddingTop || pivotBar.getPaddingBottom() != paddingBottom) {
                            Logger.printDebug(() -> "Glass navigation bar padding: " + paddingTop + "/" + paddingBottom
                                    + " capsule height: " + capsuleHeight + " buttons height: " + buttonsHeight
                                    + " room below bar: " + roomBelowBar);
                            pivotBar.setPadding(pivotBar.getPaddingLeft(), paddingTop,
                                    pivotBar.getPaddingRight(), paddingBottom);
                            layoutChanged = true;
                        }

                        GlassNavigationBarLayout.lastContentWidth = buttons.contentWidth;
                        GlassNavigationBarLayout.lastMinButtonWidth = buttons.minWidth;
                        GlassNavigationBarLayout.lastContentHeight = contentHeight;
                        GlassNavigationBarLayout.lastBaseHeight = buttonsHeight;
                        GlassNavigationBarLayout.setLastButtons(buttons.views);
                    }
                    clipPlayer(pivotBar);
                    hideMiniplayerScrim(pivotBar);

                    // The blurred page is drawn again while the bar or the player moves, such as when the
                    // player is opened or closed, or the glass keeps the blurred player behind it.
                    View playerPage = currentPlayerPage.get();
                    final float barPosition = pivotBar.getTranslationY();
                    final float playerPosition = playerPage == null ? 0 : playerPage.getTop() + playerPage.getTranslationY();
                    if (barPosition != lastPositions[0] || playerPosition != lastPositions[1]) {
                        lastPositions[0] = barPosition;
                        lastPositions[1] = playerPosition;
                        pivotBar.invalidate();
                    }
                } else if (background == glass) {
                    glass.setAppBackground(null);
                    pivotBar.setBackground(appBackground[0]);
                    pivotBar.setPadding(pivotBar.getPaddingLeft(), appBarPadding[0],
                            pivotBar.getPaddingRight(), appBarPadding[1]);
                    if (tabs != null && appTabsPadding[0] >= 0) {
                        tabs.setPadding(appTabsPadding[0], tabs.getPaddingTop(),
                                appTabsPadding[1], tabs.getPaddingBottom());
                    }
                    GlassNavigationBarLayout.showHiddenButtons(hiddenButtons);
                    capsuleHost.set(0, 0, 0, 0);
                    setGlassActive(false);
                    layoutChanged = true;
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
     * Where the capsule is drawn in the navigation bar, set when the bar is laid out.
     */
    private static final class CapsuleHost implements GlassCapsuleDrawable.Host {
        private final ViewGroup pivotBar;
        private final Rect capsule = new Rect();

        CapsuleHost(ViewGroup pivotBar) {
            this.pivotBar = pivotBar;
        }

        void set(int left, int top, int right, int bottom) {
            if (capsule.left != left || capsule.top != top || capsule.right != right || capsule.bottom != bottom) {
                capsule.set(left, top, right, bottom);
                pivotBar.invalidate();
            }
        }

        @Override
        public void getCapsule(Rect bounds, RectF out) {
            out.set(bounds.left + capsule.left, bounds.top + capsule.top,
                    bounds.left + capsule.right, bounds.top + capsule.bottom);
        }

        @Nullable
        @Override
        public View getSelectedTabView() {
            return indicatorShown ? getSelectedTab(getTabs(pivotBar)) : null;
        }

        @Nullable
        private GlassBackdrop backdrop;

        @Override
        public void drawBackdrop(Canvas canvas) {
            if (glassActive) {
                backdrop = drawPageBehind(canvas, pivotBar, blurRadius, false, backdrop, "GlassNavigationBar");
            }
        }
    }

    /**
     * Draws the page behind a glass view blurred ({@link GlassBackdrop}), on Android 14 and later with hardware
     * acceleration. Like the blur of the YouTube navigation bar, it is left out on Android 12 and 13, where a blur
     * render effect redrawn while the player moves can crash the app. Without the blur, the page is covered by
     * its background, or the sharp page would show through the clear glass. A blur radius of 0 instead draws
     * nothing, so the page shows sharp through the glass.
     *
     * @param pagesOnly If only the pages are drawn, not the surrounding layout. The miniplayer is in the layout, and
     *                  a render node that holds the node of the miniplayer, which then holds that node again,
     *                  is a loop that overflows the stack of the render thread.
     * @param backdrop  The backdrop this returned for the last frame, or null.
     * @return The backdrop to pass for the next frame.
     */
    @Nullable
    static GlassBackdrop drawPageBehind(Canvas canvas, View target, float blurRadius, boolean pagesOnly,
                                        @Nullable GlassBackdrop backdrop, String name) {
        if (blurRadius <= 0) return backdrop;

        View content = currentContent.get();
        if (recordingBackdrop || content == null
                || !(content.getParent() instanceof View source) || source.getWidth() <= 0) return backdrop;

        // The page has no background of its own, the layout it is in has. The blurred page is drawn
        // over that background, or the blurred text would be mostly clear and the sharp text would show.
        final int background = getPageBackgroundColor(source);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || !canvas.isHardwareAccelerated()) {
            canvas.drawColor(background);
            return backdrop;
        }

        if (backdrop == null) {
            backdrop = new GlassBackdrop(name, blurRadius);
        } else {
            backdrop.setBlurRadius(blurRadius);
        }
        recordingBackdrop = true;
        try {
            backdrop.draw(canvas, target, pagesOnly ? content : source, background);
        } finally {
            recordingBackdrop = false;
        }
        return backdrop;
    }
}
