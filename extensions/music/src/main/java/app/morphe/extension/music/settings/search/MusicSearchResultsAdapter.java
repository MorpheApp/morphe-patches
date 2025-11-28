package app.morphe.extension.music.settings.search;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.search.BaseSearchResultsAdapter;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.settings.search.BaseSearchResultItem;

import java.util.List;

/**
 * Music-specific search results adapter.
 */
@SuppressWarnings("deprecation")
public class MusicSearchResultsAdapter extends BaseSearchResultsAdapter {

    public MusicSearchResultsAdapter(Context context, List<BaseSearchResultItem> items,
                                     BaseSearchViewController.BasePreferenceFragment fragment,
                                     BaseSearchViewController searchViewController) {
        super(context, items, fragment, searchViewController);
    }

    @Override
    protected PreferenceScreen getMainPreferenceScreen() {
        return fragment.getPreferenceScreenForSearch();
    }
}
