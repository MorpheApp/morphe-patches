package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings({"deprecation", "unused"})
public class DrcAudioSwitchPreference extends SwitchPreference {

    {
        setKey("morphe_disable_drc_audio");
        setSummaryOn(str("morphe_drc_audio_summary_on"));
        setSummaryOff(str("morphe_drc_audio_summary_off"));
        setChecked(BaseSettings.DISABLE_DRC_AUDIO.get());

        setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean) {
                getSharedPreferences().edit()
                        .putBoolean("morphe_disable_drc_audio", (Boolean) newValue)
                        .apply();
            }
            return true;
        });
    }

    public DrcAudioSwitchPreference(Context context) {
        super(context);
    }

    public DrcAudioSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DrcAudioSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DrcAudioSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}