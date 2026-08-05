/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    private static final String queueButtonName = "QUEUE_PLAY_NEXT";
    private static final Drawable queueButtonDrawable =
            ResourceUtils.getDrawable("quantum_ic_playlist_add_white_24");
    private static final String shareButtonName = "SHARE_ARROW";

    private static final int BLACK_COLOR = ResourceUtils.getColor("yt_black1");
    private static final int GREY_COLOR = ResourceUtils.getColor("yt_grey1");
    private static final int WHITE_COLOR = ResourceUtils.getColor("yt_white1");

    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();

    private static String currentButtonName = "";
    private static int currentButtonIndex;

    public static void injectFlyoutElements(Object popupView) {
        int currentInjectIndex = initializeNewButton(
                popupView,
                queueButtonDrawable,
                "Add to queue (Morphe)",
                v -> {
                    flyoutButtonClickLogic(queueButtonName);
                },
                0
        );
        initializeNewDivider(popupView, currentInjectIndex + 1);
    }
    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    private static int initializeNewButton(
            Object popupView,
            Drawable icon,
            String text,
            View.OnClickListener clickListener,
            int index
    ) {
        return initializeNewElement(
                popupView,
                icon,
                text,
                clickListener,
                index,
                false
        );
    }
    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    private static int initializeNewDivider(
            Object popupView,
            int index
    ) {
        return initializeNewElement(
                popupView,
                null,
                null,
                null,
                index,
                true
        );
    }
    private static int initializeNewElement(
            Object popupView,
            Drawable icon,
            String text,
            View.OnClickListener clickListener,
            int index,
            boolean isDivider
    ) {
        try {
            if (popupView == null) {
                return -1;
            }

            PopupWindow popupWindow = null;
            FrameLayout frameLayout = null;

            if (popupView instanceof PopupWindow checkedPopupWindow) {
                popupWindow = checkedPopupWindow;
                if (checkedPopupWindow.getContentView() instanceof FrameLayout checkFrame) {
                    frameLayout = checkFrame;
                }
            } else if (popupView instanceof Dialog checkedDialog) {
                Window window = checkedDialog.getWindow();
                if (window != null && window.getDecorView() instanceof ViewGroup decorView) {
                    if (decorView.getChildAt(0) instanceof FrameLayout checkFrame) {
                        frameLayout = checkFrame;
                    }
                }
            }

            if (frameLayout == null ||
                    !(frameLayout.getChildAt(0) instanceof ViewGroup viewGroup)) {
                return -1;
            }

            if (!(viewGroup.getChildAt(0) instanceof LinearLayout menuContainer)) {
                return -1;
            }

            Context context = Utils.getContext();
            if (context == null) {
                return -1;
            }

            float density = Dim.getMetrics().density;
            int density1 = (int) (1 * density);
            int density4 = (int) (4 * density);
            int density12 = (int) (12 * density);
            int density16 = (int) (16 * density);
            int density24 = (int) (24 * density);

            if (!isDivider) {
                final LinearLayout customButton = new LinearLayout(context);
                customButton.setOrientation(LinearLayout.HORIZONTAL);
                customButton.setGravity(Gravity.CENTER_VERTICAL);
                customButton.setPadding(density16, density12, density16, density12);
                customButton.setClickable(true);
                customButton.setBackgroundColor(
                        Utils.isDarkModeEnabled()
                                ? BLACK_COLOR
                                : WHITE_COLOR
                );

                final LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(density24, density24);
                layoutParams.rightMargin = density16;

                if (icon != null) {
                    final ImageView iconView = new ImageView(context);
                    iconView.setLayoutParams(layoutParams);

                    final Drawable mutableIcon = icon.mutate();
                    mutableIcon.setTint(Utils.isDarkModeEnabled() ? WHITE_COLOR : BLACK_COLOR);
                    mutableIcon.setTintMode(PorterDuff.Mode.SRC_IN);
                    iconView.setImageDrawable(mutableIcon);

                    customButton.addView(iconView);
                }

                final TextView textView = new TextView(context);
                textView.setText(text);
                textView.setTextSize(16);
                textView.setTypeface(null, Typeface.BOLD);

                customButton.addView(textView);
                customButton.setOnClickListener(clickListener);

                menuContainer.addView(customButton, index);
            } else {
                final View divider = new View(context);
                final LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        density1
                );
                dividerParams.setMargins(density16, density4, density16, density4);
                divider.setLayoutParams(dividerParams);
                divider.setBackgroundColor(GREY_COLOR);

                menuContainer.addView(divider, index);
            }

            if (popupWindow != null) {
                popupWindow.update();
            }

            return index;
        } catch (Exception ex) {
            Logger.printException(() -> "injectButton failure", ex);
        }

        return -1;
    }

    /**
     * Injection point.
     */
    public static void setCurrentButtonInfo(@Nullable Enum<?> buttonEnum, @Nullable Object buttonInfo) {
        if (buttonEnum == null) {
            return;
        }

        if (buttonInfo instanceof CharSequence charSequence && charSequence.toString().isEmpty()) {
            return;
        }

        if (buttonInfo instanceof View view && view.getVisibility() == View.GONE) {
            return;
        }

        if (currentButtonIndex == 0 && !visibleFlyoutButtons.isEmpty()) {
            visibleFlyoutButtons.clear();
        }

        currentButtonName = buttonEnum.name();
        currentButtonIndex++;

        visibleFlyoutButtons.add(new Pair<>(currentButtonName, currentButtonIndex));
    }

    /**
     * Injection point.
     */
    public static Runnable replaceButtonRunnable(Runnable original) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return original;
        }

        if (FlyoutUtils.getFlyoutVideoId().isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            return original;
        }

        return getNewRunnable(original, currentButtonName);
    }

    /**
     * Injection point.
     * -
     * 21.04 and older.
     */
    public static boolean replaceOnItemClick(Object object) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return false;
        }

        if (FlyoutUtils.getFlyoutVideoId().isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            return false;
        }

        int buttonIndex = -1;
        String buttonName = "";

        if (object instanceof Integer index) {
            buttonIndex = index;
        } else if (object instanceof String name) {
            buttonName = name;
        }

        try {
            if (!visibleFlyoutButtons.isEmpty()) {
                if (buttonIndex >= 0) {
                    return flyoutButtonClickLogic(visibleFlyoutButtons.get(buttonIndex).first);
                } else if (!buttonName.isEmpty()) {
                    return flyoutButtonClickLogic(buttonName);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOnItemClick failure", ex);
        }
        return false;
    }

    private static Runnable getNewRunnable(@Nullable Runnable original, String buttonName) {
        return () -> {
            // Reset index logic goes here if needed between UI clicks
            currentButtonIndex = 0;

            if (flyoutButtonClickLogic(buttonName)) {
                return;
            }

            if (original != null) {
                original.run();
            }
        };
    }

    private static boolean flyoutButtonClickLogic(String buttonName) {
        if (buttonName.equals(queueButtonName)) {
            Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + FlyoutUtils.getFlyoutVideoId());

            Activity activity = Utils.getActivity();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                PlaylistPatch.prepareDialogBuilder(Utils.getActivity(), FlyoutUtils.getFlyoutVideoId());
            }

            FlyoutUtils.dismissBottomSheetFlyout(); // Must dismiss after showing dialog.
            FlyoutUtils.dismissPopupWindowFlyout();
            return true;
        }

        return false;
    }
}
