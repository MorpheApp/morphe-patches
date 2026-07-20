/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class HideAdsPatch {

    /**
     * Injection point.
     */
    public static boolean hideGetPremiumLabel() {
        return Settings.HIDE_GET_PREMIUM_LABEL.get();
    }

    /**
     * Injection point.
     */
    public static boolean hideVideoAds(boolean original) {
        if (Settings.HIDE_VIDEO_ADS.get()) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static void hidePremiumPromotionBottomSheet(View view) {
        if (Settings.HIDE_MUSIC_PREMIUM_PROMOTIONS.get()) {
            view.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    if (!(view instanceof ViewGroup viewGroup)) {
                        return;
                    }
                    if (!(viewGroup.getChildAt(0) instanceof ViewGroup mealBarLayoutRoot)) {
                        return;
                    }
                    if (!(mealBarLayoutRoot.getChildAt(0) instanceof LinearLayout linearLayout)) {
                        return;
                    }
                    if (!(linearLayout.getChildAt(0) instanceof ImageView imageView)) {
                        return;
                    }
                    if (imageView.getVisibility() == View.VISIBLE) {
                        view.setVisibility(View.GONE);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "hidePremiumPromotionBottomSheet failure", ex);
                }
            });
        }
    }
}
