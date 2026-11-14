package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import java.io.IOException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.PushNotificationPayloadCapture;

@SuppressWarnings({"unused", "deprecation"})
public final class ClearPushNotificationPayloadsPreference extends Preference {
    public ClearPushNotificationPayloadsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public ClearPushNotificationPayloadsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ClearPushNotificationPayloadsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ClearPushNotificationPayloadsPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOnPreferenceClickListener(preference -> {
            try {
                PushNotificationPayloadCapture.clearCapturedPayloads(getContext());
                Utils.showToastShort(str("morphe_push_notification_payloads_cleared"));
            } catch (IOException | RuntimeException ex) {
                Logger.printException(() -> "Could not clear push notification payload capture", ex);
                Utils.showToastShort(str("morphe_push_notification_payloads_clear_failed"));
            }
            return true;
        });
    }
}
