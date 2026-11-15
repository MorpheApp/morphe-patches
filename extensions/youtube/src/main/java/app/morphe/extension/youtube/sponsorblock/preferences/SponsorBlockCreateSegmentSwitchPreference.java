/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.preferences;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.preference.GuidelinesSwitchPreference;

/**
 * Shows the SponsorBlock submission guidelines popup the first time creating new segments is turned on.
 */
@SuppressWarnings("unused")
public class SponsorBlockCreateSegmentSwitchPreference extends GuidelinesSwitchPreference {

    public SponsorBlockCreateSegmentSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SponsorBlockCreateSegmentSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SponsorBlockCreateSegmentSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SponsorBlockCreateSegmentSwitchPreference(Context context) {
        super(context);
    }

    @Override
    protected BooleanSetting getSeenGuidelinesSetting() {
        return Settings.SB_SEEN_GUIDELINES;
    }

    @Override
    protected String getGuidelinesUrl() {
        return "https://wiki.sponsor.ajay.app/w/Guidelines";
    }

    @Override
    protected String getPopupStringPrefix() {
        return "morphe_sb_guidelines_popup";
    }
}
