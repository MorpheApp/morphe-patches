/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.session;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.session.MediaSession;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Updates the text of the app's media notification. Some system media controls only read the
 * session metadata again when the notification is posted, so a new line would not show otherwise.
 * The notification is posted again as is with only the title and the text changed, so it keeps
 * its id, channel, controls and artwork.
 */
final class LyricNotificationRefresh {

    private LyricNotificationRefresh() {
    }

    // Media notifications do not need the notification permission.
    @SuppressLint("NotificationPermission")
    @SuppressWarnings("deprecation")
    static void refresh(MediaSession session, @Nullable String title, @Nullable String text) {
        try {
            if (title == null) {
                return;
            }

            NotificationManager manager = (NotificationManager) Utils.getContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            for (StatusBarNotification entry : manager.getActiveNotifications()) {
                Notification original = entry.getNotification();
                MediaSession.Token token = original.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (!session.getSessionToken().equals(token)) {
                    continue;
                }
                if (TextUtils.equals(title, original.extras.getCharSequence(Notification.EXTRA_TITLE))
                        && (text == null || TextUtils.equals(text,
                        original.extras.getCharSequence(Notification.EXTRA_TEXT)))) {
                    continue;
                }

                Notification changed = original.clone();
                changed.extras.putCharSequence(Notification.EXTRA_TITLE, title);
                if (text != null) {
                    changed.extras.putCharSequence(Notification.EXTRA_TEXT, text);
                }
                changed.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
                manager.notify(entry.getTag(), entry.getId(), changed);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "refresh failure", ex);
        }
    }
}
