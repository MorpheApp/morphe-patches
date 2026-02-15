package app.morphe.extension.reddit.settings.preference;

import android.content.Context;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.ListView;

import app.morphe.extension.reddit.settings.preference.categories.AdsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.LayoutPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.MiscellaneousPreferenceCategory;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Preference fragment for Reddit Morphe settings
 */
@SuppressWarnings("deprecation")
public class RedditPreferenceFragment extends AbstractPreferenceFragment {

    @Override
    protected void initialize() {
        final Context context = getContext();

        // Currently no resources can be compiled for Reddit due to apktool limitations.
        // So all Reddit Strings are hard coded in extensions.
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

    @Override
    public void onResume() {
        super.onResume();

        View rootView = getView();
        if (rootView == null) return;

        ListView listView = rootView.findViewById(android.R.id.list);
        if (listView == null) return;

        // Hide divider.
        listView.setDivider(null);
        listView.setDividerHeight(0);
    }
}
