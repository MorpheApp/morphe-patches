/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.glassmenus;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Draws the menus of the app as frosted glass: the bottom sheets (the menu of a video, the player settings,
 * the share sheet), the popup menus, the sheet to save a video to a playlist, the dialogs that are a panel at the
 * bottom of the screen (the video quality, the download quality) and the sheets of Morphe
 * (playback speed, voice over translation).
 * <p>
 * Only the content behind the menu is blurred ({@link BlurRegion}), and the menu is a translucent tint of the app
 * background with a light rim, so the blurred content shows through it. The rest of the screen is only dimmed, as
 * without the glass. The system turns the blur off when the device does not support it or while the battery saver
 * is on; the menu is then nearly opaque, so it stays readable.
 * <p>
 * The content of the menu keeps its views. Backgrounds inside the menu that have the color of the menu are removed,
 * so they do not hide the glass. Items keep their ripples and their selected state.
 */
@SuppressWarnings("unused")
public final class GlassMenusPatch {

    /**
     * How opaque the menu is at least, in percent, while the content behind it is not blurred.
     */
    static final int MIN_OPACITY_WITHOUT_BLUR = 92;

    private static final int DESIGN_BOTTOM_SHEET = ResourceUtils.getIdentifier(ResourceType.ID, "design_bottom_sheet");
    private static final int PLAYLIST_LIST = ResourceUtils.getIdentifier(ResourceType.ID, "list");
    private static final int PLAYLIST_CLOSE_BAR =
            ResourceUtils.getIdentifier(ResourceType.ID, "add_to_playlist_bottom_sheet_close_button");

    /**
     * Theme colors the app gives the background of its menus.
     */
    private static final int[] MENU_COLOR_ATTRIBUTES = {
            ResourceUtils.getIdentifier(ResourceType.ATTR, "ytRaisedBackground"),
            ResourceUtils.getIdentifier(ResourceType.ATTR, "ytBaseBackground"),
            ResourceUtils.getIdentifier(ResourceType.ATTR, "ytMenuBackground"),
            ResourceUtils.getIdentifier(ResourceType.ATTR, "ytAdditiveBackground"),
    };

    /**
     * Corner radius of a menu if its background does not give one.
     */
    private static final float DEFAULT_CORNER_RADIUS = Dim.dp(12);

    /**
     * How much a background color may differ from the color of the menu, per channel, to be the color of the menu.
     */
    private static final int COLOR_TOLERANCE = 8;

    /**
     * Rounds the corners that are not at an edge of the screen.
     */
    private static final int CORNERS_AUTO = 0;
    /**
     * Rounds the top corners only.
     */
    private static final int CORNERS_TOP = 1;
    /**
     * No round corners.
     */
    private static final int CORNERS_NONE = 2;

    /**
     * Views that are drawn as glass. Each view keeps its {@link GlassSurface} as its attach listener.
     */
    private static final Set<View> glassViews = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * How often the drawables of Litho hosts are checked while a menu is shown, as finding them uses reflection.
     */
    private static final long LITHO_SCAN_INTERVAL_MS = 150;

    /**
     * How long the drawables of Litho hosts are checked on every frame after a menu is attached.
     */
    private static final long LITHO_SCAN_EVERY_FRAME_MS = 1000;

