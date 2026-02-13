package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * A custom preference that clears the Morphe debug log buffer when clicked.
 * Invokes the {@link LogBufferManager#clearLogBuffer} method.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ClearLogBufferPreference extends Preference {

    {
        setOnPreferenceClickListener(pref -> {
            LogBufferManager.clearLogBuffer();
            return true;
        });
    }

    public ClearLogBufferPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ClearLogBufferPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ClearLogBufferPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ClearLogBufferPreference(Context context) {
        super(context);
    }
}
