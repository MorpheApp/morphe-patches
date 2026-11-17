/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4978
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import com.reddit.screen.dialog.ModalBackdropView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class PostSidePanelPatch {

    private static final int MIN_TABLET_WIDTH_DP = 840;
    private static final int NAV_RAIL_WIDTH_DP = 96;
    private static final int CORNER_RADIUS_DP = 16;
    private static final long ANIMATION_DURATION_MS = 250L;

    private static final Map<Object, Boolean> DECISION_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public enum PanelWidth {
        ONE_THIRD,
        TWO_THIRDS
    }

    public enum FeedDim {
        OFF(0f),
        LIGHT(0.38f),
        MEDIUM(0.55f),
        STRONG(0.72f);

        private final float alpha;

        FeedDim(float alpha) {
            this.alpha = alpha;
        }

        public float getAlpha() {
            return alpha;
        }
    }

    private PostSidePanelPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean usePanel(@Nullable Object screen) {
        if (screen == null) {
            return false;
        }
        try {
            if (!Settings.POST_SIDE_PANEL.get()) {
                return false;
            }
            synchronized (DECISION_CACHE) {
                Boolean cached = DECISION_CACHE.get(screen);
                if (cached != null) {
                    return cached;
                }
                final boolean decision = Utils.isTablet()
                        && Utils.getContext().getResources().getConfiguration().screenWidthDp >= MIN_TABLET_WIDTH_DP;
                DECISION_CACHE.put(screen, decision);
                return decision;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "usePanel failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     */
    public static void onScreenViewCreated(@Nullable Object screen, @Nullable View root) {
        if (screen == null || !(root instanceof ViewGroup)) {
            return;
        }
        try {
            if (!Settings.POST_SIDE_PANEL.get()) {
                return;
            }
            final Boolean cached;
            synchronized (DECISION_CACHE) {
                cached = DECISION_CACHE.get(screen);
            }
            if (!Boolean.TRUE.equals(cached)) {
                return;
            }

            if (root instanceof ModalBackdropView) {
                ((ModalBackdropView) root).setBackdropAlpha(Settings.POST_SIDE_PANEL_DIM.get().getAlpha());
            }

            final int containerId = ResourceUtils.getIdIdentifier("screen_modal_container");
            if (containerId == 0) {
                return;
            }
            View containerView = root.findViewById(containerId);
            if (!(containerView instanceof ViewGroup)) {
                return;
            }
            ViewGroup container = (ViewGroup) containerView;

            final float cornerRadiusPx = Dim.dp(CORNER_RADIUS_DP);
            container.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    final int width = view.getWidth();
                    final int height = view.getHeight();
                    final boolean isRtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    if (isRtl) {
                        outline.setRoundRect((int) -cornerRadiusPx, 0, width, height, cornerRadiusPx);
                    } else {
                        outline.setRoundRect(0, 0, (int) (width + cornerRadiusPx), height, cornerRadiusPx);
                    }
                }
            });
            container.setClipToOutline(true);

            ViewGroup.LayoutParams containerLp = container.getLayoutParams();
            ViewGroup.MarginLayoutParams marginLp;
            if (containerLp instanceof ViewGroup.MarginLayoutParams) {
                marginLp = (ViewGroup.MarginLayoutParams) containerLp;
            } else {
                marginLp = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
            }
            marginLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            marginLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            marginLp.setMargins(0, 0, 0, 0);
            marginLp.setMarginStart(0);
            marginLp.setMarginEnd(0);
            container.setLayoutParams(marginLp);

            if (container.getChildCount() > 0) {
                View firstChild = container.getChildAt(0);
                ViewGroup.LayoutParams childLp = firstChild.getLayoutParams();
                if (childLp != null) {
                    childLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    childLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    firstChild.setLayoutParams(childLp);
                } else {
                    firstChild.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    ));
                }
            }

            root.setOnApplyWindowInsetsListener((v, insets) -> {
                final int left = insets.getSystemWindowInsetLeft();
                final int right = insets.getSystemWindowInsetRight();
                v.setPadding(left, 0, right, 0);
                return insets;
            });
            root.requestApplyInsets();

            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                private boolean animated = false;

                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    final int width = right - left;
                    final int height = bottom - top;
                    if (width <= 0) {
                        return;
                    }

                    final int oldWidth = oldRight - oldLeft;
                    final int oldHeight = oldBottom - oldTop;
                    if (width != oldWidth || height != oldHeight) {
                        container.invalidateOutline();
                    }

                    final boolean isWide = width >= Dim.dp(MIN_TABLET_WIDTH_DP);
                    final int feedWidth = width - Dim.dp(NAV_RAIL_WIDTH_DP);
                    final int panelWidth = Settings.POST_SIDE_PANEL_WIDTH.get() == PanelWidth.TWO_THIRDS
                            ? feedWidth * 2 / 3
                            : feedWidth / 3;
                    final int targetStartMargin = isWide ? width - panelWidth : 0;

                    ViewGroup.LayoutParams lp = container.getLayoutParams();
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                        final boolean startMarginDiffers = mlp.getMarginStart() != targetStartMargin;
                        final boolean shouldAnimate = !animated && isWide;
                        if (shouldAnimate) {
                            animated = true;
                        }

                        if (startMarginDiffers || shouldAnimate) {
                            root.post(() -> {
                                if (startMarginDiffers) {
                                    mlp.setMarginStart(targetStartMargin);
                                    container.setLayoutParams(mlp);
                                    container.setClipToOutline(isWide);
                                }
                                if (shouldAnimate) {
                                    final boolean isRtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                                    final float initialTranslation = isRtl ? -panelWidth : panelWidth;
                                    container.setTranslationX(initialTranslation);
                                    container.animate()
                                            .translationX(0f)
                                            .setDuration(ANIMATION_DURATION_MS)
                                            .setInterpolator(new DecelerateInterpolator())
                                            .start();
                                }
                            });
                        }
                    }
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenViewCreated failure", ex);
        }
    }
}
