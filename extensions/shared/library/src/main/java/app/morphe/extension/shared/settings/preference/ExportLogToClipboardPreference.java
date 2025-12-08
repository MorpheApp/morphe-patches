package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * A custom preference that triggers exporting Morphe debug logs to the clipboard when clicked.
 * Invokes the {@link LogBufferManager#exportToClipboard} method.
 */
@SuppressWarnings({"deprecation", "unused"})
public class ExportLogToClipboardPreference extends Preference {

    {
        setOnPreferenceClickListener(pref -> {
            LogBufferManager.exportToClipboard();
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
