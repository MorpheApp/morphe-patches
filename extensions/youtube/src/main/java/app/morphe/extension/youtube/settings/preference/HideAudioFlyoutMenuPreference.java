package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class HideAudioFlyoutMenuPreference extends SwitchPreference {

    {
        // Audio menu is not available if spoofing to most client types.
        if (SpoofVideoStreamsPatch.spoofingToClientWithNoMultiAudioStreams()) {
            String summary = str("morphe_hide_player_flyout_audio_track_not_available");
            setSummary(summary);
            setSummaryOn(summary);
            setSummaryOff(summary);
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
