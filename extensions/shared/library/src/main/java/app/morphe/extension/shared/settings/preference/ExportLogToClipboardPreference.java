package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * A custom preference that displays a preview of Morphe debug logs.
 * Invokes the {@link LogBufferManager#showLogDialog} method.
 */
@SuppressWarnings({"deprecation", "unused"})
public class ExportLogToClipboardPreference extends Preference {

    {
        setOnPreferenceClickListener(pref -> {
            LogBufferManager.showLogDialog(getContext());
            return true;
        });
    }

    public ExportLogToClipboardPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ExportLogToClipboardPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ExportLogToClipboardPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ExportLogToClipboardPreference(Context context) {
        super(context);
    }
}
