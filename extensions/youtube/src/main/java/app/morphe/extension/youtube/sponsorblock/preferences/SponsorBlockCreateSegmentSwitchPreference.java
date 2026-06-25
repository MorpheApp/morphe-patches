/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.preferences;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.SwitchPreference;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.settings.Settings;

/**
 * SwitchPreference that, on its first OFF→ON transition, shows the SponsorBlock submission
 * guidelines popup before persisting the new value. After the user has acknowledged the popup
 * ({@code SB_SEEN_GUIDELINES}) the toggle behaves like a normal SwitchPreference.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SponsorBlockCreateSegmentSwitchPreference extends SwitchPreference {

    private static final Uri GUIDELINES_URI = Uri.parse("https://wiki.sponsor.ajay.app/w/Guidelines");

    public SponsorBlockCreateSegmentSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installChangeGate();
    }

    public SponsorBlockCreateSegmentSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installChangeGate();
    }

    public SponsorBlockCreateSegmentSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installChangeGate();
    }

    public SponsorBlockCreateSegmentSwitchPreference(Context context) {
        super(context);
        installChangeGate();
    }

    private void installChangeGate() {
        setOnPreferenceChangeListener((p, newValue) -> {
            boolean enabling = Boolean.TRUE.equals(newValue);
            if (enabling && !Settings.SB_SEEN_GUIDELINES.get()) {
                showGuidelinesPopup(p.getContext());
            }
            return true;
        });
    }

    private void showGuidelinesPopup(Context context) {
        try {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_sb_guidelines_popup_title"),
                    str("morphe_sb_guidelines_popup_content"),
                    null,
                    str("morphe_sb_guidelines_popup_open"),
                    this::openGuidelines,
                    null,
                    str("morphe_sb_guidelines_popup_already_read"),
                    () -> {},
                    true
            );
            dialogPair.first.setCancelable(false);
            dialogPair.first.setOnDismissListener(d -> Settings.SB_SEEN_GUIDELINES.save(true));
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "Show guidelines popup failure", ex);
        }
    }

    private void openGuidelines() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, GUIDELINES_URI);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Open guidelines failure", ex);
        }
    }
}
