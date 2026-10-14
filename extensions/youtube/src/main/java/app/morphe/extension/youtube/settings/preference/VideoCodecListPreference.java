/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.patches.DisableVideoCodecsPatch;
import app.morphe.extension.youtube.patches.DisableVideoCodecsPatch.VideoCodec;

/**
 * Video codec list that shows which spoof clients are needed,
 * if AVC is selected and the spoofed client cannot use it so VP9 is used instead.
 */
@SuppressWarnings({"unused", "deprecation"})
public class VideoCodecListPreference extends CustomDialogListPreference {

    public VideoCodecListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public VideoCodecListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VideoCodecListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoCodecListPreference(Context context) {
        super(context);
    }

    @Override
    public void setSummary(@Nullable CharSequence summary) {
        if (VideoCodec.AVC.name().equals(getValue()) && !DisableVideoCodecsPatch.isAvcAvailable()) {
            String note = str("morphe_video_codec_avc_not_available");
            summary = summary == null ? note : summary + "\n\n" + note;
        }
        super.setSummary(summary);
    }
}
