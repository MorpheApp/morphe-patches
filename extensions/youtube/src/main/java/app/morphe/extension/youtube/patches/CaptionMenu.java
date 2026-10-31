/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.app.Dialog;
import android.graphics.Typeface;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.util.List;
import android.widget.ListView;
import android.widget.Switch;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.preference.CaptionLanguagePreference;

/** Native captions-menu control; language selection shares the Morphe preference catalogue. */
public final class CaptionMenu {
    private static final Map<ViewGroup, Boolean> menus = new WeakHashMap<>();

    private CaptionMenu() {}

    public static void addToggle(ListView list) {
        if (!StableCaptionBlocks.blockLayoutEnabled() || menus.containsKey(list)) return;
        try {
            list.addFooterView(createToggle(list.getContext()), null, false);
            menus.put(list, true);
        } catch (Exception ex) {
            Logger.printException(() -> "Unable to add bilingual caption control", ex);
        }
    }

    private static WeakReference<Dialog> activeDialog = new WeakReference<>(null);
    private static volatile boolean pendingSheet;

    public static void onCaptionSheet(CharSequence path, List<Object> ignored) {
        if (!Utils.startsWith(path, "captions_sheet_content.e")) return;
        pendingSheet = true;
        Utils.runOnMainThread(() -> decorate(activeDialog.get()));
    }