    private static final PorterDuffColorFilter TRANSPARENT_FILTER =
            new PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC);

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * The blur behind a menu needs Android 12.
     */
    public static final class GlassMenusAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        }
    }

    private static boolean isEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Settings.GLASS_MENUS.get();
    }

    private static int opacityPercent() {
        return SeekBarPreference.clampToRange(Settings.GLASS_MENUS_OPACITY);
    }

    private static int blurRadiusPx() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.GLASS_MENUS_BLUR));
    }

    private static float dimAmount() {
        return SeekBarPreference.clampToRange(Settings.GLASS_MENUS_DIM) / 100f;
    }

    /**
     * Injection point: a bottom sheet dialog was attached to its window.
     */
    public static void onBottomSheetDialog(@Nullable Dialog dialog) {
        if (dialog == null || !isEnabled()) return;

        try {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setDimAmount(dimAmount());
            }

            View sheet = dialog.findViewById(DESIGN_BOTTOM_SHEET);
            if (sheet != null) {
                GlassSurface.apply(sheet, CORNERS_AUTO);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "onBottomSheetDialog failure", ex);
        }
    }

    /**
     * Injection point: a popup menu, before it is shown. The app can set its content view after this call.
     */
    public static void onPopupWindow(@Nullable PopupWindow popup) {
        if (popup == null || !isEnabled()) return;

        Utils.runOnMainThreadDelayed(() -> {
            try {
                View content = popup.getContentView();
                if (content == null) return;

                // The background view the popup adds around the content if it has a background,
                // otherwise the content itself.
                View surface = content.getParent() instanceof View parent && parent.getBackground() != null
                        ? parent
                        : content;
                GlassSurface.apply(surface, CORNERS_AUTO);
            } catch (Throwable ex) {
                Logger.printException(() -> "onPopupWindow failure", ex);
            }
        }, 0);
    }

    /**
     * Injection point: the view of the sheet to save a video to a playlist, which is not a bottom sheet dialog.
     */
    public static void onAddToPlaylistView(@Nullable View root) {
        if (root == null || !isEnabled()) return;

        try {
            View list = root.findViewById(PLAYLIST_LIST);
            if (list != null) {
                GlassSurface.apply(list, CORNERS_TOP);
            }
            View closeBar = root.findViewById(PLAYLIST_CLOSE_BAR);
            if (closeBar != null) {
                GlassSurface.apply(closeBar, CORNERS_NONE);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "onAddToPlaylistView failure", ex);
        }
    }

    /**
     * Injection point: a dialog of a dialog fragment of the app was shown.
     * <p>
     * Some menus, such as the video quality and the download quality, are a dialog whose window is a panel at the
     * bottom of the screen, with the color of a menu as the window background. The window background is removed,
     * and the content of the window is drawn as glass. Dialogs that fill the screen keep their look.
     */
    public static void onDialogShown(@Nullable Dialog dialog) {
        if (dialog == null || !isEnabled()) return;

        try {
            Window window = dialog.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            // After the fragment sets the window background, right after it shows the dialog.
            decor.post(() -> styleWindowPanel(window, decor));
        } catch (Throwable ex) {
            Logger.printException(() -> "onDialogShown failure", ex);
        }
    }

    private static void styleWindowPanel(Window window, View decor) {
        try {
            if (window.getAttributes().height != WindowManager.LayoutParams.WRAP_CONTENT) return;

            View content = decor.findViewById(android.R.id.content);
            Drawable background = decor.getBackground();
            if (content == null || background == null || glassViews.contains(content)) return;
            if (!isMenuColor(decor.getContext(), SolidColors.colorOf(background))) return;

            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(dimAmount());
            GlassSurface.apply(content, CORNERS_AUTO);
        } catch (Throwable ex) {
            Logger.printException(() -> "styleWindowPanel failure", ex);
        }
    }

    /**
     * Injection point: a sheet of Morphe ({@code SheetBottomDialog.createSlideDialog}) was created.
     * Its panel is the first view with a filled round background ({@code SheetBottomDialog.createMainLayout}).
     */
    public static void onSlideDialog(@Nullable Dialog dialog) {
        if (dialog == null || !isEnabled()) return;

        try {
            Window window = dialog.getWindow();
            if (window == null) return;

            Deque<View> queue = new ArrayDeque<>();
            queue.add(window.getDecorView());
            while (!queue.isEmpty()) {
                View view = queue.poll();
                if (view.getBackground() instanceof ShapeDrawable shape
                        && Color.alpha(shape.getPaint().getColor()) == 255) {
                    GlassSurface.apply(view, CORNERS_AUTO);
                    return;
                }
                if (view instanceof ViewGroup group) {
                    for (int i = 0, count = group.getChildCount(); i < count; i++) {
                        queue.add(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "onSlideDialog failure", ex);
        }
    }

    /**
     * @return The colors the app and Morphe give the background of their menus.
     */
    private static List<Integer> menuColors(Context context) {
        List<Integer> colors = new ArrayList<>();
        for (int attribute : MENU_COLOR_ATTRIBUTES) {
            Integer color = resolveThemeColor(context, attribute);
            if (color != null) colors.add(color);
        }
        colors.add(ThemeUtils.getAppBackgroundColor());
        colors.add(ThemeUtils.getDialogBackgroundColor());
        return colors;
    }

    private static boolean isMenuColor(Context context, @ColorInt int color) {
        for (int menu : menuColors(context)) {
            if (isSimilarOpaqueColor(color, menu)) return true;
        }
        return false;
    }

    /**
     * @return The corner radius of the background the app gave the menu, or the default radius.
     */
    private static float cornerRadiusOf(@Nullable Drawable background) {
        if (background != null && !background.getBounds().isEmpty()) {
            Outline outline = new Outline();
            background.getOutline(outline);
            final float radius = outline.getRadius();
            if (radius > 0) return radius;
        }
        return DEFAULT_CORNER_RADIUS;
    }

    private static boolean isSimilarOpaqueColor(@ColorInt int color, @ColorInt int other) {
        return Color.alpha(color) >= 0xF0
                && Math.abs(Color.red(color) - Color.red(other)) <= COLOR_TOLERANCE
                && Math.abs(Color.green(color) - Color.green(other)) <= COLOR_TOLERANCE
                && Math.abs(Color.blue(color) - Color.blue(other)) <= COLOR_TOLERANCE;
    }

    @Nullable
    private static Integer resolveThemeColor(Context context, int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) return null;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            try {
                return context.getColor(value.resourceId);
            } catch (Resources.NotFoundException ex) {
                Logger.printDebug(() -> "Theme attribute is not a color: " + attribute, ex);
            }
        }
        return null;
    }

    /**
     * A view of a menu drawn as glass, from when it is attached to its window until it is detached.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class GlassSurface implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnPreDrawListener {

        private final View view;
        private final int corners;
        private final GlassDrawable tint;
        private final Consumer<Boolean> blurListener = this::onBlurEnabledChanged;

        /**
         * Colors of the menu, a background of one of these colors inside the menu is removed.
         */
        private final List<Integer> menuColors = new ArrayList<>();
        /**
         * The background the app gave the menu, until its color is known.
         */
        @Nullable
        private Drawable originalBackground;
        /**
         * Backgrounds and drawables of Litho hosts that are hidden with a color filter, shown again when the menu
         * is detached. They are never removed: the app can use the background of a view it set.
         */
        private final Set<Drawable> hiddenDrawables = Collections.newSetFromMap(new IdentityHashMap<>());
        private long lastLithoScan;
        /**
         * When the menu was attached. Litho is checked on every frame right after, so the menu is not shown
         * opaque before the glass, and then less often.
         */
        private long attachedTime;

        @Nullable
        private Drawable blur;
        @Nullable
        private WindowManager windowManager;
        private boolean blurEnabled;
        private boolean roundBottom = true;
        private boolean roundTop = true;
        private final int[] location = new int[2];

        static void apply(View view, int corners) {
            if (glassViews.add(view)) {
                new GlassSurface(view, corners);
            }
        }

        private GlassSurface(View view, int corners) {
            this.view = view;
            this.corners = corners;

            originalBackground = view.getBackground();
            tint = new GlassDrawable(corners == CORNERS_NONE ? 0 : cornerRadiusOf(originalBackground));
            updateOpacity();

            menuColors.addAll(menuColors(view.getContext()));
            sampleOriginalBackground();

            view.setBackground(tint);
            if (corners != CORNERS_NONE) {
                view.setClipToOutline(true);
            }
            view.addOnAttachStateChangeListener(this);
            if (view.isAttachedToWindow()) {
                onViewAttachedToWindow(view);
            }
        }

        private void sampleOriginalBackground() {
            if (originalBackground == null) return;
            final int color = SolidColors.colorOf(originalBackground);
            if (Color.alpha(color) != 0) {
                menuColors.add(color);
                originalBackground = null;
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View attached) {
            try {
                // The blur belongs to the window the view is attached to.
                windowManager = view.getContext().getSystemService(WindowManager.class);
                blur = BlurRegion.create(view);
                if (blur != null) {
                    view.setBackground(new LayerDrawable(new Drawable[]{blur, tint}));
                    if (BlurRegion.isSamsungWindowBlurSupported()) {
                        onBlurEnabledChanged(true);
                    } else {
                        BlurRegion.setBlurRadius(blur, 0);
                        // Called right away with the current state, and again when the system turns the blur
                        // on or off.
                        windowManager.addCrossWindowBlurEnabledListener(blurListener);
                    }
                }
                updateCorners(true);
                attachedTime = SystemClock.uptimeMillis();
                view.getViewTreeObserver().addOnPreDrawListener(this);
                if (Logger.isDebugEnabled()) {
                    view.postDelayed(this::logViewTree, 800);
                }
            } catch (Throwable ex) {
                Logger.printException(() -> "GlassSurface attach failure", ex);
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View detached) {
            try {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                for (Drawable drawable : hiddenDrawables) {
                    drawable.setColorFilter(null);
                }
                hiddenDrawables.clear();
                if (blur != null) {
                    if (windowManager != null && !BlurRegion.isSamsungWindowBlurSupported()) {
                        windowManager.removeCrossWindowBlurEnabledListener(blurListener);
                    }
                    blur = null;
                    blurEnabled = false;
                    view.setBackground(tint);
                    updateOpacity();
                }
                windowManager = null;
            } catch (Throwable ex) {
                Logger.printException(() -> "GlassSurface detach failure", ex);
            }
        }

        private void onBlurEnabledChanged(boolean enabled) {
            blurEnabled = enabled;
            if (blur != null) {
                BlurRegion.setBlurRadius(blur, enabled ? blurRadiusPx() : 0);
            }
            updateOpacity();
        }

        private void updateOpacity() {
            tint.setOpacityPercent(blurEnabled && blur != null
                    ? opacityPercent()
                    : Math.max(opacityPercent(), MIN_OPACITY_WITHOUT_BLUR));
        }

        @Override
        public boolean onPreDraw() {
            try {
                if (originalBackground != null) {
                    sampleOriginalBackground();
                }
                updateCorners(false);
                final long now = SystemClock.uptimeMillis();
                final boolean scanLitho = now - attachedTime < LITHO_SCAN_EVERY_FRAME_MS
                        || now - lastLithoScan >= LITHO_SCAN_INTERVAL_MS;
                if (scanLitho) lastLithoScan = now;
                removeMenuBackgrounds(view, scanLitho);
            } catch (Throwable ex) {
                Logger.printException(() -> "GlassSurface onPreDraw failure", ex);
            }
            return true;
        }

        /**
         * A menu at the bottom of the screen has round top corners only, a floating menu has all corners round.
         */
        private void updateCorners(boolean force) {
            boolean top = corners != CORNERS_NONE;
            boolean bottom = false;
            if (corners == CORNERS_AUTO) {
                if (windowManager == null) return;
                view.getLocationOnScreen(location);
                final int screenBottom = windowManager.getCurrentWindowMetrics().getBounds().bottom;
                bottom = location[1] + view.getHeight() < screenBottom - Dim.dp(2);
            }
            if (!force && top == roundTop && bottom == roundBottom) return;

            roundTop = top;
            roundBottom = bottom;
            tint.setRoundCorners(top, bottom);
            view.invalidateOutline();
            if (blur != null) {
                final float radius = tint.radius;
                BlurRegion.setCornerRadius(blur, top ? radius : 0, bottom ? radius : 0);
            }
        }

        private void removeMenuBackgrounds(View parent, boolean scanLitho) {
            if (!(parent instanceof ViewGroup group)) return;

            final int minWidth = view.getWidth() / 2;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                try {
                    Drawable background = child.getBackground();
                    // Only fills of the menu, not small views such as buttons or a progress circle.
                    if (background != null && child.getWidth() >= minWidth) {
                        hideIfMenuColor(background);
                    }
                    if (scanLitho && LithoDrawables.isHost(child)) {
                        boolean hidden = false;
                        for (Drawable drawable : LithoDrawables.drawablesOf(child)) {
                            if (drawable.getBounds().width() >= minWidth) {
                                hidden |= hideIfMenuColor(drawable);
                            }
                        }
                        if (hidden) child.invalidate();
                    }
                } catch (Throwable ex) {
                    Logger.printDebug(() -> "Menu background check failed: " + child + ": " + ex);
                }
                removeMenuBackgrounds(child, scanLitho);
            }
        }

        /**
         * Hides a drawable of the color of the menu with a transparent color filter. The drawable itself is not
         * changed, as the app can draw it again elsewhere, and the filter is removed when the menu is detached.
         *
         * @return If the drawable was hidden now.
         */
        private boolean hideIfMenuColor(Drawable drawable) {
            // Checked every time: Litho can mount another drawable in the same wrapper.
            List<Drawable> chain = new ArrayList<>();
            if (!isMenuColor(SolidColors.colorOf(drawable, chain))) return false;

            // A wrapper may not pass the filter on, so every drawable down to the color gets it.
            boolean hidden = false;
            for (Drawable link : chain) {
                if (hiddenDrawables.add(link)) {
                    link.setColorFilter(TRANSPARENT_FILTER);
                    hidden = true;
                }
            }
            if (hidden) drawable.invalidateSelf();
            return hidden;
        }

        /**
         * Writes the views of the menu to the debug log, to find backgrounds that are not removed.
         */
        private void logViewTree() {
            try {
                StringBuilder builder = new StringBuilder("Glass menu views:\n");
                appendViewTree(builder, view, 0);
                Logger.printDebug(builder::toString);
            } catch (Throwable ex) {
                Logger.printException(() -> "logViewTree failure", ex);
            }
        }

        private void appendViewTree(StringBuilder builder, View current, int depth) {
            if (depth > 12) return;
            for (int i = 0; i < depth; i++) builder.append("  ");
            builder.append(current.getClass().getName());
            if (current.getId() != View.NO_ID) {
                try {
                    builder.append(" #").append(current.getResources().getResourceEntryName(current.getId()));
                } catch (Resources.NotFoundException ex) {
                    // A generated id.
                    builder.append(" #").append(Integer.toHexString(current.getId()));
                }
            }
            builder.append(' ').append(current.getWidth()).append('x').append(current.getHeight());
            appendDrawable(builder, "bg", current.getBackground());
            if (LithoDrawables.isHost(current)) {
                for (Drawable drawable : LithoDrawables.drawablesOf(current)) {
                    appendDrawable(builder, "litho", drawable);
                }
            }
            builder.append('\n');
            if (current instanceof ViewGroup group) {
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    appendViewTree(builder, group.getChildAt(i), depth + 1);
                }
            }
        }

        private void appendDrawable(StringBuilder builder, String kind, @Nullable Drawable drawable) {
            if (drawable == null) return;
            builder.append(' ').append(kind).append('=');
            try {
                List<Drawable> chain = new ArrayList<>();
                final int color = SolidColors.colorOf(drawable, chain);
                for (int i = 0; i < chain.size(); i++) {
                    if (i > 0) builder.append('>');
                    builder.append(chain.get(i).getClass().getSimpleName());
                }
                builder.append(String.format(Locale.US, "(#%08X %dx%d%s)", color, drawable.getBounds().width(),
                        drawable.getBounds().height(), hiddenDrawables.contains(drawable) ? " hidden" : ""));
            } catch (Exception ex) {
                builder.append(drawable.getClass().getSimpleName()).append("(?)");
                Logger.printDebug(() -> "Drawable color check failed: " + drawable, ex);
            }
        }

        private boolean isMenuColor(@ColorInt int color) {
            for (int menu : menuColors) {
                if (isSimilarOpaqueColor(color, menu)) return true;
            }
            return false;
        }
    }

    /**
     * A translucent tint of the app background with rounded corners and a light rim.
     */
    private static final class GlassDrawable extends Drawable {
        private static final float RIM_WIDTH = Dim.dp(1);

        final float radius;
        private boolean roundTop = true;
        private boolean roundBottom = true;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path fillPath = new Path();
        private final Path rimPath = new Path();
        private final RectF rect = new RectF();
        private int opacityPercent = 100;

        GlassDrawable(float radius) {
            this.radius = radius;

            final boolean dark = Utils.isDarkModeEnabled();
            final int background = ThemeUtils.getAppBackgroundColor();
            // A menu is raised above the page, so it is a little lighter than the page, as in the app.
            fill.setColor(blend(background, Color.WHITE, dark ? 0.08f : 0.6f));

            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(RIM_WIDTH);
            rim.setColor(dark ? Color.WHITE : Color.BLACK);
            rim.setAlpha(dark ? 0x30 : 0x18);
        }

        void setOpacityPercent(int percent) {
            opacityPercent = Math.max(0, Math.min(100, percent));
            fill.setAlpha(255 * opacityPercent / 100);
            invalidateSelf();
        }

        void setRoundCorners(boolean top, boolean bottom) {
            roundTop = top;
            roundBottom = bottom;
            updatePaths();
            invalidateSelf();
        }

        private static int blend(int color, int with, float amount) {
            final float keep = 1 - amount;
            return Color.rgb(
                    (int) (Color.red(color) * keep + Color.red(with) * amount),
                    (int) (Color.green(color) * keep + Color.green(with) * amount),
                    (int) (Color.blue(color) * keep + Color.blue(with) * amount));
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            updatePaths();
        }

        private void updatePaths() {
            Rect bounds = getBounds();
            final float top = roundTop ? radius : 0;
            final float bottom = roundBottom ? radius : 0;

            rect.set(bounds);
            fillPath.reset();
            fillPath.addRoundRect(rect, new float[]{top, top, top, top, bottom, bottom, bottom, bottom},
                    Path.Direction.CW);

            // The rim is drawn inside the menu, so it is not cut by the outline.
            final float inset = RIM_WIDTH / 2;
            final float rimTop = Math.max(0, top - inset);
            final float rimBottom = Math.max(0, bottom - inset);
            rect.inset(inset, inset);
            rimPath.reset();
            rimPath.addRoundRect(rect, new float[]{rimTop, rimTop, rimTop, rimTop, rimBottom, rimBottom, rimBottom,
                    rimBottom}, Path.Direction.CW);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawPath(fillPath, fill);
            canvas.drawPath(rimPath, rim);
        }

        @Override
        public void getOutline(@NonNull Outline outline) {
            Rect bounds = getBounds();
            if (!roundTop && !roundBottom) {
                outline.setRect(bounds);
            } else if (roundTop && !roundBottom) {
                // Round top corners only: the outline reaches below the bottom of the menu.
                outline.setRoundRect(bounds.left, bounds.top, bounds.right,
                        bounds.bottom + (int) Math.ceil(radius), radius);
            } else if (!roundTop) {
                outline.setRoundRect(bounds.left, bounds.top - (int) Math.ceil(radius), bounds.right,
                        bounds.bottom, radius);
            } else {
                outline.setRoundRect(bounds, radius);
            }
            outline.setAlpha(opacityPercent / 100f);
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

    private GlassMenusPatch() {
    }
}
