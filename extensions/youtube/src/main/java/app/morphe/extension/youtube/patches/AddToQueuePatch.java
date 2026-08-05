/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.View;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    private static final String queueButtonName = "QUEUE_PLAY_NEXT";
    private static final Drawable queueButtonDrawable = ResourceUtils
            .getDrawable("quantum_ic_playlist_add_white_24");
    private static final String shareButtonName = "SHARE_ARROW";

    private static final List<Pair<String, Integer>> visibleFlyoutButtons = new ArrayList<>();

    private static String currentButtonName = "";
    private static int currentButtonIndex;

    public static void injectFlyoutElements(Object flyoutPanel) {
        int currentInjectIndex = initializeNewButton(
                flyoutPanel,
                queueButtonDrawable,
                str("morphe_queue_flyout_title"),
                v -> flyoutButtonClickLogic(queueButtonName),
                0
        );
        if (currentInjectIndex > 0) {
            initializeNewDivider(flyoutPanel, currentInjectIndex);
        }
    }

    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    private static int initializeNewButton(Object flyoutPanel, Drawable icon, String text,
                                           View.OnClickListener clickListener, int index) {
        return initializeNewElement(flyoutPanel, icon, text, clickListener, index, false);
    }

    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    private static int initializeNewDivider(Object flyoutPanel, int index) {
        return initializeNewElement(flyoutPanel, null, null, null, index, true);
    }

    private static int initializeNewElement(Object flyoutPanel, Drawable icon, String text,
                                            View.OnClickListener clickListener, int index,
                                            boolean isDivider) {
        try {
            FlyoutUtils.FlyoutMenuInfo menuInfo = FlyoutUtils.getFlyoutMenuInfo(flyoutPanel, index);
            if (menuInfo == null) {
                return -1;
            }

            Context context = Utils.getActivity();
            if (context == null) {
                return -1;
            }

            View view = isDivider
                    ? FlyoutUtils.createFlyoutDivider(context)
                    : FlyoutUtils.createFlyoutButton(context, icon, text, clickListener);

            int fixedIndex = menuInfo.adjustedIndex();
            menuInfo.menuContainer().addView(view, fixedIndex);

            PopupWindow popupWindow = menuInfo.popupWindow();
            if (popupWindow != null) {
                popupWindow.update();
            }

            // For new layout only:
            // Skip an index to inject the next element after the current button.
            if (menuInfo.isPopupWindow()) {
                fixedIndex++;
            }

            return fixedIndex;
        } catch (Exception ex) {
            Logger.printException(() -> "initializeNewElement failure", ex);
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
