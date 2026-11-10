/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4542
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;

/**
 * Draws the bottom navigation bar and floating miniplayer of YouTube Music as floating, rounded, translucent capsules,
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
 * of the miniplayer ends there. The page behind the capsule is blurred on Android 12 and later, like the
 * blur of the YouTube navigation bar, and is covered by the background of the page before that.
 * The floating miniplayer of the app (9.39+) is used, with the same room on its sides as the capsule
 * and the page.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class MusicGlassNavigationBar {

    private static final int PIVOT_BAR = ResourceUtils.getIdentifier(ResourceType.ID, "pivot_bar");

    /**
     * Views of a button that hold its content, the icon and the label. Also used by the preview in the settings.
     */
    public static final int BUTTON_ICON = ResourceUtils.getIdentifier(ResourceType.ID, "icon");
    public static final int BUTTON_LABEL = ResourceUtils.getIdentifier(ResourceType.ID, "text1");

    private static final int PLAYER_PAGE = ResourceUtils.getIdentifier(ResourceType.ID, "player_page");
    private static final int MINI_PLAYER = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player");

    /**
     * The gradient the app draws behind the floating miniplayer, across the whole width of the screen.
     */
    private static final int MINIPLAYER_SCRIM = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player_scrim");

    private static final boolean NAV_BAR_ENABLED = Settings.NAVIGATION_BAR_GLASS.get()
            && !Settings.HIDE_NAVIGATION_BAR.get()
            && PIVOT_BAR != 0;

    private static final boolean MINIPLAYER_ENABLED = Settings.MINIPLAYER_GLASS.get()
            && MINI_PLAYER != 0;

    private static final boolean ENABLED = NAV_BAR_ENABLED || MINIPLAYER_ENABLED;

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
     * Minimum right padding inside the miniplayer so the right-most transport button (Next)
     * never touches or overflows the curved right glass border.
     */
    private static final int MINIPLAYER_RIGHT_BUTTON_PADDING = Dim.dp(10);

    private static final Rect EMPTY_CLIP_RECT = new Rect(0, 0, 0, 0);

    /**
     * Buttons hidden because they do not fit in the width of the bar, shown again when they fit,
     * such as after a rotation.
     */
    private static final Set<View> hiddenButtons = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The navigation bar the glass was last applied to, and its drawable.
     */
    private static WeakReference<View> currentPivotBar = new WeakReference<>(null);
    private static WeakReference<GlassCapsuleDrawable> currentNavBarGlass = new WeakReference<>(null);

    /**
     * The miniplayer view the glass was last applied to, and its drawable.
     */
    private static WeakReference<View> currentMiniplayer = new WeakReference<>(null);
    private static WeakReference<GlassCapsuleDrawable> currentMiniplayerGlass = new WeakReference<>(null);
    private static boolean miniplayerLookupPosted;

    private static WeakReference<ImageView> styledArtworkView = new WeakReference<>(null);

    /** Original clipping and background states for artwork ancestor containers. */
    private static final Map<ViewGroup, ArtworkAncestorState> artworkClipStates = new WeakHashMap<>();

    /** Sibling views inside the compact artwork wrapper temporarily hidden while in miniplayer mode. */
    private static final Map<View, Float> hiddenArtworkSiblings = new WeakHashMap<>();

    /** Progress/time bar views modified while collapsed in the miniplayer. */
    private static final Set<View> styledProgressBars = Collections.newSetFromMap(new WeakHashMap<>());

    private static final class ArtworkAncestorState {
        final boolean clipChildren;
        final boolean clipToPadding;
        final boolean clipToOutline;
        final boolean willNotDraw;

        ArtworkAncestorState(ViewGroup vg) {
            this.clipChildren = vg.getClipChildren();
            this.clipToPadding = vg.getClipToPadding();
            this.clipToOutline = vg.getClipToOutline();
            this.willNotDraw = vg.willNotDraw();
        }

        void restore(ViewGroup vg) {
            vg.setClipChildren(clipChildren);
            vg.setClipToPadding(clipToPadding);
            vg.setClipToOutline(clipToOutline);
            vg.setWillNotDraw(willNotDraw);
            Drawable bg = vg.getBackground();
            if (bg != null && !(bg instanceof GlassCapsuleDrawable)) {
                bg.setAlpha(255);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Drawable fg = vg.getForeground();
                if (fg != null) {
                    fg.setAlpha(255);
                }
            }
        }
    }

    private static int lastNavBarSettingsHash;
    private static int lastMiniplayerSettingsHash;

    /**
     * Cached playback state for spinning vinyl artwork animation.
     */
    private static long lastAudioCheckTimeMs;
    private static boolean cachedMusicPlaying;
    private static long lastSpinFrameTimeMs;

    /**
     * Prevents nested RenderNode recording when both navigation bar and miniplayer glass are active.
     */
    private static boolean inBackdropDraw;

    /**
     * The colors of the blurred page are made more vivid and slightly brighter, which looks like frosted
     * glass. A blur alone makes the colors dull and gray.
     */
    private static final ColorMatrix FROSTED_GLASS_COLORS = createFrostedGlassColors();

    private static ColorMatrix createFrostedGlassColors() {
        ColorMatrix colors = new ColorMatrix();
        colors.setSaturation(1.5f);
        final float brightness = 15;
        colors.postConcat(new ColorMatrix(new float[]{
                1, 0, 0, 0, brightness,
                0, 1, 0, 0, brightness,
                0, 0, 1, 0, brightness,
                0, 0, 0, 1, 0
        }));
        return colors;
    }

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

    private static float getNavBarOpacityScale() {
        return SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_OPACITY) / 50f;
    }

    private static float getNavBarBlurRadiusPx() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_BLUR));
    }

    private static float getNavBarRimScale() {
        return SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_RIM) / 50f;
    }

    private static boolean isNavBarLightTint() {
        return Settings.NAVIGATION_BAR_GLASS_LIGHT.get();
    }

    private static int getHeightPercent() {
        return SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_HEIGHT);
    }

    private static int getWidthPercent() {
        return SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_WIDTH);
    }

    private static int getButtonSpacing() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING));
    }

    private static float getMiniplayerOpacityScale() {
        return SeekBarPreference.clampToRange(Settings.MINIPLAYER_GLASS_OPACITY) / 50f;
    }

    private static float getMiniplayerBlurRadiusPx() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.MINIPLAYER_GLASS_BLUR));
    }

    private static float getMiniplayerRimScale() {
        return SeekBarPreference.clampToRange(Settings.MINIPLAYER_GLASS_RIM) / 50f;
    }

    private static boolean isMiniplayerLightTint() {
        return Settings.MINIPLAYER_GLASS_LIGHT.get();
    }

    private static int getMiniplayerCornerRadiusPx() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.MINIPLAYER_GLASS_CORNER_RADIUS));
    }

    private static int getMiniplayerArtworkPaddingPx() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.MINIPLAYER_GLASS_ARTWORK_PADDING));
    }

    /**
     * Returns the dominant album art color captured by {@link ChangeMiniplayerColorPatch}.
     */
    public static int getResolvedAlbumColor() {
        Integer hookColor = ChangeMiniplayerColorPatch.getLastMiniplayerColor();
        return hookColor != null ? hookColor : 0;
    }

    private static int computeNavBarSettingsHash() {
        return Objects.hash(
                Settings.NAVIGATION_BAR_GLASS_OPACITY.get(),
                Settings.NAVIGATION_BAR_GLASS_BLUR.get(),
                Settings.NAVIGATION_BAR_GLASS_RIM.get(),
                Settings.NAVIGATION_BAR_GLASS_LIGHT.get(),
                Settings.NAVIGATION_BAR_GLASS_ALBUM_TINT.get(),
                Settings.NAVIGATION_BAR_GLASS_HIDE_INDICATOR.get(),
                Settings.NAVIGATION_BAR_GLASS_HEIGHT.get(),
                Settings.NAVIGATION_BAR_GLASS_WIDTH.get(),
                Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING.get(),
                getResolvedAlbumColor()
        );
    }

    private static int computeMiniplayerSettingsHash() {
        return Objects.hash(
                Settings.MINIPLAYER_GLASS_OPACITY.get(),
                Settings.MINIPLAYER_GLASS_BLUR.get(),
                Settings.MINIPLAYER_GLASS_RIM.get(),
                Settings.MINIPLAYER_GLASS_LIGHT.get(),
                Settings.MINIPLAYER_GLASS_ALBUM_TINT.get(),
                Settings.MINIPLAYER_GLASS_SPINNING_ARTWORK.get(),
                Settings.MINIPLAYER_GLASS_HIDE_PROGRESS_BAR.get(),
                Settings.MINIPLAYER_GLASS_CORNER_RADIUS.get(),
                Settings.MINIPLAYER_GLASS_ARTWORK_PADDING.get(),
                getResolvedAlbumColor()
        );
    }

    /**
     * Injection point.
     * <p>
     * The floating miniplayer of the app, a rounded card with room on its sides and below it,
     * is used with the glass navigation bar and glass miniplayer.
     */
    public static boolean useFloatingMiniplayer(boolean original) {
        return ENABLED || original;
    }

    /**
     * Called when the dominant color of the current song's album art changes or the miniplayer is dismissed.
     */
    public static void onMiniplayerColorChanged() {
        if (!ENABLED) return;


        View miniplayer = currentMiniplayer.get();
        GlassCapsuleDrawable miniGlass = currentMiniplayerGlass.get();
        if (miniplayer != null && miniGlass != null) {
            miniplayer.post(() -> {
                miniGlass.updateStyle(getMiniplayerOpacityScale(), isMiniplayerLightTint());
                miniGlass.invalidateStyle();
                miniplayer.invalidate();
            });
        }

        View pivotBar = currentPivotBar.get();
        GlassCapsuleDrawable navGlass = currentNavBarGlass.get();
        if (pivotBar != null && navGlass != null) {
            pivotBar.post(() -> {
                navGlass.updateStyle(getNavBarOpacityScale(), isNavBarLightTint());
                navGlass.invalidateStyle();
                pivotBar.invalidate();
            });
        }
    }

    /**
     * Injection point.
     * <p>
     * Called for every button added to the navigation bar.
     *
     * @param tabView The view of the button.
     */
    public static void navigationTabCreated(View tabView) {
        if (!ENABLED) return;

        try {
            if (tabView.getParent() instanceof ViewGroup tabs
                    && tabs.getParent() instanceof ViewGroup pivotBar
                    && pivotBar.getId() == PIVOT_BAR) {
                if (NAV_BAR_ENABLED) {
                    apply(pivotBar);
                }
                if (MINIPLAYER_ENABLED) {
                    scheduleMiniplayerLookup(pivotBar);
                }
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
        if (!ENABLED) return height;

        try {
            if (currentContent.get() != content) {
                currentContent = new WeakReference<>(content);
                if (NAV_BAR_ENABLED) {
                    watchContent(content);
                }
            }
            if (MINIPLAYER_ENABLED) {
                scheduleMiniplayerLookup(content);
            }
            if (!NAV_BAR_ENABLED || !glassActive || isKeyboardShown(content)) return height;
            final int room = getContentRoom();
            if (room > 0) return height + room;
        } catch (Exception ex) {
            Logger.printException(() -> "getContentHeight failure", ex);
        }
        return height;
    }

    /**
     * @return The height of the navigation bar, which the page is extended by to reach the bottom of the screen.
     */
    private static int getContentRoom() {
        View pivotBar = currentPivotBar.get();
        if (pivotBar == null || pivotBar.getVisibility() != View.VISIBLE) return 0;
        return pivotBar.getHeight();
    }

    @SuppressWarnings("deprecation")
    private static boolean isKeyboardShown(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.isVisible(WindowInsets.Type.ime());
        }
        return insets.getSystemWindowInsetBottom() > view.getRootView().getHeight() / 4;
    }

    private static void setGlassActive(boolean active) {
        if (glassActive == active) return;
        glassActive = active;
        Logger.printDebug(() -> "Glass navigation bar active: " + active);
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
            setBottomRoom(view, room, true, true);
            return;
        }
        if (id != View.NO_ID && id == OVERLAY_ELEMENTS_CONTAINER) {
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
            final boolean atBottom = getBottomIn(view, content) >= content.getHeight() - Dim.dp2
                    && view.getHeight() > content.getHeight() / 3;
            final ViewGroup list = (ViewGroup) view;
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

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Adds the room behind the bar to the bottom padding or the bottom margin of a view, or takes it away.
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
        int bottom = barTop - location[1];
        if (MINIPLAYER_ENABLED) {
            View miniplayer = currentMiniplayer.get();
            if (miniplayer != null && miniplayer.getVisibility() == View.VISIBLE
                    && miniplayer.getAlpha() > 0.9f && isMiniplayerAtBottom(miniplayer)) {
                bottom = Math.min(bottom, miniplayer.getBottom());
            }
        }
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
     * @return True when the miniplayer is in its collapsed bottom bar position, false when pinned
     *         at the top of the expanded player (e.g. while Lyrics or Comments sheets are open).
     */
    private static boolean isMiniplayerAtBottom(View miniplayer) {
        View rootView = miniplayer.getRootView();
        if (rootView == null || rootView.getHeight() <= 0) return true;
        int[] loc = new int[2];
        miniplayer.getLocationInWindow(loc);
        return loc[1] > rootView.getHeight() / 2;
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
    private static void hideMiniplayerScrim(View anchor) {
        if (MINIPLAYER_SCRIM == 0) return;
        View rootView = anchor.getRootView();
        if (rootView == null) return;
        View scrim = rootView.findViewById(MINIPLAYER_SCRIM);
        if (scrim != null && scrim.getVisibility() == View.VISIBLE) {
            scrim.setVisibility(View.INVISIBLE);
        }
    }

    @Nullable
    private static ViewGroup getTabs(ViewGroup pivotBar) {
        return pivotBar.getChildCount() > 0 && pivotBar.getChildAt(0) instanceof ViewGroup tabs ? tabs : null;
    }

    @Nullable
    private static View getSelectedTab(@Nullable ViewGroup tabs) {
        if (tabs == null) return null;
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View button = tabs.getChildAt(i);
            if (button.isSelected()) return button;
        }
        return null;
    }

    private static boolean hasButtons(@Nullable ViewGroup tabs) {
        if (tabs == null || tabs.getVisibility() != View.VISIBLE) return false;
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View button = tabs.getChildAt(i);
            if (button.getVisibility() == View.VISIBLE || hiddenButtons.contains(button)) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static int getSystemBottomRoom(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

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
        GlassCapsuleDrawable glass = new GlassCapsuleDrawable(pivotBar, capsuleHost, getNavBarOpacityScale(), isNavBarLightTint());
        currentNavBarGlass = new WeakReference<>(glass);
        Drawable[] appBackground = {pivotBar.getBackground()};
        int[] appBarPadding = {pivotBar.getPaddingTop(), pivotBar.getPaddingBottom()};
        int[] appTabsPadding = {-1, -1};
        boolean[] logged = {false, false};
        int[] skippedFrames = {0};
        View[] lastSelected = {null};
        float[] lastPositions = {Float.NaN, Float.NaN};

        pivotBar.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (glassActive && currentPivotBar.get() == pivotBar && pivotBar.getBackground() == glass) {
                pivotBar.invalidate();
            }
        });

        pivotBar.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && currentPivotBar.get() == pivotBar) {
                capsuleHost.lastBlurRadius = -1f;
                glass.updateStyle(getNavBarOpacityScale(), isNavBarLightTint());
                glass.invalidateStyle();
                pivotBar.requestLayout();
                pivotBar.invalidate();
            }
        });

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
                        if (!logged[0]) {
                            logged[0] = true;
                            Logger.printDebug(() -> "Glass navigation bar applied, height: " + pivotBar.getHeight());
                        }
                    }
                    glass.updateStyle(getNavBarOpacityScale(), isNavBarLightTint());

                    final int navHash = computeNavBarSettingsHash();
                    if (navHash != lastNavBarSettingsHash) {
                        lastNavBarSettingsHash = navHash;
                        capsuleHost.lastBlurRadius = -1f;
                        glass.invalidateStyle();
                        pivotBar.invalidate();
                    }
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

                    if (laidOutButton != null) {
                        final int count = buttons.views.size();
                        final int[] layout = GlassNavigationBarLayout.getButtonsLayout(
                                pivotBar.getWidth(), count, buttons.contentWidth, buttons.minWidth,
                                getWidthPercent(), getButtonSpacing(), MARGIN_HORIZONTAL);
                        final int shown = layout[0];
                        if (!logged[1]) {
                            logged[1] = true;
                            Logger.printDebug(() -> "Glass navigation bar buttons: " + shown + "/" + count
                                    + " button width: " + layout[1]);
                        }
                        if (GlassNavigationBarLayout.fitButtons(buttons.views, shown, selected, hiddenButtons)) {
                            layoutChanged = true;
                        }

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

                        final int buttonsHeight = tabs.getHeight();
                        final int contentHeight = buttons.contentHeight();
                        final int contentTop = laidOutButton.getTop() + buttons.contentTop;
                        final int capsuleHeight = GlassNavigationBarLayout.getCapsuleHeight(
                                buttonsHeight, contentHeight, getHeightPercent());
                        final int capsuleTopInButtons = contentTop - (capsuleHeight - contentHeight) / 2;
                        final int paddingTop = Math.max(0, TOP_ROOM - capsuleTopInButtons);
                        final int capsuleTop = paddingTop + capsuleTopInButtons;
                        final int capsuleBottom = capsuleTop + capsuleHeight;

                        final int neededRoom = Math.max(FLOATING_ROOM,
                                getSystemBottomRoom(pivotBar) - MAX_SYSTEM_ROOM_OVERLAP);
                        final int roomBelowButtons = paddingTop + buttonsHeight - capsuleBottom;
                        final int roomBelowBar = getRoomBelow(pivotBar);
                        final int paddingBottom = Math.max(0, neededRoom - roomBelowButtons - roomBelowBar);

                        capsuleHost.set(tabs.getLeft() + horizontalPadding, capsuleTop,
                                tabs.getRight() - horizontalPadding, capsuleBottom);

                        if (pivotBar.getPaddingTop() != paddingTop || pivotBar.getPaddingBottom() != paddingBottom) {
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
            if (layoutChanged && skippedFrames[0] < 2) {
                skippedFrames[0]++;
                return false;
            }
            skippedFrames[0] = 0;
            return true;
        });
    }

    /**
     * Schedules a single lookup on the UI thread to attach the glass drawable to the miniplayer view
     * once the layout has finished inflating.
     */
    private static void scheduleMiniplayerLookup(View anchor) {
        View existing = currentMiniplayer.get();
        if (existing != null && existing.isAttachedToWindow()) return;
        if (miniplayerLookupPosted) return;
        miniplayerLookupPosted = true;
        anchor.post(() -> {
            miniplayerLookupPosted = false;
            try {
                View rootView = anchor.getRootView();
                if (rootView == null) return;
                View miniplayer = rootView.findViewById(MINI_PLAYER);
                if (miniplayer != null) {
                    applyMiniplayer(miniplayer);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "scheduleMiniplayerLookup failure", ex);
            }
        });
    }

    /**
     * Resets any custom scale, translation, rotation, and outline clipping applied to the miniplayer
     * artwork view and its ancestors when the player expands to full screen.
     */
    private static void resetStyledArtworkView() {
        ImageView prev = styledArtworkView.get();
        styledArtworkView = new WeakReference<>(null);
        lastSpinFrameTimeMs = 0L;

        if (prev != null) {
            if (prev.getScaleX() != 1f || prev.getScaleY() != 1f) {
                prev.setScaleX(1f);
                prev.setScaleY(1f);
            }
            if (prev.getTranslationX() != 0f) {
                prev.setTranslationX(0f);
            }
            if (prev.getTranslationY() != 0f) {
                prev.setTranslationY(0f);
            }
            if (prev.getRotation() != 0f) {
                prev.setRotation(0f);
            }
            if (prev.getOutlineProvider() instanceof ArtworkOutlineProvider) {
                prev.setClipToOutline(false);
            }
            if (prev.getBackground() != null) {
                prev.getBackground().setAlpha(255);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && prev.getForeground() != null) {
                prev.getForeground().setAlpha(255);
            }
        }
        restoreArtworkClipping();
        restoreProgressBars();
    }

    private static void restoreArtworkClipping() {
        for (Map.Entry<ViewGroup, ArtworkAncestorState> entry : artworkClipStates.entrySet()) {
            ViewGroup group = entry.getKey();
            ArtworkAncestorState state = entry.getValue();
            if (group != null && state != null) {
                state.restore(group);
            }
        }
        artworkClipStates.clear();

        for (Map.Entry<View, Float> entry : hiddenArtworkSiblings.entrySet()) {
            View sibling = entry.getKey();
            Float alpha = entry.getValue();
            if (sibling != null && alpha != null) {
                sibling.setAlpha(alpha);
            }
        }
        hiddenArtworkSiblings.clear();
    }

    private static void restoreProgressBars() {
        for (View bar : styledProgressBars) {
            if (bar != null) {
                if (bar.getAlpha() != 1f) {
                    bar.setAlpha(1f);
                }
                if (bar.getClipBounds() != null) {
                    bar.setClipBounds(null);
                }
            }
        }
        styledProgressBars.clear();
    }

    /**
     * Checks whether {@code candidate} is currently positioned in the left artwork slot of {@code miniplayer}.
     */
    private static boolean isViewInMiniplayerArtworkSlot(View miniplayer, int[] miniLoc, ImageView candidate) {
        if (!candidate.isShown() || candidate.getWidth() <= 0 || candidate.getHeight() <= 0) return false;
        final int miniW = miniplayer.getWidth();
        final int miniH = miniplayer.getHeight();
        if (miniW <= 0 || miniH <= 0) return false;

        View stopRoot = currentPlayerPage.get();
        if (stopRoot == null) stopRoot = miniplayer.getRootView();

        float parentScaleX = 1f;
        float parentScaleY = 1f;
        for (ViewParent p = candidate.getParent(); p instanceof View v && v != stopRoot; p = v.getParent()) {
            parentScaleX *= v.getScaleX();
            parentScaleY *= v.getScaleY();
        }

        final float baseVisualW = candidate.getWidth() * parentScaleX;
        final float baseVisualH = candidate.getHeight() * parentScaleY;
        if (baseVisualW < Dim.dp(20) || baseVisualH < Dim.dp(20) || baseVisualH > miniH + Dim.dp(24)) {
            return false;
        }

        int[] artLoc = new int[2];
        candidate.getLocationInWindow(artLoc);
        final float currentVisualW = baseVisualW * candidate.getScaleX();
        final float currentVisualH = baseVisualH * candidate.getScaleY();
        final float centerX = artLoc[0] + currentVisualW / 2f;
        final float centerY = artLoc[1] + currentVisualH / 2f;

        return centerX >= miniLoc[0] - Dim.dp(12)
                && centerX < miniLoc[0] + miniW * 0.45f
                && centerY >= miniLoc[1] - Dim.dp(16)
                && centerY <= miniLoc[1] + miniH + Dim.dp(16);
    }

    /**
     * Locates the album artwork {@link ImageView} associated with the miniplayer, checking inside
     * {@code miniplayer} first and falling back to {@code player_page}.
     */
    @Nullable
    private static ImageView findActiveMiniplayerArtworkView(View miniplayer, int[] miniLoc) {
        ImageView inMini = searchArtworkViewInTree(miniplayer, miniLoc, miniplayer, 0);
        if (inMini != null) return inMini;

        View playerPage = currentPlayerPage.get();
        if (playerPage != null && playerPage != miniplayer) {
            return searchArtworkViewInTree(miniplayer, miniLoc, playerPage, 0);
        }
        return null;
    }

    @Nullable
    private static ImageView searchArtworkViewInTree(View miniplayer, int[] miniLoc, View current, int depth) {
        if (depth > 8 || current.getVisibility() != View.VISIBLE) return null;
        if (current instanceof ImageView imageView) {
            return isViewInMiniplayerArtworkSlot(miniplayer, miniLoc, imageView) ? imageView : null;
        }
        if (current instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                ImageView found = searchArtworkViewInTree(miniplayer, miniLoc, group.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Checks whether audio playback is currently active, throttled to every 250ms.
     */
    private static boolean isMusicPlaying(Context context) {
        final long now = SystemClock.uptimeMillis();
        if (now - lastAudioCheckTimeMs > 250L) {
            lastAudioCheckTimeMs = now;
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                cachedMusicPlaying = am != null && am.isMusicActive();
            } catch (Exception ignored) {
                cachedMusicPlaying = false;
            }
        }
        return cachedMusicPlaying;
    }

    /**
     * Updates the miniplayer's album artwork (fitting it inside the glass capsule with configurable
     * padding, concentric rounded or circular vinyl corners, spinning animation when playing, and
     * dominant color extraction) and configures right-side transport button padding.
     */
    private static void styleMiniplayerChildren(View miniplayer, int horizontalInset) {
        final int[] miniLoc = new int[2];
        miniplayer.getLocationInWindow(miniLoc);
        final int miniW = miniplayer.getWidth();
        final int miniH = miniplayer.getHeight();
        if (miniW <= 0 || miniH <= 0) return;

        // Shift right-side transport buttons slightly inward so the Next button never touches or
        // overflows the curved right border of the glass capsule.
        final float buttonShiftX = -(horizontalInset + Dim.dp(8));
        if (miniplayer instanceof ViewGroup rootGroup) {
            for (int i = 0, count = rootGroup.getChildCount(); i < count; i++) {
                View child = rootGroup.getChildAt(i);
                if (child instanceof ViewGroup && child.getBackground() != null
                        && !(child.getBackground() instanceof GlassCapsuleDrawable)) {
                    child.getBackground().mutate().setAlpha(0);
                }
                if (child instanceof ImageView && child.isClickable() && child.getLeft() > miniW / 2) {
                    if (Math.abs(child.getTranslationX() - buttonShiftX) > 0.5f) {
                        child.setTranslationX(buttonShiftX);
                    }
                }
            }
        }

        if (miniplayer.getAlpha() < 0.75f) {
            resetStyledArtworkView();
            return;
        }

        ImageView artworkView = findActiveMiniplayerArtworkView(miniplayer, miniLoc);
        if (artworkView == null) {
            resetStyledArtworkView();
            return;
        }

        ImageView prevStyled = styledArtworkView.get();
        if (prevStyled != null && prevStyled != artworkView) {
            resetStyledArtworkView();
        }
        styledArtworkView = new WeakReference<>(artworkView);

        // Clear any background/foreground on the artwork view itself so dark corners never show behind the outline.
        if (artworkView.getBackground() != null) {
            artworkView.getBackground().mutate().setAlpha(0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && artworkView.getForeground() != null) {
            artworkView.getForeground().mutate().setAlpha(0);
        }

        final int artW = artworkView.getWidth();
        final int artH = artworkView.getHeight();
        if (artW <= 0 || artH <= 0) return;

        View playerPage = currentPlayerPage.get();
        View stopRoot = playerPage != null ? playerPage : miniplayer.getRootView();
        float parentScaleX = 1f;
        float parentScaleY = 1f;
        for (ViewParent p = artworkView.getParent(); p instanceof View v && v != stopRoot; p = v.getParent()) {
            parentScaleX *= v.getScaleX();
            parentScaleY *= v.getScaleY();
        }

        // Disable outline/child clipping and clear opaque black backdrops on all ancestor containers
        // between artworkView and miniplayer/playerPage so the translated or spinning artwork is
        // never sliced by a parent rounded-square outline or backed by a black box.
        View childOnArtworkPath = artworkView;
        float runningScaleX = parentScaleX;
        for (ViewParent p = artworkView.getParent(); p instanceof ViewGroup vg; p = vg.getParent()) {
            if (vg == miniplayer || vg == playerPage || vg == stopRoot) break;

            artworkClipStates.computeIfAbsent(vg, ArtworkAncestorState::new);
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
            vg.setClipToOutline(false);

            Drawable bg = vg.getBackground();
            if (bg != null && !(bg instanceof GlassCapsuleDrawable)) {
                bg.mutate().setAlpha(0);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && vg.getForeground() != null) {
                vg.getForeground().mutate().setAlpha(0);
            }

            final float vgVisualW = vg.getWidth() * runningScaleX;
            if (vgVisualW > 0 && vgVisualW < miniW * 0.55f) {
                vg.setWillNotDraw(true);
                for (int i = 0, count = vg.getChildCount(); i < count; i++) {
                    View sibling = vg.getChildAt(i);
                    if (sibling != childOnArtworkPath && sibling.getVisibility() == View.VISIBLE) {
                        hiddenArtworkSiblings.putIfAbsent(sibling, sibling.getAlpha());
                        sibling.setAlpha(0f);
                    }
                }
            }
            if (vg.getScaleX() != 0f) {
                runningScaleX /= vg.getScaleX();
            }
            childOnArtworkPath = vg;
        }

        final float baseVisualH = artH * parentScaleY;
        final int paddingPx = getMiniplayerArtworkPaddingPx();
        final int targetVisualH = Math.max(Dim.dp(24), miniH - 2 * paddingPx);
        final float desiredScale = Math.min(1f, targetVisualH / Math.max(1f, baseVisualH));

        // Center pivot explicitly before scaling and rotating.
        artworkView.setPivotX(artW / 2f);
        artworkView.setPivotY(artH / 2f);
        if (Math.abs(artworkView.getScaleX() - desiredScale) > 0.005f) {
            artworkView.setScaleX(desiredScale);
            artworkView.setScaleY(desiredScale);
        }

        int[] artLoc = new int[2];
        artworkView.getLocationInWindow(artLoc);
        final float untranslatedVisualLeft = artLoc[0] - artworkView.getTranslationX() * parentScaleX;

        // Inset artwork accounting for both user padding and capsule corner curve.
        final int cornerSafeInset = Math.max(paddingPx, Math.round(getMiniplayerCornerRadiusPx() * 0.35f));
        final float desiredVisualLeft = miniLoc[0] + horizontalInset + cornerSafeInset;
        final float neededTranslationX = Math.max(0f,
                (desiredVisualLeft - untranslatedVisualLeft) / Math.max(0.05f, parentScaleX));
        if (Math.abs(artworkView.getTranslationX() - neededTranslationX) > 0.5f) {
            artworkView.setTranslationX(neededTranslationX);
        }

        final boolean spinning = Settings.MINIPLAYER_GLASS_SPINNING_ARTWORK.get();
        final float totalScale = Math.max(0.05f, parentScaleY * desiredScale);
        final float autoArtworkRadiusPx = Math.max(Dim.dp(6), getMiniplayerCornerRadiusPx() - paddingPx);
        final float localRadius = spinning
                ? Math.min(artW, artH) / 2f
                : Math.min(Math.min(artW, artH) / 2f, autoArtworkRadiusPx / totalScale);

        if (!(artworkView.getOutlineProvider() instanceof ArtworkOutlineProvider provider)
                || Math.abs(provider.radius - localRadius) > 0.5f
                || !artworkView.getClipToOutline()) {
            artworkView.setOutlineProvider(new ArtworkOutlineProvider(localRadius));
            artworkView.setClipToOutline(true);
            artworkView.invalidateOutline();
        }

        updateArtworkRotation(artworkView, miniplayer);
    }

    private static void updateArtworkRotation(@Nullable ImageView artworkView, View miniplayer) {
        if (artworkView == null) return;
        if (Settings.MINIPLAYER_GLASS_SPINNING_ARTWORK.get()) {
            final long now = SystemClock.uptimeMillis();
            if (isMusicPlaying(miniplayer.getContext())) {
                final long dt = lastSpinFrameTimeMs == 0L ? 16L : Math.min(50L, now - lastSpinFrameTimeMs);
                lastSpinFrameTimeMs = now;
                artworkView.setRotation((artworkView.getRotation() + dt * 0.036f) % 360f);
                miniplayer.postInvalidateOnAnimation();
            } else {
                lastSpinFrameTimeMs = 0L;
            }
        } else if (artworkView.getRotation() != 0f) {
            lastSpinFrameTimeMs = 0L;
            artworkView.setRotation(0f);
        }
    }

    private static final class ArtworkOutlineProvider extends ViewOutlineProvider {
        final float radius;

        ArtworkOutlineProvider(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            final int w = view.getWidth();
            final int h = view.getHeight();
            if (w <= 0 || h <= 0) return;
            final int size = Math.min(w, h);
            final int left = (w - size) / 2;
            final int top = (h - size) / 2;
            final float effectiveRadius = Math.min(radius, size / 2f);
            outline.setRoundRect(left, top, left + size, top + size, effectiveRadius);
        }
    }

    /**
     * Hides or clips any {@link ProgressBar} or custom time bar attached to the bottom of the
     * miniplayer so it never bleeds past the rounded glass capsule corners or stays visible when hidden.
     */
    private static void styleProgressBars(View view, View miniplayer, int[] miniLoc, int horizontalInsetPx, int depth) {
        if (depth > 6 || view == null) return;
        final int miniW = miniplayer.getWidth();
        final int miniH = miniplayer.getHeight();
        if (miniW <= 0 || miniH <= 0) return;

        final String simpleName = view.getClass().getSimpleName();
        final boolean isNamedProgress = view instanceof ProgressBar
                || simpleName.contains("TimeBar")
                || simpleName.contains("ProgressBar");
        final boolean isLeafBottomBar = !(view instanceof ViewGroup)
                && !(view instanceof ImageView)
                && !(view instanceof TextView)
                && view.getHeight() > 0 && view.getHeight() <= Dim.dp(28)
                && view.getWidth() >= miniW / 2;

        if (isNamedProgress || isLeafBottomBar) {
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            final int viewBottom = loc[1] + view.getHeight();
            final int miniBottom = miniLoc[1] + miniH;
            final boolean attachedToMini = view.getParent() == miniplayer
                    || (viewBottom >= miniBottom - Dim.dp(20) && loc[1] <= miniBottom + Dim.dp(12));
            if (attachedToMini) {
                styledProgressBars.add(view);
                final boolean hide = Settings.MINIPLAYER_GLASS_HIDE_PROGRESS_BAR.get()
                        && miniplayer.getVisibility() == View.VISIBLE
                        && miniplayer.getAlpha() > 0.5f;
                if (hide) {
                    view.setAlpha(0f);
                    view.setClipBounds(EMPTY_CLIP_RECT);
                } else {
                    view.setAlpha(1f);
                    final int clipLeft = Math.min(horizontalInsetPx, view.getWidth() / 2);
                    final int clipRight = Math.max(clipLeft, view.getWidth() - horizontalInsetPx);
                    Rect currentClip = view.getClipBounds();
                    if (currentClip == null
                            || currentClip.left != clipLeft
                            || currentClip.right != clipRight
                            || currentClip.bottom != view.getHeight()) {
                        view.setClipBounds(new Rect(clipLeft, 0, clipRight, view.getHeight()));
                    }
                }
                return;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                styleProgressBars(group.getChildAt(i), miniplayer, miniLoc, horizontalInsetPx, depth + 1);
            }
        }
    }

    private static void applyMiniplayer(View miniplayer) {
        if (miniplayer == currentMiniplayer.get()) return;
        resetStyledArtworkView();
        currentMiniplayer = new WeakReference<>(miniplayer);

        MiniplayerHost host = new MiniplayerHost(miniplayer);
        GlassCapsuleDrawable glass = new GlassCapsuleDrawable(
                miniplayer, host, getMiniplayerOpacityScale(), isMiniplayerLightTint());
        currentMiniplayerGlass = new WeakReference<>(glass);
        miniplayer.setBackground(glass);
        hideMiniplayerScrim(miniplayer);

        float[] lastPlayerPosition = {Float.NaN};
        int[] lastStyleGeometry = {-1, -1, -1, -1, -1};
        int[] lastContainerMode = {-1};
        int[] miniLoc = new int[2];

        miniplayer.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (currentMiniplayer.get() == miniplayer
                    && miniplayer.getVisibility() == View.VISIBLE
                    && miniplayer.getBackground() == glass) {
                miniplayer.invalidate();
            }
        });

        miniplayer.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && currentMiniplayer.get() == miniplayer) {
                host.lastBlurRadius = -1f;
                glass.updateStyle(getMiniplayerOpacityScale(), isMiniplayerLightTint());
                glass.invalidateStyle();
                miniplayer.requestLayout();
                miniplayer.invalidate();
            }
        });

        miniplayer.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                if (currentMiniplayer.get() != miniplayer) return true;
                final boolean visible = miniplayer.getVisibility() == View.VISIBLE && miniplayer.getAlpha() > 0.01f;
                final boolean atBottom = isMiniplayerAtBottom(miniplayer);
                final int collapsedParentAlpha = (visible && atBottom)
                        ? Math.round((1f - Math.min(1f, miniplayer.getAlpha())) * 255f)
                        : 255;

                // When the miniplayer is collapsed at the bottom, fade out the solid player container
                // background so the browse page behind it shows around the rounded capsule corners.
                // As soon as the player starts expanding or is in full-screen mode, restore alpha to 255
                // so the full-screen song/lyrics/queue interface is always completely opaque.
                for (ViewParent p = miniplayer.getParent(); p instanceof View parent; p = parent.getParent()) {
                    final boolean isPlayerPage = parent.getId() == PLAYER_PAGE;
                    Drawable parentBg = parent.getBackground();
                    if (parentBg != null && parentBg != glass) {
                        parentBg.mutate().setAlpha(collapsedParentAlpha);
                    }
                    if (isPlayerPage) {
                        currentPlayerPage = new WeakReference<>(parent);
                        if (!NAV_BAR_ENABLED) {
                            if (visible && atBottom && miniplayer.getAlpha() > 0.9f) {
                                final int clipBottom = miniplayer.getBottom();
                                Rect current = parent.getClipBounds();
                                if (current == null || current.bottom != clipBottom || current.right != parent.getWidth()) {
                                    playerClip.set(0, 0, parent.getWidth(), clipBottom);
                                    parent.setClipBounds(playerClip);
                                }
                            } else if (parent.getClipBounds() != null) {
                                parent.setClipBounds(null);
                            }
                        }
                        break;
                    }
                }

                final int containerMode = atBottom ? 1 : 0;
                if (!visible || lastContainerMode[0] != containerMode) {
                    resetStyledArtworkView();
                    lastContainerMode[0] = containerMode;
                    if (!visible) {
                        lastStyleGeometry[0] = -1;
                        return true;
                    }
                }
                if (miniplayer.getBackground() != glass) {
                    miniplayer.setBackground(glass);
                }

                final int miniHash = computeMiniplayerSettingsHash();
                if (miniHash != lastMiniplayerSettingsHash) {
                    lastMiniplayerSettingsHash = miniHash;
                    host.lastBlurRadius = -1f;
                    glass.updateStyle(getMiniplayerOpacityScale(), isMiniplayerLightTint());
                    glass.invalidateStyle();
                    miniplayer.invalidate();
                }

                final int horizontalInset = host.getHorizontalInset();
                miniplayer.getLocationInWindow(miniLoc);
                final int progressInsetPx = horizontalInset + getMiniplayerCornerRadiusPx();
                styleProgressBars(miniplayer, miniplayer, miniLoc, progressInsetPx, 0);
                View playerPage = currentPlayerPage.get();
                if (playerPage != null && playerPage != miniplayer) {
                    styleProgressBars(playerPage, miniplayer, miniLoc, progressInsetPx, 0);
                }

                ImageView artworkView = styledArtworkView.get();
                final boolean artworkNeedsLookup = artworkView == null || !artworkView.isShown()
                        || artworkView.getWidth() <= 0 || artworkView.getHeight() <= 0;
                final boolean geometryChanged = lastStyleGeometry[0] != miniplayer.getWidth()
                        || lastStyleGeometry[1] != miniplayer.getHeight()
                        || lastStyleGeometry[2] != horizontalInset
                        || lastStyleGeometry[3] != miniHash
                        || lastStyleGeometry[4] != containerMode;
                if (geometryChanged || artworkNeedsLookup) {
                    lastStyleGeometry[0] = miniplayer.getWidth();
                    lastStyleGeometry[1] = miniplayer.getHeight();
                    lastStyleGeometry[2] = horizontalInset;
                    lastStyleGeometry[3] = miniHash;
                    lastStyleGeometry[4] = containerMode;
                    styleMiniplayerChildren(miniplayer, horizontalInset);
                    artworkView = styledArtworkView.get();
                }
                updateArtworkRotation(artworkView, miniplayer);
                hideMiniplayerScrim(miniplayer);

                final float playerPosition = playerPage == null
                        ? miniplayer.getTranslationY()
                        : playerPage.getTop() + playerPage.getTranslationY();
                if (playerPosition != lastPlayerPosition[0]) {
                    lastPlayerPosition[0] = playerPosition;
                    miniplayer.invalidate();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Glass miniplayer pre draw failure", ex);
            }
            return true;
        });
    }

    /**
     * Host for the bottom navigation bar capsule.
     */
    private static final class CapsuleHost implements GlassCapsuleDrawable.Host {

        private final ViewGroup pivotBar;
        private final Rect capsule = new Rect();
        @Nullable
        private Object blurNode;
        private float lastBlurRadius = -1f;
        private final int[] barLocation = new int[2];
        private final int[] sourceLocation = new int[2];

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
            if (Settings.NAVIGATION_BAR_GLASS_HIDE_INDICATOR.get()) {
                return null;
            }
            return getSelectedTab(getTabs(pivotBar));
        }

        @Override
        public float getRimScale() {
            return getNavBarRimScale();
        }

        @Override
        public int getCustomTintColor() {
            if (Settings.NAVIGATION_BAR_GLASS_ALBUM_TINT.get()) {
                return getResolvedAlbumColor();
            }
            return 0;
        }

        @Override
        public void drawBackdrop(Canvas canvas) {
            View content = currentContent.get();
            if (!glassActive || content == null
                    || !(content.getParent() instanceof View source) || source.getWidth() <= 0) return;
            blurNode = drawBlurredBackdrop(canvas, pivotBar, source, getNavBarBlurRadiusPx(),
                    blurNode, lastBlurRadius, barLocation, sourceLocation, "GlassNavigationBar");
            lastBlurRadius = getNavBarBlurRadiusPx();
        }
    }

    /**
     * Host for the floating glass miniplayer capsule.
     */
    private static final class MiniplayerHost implements GlassCapsuleDrawable.Host {
        private final View miniplayer;
        @Nullable
        private Object blurNode;
        private float lastBlurRadius = -1f;
        private final int[] miniplayerLocation = new int[2];
        private final int[] contentLocation = new int[2];

        MiniplayerHost(View miniplayer) {
            this.miniplayer = miniplayer;
        }

        int getHorizontalInset() {
            View rootView = miniplayer.getRootView();
            final boolean fullWidth = rootView != null
                    && rootView.getWidth() - miniplayer.getWidth() < MARGIN_HORIZONTAL;
            return fullWidth ? MARGIN_HORIZONTAL : 0;
        }

        @Override
        public void getCapsule(Rect bounds, RectF out) {
            final int horizontalInset = getHorizontalInset();
            out.set(bounds.left + horizontalInset, bounds.top,
                    bounds.right - horizontalInset, bounds.bottom);
        }

        @Nullable
        @Override
        public View getSelectedTabView() {
            return null;
        }

        @Override
        public float getCornerRadius(RectF capsule) {
            return Math.min(getMiniplayerCornerRadiusPx(), capsule.height() / 2f);
        }

        @Override
        public float getRimScale() {
            return getMiniplayerRimScale();
        }

        @Override
        public int getCustomTintColor() {
            return Settings.MINIPLAYER_GLASS_ALBUM_TINT.get() ? getResolvedAlbumColor() : 0;
        }

        @Override
        public void drawBackdrop(Canvas canvas) {
            if (!isMiniplayerAtBottom(miniplayer)) return;
            View content = currentContent.get();
            if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return;
            blurNode = drawBlurredBackdrop(canvas, miniplayer, content, getMiniplayerBlurRadiusPx(),
                    blurNode, lastBlurRadius, miniplayerLocation, contentLocation, "GlassMiniplayer");
            lastBlurRadius = getMiniplayerBlurRadiusPx();
        }
    }

    @Nullable
    private static Object drawBlurredBackdrop(
            Canvas canvas, View target, View source, float blurRadius,
            @Nullable Object existingNode, float lastRadius,
            int[] targetLoc, int[] sourceLoc, String nodeName) {
        if (inBackdropDraw || blurRadius <= 0f
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !canvas.isHardwareAccelerated()) {
            return existingNode;
        }
        final int width = target.getWidth();
        final int height = target.getHeight();
        if (width <= 0 || height <= 0) return existingNode;

        final int background = getPageBackgroundColor(source);
        try {
            RenderNode node = existingNode instanceof RenderNode r ? r : new RenderNode(nodeName);
            if (blurRadius != lastRadius) {
                node.setRenderEffect(RenderEffect.createChainEffect(
                        RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(FROSTED_GLASS_COLORS)),
                        RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)));
            }
            target.getLocationInWindow(targetLoc);
            source.getLocationInWindow(sourceLoc);
            node.setPosition(0, 0, width, height);
            RecordingCanvas recording = node.beginRecording(width, height);
            inBackdropDraw = true;
            try {
                recording.drawColor(background);
                recording.translate(sourceLoc[0] - targetLoc[0], sourceLoc[1] - targetLoc[1]);
                source.draw(recording);
            } finally {
                inBackdropDraw = false;
                node.endRecording();
            }
            canvas.drawRenderNode(node);
            return node;
        } catch (Throwable t) {
            Logger.printException(() -> nodeName + " backdrop draw failure", t);
            return existingNode;
        }
    }
}
