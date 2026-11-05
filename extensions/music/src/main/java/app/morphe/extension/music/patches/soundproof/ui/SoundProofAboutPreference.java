/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.ui;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

@SuppressWarnings("unused")
public class SoundProofAboutPreference extends URLLinkPreference {
    {
        externalURL = "https://github.com/NuclearBlox/SoundProof-extension";
    }

    public SoundProofAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
