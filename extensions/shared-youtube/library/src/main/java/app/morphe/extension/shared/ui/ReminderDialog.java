/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3659
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.ui;

import android.app.Activity;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.LongSetting;

/**
 * A dialog that asks to fix a problem, shown at most once an hour, so cancelling it is respected
 * but a user who keeps the app in the background for days is still reminded.
 */
public final class ReminderDialog {
    private static final long INTERVAL_MILLISECONDS = 60 * 60 * 1000;

    private ReminderDialog() {
    }

    /**
     * @param lastShown When the dialog was last shown.
     * @return If the dialog was not shown within the last hour.
     */
    public static boolean isDue(LongSetting lastShown) {
        final long now = System.currentTimeMillis();
        final long last = lastShown.get();
        // A clock set back to before the last time must not hide the dialog for that long.
        return now < last || now - last >= INTERVAL_MILLISECONDS;
    }

    /**
     * Safe to call from any thread.
     */
    public static void show(Activity activity, LongSetting lastShown, CharSequence title,
                            CharSequence message, CharSequence okButtonText, Runnable onOkClick) {
        lastShown.save(System.currentTimeMillis());

        // Delay so the dialog uses the theme of the fully created activity.
        Utils.runOnMainThreadDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // Not shown with Utils.showDialog(), because its fragment cannot be restored
            // without the dialog and crashes the app when the activity is recreated.
            CustomDialog.create(activity, title, message, null, okButtonText, onOkClick,
                    () -> {}, null, null, true).first.show();
        }, 100);
    }
}
