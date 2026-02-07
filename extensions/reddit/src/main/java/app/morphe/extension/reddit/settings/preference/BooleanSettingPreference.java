package app.morphe.extension.reddit.settings.preference;

import android.content.Context;
import android.preference.SwitchPreference;

import app.morphe.extension.shared.settings.BooleanSetting;

@SuppressWarnings("deprecation")
public class BooleanSettingPreference extends SwitchPreference {
    public BooleanSettingPreference(Context context, BooleanSetting setting, String title, String summary) {
        super(context);
        this.setTitle(title);
        this.setSummary(summary);
        this.setKey(setting.key);
        this.setChecked(setting.get());
    }
}
