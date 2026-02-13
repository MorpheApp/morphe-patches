package app.morphe.extension.youtube.settings.search;

import android.content.Context;
import android.preference.PreferenceScreen;

import java.util.List;

import app.morphe.extension.shared.settings.search.BaseSearchResultItem;
import app.morphe.extension.shared.settings.search.BaseSearchResultsAdapter;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;

/**
 * YouTube-specific search results adapter.
 */
public class YouTubeSearchResultsAdapter extends BaseSearchResultsAdapter {

    public YouTubeSearchResultsAdapter(Context context, List<BaseSearchResultItem> items,
                                       BaseSearchViewController.BasePreferenceFragment fragment,
                                       BaseSearchViewController searchViewController) {
        super(context, items, fragment, searchViewController);
    }

    @Override
    protected PreferenceScreen getMainPreferenceScreen() {
        return fragment.getPreferenceScreenForSearch();
    }
}
