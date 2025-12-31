package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.OAuth2Preference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public final class SpoofStreamingDataSignInPreference extends OAuth2Preference {

    public SpoofStreamingDataSignInPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SpoofStreamingDataSignInPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SpoofStreamingDataSignInPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SpoofStreamingDataSignInPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean isSettingEnabled() {
        return Settings.SPOOF_VIDEO_STREAMS.get() &&
                Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get().supportsOAuth2;
    }
}
