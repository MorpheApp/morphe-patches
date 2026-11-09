/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.youtube.innertube.Offline.OfflineState;
import app.morphe.extension.youtube.innertube.Offline.OfflineStateAction;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OfflineDownloadPosition {
    public interface Source {
        long patch_getPlaybackPositionSeconds(String videoId);
    }

    private static final Map<String, WeakReference<Source>> sources = new HashMap<>();

    /**
     * Injection point.
     * Associates YouTube's live Downloads cache with the database name held by its owner.
     */
    public static synchronized void attach(@NonNull String databaseName, @NonNull Source source) {
        sources.values().removeIf(reference -> reference.get() == null);
        sources.put(databaseName, new WeakReference<>(source));
    }

    /** Injection point. */
    public static boolean isPlayableDownload(@Nullable MessageLite offlineState) {
        if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP || offlineState == null) {
            return false;
        }
        try {
            return OfflineState.parseFrom(offlineState.toByteArray()).getAction()
                    == OfflineStateAction.OFFLINE_STATE_ACTION_OK;
        } catch (Exception ex) {
            Logger.printException(() -> "isPlayableDownload failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * Reads the current account's Downloads cache, or returns -1 when the row is unavailable.
     */
    public static long getPlaybackPositionSeconds(@NonNull String databaseName, @NonNull String videoId) {
        Source source;
        synchronized (OfflineDownloadPosition.class) {
            WeakReference<Source> reference = sources.get(databaseName);
            source = reference == null ? null : reference.get();
        }
        if (source == null) {
            return -1;
        }
        try {
            return source.patch_getPlaybackPositionSeconds(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "getPlaybackPositionSeconds failure", ex);
            return -1;
        }
    }

    private OfflineDownloadPosition() {
    }
}
