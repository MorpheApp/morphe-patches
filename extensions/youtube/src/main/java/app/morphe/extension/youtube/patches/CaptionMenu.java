/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.content.res.ColorStateList;
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
import app.morphe.extension.youtube.settings.preference.DualCaptionLanguagePreference;

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
        if (list instanceof ListView || menus.containsKey(list)) return;
        try {
            int index = parent.indexOfChild(list);
            ViewGroup.LayoutParams params = list.getLayoutParams();
            LinearLayout wrapper = new LinearLayout(list.getContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            Switch toggle = createToggle(list.getContext());
            parent.removeView(list);
            wrapper.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            wrapper.addView(list, new LinearLayout.LayoutParams(-1, -2, 1));
            parent.addView(wrapper, index, params);
            menus.put(list, true);
        } catch (Exception ex) {
            Logger.printException(() -> "Unable to decorate bilingual caption sheet", ex);
        }
    }

    private static Switch createToggle(android.content.Context context) {
        Switch toggle = new Switch(context);
        toggle.setTag("morphe_dual_captions");
        toggle.setText(StringRef.str("morphe_dual_captions_enabled_title"));
        ThemeUtils.setThemeTextStyle(toggle, android.R.attr.textAppearanceListItem, android.R.attr.textColorPrimary);
        float density = context.getResources().getDisplayMetrics().density;
        int padding = Math.round(16 * density);
        toggle.setPaddingRelative(padding, 0, padding, 0);
        int iconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "quantum_ic_closed_caption_white_24");
        if (iconId != 0) {
            var icon = context.getDrawable(iconId).mutate();
            icon.setTint(ThemeUtils.getAppForegroundColor());
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            toggle.setCompoundDrawablePadding(padding);
        }
        int thumb = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "mtrl_switch_thumb");
        int track = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "mtrl_switch_track");
        if (thumb != 0 && track != 0) {
            toggle.setThumbDrawable(context.getDrawable(thumb));
            toggle.setTrackDrawable(context.getDrawable(track));
            toggle.setThumbTintList(ColorStateList.valueOf(ThemeUtils.getDialogBackgroundColor()));
            toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{ThemeUtils.getAppForegroundColor(), ThemeUtils.getAppForegroundColor() & 0x66ffffff}));
            toggle.setShowText(false);
        }
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(Math.round(48 * density));
        toggle.setChecked(CaptionTracks.dualCaptionEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && AutoTranslateCaptionsPatch.translationLanguage(Settings.SECONDARY_CAPTION_LANGUAGE.get()) == null) {
                DualCaptionLanguagePreference languages = new DualCaptionLanguagePreference(context, null);
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