    public static void onDialog(Dialog dialog) {
        if (dialog == null || !StableCaptionBlocks.blockLayoutEnabled()) return;
        activeDialog = new WeakReference<>(dialog);
        dialog.getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> decorate(dialog));
    }

    /** Keep the adapter and its item indices intact; reserve a separate native control row. */
    private static void decorate(Dialog dialog) {
        if (!pendingSheet || dialog == null || dialog != activeDialog.get() || !dialog.isShowing()) return;
        int id = ResourceUtils.getIdentifier(ResourceType.ID, "bottom_sheet_list");
        View view = dialog.findViewById(id);
        if (!(view instanceof ViewGroup list) || !(list.getParent() instanceof ViewGroup parent)) return;
        pendingSheet = false;
        if (list instanceof ListView) return;
        if (menus.containsKey(list)) {
            if (list.getParent() instanceof LinearLayout wrapper) moveHeaderInset(list, wrapper);
            return;
        }
        try {
            int index = parent.indexOfChild(list);
            ViewGroup.LayoutParams params = list.getLayoutParams();
            LinearLayout wrapper = new LinearLayout(list.getContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            Switch toggle = createToggle(list.getContext());
            moveHeaderInset(list, wrapper);
            parent.removeView(list);
            wrapper.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            wrapper.addView(list, new LinearLayout.LayoutParams(-1, -2, 1));
            parent.addView(wrapper, index, params);
            menus.put(list, true);
        } catch (Exception ex) {
            Logger.printException(() -> "Unable to decorate bilingual caption sheet", ex);
        }
    }

    /** The native recycler reserves space underneath its overlaid sheet header. */
    private static void moveHeaderInset(View list, LinearLayout wrapper) {
        int inset = list.getPaddingTop();
        if (inset == 0) return;
        wrapper.setPaddingRelative(0, inset, 0, 0);
        list.setPaddingRelative(list.getPaddingStart(), 0, list.getPaddingEnd(), list.getPaddingBottom());
    }

    private static int switchColor(Context context, String name, int fallback) {
        int id = ResourceUtils.getIdentifier(ResourceType.COLOR, name);
        return id == 0 ? fallback : context.getColor(id);
    }

    private static GradientDrawable switchShape(float density, int width, int height, ColorStateList colors) {
        GradientDrawable shape = new GradientDrawable();
        shape.setSize(Math.round(width * density), Math.round(height * density));
        shape.setCornerRadius(height * density / 2);
        shape.setColor(colors);
        return shape;
    }

    private static Switch createToggle(Context context) {
        Switch toggle = new Switch(context);
        toggle.setTag("morphe_dual_captions");
        toggle.setText(StringRef.str("morphe_dual_captions_enabled_title"));
        int textStyle = ResourceUtils.getIdentifier(ResourceType.ATTR, "yt_sys_typography_typestack_body_l_heavy");
        ThemeUtils.setThemeTextStyle(toggle, textStyle != 0 ? textStyle : android.R.attr.textAppearanceListItem,
                android.R.attr.textColorPrimary);
        if (textStyle == 0) toggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float density = context.getResources().getDisplayMetrics().density;
        int padding = Math.round(16 * density);
        toggle.setPaddingRelative(padding, 0, Math.round(20 * density), 0);
        int iconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "quantum_ic_closed_caption_white_24");
        if (iconId != 0) {
            var icon = context.getDrawable(iconId).mutate();
            icon.setTint(ThemeUtils.getAppForegroundColor());
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            toggle.setCompoundDrawablePadding(Math.round(12 * density));
        }
        int light = switchColor(context, "yt_ref_color_constants_baseline_opaque_white1_overlay10", 0xffe6e6e6);
        int dark = switchColor(context, "yt_ref_color_constants_baseline_opaque_black3_overlay10", 0xff272727);
        int inactive = switchColor(context, "yt_ref_color_constants_default_grey50", 0xff717171);
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[0]};
        // Match the native 40x24dp glyph, including its transparent edge margins.
        // Switch gives its thumb the full track height; keep the circle at its own size.
        LayerDrawable thumb = new LayerDrawable(new Drawable[]{
                switchShape(density, 18, 18, ColorStateList.valueOf(dark))});
        thumb.setLayerSize(0, Math.round(18 * density), Math.round(18 * density));
        thumb.setLayerGravity(0, Gravity.CENTER);
        LayerDrawable closedThumb = (LayerDrawable) thumb.getConstantState().newDrawable().mutate();
        int closedInset = Math.round(1.5f * density);
        // Move the off glyph inward without changing the thumb's measured width.
        closedThumb.setLayerInset(0, closedInset, 0, -closedInset, 0);
        StateListDrawable thumbStates = new StateListDrawable();
        boolean rtl = context.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        thumbStates.addState(states[0], rtl ? closedThumb : thumb);
        thumbStates.addState(states[1], rtl ? thumb : closedThumb);
        int thumbInset = Math.round(density);
        toggle.setThumbDrawable(new InsetDrawable(thumbStates, thumbInset, 0, thumbInset, 0));
        GradientDrawable track = switchShape(density, 37, 22, new ColorStateList(states,
                new int[]{light, inactive}));
        int insetX = Math.round(1.5f * density), insetY = Math.round(density);
        toggle.setTrackDrawable(new InsetDrawable(track, insetX, insetY, insetX, insetY));
        toggle.setSwitchMinWidth(Math.round(40 * density));
        toggle.setThumbTintList(null);
        toggle.setTrackTintList(null);
        toggle.setShowText(false);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(Math.round(48 * density));
        toggle.setChecked(CaptionTracks.dualCaptionEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && AutoTranslateCaptionsPatch.translationLanguage(Settings.SECONDARY_CAPTION_LANGUAGE.get()) == null) {
                CaptionLanguagePreference languages = new CaptionLanguagePreference(context, null);
                CharSequence[] values = languages.getEntryValues();
                Dialog dialog = CustomDialogListPreference.createListDialog(context,
                    StringRef.str("morphe_secondary_caption_language_title"), languages.getEntries(), values,
                    Settings.SECONDARY_CAPTION_LANGUAGE.get(), index -> {
                        Settings.SECONDARY_CAPTION_LANGUAGE.save(values[index].toString());
                        CaptionTracks.setDualCaptionsEnabled(!values[index].equals("off"));
                    });
                dialog.setOnDismissListener(ignored -> toggle.setChecked(CaptionTracks.dualCaptionEnabled()));
                dialog.show();
            } else CaptionTracks.setDualCaptionsEnabled(enabled);
        });
        return toggle;
    }
}
