/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Formats the summary text bullet points into Spanned text for better presentation.
 */
@SuppressWarnings({"unused", "deprecation"})
public class BulletPointSwitchPreference extends BooleanSettingPreference {

    public BulletPointSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public BulletPointSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public BulletPointSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BulletPointSwitchPreference(Context context) {
        super(context);
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(BulletPointPreference.formatIntoBulletPoints(summary));
    }
}
