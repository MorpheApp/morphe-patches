package app.morphe.extension.youtube.settings.search;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.search.BaseSearchResultsAdapter;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.settings.search.BaseSearchResultItem;

import java.util.List;

/**
 * YouTube-specific search results adapter.
 */
@SuppressWarnings("deprecation")
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
