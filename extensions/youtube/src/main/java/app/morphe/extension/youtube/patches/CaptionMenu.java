/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Adds the bilingual captions toggle to the captions menu, if a secondary language is selected.
 */
@SuppressWarnings("unused")
public final class CaptionMenu {
    private static final String CAPTIONS_SHEET_PATH = "captions_sheet_content.e";

    private static final Set<ViewGroup> menus = Collections.newSetFromMap(new WeakHashMap<>());
    private static WeakReference<Dialog> activeDialog = new WeakReference<>(null);
    private static volatile boolean pendingSheet;

    private CaptionMenu() {}

    /**
     * Injection point.
     * Called with the list of the old captions bottom sheet, before its footer is added.
     */
    public static void addToggle(ListView list) {
        if (!CaptionTracks.hasSecondaryLanguage() || menus.contains(list)) return;
        try {
            list.addFooterView(createToggle(list.getContext()), null, false);
            menus.add(list);
        } catch (Exception ex) {
            Logger.printException(() -> "addToggle failure", ex);
        }
    }

    /**
     * Injection point.
     * Called for each loaded Litho component.
     */
    public static void onCaptionSheet(CharSequence path, List<Object> ignored) {
        if (!CaptionTracks.hasSecondaryLanguage() || !Utils.startsWith(path, CAPTIONS_SHEET_PATH)) return;

        pendingSheet = true;
        Utils.runOnMainThread(() -> decorate(activeDialog.get()));
    }

    /**
     * Injection point.
     * Called with each created bottom sheet dialog.
     */
    public static void onDialog(@Nullable Dialog dialog) {
        if (dialog == null || !CaptionTracks.hasSecondaryLanguage()) return;
        try {
            activeDialog = new WeakReference<>(dialog);
            Objects.requireNonNull(dialog.getWindow()).getDecorView().getViewTreeObserver()
                    .addOnGlobalLayoutListener(() -> decorate(dialog));
        } catch (Exception ex) {
            Logger.printException(() -> "onDialog failure", ex);
        }
    }

    /**
     * Keeps the adapter and its item indices intact, and adds the toggle as a separate row.
     */
    private static void decorate(@Nullable Dialog dialog) {
        if (!pendingSheet || dialog == null || dialog != activeDialog.get() || !dialog.isShowing()) return;

        try {
            View view = dialog.findViewById(ResourceUtils.getIdentifier(ResourceType.ID, "bottom_sheet_list"));
            if (!(view instanceof ViewGroup list) || !(list.getParent() instanceof ViewGroup parent)) return;

            pendingSheet = false;
            if (list instanceof ListView) return;

            if (menus.contains(list)) {
                if (parent instanceof LinearLayout wrapper) moveHeaderInset(list, wrapper);
                return;
            }

            final int index = parent.indexOfChild(list);
            ViewGroup.LayoutParams params = list.getLayoutParams();
            LinearLayout wrapper = new LinearLayout(list.getContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            moveHeaderInset(list, wrapper);
            parent.removeView(list);
            wrapper.addView(createToggle(list.getContext()), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            wrapper.addView(list, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            parent.addView(wrapper, index, params);
            menus.add(list);
        } catch (Exception ex) {
            Logger.printException(() -> "decorate failure", ex);
        }
    }

    /**
     * The native list reserves space underneath the overlaid header of the sheet.
     */
    private static void moveHeaderInset(View list, LinearLayout wrapper) {
        final int inset = list.getPaddingTop();
        if (inset == 0) return;

        wrapper.setPaddingRelative(0, inset, 0, 0);
        list.setPaddingRelative(list.getPaddingStart(), 0, list.getPaddingEnd(), list.getPaddingBottom());
    }

    private static int switchColor(Context context, String name, int fallback) {
        final int id = ResourceUtils.getIdentifier(ResourceType.COLOR, name);
        return id == 0 ? fallback : context.getColor(id);
    }

    private static GradientDrawable switchShape(float widthDp, float heightDp, ColorStateList colors) {
        GradientDrawable shape = new GradientDrawable();
        shape.setSize(Dim.dp(widthDp), Dim.dp(heightDp));
        shape.setCornerRadius(Dim.dp(heightDp) / 2f);
        shape.setColor(colors);
        return shape;
    }

    private static Switch createToggle(Context context) {
        Switch toggle = new Switch(context);
        toggle.setText(StringRef.str("morphe_dual_captions_enabled_title"));

        final int textStyle = ResourceUtils.getIdentifier(ResourceType.ATTR, "yt_sys_typography_typestack_body_l_heavy");
        ThemeUtils.setThemeTextStyle(toggle, textStyle != 0 ? textStyle : android.R.attr.textAppearanceListItem,
                android.R.attr.textColorPrimary);
        if (textStyle == 0) toggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        toggle.setPaddingRelative(Dim.dp16, 0, Dim.dp20, 0);
        final int iconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "quantum_ic_closed_caption_white_24");
        if (iconId != 0) {
            Drawable icon = context.getDrawable(iconId).mutate();
            icon.setTint(ThemeUtils.getAppForegroundColor());
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            toggle.setCompoundDrawablePadding(Dim.dp12);
        }

        applySwitchStyle(toggle, context);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(Dim.dp48);
        toggle.setChecked(CaptionTracks.dualCaptionEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            try {
                CaptionTracks.setDualCaptionsEnabled(enabled);
            } catch (Exception ex) {
                Logger.printException(() -> "setDualCaptionsEnabled failure", ex);
            }
        });
        return toggle;
    }

    /**
     * Uses the size and colors of the native 40x24dp switch glyph, including its transparent margins.
     */
    private static void applySwitchStyle(Switch toggle, Context context) {
        final int light = switchColor(context, "yt_ref_color_constants_baseline_opaque_white1_overlay10", 0xFFE6E6E6);
        final int dark = switchColor(context, "yt_ref_color_constants_baseline_opaque_black3_overlay10", 0xFF272727);
        final int inactive = switchColor(context, "yt_ref_color_constants_default_grey50", 0xFF717171);
        final int[][] states = {{android.R.attr.state_checked}, {}};

        // Switch gives its thumb the full track height. Keep the circle at its own size.
        LayerDrawable thumb = new LayerDrawable(new Drawable[]{switchShape(18, 18, ColorStateList.valueOf(dark))});
        thumb.setLayerSize(0, Dim.dp(18), Dim.dp(18));
        thumb.setLayerGravity(0, Gravity.CENTER);

        // Move the off glyph inward without changing the measured width of the thumb.
        LayerDrawable closedThumb = (LayerDrawable) thumb.getConstantState().newDrawable().mutate();
        final int closedInset = Dim.dp(1.5f);
        closedThumb.setLayerInset(0, closedInset, 0, -closedInset, 0);

        final boolean rtl = context.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        StateListDrawable thumbStates = new StateListDrawable();
        thumbStates.addState(states[0], rtl ? closedThumb : thumb);
        thumbStates.addState(states[1], rtl ? thumb : closedThumb);
        toggle.setThumbDrawable(new InsetDrawable(thumbStates, Dim.dp1, 0, Dim.dp1, 0));
        toggle.setThumbTintList(null);

        GradientDrawable track = switchShape(37, 22, new ColorStateList(states, new int[]{light, inactive}));
        toggle.setTrackDrawable(new InsetDrawable(track, Dim.dp(1.5f), Dim.dp1, Dim.dp(1.5f), Dim.dp1));
        toggle.setTrackTintList(null);

        toggle.setSwitchMinWidth(Dim.dp40);
        toggle.setShowText(false);
    }
}
