/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.preference.WebsiteSignInPreference;
import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public final class SpoofVideoStreamsWebsiteSignInPreference extends WebsiteSignInPreference {

    public SpoofVideoStreamsWebsiteSignInPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SpoofVideoStreamsWebsiteSignInPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SpoofVideoStreamsWebsiteSignInPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SpoofVideoStreamsWebsiteSignInPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean isSettingEnabled() {
        return Settings.SPOOF_VIDEO_STREAMS.get() &&
                Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get() == ClientType.MWEB;
    }

    @Override
    protected ClientType getWebClient() {
        return ClientType.MWEB;
    }
}
