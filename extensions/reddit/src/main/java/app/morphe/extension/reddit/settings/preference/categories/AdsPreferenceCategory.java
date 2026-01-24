package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.SettingsStatus;
import app.morphe.extension.reddit.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class AdsPreferenceCategory extends ConditionalPreferenceCategory {
    public AdsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Ads");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.adsCategoryEnabled();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new TogglePreference(
                context,
                "Hide comment ads",
                "Hides ads in the comments section.",
                Settings.HIDE_COMMENT_ADS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide feed ads",
                "Hides ads in the feed (old method).",
                Settings.HIDE_OLD_POST_ADS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide feed ads",
                "Hides ads in the feed (new method).",
                Settings.HIDE_NEW_POST_ADS
        ));
    }
}
