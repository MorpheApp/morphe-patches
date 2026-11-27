/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4844
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.SavePushNotificationsPatch;

/**
 * Exports or clears the push notifications saved by {@link SavePushNotificationsPatch}.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SavedPushNotificationsPreference extends Preference {

    {
        setOnPreferenceClickListener(pref -> {
            showDialog();
            return true;
        });
    }

    public SavedPushNotificationsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public SavedPushNotificationsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public SavedPushNotificationsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public SavedPushNotificationsPreference(Context context) {
        super(context);
    }

    private void showDialog() {
        try {
            String notifications = SavePushNotificationsPatch.getSavedPushNotifications();
            if (notifications.isEmpty()) {
                Utils.showToastShort(str("morphe_debug_saved_push_notifications_none_found"));
                return;
            }

            // Each line is one notification.
            long count = notifications.chars().filter(c -> c == '\n').count();

            CustomDialog.create(
                    getContext(),
                    str("morphe_debug_saved_push_notifications_title"),
                    str("morphe_debug_saved_push_notifications_count", count),
                    null,
                    str("morphe_settings_export_file"),
                    () -> export(notifications),
                    () -> {},
                    str("morphe_settings_clear"),
                    SavedPushNotificationsPreference::clear,
                    true
            ).first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }

    private static void export(String notifications) {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) {
            return;
        }

        fragment.exportTextActivity(
                AbstractPreferenceFragment.exportFileName("Push_notifications"),
                notifications,
                success -> Utils.showToastShort(str(success
                        ? "morphe_debug_saved_push_notifications_export_success"
                        : "morphe_debug_saved_push_notifications_export_failed"))
        );
    }

    private static void clear() {
        try {
            SavePushNotificationsPatch.clearSavedPushNotifications();
            Utils.showToastShort(str("morphe_debug_saved_push_notifications_cleared"));
        } catch (Exception ex) {
            Logger.printException(() -> "clear failure", ex);
        }
    }
}
