package app.morphe.extension.youtube.returnyoutubedislike.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.youtube.returnyoutubedislike.requests.ReturnYouTubeDislikeAPI;

@SuppressWarnings({"unused", "deprecation"})
public class ReturnYouTubeDislikeDebugStatsPreferenceCategory extends PreferenceCategory {

    private static final boolean SHOW_RYD_DEBUG_STATS = BaseSettings.DEBUG.get();

    private static String createSummaryText(int value, String summaryStringZeroKey, String summaryStringOneOrMoreKey) {
        if (value == 0) {
            return str(summaryStringZeroKey);
        }
        return str(summaryStringOneOrMoreKey, value);
    }

    private static String createMillisecondStringFromNumber(long number) {
        return String.format(str("morphe_ryd_statistics_millisecond_text"), number);
    }

    public ReturnYouTubeDislikeDebugStatsPreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ReturnYouTubeDislikeDebugStatsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ReturnYouTubeDislikeDebugStatsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        if (!SHOW_RYD_DEBUG_STATS) {
            // Use an empty view to hide without removing.
            return new View(getContext());
        }

        return super.onCreateView(parent);
    }

    protected void onAttachedToActivity() {
        try {
            super.onAttachedToActivity();
            if (!SHOW_RYD_DEBUG_STATS) {
                return;
            }

            Logger.printDebug(() -> "Updating stats preferences");
            removeAll();

            addStatisticPreference(
                    "morphe_ryd_statistics_getFetchCallResponseTimeAverage_title",
                    createMillisecondStringFromNumber(ReturnYouTubeDislikeAPI.getFetchCallResponseTimeAverage())
            );

            addStatisticPreference(
                    "morphe_ryd_statistics_getFetchCallResponseTimeMin_title",
                    createMillisecondStringFromNumber(ReturnYouTubeDislikeAPI.getFetchCallResponseTimeMin())
            );

            addStatisticPreference(
                    "morphe_ryd_statistics_getFetchCallResponseTimeMax_title",
                    createMillisecondStringFromNumber(ReturnYouTubeDislikeAPI.getFetchCallResponseTimeMax())
            );

            String fetchCallTimeWaitingLastSummary;
            final long fetchCallTimeWaitingLast = ReturnYouTubeDislikeAPI.getFetchCallResponseTimeLast();
            if (fetchCallTimeWaitingLast == ReturnYouTubeDislikeAPI.FETCH_CALL_RESPONSE_TIME_VALUE_RATE_LIMIT) {
                fetchCallTimeWaitingLastSummary = str("morphe_ryd_statistics_getFetchCallResponseTimeLast_rate_limit_summary");
            } else {
                fetchCallTimeWaitingLastSummary = createMillisecondStringFromNumber(fetchCallTimeWaitingLast);
            }
            addStatisticPreference(
                    "morphe_ryd_statistics_getFetchCallResponseTimeLast_title",
                    fetchCallTimeWaitingLastSummary
            );

            addStatisticPreference(
                    "morphe_ryd_statistics_getFetchCallCount_title",
                    createSummaryText(ReturnYouTubeDislikeAPI.getFetchCallCount(),
                            "morphe_ryd_statistics_getFetchCallCount_zero_summary",
                            "morphe_ryd_statistics_getFetchCallCount_non_zero_summary"
                    )
            );

            addStatisticPreference(
                    "morphe_ryd_statistics_getFetchCallNumberOfFailures_title",
                    createSummaryText(ReturnYouTubeDislikeAPI.getFetchCallNumberOfFailures(),
                            "morphe_ryd_statistics_getFetchCallNumberOfFailures_zero_summary",
                            "morphe_ryd_statistics_getFetchCallNumberOfFailures_non_zero_summary"
                    )
            );

            addStatisticPreference(
                    "morphe_ryd_statistics_getNumberOfRateLimitRequestsEncountered_title",
                    createSummaryText(ReturnYouTubeDislikeAPI.getNumberOfRateLimitRequestsEncountered(),
                            "morphe_ryd_statistics_getNumberOfRateLimitRequestsEncountered_zero_summary",
                            "morphe_ryd_statistics_getNumberOfRateLimitRequestsEncountered_non_zero_summary"
                    )
            );
        } catch (Exception ex) {
            Logger.printException(() -> "onAttachedToActivity failure", ex);
        }
    }

    private void addStatisticPreference(String titleKey, String SummaryText) {
        Preference statisticPreference = new Preference(getContext());
        statisticPreference.setSelectable(false);
        statisticPreference.setTitle(str(titleKey));
        statisticPreference.setSummary(SummaryText);
        addPreference(statisticPreference);
    }
}
