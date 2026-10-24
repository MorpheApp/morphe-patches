/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.session;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Refresh the existing notification, retaining its ID, token, controls, channel and art. */
final class LyricNotificationRefresh {
    private static final Map<String, String> published = new HashMap<>();

    private LyricNotificationRefresh() { }

    // Media notifs are exempt; only refresh the existing notification for this session.
    @android.annotation.SuppressLint("NotificationPermission")
    static void refresh(MediaSession session) {
        try {
            final MediaMetadata metadata = session.getController().getMetadata();
            if (metadata == null) return;
            String title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) return;
            final String subtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            final NotificationManager manager = (NotificationManager) Utils.getContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            for (StatusBarNotification entry : manager.getActiveNotifications()) {
                final Notification original = entry.getNotification();
                final MediaSession.Token token = original.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (token == null || !token.equals(session.getSessionToken())) continue;
                final String signature = title + "\u0000" + subtitle;
                if (signature.equals(published.get(entry.getKey()))
                        && title.contentEquals(original.extras.getCharSequence(Notification.EXTRA_TITLE, ""))) continue;
                final Notification changed = original.clone();
                changed.extras = new Bundle(original.extras);
                changed.extras.putCharSequence(Notification.EXTRA_TITLE, title);
                if (subtitle != null) changed.extras.putCharSequence(Notification.EXTRA_TEXT, subtitle);
                changed.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
                manager.notify(entry.getTag(), entry.getId(), changed);
                if (published.size() >= 4) published.clear();
                published.put(entry.getKey(), signature);
            }
        } catch (Exception e) {
            Logger.printException(() -> "Could not refresh existing lyric notification", e);
        }
    }
}
