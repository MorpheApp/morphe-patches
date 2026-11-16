/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.MwebSignInPreference;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SpoofVideoStreamsMwebSignInPreference extends MwebSignInPreference {

    public SpoofVideoStreamsMwebSignInPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SpoofVideoStreamsMwebSignInPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SpoofVideoStreamsMwebSignInPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SpoofVideoStreamsMwebSignInPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean isSettingEnabled() {
        return Settings.SPOOF_VIDEO_STREAMS.get() &&
                Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get() == ClientType.MWEB;
    }
}
