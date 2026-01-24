package app.morphe.extension.reddit.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import org.jetbrains.annotations.NotNull;

import app.morphe.extension.reddit.settings.preference.categories.AdsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.LayoutPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.MiscellaneousPreferenceCategory;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Preference fragment for RVX Morphed settings
 */
@SuppressWarnings("deprecation")
public class RVXMorphedPreferenceFragment extends AbstractPreferenceFragment {

    @Override
    protected void syncSettingWithPreference(@NotNull Preference pref,
                                             @NotNull Setting<?> setting,
                                             boolean applySettingToPreference) {
        super.syncSettingWithPreference(pref, setting, applySettingToPreference);
    }

    @Override
    protected void initialize() {
        final Context context = getContext();

        // Currently no resources can be compiled for Reddit (fails with aapt error).
        // So all Reddit Strings are hard coded in integrations.
        restartDialogTitle = "Restart required";
        restartDialogMessage = "Restart the app for this change to take effect.";
        restartDialogButtonText = "Restart";

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        // Custom categories reference app specific Settings class.
        new AdsPreferenceCategory(context, preferenceScreen);
        new LayoutPreferenceCategory(context, preferenceScreen);
        new MiscellaneousPreferenceCategory(context, preferenceScreen);
    }
}
