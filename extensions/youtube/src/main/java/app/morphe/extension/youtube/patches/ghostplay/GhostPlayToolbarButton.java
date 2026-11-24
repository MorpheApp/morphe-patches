/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Compact top-toolbar toggle aligned beside YouTube's ActionMenu icons.
 * Hidden automatically on Shorts and active search screens.
 */
public final class GhostPlayToolbarButton {

    private static final String BUTTON_TAG = "morphe_ghost_toolbar_toggle";

    /**
     * Text field of the toolbar shown while entering a search.
     */
    private static final int ID_SEARCH_EDIT_TEXT =
            ResourceUtils.getIdentifier(ResourceType.ID, "search_edit_text");

    // Map attached pills to their parent toolbar container and last rendered state string.
    private static final Map<LinearLayout, WeakReference<FrameLayout>> attachedButtons =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LinearLayout, String> lastRenderedStates =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean listenersRegistered = false;
    private static volatile boolean isOnShortsTab = false;

    /**
     * Action menu view of a toolbar, which the button is positioned next to.
     */
    private static final class MenuViewRef {
        WeakReference<View> view = new WeakReference<>(null);
    }

    private GhostPlayToolbarButton() {
    }

    private static void ensureNavigationListeners() {
        if (listenersRegistered) return;
        listenersRegistered = true;
        try {
            NavigationBar.addOnNavigationButtonChangedListener(activeButton -> {
                isOnShortsTab = (activeButton == NavigationBar.NavigationButton.SHORTS);
                refreshAll();
            });
            ShortsPlayerState.getOnChange().addObserver((Boolean open) -> refreshAll());
        } catch (Exception ex) {
            Logger.printException(() -> "GhostPlayToolbarButton listener setup failure", ex);
        }
    }

    private static boolean shouldHideForCurrentScreen() {
        return NavigationBar.isSearchBarActive()
                || ShortsPlayerState.isOpen()
                || isOnShortsTab;
    }

