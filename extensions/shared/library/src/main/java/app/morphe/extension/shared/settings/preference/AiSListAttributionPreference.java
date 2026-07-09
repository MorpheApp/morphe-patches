/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

@SuppressWarnings("unused")
public class AiSListAttributionPreference extends URLLinkPreference {
    {
        externalURL = "https://aisloplist.com";
    }

    public AiSListAttributionPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public AiSListAttributionPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public AiSListAttributionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public AiSListAttributionPreference(Context context) {
        super(context);
    }
}
