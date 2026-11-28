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

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.ComposeUtils;
import app.morphe.extension.reddit.utils.ComposeUtils.ContentDrawScope;
import app.morphe.extension.reddit.utils.ComposeUtils.Modifier;
import app.morphe.extension.reddit.utils.ComposeUtils.MutableState;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/**
 * Moves the Home feed tabs into a glass capsule in the top bar row.
 * <p>
 * The tab bar is composed inside the top bar row, and its original place below the top bar
 * composes nothing.
 */
@SuppressWarnings("unused")
public final class GlassNavigationBarTopTabs {

    /**
     * Compose LazyListState of the list feed. Interface added during patching.
     */
    public interface ListState {
        /**
         * @return If the list is scrolled away from the top.
         */
        boolean patch_isScrolled();
    }

    /**
     * Draws the glass capsule behind the tabs.
     */
    private static final class CapsuleDraw implements Function1<ContentDrawScope, Object> {
        private static final CapsuleDraw INSTANCE = new CapsuleDraw();

        private CapsuleDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                if (isActive()) {
                    drawCapsule(scope);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "CapsuleDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Draws a pill behind the selected tab, in place of the indicator line.
     */
    private static final class SelectedPillDraw implements Function1<ContentDrawScope, Object> {
        private static final SelectedPillDraw INSTANCE = new SelectedPillDraw();

        private SelectedPillDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                if (isActive()) {
                    drawSelectedPill(scope);
                    return null;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "SelectedPillDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Content below the top bar. Composes nothing there while the tabs are in the top bar row.
     */
    private static final class BelowContent implements Function2<Object, Object, Object> {
        private final Function2<Object, Object, Object> original;

        private BelowContent(Function2<Object, Object, Object> original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object composer, Object changed) {
            if (isActive() && !composingInRow) {
                return null;
            }

            return original.invoke(composer, changed);
        }
    }

    /**
     * Much larger than the weight of the invisible search field in the same row,
     * so the tabs get nearly all the free space of the row.
     */
    private static final float TABS_WEIGHT = 1000;
    private static final float HEADER_HEIGHT_DP = 8;
    private static final float CAPSULE_MARGIN_DP = 4;
    private static final float TOP_BAR_GLASS_GAP_DP = 8;
    private static final float SELECTED_PILL_INSET_X_DP = 2;
    private static final float SELECTED_PILL_INSET_Y_DP = 4;
    private static final float TAB_CORNER_RADIUS_DP = 100;
    private static final float LABEL_FONT_SIZE_SP = 13;
    private static final float LABEL_LINE_HEIGHT_SP = 17;
    /**
     * TextUnit type bits of a sp value.
     */
    private static final long TEXT_UNIT_TYPE_SP = 0x100000000L;
    /**
     * Default parameter bits of the font size and line height of the label text.
     */
    private static final int LABEL_TEXT_DEFAULT_BITS = (1 << 3) | (1 << 10);

    private static final boolean ENABLED = GlassNavigationBarPatch.isSearchButtonEnabled()
            && Settings.GLASS_NAVIGATION_BAR_TOP_TABS.get();

    /**
     * Read when the top bar is drawn, so Compose draws it again when the feed on screen changes.
     */
    @Nullable
    private static final MutableState FEED_CHANGE = isTrackingFeeds()
            ? (MutableState) ComposeUtils.newMutableState(0)
            : null;
    private static int feedChangeCount;

    @Nullable
    private static WeakReference<BelowContent> belowContentRef;
    @Nullable
    private static WeakReference<Object> pagerRef;
    @Nullable
    private static WeakReference<Modifier> replacedModifierRef;
    /**
     * Weight and capsule modifier of the tab bar. Created once.
     */
    @Nullable
    private static Object tabsModifier;
    @Nullable
    private static Object tabShape;

    /**
     * If the tab bar is being composed inside the top bar row.
     */
    private static boolean composingInRow;
    private static boolean tabsInRow;
    private static float tabsHeightPx;
    private static float capsuleHeightPx;

    private GlassNavigationBarTopTabs() {
    }

    private static boolean isActive() {
        return ENABLED && GlassNavigationBarPatch.isSearchButtonActive();
    }

    /**
     * @return If the tabs are currently shown in the top bar row.
     */
    static boolean isShown() {
        return isActive() && tabsInRow;
    }

    /**
     * @return If {@link FeedHookPatch} must track the feeds for this patch.
     *         The top bar is see-through only with the app bar drawn by "See-through status bar".
     */
    static boolean isTrackingFeeds() {
        return ENABLED && SeeThroughStatusBarPatch.isSeeThroughEnabled();
    }

    /**
     * @return If the list feed on screen is at the top, or if there is no list feed on screen.
     */
    static boolean isFeedOnScreenAtTop() {
        if (FEED_CHANGE != null) {
            FEED_CHANGE.patch_getValue();
        }

        return !(FeedHookPatch.findOnScreenFeedState() instanceof ListState state)
                || !state.patch_isScrolled();
    }

    /**
     * Called when a feed is added or the feed visibility changes.
     */
    static void onFeedsChanged() {
        final MutableState change = FEED_CHANGE;
        if (change == null || !isActive()) {
            return;
        }

        // Feeds are added while they are composed, so the state is changed afterwards.
        Utils.runOnMainThread(() -> {
            try {
                change.patch_setValue(++feedChangeCount);
            } catch (Exception ex) {
                Logger.printException(() -> "onFeedsChanged failure", ex);
            }
        });
    }

    /**
     * Injection point.
     *
     * @param below Content shown below the Home top bar.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static Function2<?, ?, ?> getTopBarBelow(@Nullable Function2<?, ?, ?> below) {
        if (!ENABLED || below == null) {
            return below;
        }

        try {
            // Recomposition passes the replaced content again.
            if (below instanceof BelowContent content) {
                belowContentRef = new WeakReference<>(content);
                return below;
            }

            if (!isActive()) {
                return below;
            }

            // Keep the same instance, so Compose can skip the content.
            BelowContent content = belowContentRef == null ? null : belowContentRef.get();
            if (content == null || content.original != below) {
                content = new BelowContent((Function2<Object, Object, Object>) below);
                belowContentRef = new WeakReference<>(content);
            }

            return content;
        } catch (Exception ex) {
            Logger.printException(() -> "getTopBarBelow failure", ex);
        }

        return below;
    }

    /**
     * Injection point.
     *
     * @param composer Composer of the Home top bar row, after the search field.
     */
    public static void composeTabsInRow(@Nullable Object composer) {
        if (!ENABLED || composer == null) {
            return;
        }

        try {
            if (!isActive()) {
                return;
            }

            BelowContent content = belowContentRef == null ? null : belowContentRef.get();
            if (content == null) {
                tabsInRow = false;
                return;
            }

            composingInRow = true;
            try {
                content.original.invoke(composer, 0);
            } finally {
                composingInRow = false;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "composeTabsInRow failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the Home tab bar.
     */
    @Nullable
    public static Object getTabBarModifier(@Nullable Modifier modifier) {
        if (!ENABLED || modifier == null) {
            return modifier;
        }

        try {
            // Recomposition passes the replaced modifier again.
            final boolean replaced = modifier == tabsModifier;

            if (!isActive()) {
                Modifier original = replaced && replacedModifierRef != null
                        ? replacedModifierRef.get()
                        : null;
                return original != null ? original : modifier;
            }

            if (composingInRow) {
                tabsInRow = true;
            }

            if (replaced) {
                return modifier;
            }

            // The original modifier is not used, because it gives the tab bar
            // its full height in the top bar row.
            if (tabsModifier == null) {
                Modifier weight = (Modifier) ComposeUtils.newWeightModifier(TABS_WEIGHT, true);
                final float gap = GlassNavigationBarPatch.TOP_BAR_BUTTON_GLASS_EXTRA_DP / 2
                        + TOP_BAR_GLASS_GAP_DP - CAPSULE_MARGIN_DP;
                tabsModifier = ((Modifier) weight.patch_padding(gap, 0, gap, 0))
                        .patch_drawWithContent(CapsuleDraw.INSTANCE);
            }
            replacedModifierRef = new WeakReference<>(modifier);

            return tabsModifier;
        } catch (Exception ex) {
            Logger.printException(() -> "getTabBarModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param evenly If the tabs are spread evenly.
     */
    public static boolean spreadTabsEvenly(boolean evenly) {
        return evenly || isActive();
    }

    /**
     * Injection point.
     *
     * @param pager Pager state of the Home feed tabs.
     */
    public static void setTabBarPager(@Nullable Object pager) {
        if (ENABLED) {
            pagerRef = pager == null ? null : new WeakReference<>(pager);
        }
    }

    /**
     * Injection point.
     *
     * @param height Height of the Home tab bar header below the top bar.
     */
    public static float getHeaderHeight(float height) {
        return isActive() ? HEADER_HEIGHT_DP : height;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of a Home tab.
     */
    @Nullable
    public static Object getTabModifier(@Nullable Modifier modifier) {
        if (!ENABLED || modifier == null) {
            return modifier;
        }

        try {
            if (isActive()) {
                if (tabShape == null) {
                    tabShape = newRoundedCornerShape(TAB_CORNER_RADIUS_DP);
                }

                return modifier.patch_clip(tabShape);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getTabModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param padding Top padding of a Home tab.
     */
    public static float getTabTopPadding(float padding) {
        return isActive() ? 0 : padding;
    }

    /**
     * Injection point.
     *
     * @param fontSize Font size of a Home tab label, as a TextUnit.
     */
    public static long getLabelFontSize(long fontSize) {
        return isActive() ? packSp(LABEL_FONT_SIZE_SP) : fontSize;
    }

    /**
     * Injection point.
     *
     * @param lineHeight Line height of a Home tab label, as a TextUnit.
     */
    public static long getLabelLineHeight(long lineHeight) {
        return isActive() ? packSp(LABEL_LINE_HEIGHT_SP) : lineHeight;
    }

    /**
     * Injection point.
     *
     * @param mask Default parameter mask of a Home tab label.
     */
    public static int getLabelDefaultMask(int mask) {
        // Clear the default bits of the font size and line height, so the values above are used.
        return isActive() ? mask & ~LABEL_TEXT_DEFAULT_BITS : mask;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of a tab row indicator.
     * @param pager    Pager state of the tab row.
     */
    @Nullable
    public static Object getIndicatorModifier(@Nullable Modifier modifier, @Nullable Object pager) {
        if (!ENABLED || modifier == null || pager == null) {
            return modifier;
        }

        try {
            // Other tab rows use the same indicator.
            if (isActive() && pagerRef != null && pagerRef.get() == pager) {
                return modifier.patch_drawWithContent(SelectedPillDraw.INSTANCE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getIndicatorModifier failure", ex);
        }

        return modifier;
    }

    /**
     * RoundedCornerShape(dp).
     */
    @SuppressWarnings("SameReturnValue")
    @Nullable
    public static Object newRoundedCornerShape(float dp) {
        return null; // Modified during patching.
    }

    private static long packSp(float value) {
        return TEXT_UNIT_TYPE_SP | (Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
    }

    private static void drawCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float height = unpackSecond(size);
        final float marginX = CAPSULE_MARGIN_DP * getDensity();
        final float buttonHeight = GlassNavigationBarPatch.getTopBarButtonGlassHeightPx();
        final float capsuleHeight = buttonHeight > 0 ? buttonHeight : height;

        tabsHeightPx = height;
        capsuleHeightPx = capsuleHeight;

        GlassNavigationBarPatch.drawTopBarGlass(scope, marginX, (height - capsuleHeight) / 2,
                width - 2 * marginX, capsuleHeight);
    }

    private static void drawSelectedPill(ContentDrawScope scope) {
        if (tabsHeightPx <= 0 || capsuleHeightPx <= 0) {
            return;
        }

        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float height = unpackSecond(size);
        final float density = getDensity();
        final float left = SELECTED_PILL_INSET_X_DP * density;
        final float pillWidth = width - 2 * left;
        final float pillHeight = capsuleHeightPx - 2 * SELECTED_PILL_INSET_Y_DP * density;
        if (pillWidth <= 0 || pillHeight <= 0) {
            return;
        }

        // The indicator is at the bottom of the tab bar, so center the pill on the tab bar.
        final float top = height - tabsHeightPx / 2 - pillHeight / 2;
        final float radius = Math.min(pillWidth, pillHeight) / 2;

        GlassNavigationBarPatch.drawPill(scope, left, top, pillWidth, pillHeight, radius);
    }
}
