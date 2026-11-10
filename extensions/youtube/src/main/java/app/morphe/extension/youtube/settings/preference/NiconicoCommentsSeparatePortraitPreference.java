/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4515
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.settings.Settings;

/**
 * Turning on separate portrait settings starts the portrait values from the current values,
 * so the comments look the same until a portrait value is changed.
 */
@SuppressWarnings({"deprecation", "unused"})
public class NiconicoCommentsSeparatePortraitPreference extends SwitchPreference {

    public NiconicoCommentsSeparatePortraitPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public NiconicoCommentsSeparatePortraitPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public NiconicoCommentsSeparatePortraitPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public NiconicoCommentsSeparatePortraitPreference(Context context) {
        super(context);
    }

    /**
     * Also called when the switch itself or a search result is tapped, which skip onClick().
     * The setting is still off then. When the settings are loaded or imported, the setting is
     * already on and the imported portrait values are kept.
     */
    @Override
    public void setChecked(boolean checked) {
        if (checked && !Settings.NICONICO_COMMENTS_SEPARATE_PORTRAIT.get()) {
            Settings.NICONICO_COMMENTS_PORTRAIT_FONT_SIZE.save(Settings.NICONICO_COMMENTS_FONT_SIZE.get());
            Settings.NICONICO_COMMENTS_PORTRAIT_TRAVEL_DURATION.save(Settings.NICONICO_COMMENTS_TRAVEL_DURATION.get());
            Settings.NICONICO_COMMENTS_PORTRAIT_DISPLAY_AREA.save(Settings.NICONICO_COMMENTS_DISPLAY_AREA.get());
        }
        super.setChecked(checked);
    }
}
