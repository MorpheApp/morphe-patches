/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5411
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackFirst;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackSecond;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.utils.ComposeUtils;
import app.morphe.extension.reddit.utils.ComposeUtils.ContentDrawScope;
import app.morphe.extension.reddit.utils.ComposeUtils.Modifier;
import app.morphe.extension.shared.Logger;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/**
 * Moves the Home feed tabs into a glass capsule in the top bar row.
 */
final class GlassNavigationBarTopTabs {

    private static final class TopTabsDraw implements Function1<ContentDrawScope, Object> {
        private static final TopTabsDraw INSTANCE = new TopTabsDraw();

        private TopTabsDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                if (GlassNavigationBarPatch.isTopTabsActive()) {
                    drawTabsCapsule(scope);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "TopTabsDraw failure", ex);
            } finally {
                scope.patch_drawContent();
            }
            return null;
        }
    }

    private static final class BelowWrapper implements Function2<Object, Object, Object> {
        private final Function2<Object, Object, Object> original;

        private BelowWrapper(Function2<Object, Object, Object> original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object composer, Object changed) {
            if (GlassNavigationBarPatch.isTopTabsActive() && !Boolean.TRUE.equals(IN_ROW.get())) {
                return null;
            }
            return original.invoke(composer, changed);
        }
    }

    private static final class TabPillDraw implements Function1<ContentDrawScope, Object> {
        private static final TabPillDraw INSTANCE = new TabPillDraw();

        private TabPillDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                if (GlassNavigationBarPatch.isTopTabsActive()) {
                    drawTabPill(scope);
                    return null;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "TabPillDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    private static final float TABS_WEIGHT = 1000f;
    private static final float HEADER_TABS_HEIGHT_DP = 8f;
    private static final float CAPSULE_MARGIN_DP = 4f;
    private static final float PILL_INSET_X_DP = 2f;
    private static final float PILL_INSET_Y_DP = 4f;
    private static final float LABEL_FONT_SIZE_SP = 13f;
    private static final float LABEL_LINE_HEIGHT_SP = 17f;
    private static final int TEXT_DEFAULT_BITS = (1 << 3) | (1 << 10);
    private static final float TAB_ITEM_CORNER_DP = 100f;

    private static final Map<Function2<?, ?, ?>, WeakReference<BelowWrapper>> BELOW_WRAPPERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Modifier>> WRAPPED_TO_ORIG =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> IN_ROW = new ThreadLocal<>();

    @Nullable
    private static volatile WeakReference<Function2<Object, Object, Object>> currentBelow;
    @Nullable
    private static volatile WeakReference<Object> pagerRef;
    @Nullable
    private static volatile Object cachedWrappedModifier;
    @Nullable
    private static volatile Object cachedItemShape;
    private static volatile boolean tabsInRow;
    private static volatile float tabsHeightPx;
    private static volatile float capsuleHeightPx;

    private GlassNavigationBarTopTabs() {
    }

    @SuppressWarnings("unchecked")
    @Nullable
    static Function2<?, ?, ?> getBelow(@Nullable Function2<?, ?, ?> original) {
        if (original == null) {
            return null;
        }
        if (original instanceof BelowWrapper wrapper) {
            currentBelow = new WeakReference<>(wrapper.original);
            return original;
        }
        if (!GlassNavigationBarPatch.isTopTabsActive()) {
            return original;
        }

        Function2<Object, Object, Object> typed = (Function2<Object, Object, Object>) original;
        BelowWrapper wrapper;
        synchronized (BELOW_WRAPPERS) {
            WeakReference<BelowWrapper> ref = BELOW_WRAPPERS.get(original);
            wrapper = ref != null ? ref.get() : null;
            if (wrapper == null) {
                wrapper = new BelowWrapper(typed);
                BELOW_WRAPPERS.put(original, new WeakReference<>(wrapper));
            }
        }
        currentBelow = new WeakReference<>(typed);
        return wrapper;
    }

    static void inlineTabs(@Nullable Object composer) {
        if (!GlassNavigationBarPatch.isTopTabsActive() || composer == null) {
            return;
        }

        WeakReference<Function2<Object, Object, Object>> ref = currentBelow;
        Function2<Object, Object, Object> below = ref != null ? ref.get() : null;
        if (below == null) {
            tabsInRow = false;
            return;
        }

        IN_ROW.set(Boolean.TRUE);
        try {
            below.invoke(composer, 0);
        } finally {
            IN_ROW.remove();
        }
    }

    @Nullable
    static Object getModifier(@Nullable Modifier modifier) {
        if (modifier == null) {
            return null;
        }
        if (!GlassNavigationBarPatch.isTopTabsActive()) {
            WeakReference<Modifier> origRef;
            synchronized (WRAPPED_TO_ORIG) {
                origRef = WRAPPED_TO_ORIG.get(modifier);
            }
            Modifier orig = origRef != null ? origRef.get() : null;
            return orig != null ? orig : modifier;
        }

        if (Boolean.TRUE.equals(IN_ROW.get())) {
            tabsInRow = true;
        }

        synchronized (WRAPPED_TO_ORIG) {
            if (WRAPPED_TO_ORIG.containsKey(modifier)) {
                return modifier;
            }
        }

        // Without the original modifier, the tab bar keeps its full height in the top bar row.
        Object wrapped = cachedWrappedModifier;
        if (wrapped == null) {
            Object weight = ComposeUtils.newWeightModifier(TABS_WEIGHT, true);
            if (!(weight instanceof Modifier weightModifier)) {
                return modifier;
            }
            wrapped = weightModifier.patch_drawWithContent(TopTabsDraw.INSTANCE);
            cachedWrappedModifier = wrapped;
        }
        synchronized (WRAPPED_TO_ORIG) {
            WRAPPED_TO_ORIG.put(wrapped, new WeakReference<>(modifier));
        }
        return wrapped;
    }

    static boolean isTabsInRow() {
        return tabsInRow;
    }

    static float getHeaderHeight(float original) {
        return GlassNavigationBarPatch.isTopTabsActive() ? HEADER_TABS_HEIGHT_DP : original;
    }

    static boolean getEvenly(boolean original) {
        return GlassNavigationBarPatch.isTopTabsActive() || original;
    }

    static float getItemTopPadding(float original) {
        return GlassNavigationBarPatch.isTopTabsActive() ? 0f : original;
    }

    @Nullable
    static Object getItemModifier(@Nullable Modifier modifier) {
        if (modifier == null || !GlassNavigationBarPatch.isTopTabsActive()) {
            return modifier;
        }
        Object shape = cachedItemShape;
        if (shape == null) {
            shape = ComposeUtils.newRoundedCornerShape(TAB_ITEM_CORNER_DP);
            if (shape == null) {
                return modifier;
            }
            cachedItemShape = shape;
        }
        return modifier.patch_clip(shape);
    }

    static long getLabelFontSize(long original) {
        return GlassNavigationBarPatch.isTopTabsActive() ? packSp(LABEL_FONT_SIZE_SP) : original;
    }

    static long getLabelLineHeight(long original) {
        return GlassNavigationBarPatch.isTopTabsActive() ? packSp(LABEL_LINE_HEIGHT_SP) : original;
    }

    static int getLabelMask(int original) {
        // Clear the default bits of fontSize and lineHeight, so the values above are used.
        return GlassNavigationBarPatch.isTopTabsActive() ? original & ~TEXT_DEFAULT_BITS : original;
    }

    static void setPager(@Nullable Object pager) {
        pagerRef = pager != null ? new WeakReference<>(pager) : null;
    }

    @Nullable
    static Object getIndicatorModifier(@Nullable Modifier modifier, @Nullable Object pager) {
        if (modifier == null || pager == null || !GlassNavigationBarPatch.isTopTabsActive()) {
            return modifier;
        }
        WeakReference<Object> ref = pagerRef;
        if (ref == null || ref.get() != pager) {
            return modifier;
        }
        return modifier.patch_drawWithContent(TabPillDraw.INSTANCE);
    }

    private static long packSp(float value) {
        return 0x100000000L | (Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
    }

    private static void drawTabsCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float height = unpackSecond(size);
        if (width <= 0f || height <= 0f) {
            return;
        }

        final float marginX = CAPSULE_MARGIN_DP * getDensity();
        final float rectWidth = width - 2f * marginX;
        final float buttonHeight = GlassNavigationBarPatch.getTopBarButtonGlassHeightPx();
        final float rectHeight = buttonHeight > 0f ? buttonHeight : height;
        if (rectWidth <= 0f || rectHeight <= 0f) {
            return;
        }

        tabsHeightPx = height;
        capsuleHeightPx = rectHeight;

        final float top = (height - rectHeight) / 2f;
        GlassNavigationBarPatch.drawTopBarGlass(scope, marginX, top, rectWidth, rectHeight);
    }

    private static void drawTabPill(ContentDrawScope scope) {
        final float tabsHeight = tabsHeightPx;
        final float capsuleHeight = capsuleHeightPx;
        if (tabsHeight <= 0f || capsuleHeight <= 0f) {
            return;
        }

        final long size = scope.patch_getSize();
        final float w = unpackFirst(size);
        final float h = unpackSecond(size);
        if (w <= 0f || h <= 0f) {
            return;
        }

        final float density = getDensity();
        final float pillHeight = capsuleHeight - 2f * PILL_INSET_Y_DP * density;
        final float pillLeft = PILL_INSET_X_DP * density;
        final float pillWidth = w - 2f * pillLeft;
        if (pillWidth <= 0f || pillHeight <= 0f) {
            return;
        }

        final float pillTop = (h - tabsHeight / 2f) - pillHeight / 2f;
        final float radius = Math.min(pillWidth, pillHeight) / 2f;
        GlassNavigationBarPatch.drawPill(scope, pillLeft, pillTop, pillWidth, pillHeight, radius);
    }
}
