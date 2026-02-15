package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.HideAdsPatch;
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
        return HideAdsPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_COMMENT_ADS, "Hide comment ads",
                "Hides ads in the comments section"
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_ADS, "Hide feed ads",
                "Hides ads in the feed"
        ));
    }
}
