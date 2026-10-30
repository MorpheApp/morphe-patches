/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.app.Dialog;
import android.view.Gravity;
import android.widget.ListView;
import android.widget.Switch;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.preference.DualCaptionLanguagePreference;

/** Native captions-menu control; language selection shares the Morphe preference catalogue. */
public final class CaptionMenu {
    private static final Map<ListView, Boolean> menus = new WeakHashMap<>();

    private CaptionMenu() {}

    public static void addToggle(ListView list) {
        if (!StableCaptionBlocks.blockLayoutEnabled() || menus.containsKey(list)) return;
        try {
            Switch toggle = new Switch(list.getContext());
            toggle.setTag("morphe_dual_captions");
            toggle.setText(StringRef.str("morphe_dual_captions_enabled_title"));
            ThemeUtils.setThemeTextStyle(toggle, android.R.attr.textAppearanceListItem, android.R.attr.textColorPrimary);
            float density = list.getResources().getDisplayMetrics().density;
            int padding = Math.round(16 * density);
            toggle.setPaddingRelative(padding, 0, padding, 0);
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setMinHeight(Math.round(48 * density));
            toggle.setChecked(CaptionTracks.dualCaptionEnabled());
            toggle.setOnCheckedChangeListener((button, enabled) -> {
                if (enabled && AutoTranslateCaptionsPatch.translationLanguage(Settings.SECONDARY_CAPTION_LANGUAGE.get()) == null) {
                    DualCaptionLanguagePreference languages = new DualCaptionLanguagePreference(list.getContext(), null);
                    CharSequence[] values = languages.getEntryValues();
                    Dialog dialog = CustomDialogListPreference.createListDialog(list.getContext(),
                            StringRef.str("morphe_secondary_caption_language_title"), languages.getEntries(), values,
                            Settings.SECONDARY_CAPTION_LANGUAGE.get(), index -> {
                                Settings.SECONDARY_CAPTION_LANGUAGE.save(values[index].toString());
                                CaptionTracks.setDualCaptionsEnabled(!values[index].equals("off"));
                            });
                    dialog.setOnDismissListener(ignored -> toggle.setChecked(CaptionTracks.dualCaptionEnabled()));
                    dialog.show();
                } else CaptionTracks.setDualCaptionsEnabled(enabled);
            });
            list.addFooterView(toggle, null, false);
            menus.put(list, true);
        } catch (Exception ex) {
            Logger.printException(() -> "Unable to add bilingual caption control", ex);
        }
    }
}
