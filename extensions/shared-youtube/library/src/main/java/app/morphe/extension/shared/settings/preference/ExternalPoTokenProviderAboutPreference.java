/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

@SuppressWarnings("unused")
public class ExternalPoTokenProviderAboutPreference extends URLLinkPreference {
    {
        externalURL = "https://github.com/MorpheApp/PotHelper/releases/latest";
    }

    public ExternalPoTokenProviderAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ExternalPoTokenProviderAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ExternalPoTokenProviderAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ExternalPoTokenProviderAboutPreference(Context context) {
        super(context);
    }
}

