package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.GeneralAdsPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;

@SuppressWarnings("deprecation")
public class AdsPreferenceCategory extends ConditionalPreferenceCategory {
    public AdsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Ads");
    }

    @Override
    public boolean getSettingsStatus() {
        return GeneralAdsPatch.patchEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_COMMENT_ADS, "Hide comment ads",
                "Hides ads in the comments section."
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_OLD_POST_ADS, "Hide feed ads",
                "Hides ads in the feed (old method)."
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_NEW_POST_ADS, "Hide feed ads",
                "Hides ads in the feed (new method)."
        ));
    }
}
