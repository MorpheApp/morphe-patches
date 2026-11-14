/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the DeArrow title guidelines popup the first time the DeArrow player button is turned on.
 */
@SuppressWarnings("unused")
public class DeArrowVoteButtonSwitchPreference extends GuidelinesSwitchPreference {

    public DeArrowVoteButtonSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DeArrowVoteButtonSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DeArrowVoteButtonSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DeArrowVoteButtonSwitchPreference(Context context) {
        super(context);
    }

    @Override
    protected BooleanSetting getSeenGuidelinesSetting() {
        return Settings.DEARROW_SEEN_GUIDELINES;
    }

    @Override
    protected String getGuidelinesUrl() {
        return DeArrowGuidelinesPreference.GUIDELINES_URL;
    }

    @Override
    protected String getPopupStringPrefix() {
        return "morphe_dearrow_guidelines_popup";
    }
}
