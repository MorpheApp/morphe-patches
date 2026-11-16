/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class HideAudioFlyoutMenuPreference extends BooleanSettingPreference {

    {
        // Audio menu is not available if spoofing to most client types.
        if (SpoofVideoStreamsPatch.spoofingToClientWithNoMultiAudioStreams()) {
            String summary = str("morphe_hide_player_flyout_audio_track_not_available");
            setSummary(summary);
        }
    }

    public HideAudioFlyoutMenuPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public HideAudioFlyoutMenuPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public HideAudioFlyoutMenuPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public HideAudioFlyoutMenuPreference(Context context) {
        super(context);
    }
}
