/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.patches.BaseAppRefreshRatePatch;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings({"unused", "deprecation"})
public class AppRefreshRateListPreference extends CustomDialogListPreference {

    public AppRefreshRateListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public AppRefreshRateListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public AppRefreshRateListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AppRefreshRateListPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        String[] available = BaseAppRefreshRatePatch.getAvailableRefreshRates();

        List<String> entries = new ArrayList<>();
        List<String> values = new ArrayList<>();

        entries.add(str("morphe_app_refresh_rate_default"));
        values.add(SharedYouTubeSettings.APP_REFRESH_RATE.defaultValue);

        if (available != null) {
            for (String fps : available) {
                entries.add(str("morphe_app_refresh_rate_fps", fps));
                values.add(fps);
            }
        }

        setEntries(entries.toArray(new CharSequence[0]));
        setEntryValues(values.toArray(new CharSequence[0]));
    }
}
