package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Allows tapping the DeArrow about preference to open the DeArrow website.
 */
@SuppressWarnings("unused")
public class DeArrowAboutPreference extends URLLinkPreference {
    {
        externalURL = "https://dearrow.ajay.app";
    }

    public DeArrowAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public DeArrowAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public DeArrowAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public DeArrowAboutPreference(Context context) {
        super(context);
    }
}
