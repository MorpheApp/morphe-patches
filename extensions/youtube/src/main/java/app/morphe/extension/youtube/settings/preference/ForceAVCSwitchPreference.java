/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import java.util.List;import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class ForceAVCSwitchPreference extends SwitchPreference {

    // Spoof stream patch is not included/enabled, or is spoofing to a client that forcing AVC works.
    private static final boolean available = !SpoofVideoStreamsPatch.isPatchIncluded()
            || !SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get() || List.of(
            ClientType.ANDROID_CREATOR,
            ClientType.ANDROID_VR_1_65,
            ClientType.ANDROID_VR_1_64,
            ClientType.VISIONOS).contains(SpoofVideoStreamsPatch.getPreferredClient());

    {
        if (!available) {
            // Show why force audio is not available.
            String summary = str("morphe_force_avc_codec_not_available");
            super.setSummary(summary);
            super.setSummaryOn(summary);
            super.setSummaryOff(summary);
            super.setEnabled(false);
        }
    }

    public ForceAVCSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ForceAVCSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ForceAVCSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ForceAVCSwitchPreference(Context context) {
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