    public static void attachToToolbar(FrameLayout toolbarContainer) {
        if (!GhostPlayPatch.isEnabled() || !Settings.GHOST_PLAY_TOOLBAR_BUTTON.get()) {
            return;
        }
        ensureNavigationListeners();
        Utils.runOnMainThread(() -> {
            try {
                if (toolbarContainer.findViewWithTag(BUTTON_TAG) != null) {
                    refreshAll();
                    return;
                }

                Context ctx = toolbarContainer.getContext();
                LinearLayout pill = new LinearLayout(ctx);
                pill.setTag(BUTTON_TAG);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER);
                pill.setClickable(true);
                pill.setFocusable(true);
                pill.setMinimumWidth(Dim.dp(34));
                pill.setMinimumHeight(Dim.dp(34));

                ImageView icon = new ImageView(ctx);
                int iconSize = Dim.dp(22);
                icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                pill.addView(icon);

                TextView timerLabel = new TextView(ctx);
                timerLabel.setTextColor(Color.WHITE);
                timerLabel.setTextSize(10.5f);
                timerLabel.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lblLp.leftMargin = Dim.dp(3);
                timerLabel.setLayoutParams(lblLp);
                pill.addView(timerLabel);

                pill.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    GhostPlayPatch.toggleSessionGhostMode();
                    GhostTheme.playCuteGhostBounce(icon, GhostPlayPatch.isSessionGhostActive());
                });
                pill.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    GhostHistoryDialog.showQuickMenu(v.getContext());
                    return true;
                });

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Dim.dp(34),
                        Gravity.START | Gravity.TOP
                );
                pill.setLayoutParams(lp);
                toolbarContainer.addView(pill);

                attachedButtons.put(pill, new WeakReference<>(toolbarContainer));
                updateButtonVisual(pill, false);

                WeakReference<FrameLayout> containerRef = new WeakReference<>(toolbarContainer);
                WeakReference<LinearLayout> pillRef = new WeakReference<>(pill);
                MenuViewRef observedMenuRef = new MenuViewRef();

                View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                    FrameLayout c = containerRef.get();
                    LinearLayout p = pillRef.get();
                    if (c == null || p == null) return;
                    positionNextToActionMenu(c, p, observedMenuRef, null);
                };

                View.OnLayoutChangeListener containerLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                    FrameLayout c = containerRef.get();
                    LinearLayout p = pillRef.get();
                    if (c == null || p == null) return;
                    positionNextToActionMenu(c, p, observedMenuRef, layoutListener);
                };

                toolbarContainer.addOnLayoutChangeListener(containerLayoutListener);
                pill.addOnLayoutChangeListener(layoutListener);

                pill.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        FrameLayout c = containerRef.get();
                        if (c != null) {
                            positionNextToActionMenu(c, pill, observedMenuRef, layoutListener);
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {
                        FrameLayout c = containerRef.get();
                        if (c != null) {
                            c.removeOnLayoutChangeListener(containerLayoutListener);
                        }
                        View menu = observedMenuRef.view.get();
                        if (menu != null) {
                            menu.removeOnLayoutChangeListener(layoutListener);
                        }
                        pill.removeOnLayoutChangeListener(layoutListener);
                        attachedButtons.remove(pill);
                        lastRenderedStates.remove(pill);
                    }
                });

                pill.post(() -> {
                    FrameLayout c = containerRef.get();
                    LinearLayout p = pillRef.get();
                    if (c != null && p != null) {
                        positionNextToActionMenu(c, p, observedMenuRef, layoutListener);
                    }
                });
            } catch (Exception ex) {
                Logger.printException(() -> "GhostPlayToolbarButton.attachToToolbar failure", ex);
            }
        });
    }

    public static void refreshAll() {
        Utils.runOnMainThread(() -> {
            boolean show = GhostPlayPatch.isEnabled()
                    && Settings.GHOST_PLAY_TOOLBAR_BUTTON.get()
                    && !shouldHideForCurrentScreen();
            for (Map.Entry<LinearLayout, WeakReference<FrameLayout>> entry : attachedButtons.entrySet()) {
                LinearLayout pill = entry.getKey();
                if (pill == null) continue;
                if (show) {
                    updateButtonVisual(pill, true);
                }
                FrameLayout container = entry.getValue() != null ? entry.getValue().get() : null;
                if (container != null) {
                    // Also hides the button if it should not be shown.
                    pill.post(() -> positionNextToActionMenu(container, pill, null, null));
                }
            }
        });
    }

    private static void updateButtonVisual(LinearLayout pill, boolean animate) {
        boolean active = GhostPlayPatch.isSessionGhostActive();
        long rem = GhostPlayPatch.getTimedGhostRemainingMinutes();
        ImageView icon = (ImageView) pill.getChildAt(0);
        TextView timerLabel = (TextView) pill.getChildAt(1);

        lastRenderedStates.put(pill, active + ":" + rem);

        String resName = active ? "morphe_ghost_play_button_on" : "morphe_ghost_play_button_off";
        int iconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, resName);
        if (iconId != 0) {
            icon.setImageResource(iconId);
        }

        if (active) {
            pill.setPadding(Dim.dp(6), Dim.dp(4), Dim.dp(6), Dim.dp(4));
            pill.setBackground(null);
            icon.setColorFilter(ThemeUtils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN);
            if (rem > 0) {
                timerLabel.setVisibility(View.VISIBLE);
                timerLabel.setText(str("morphe_ghost_play_toolbar_timed_badge", rem));
                timerLabel.setTextColor(ThemeUtils.getAppForegroundColor());
            } else {
                timerLabel.setVisibility(View.GONE);
            }
            pill.setContentDescription(str("morphe_ghost_play_session_on_toast"));
        } else {
            pill.setPadding(Dim.dp(6), Dim.dp(4), Dim.dp(6), Dim.dp(4));
            pill.setBackground(null);
            icon.setColorFilter(
                    GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.72f),
                    PorterDuff.Mode.SRC_IN
            );
            timerLabel.setVisibility(View.GONE);
            pill.setContentDescription(str("morphe_ghost_play_dialog_title"));
        }

        if (animate && GhostTheme.areAnimationsEnabled(pill.getContext())) {
            Drawable d = icon.getDrawable();
            if (d instanceof Animatable anim) {
                anim.start();
            }
        }
    }

    private static void positionNextToActionMenu(
            FrameLayout container,
            LinearLayout pill,
            @Nullable MenuViewRef observedMenuRef,
            @Nullable View.OnLayoutChangeListener menuLayoutListener
    ) {
        View actionMenu = (observedMenuRef != null) ? observedMenuRef.view.get() : null;
        if (actionMenu == null || !actionMenu.isAttachedToWindow()) {
            actionMenu = findActionMenuView(container);
            if (actionMenu != null && observedMenuRef != null && menuLayoutListener != null) {
                View prev = observedMenuRef.view.get();
                if (prev != null && prev != actionMenu) {
                    prev.removeOnLayoutChangeListener(menuLayoutListener);
                }
                actionMenu.addOnLayoutChangeListener(menuLayoutListener);
                observedMenuRef.view = new WeakReference<>(actionMenu);
            }
        }

        if (actionMenu == null
                || actionMenu.getVisibility() != View.VISIBLE
                || !GhostPlayPatch.isEnabled()
                || !Settings.GHOST_PLAY_TOOLBAR_BUTTON.get()
                || shouldHideForCurrentScreen()
                || container.findViewById(ID_SEARCH_EDIT_TEXT) != null) {
            if (pill.getVisibility() != View.GONE) {
                pill.setVisibility(View.GONE);
            }
            if (actionMenu != null) {
                reserveMenuSpace(actionMenu, 0);
            }
            return;
        }

        String desiredState = GhostPlayPatch.isSessionGhostActive() + ":" + GhostPlayPatch.getTimedGhostRemainingMinutes();
        if (!desiredState.equals(lastRenderedStates.get(pill))) {
            updateButtonVisual(pill, false);
        }

        if (pill.getVisibility() != View.VISIBLE) {
            pill.setVisibility(View.VISIBLE);
        }

        final int pillWidth = pill.getWidth();
        final int pillHeight = pill.getHeight();
        if (container.getWidth() <= 0 || pillWidth <= 0 || pillHeight <= 0) {
            return; // Not laid out yet. Called again after the layout.
        }

        reserveMenuSpace(actionMenu, pillWidth + Dim.dp2);

        int[] containerLoc = new int[2];
        int[] menuLoc = new int[2];
        container.getLocationInWindow(containerLoc);
        actionMenu.getLocationInWindow(menuLoc);

        final int menuLeft = menuLoc[0] - containerLoc[0];
        final int menuTop = menuLoc[1] - containerLoc[1];
        final float targetX = actionMenu.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? menuLeft + actionMenu.getWidth() - pillWidth
                : menuLeft;
        final float targetY = menuTop + (actionMenu.getHeight() - pillHeight) / 2f;

        if (Math.abs(pill.getX() - targetX) > 0.5f) {
            pill.setX(targetX);
        }
        if (Math.abs(pill.getY() - targetY) > 0.5f) {
            pill.setY(targetY);
        }
    }

    /**
     * Adds start padding to the menu, so the toolbar lays out its other content
     * (such as the wide search bar) beside the button and not under it.
     */
    private static void reserveMenuSpace(View actionMenu, int width) {
        if (actionMenu.getPaddingStart() == width) {
            return;
        }
        // Can be called during layout, where a new layout request may be dropped.
        actionMenu.post(() -> {
            if (actionMenu.getPaddingStart() != width) {
                actionMenu.setPaddingRelative(width, actionMenu.getPaddingTop(),
                        actionMenu.getPaddingEnd(), actionMenu.getPaddingBottom());
            }
        });
    }

    @Nullable
    private static View findActionMenuView(ViewGroup parent) {
        for (int i = 0, n = parent.getChildCount(); i < n; i++) {
            View child = parent.getChildAt(i);
            if (BUTTON_TAG.equals(child.getTag())) continue;
            if (child.getClass().getSimpleName().contains("ActionMenuView")) {
                return child;
            }
            if (child instanceof ViewGroup vg) {
                View found = findActionMenuView(vg);
                if (found != null) return found;
            }
        }
        return null;
    }
}
