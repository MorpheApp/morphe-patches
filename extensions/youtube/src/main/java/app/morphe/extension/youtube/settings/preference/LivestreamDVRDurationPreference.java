/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class LivestreamDVRDurationPreference extends SwitchPreference {

    {
        // Expand livestream DVR duration is not available in SABR playback.
        String summary = SpoofVideoStreamsPatch.spoofingToClientWithSABROrSpoofingDisabled()
                ? str("morphe_expand_livestream_dvr_duration_not_available")
                : str("morphe_expand_livestream_dvr_duration_summary");
        setSummary(summary);
    }

    public LivestreamDVRDurationPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public LivestreamDVRDurationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public LivestreamDVRDurationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public LivestreamDVRDurationPreference(Context context) {
        super(context);
    }
}
