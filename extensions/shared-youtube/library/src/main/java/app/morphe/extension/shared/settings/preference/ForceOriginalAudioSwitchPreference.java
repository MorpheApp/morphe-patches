/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class ForceOriginalAudioSwitchPreference extends BooleanSettingPreference {

    // Spoof stream patch is not included, or is not spoofing to a client with multiple audio streams.
    private static final boolean available = !SpoofVideoStreamsPatch.isPatchIncluded()
            || !SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get()
            || SpoofVideoStreamsPatch.getPreferredClient() != ClientType.ANDROID_CREATOR;

    {
        if (!available) {
            // Show why force audio is not available.
            String summary = str("morphe_force_original_audio_not_available");
            super.setSummary(summary);
            super.setEnabled(false);
        }
    }

    public ForceOriginalAudioSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ForceOriginalAudioSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ForceOriginalAudioSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ForceOriginalAudioSwitchPreference(Context context) {
        super(context);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (!available) {
            return;
        }

        super.setEnabled(enabled);
    }

    @Override
    public void setSummary(CharSequence summary) {
        if (!available) {
            return;
        }

        super.setSummary(summary);
    }
}

