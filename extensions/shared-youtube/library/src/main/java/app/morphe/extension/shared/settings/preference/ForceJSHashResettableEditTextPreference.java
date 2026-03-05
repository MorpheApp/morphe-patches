/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class ForceJSHashResettableEditTextPreference extends ResettableEditTextPreference {

    {
        super.setEnabled(SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get()
                && SpoofVideoStreamsPatch.getPreferredClient().requireJS);
    }

    public ForceJSHashResettableEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ForceJSHashResettableEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ForceJSHashResettableEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ForceJSHashResettableEditTextPreference(Context context) {
        super(context);
    }
}

