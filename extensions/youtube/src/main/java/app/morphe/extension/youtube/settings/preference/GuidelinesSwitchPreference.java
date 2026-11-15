/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * SwitchPreference of a feature that submits to a crowdsourced service. The first time it is
 * turned on, it shows a popup that asks to read the submission guidelines of the service.
 * After the popup is closed the toggle behaves like a normal SwitchPreference.
 * <p>
 * The popup strings are named after {@link #getPopupStringPrefix()}, with the suffixes
 * {@code _title}, {@code _content}, {@code _open} and {@code _already_read}.
 */
@SuppressWarnings("deprecation")
public abstract class GuidelinesSwitchPreference extends BooleanSettingPreference {

    public GuidelinesSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installChangeGate();
    }

    public GuidelinesSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installChangeGate();
    }

    public GuidelinesSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installChangeGate();
    }

    public GuidelinesSwitchPreference(Context context) {
        super(context);
        installChangeGate();
    }

    /**
     * @return If the popup was already shown.
     */
    protected abstract BooleanSetting getSeenGuidelinesSetting();

    protected abstract String getGuidelinesUrl();

    protected abstract String getPopupStringPrefix();

    private void installChangeGate() {
        setOnPreferenceChangeListener((p, newValue) -> {
            boolean enabling = Boolean.TRUE.equals(newValue);
            if (enabling && !getSeenGuidelinesSetting().get()) {
                showGuidelinesPopup(p.getContext());
            }
            return true;
        });
    }

    private void showGuidelinesPopup(Context context) {
        try {
            String prefix = getPopupStringPrefix();
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str(prefix + "_title"),
                    str(prefix + "_content"),
                    null,
                    str(prefix + "_open"),
                    () -> Utils.openLink(getGuidelinesUrl()),
                    null,
                    str(prefix + "_already_read"),
                    () -> {},
                    true
            );
            dialogPair.first.setCancelable(false);
            dialogPair.first.setOnDismissListener(d -> getSeenGuidelinesSetting().save(true));
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showGuidelinesPopup failure", ex);
        }
    }
}
