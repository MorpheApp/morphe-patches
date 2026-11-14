/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.BooleanSetting;

@SuppressWarnings("deprecation")
public class BooleanSettingPreference
        extends app.morphe.extension.shared.settings.preference.BooleanSettingPreference {

    public BooleanSettingPreference(Context context, BooleanSetting setting) {
        super(context, setting);
        this.setTitle(str(setting.key + "_title"));
        String summaryKey = setting.key + "_summary";
        if (ResourceUtils.getStringIdentifier(summaryKey) != 0) {
            this.setSummary(str(summaryKey));
        }
    }
}
