/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.data;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import app.morphe.extension.music.patches.soundproof.domain.ArtistDirectory;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Schedules the background work of {@link SoundProofDumpStore}: the local copy is read on the
 * first request, then a download is attempted whenever the copy is a day old. Every loaded
 * database is handed to the listener on the main thread.
 * <p>
 * {@link #requestDatabase()} must be called on the main thread.
 */
public final class SoundProofDatabaseLoader {

    /** A failed download, for example while offline, is retried at most this often. */
    private static final long RETRY_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final SoundProofDumpStore store;
    private final Consumer<ArtistDirectory> onDatabaseLoaded;

    private boolean isCacheRead;
    private boolean isWorking;
    private long nextAttemptMillis;

    public SoundProofDatabaseLoader(SoundProofDumpStore store, Consumer<ArtistDirectory> onDatabaseLoaded) {
        this.store = store;
        this.onDatabaseLoaded = onDatabaseLoaded;
    }

    /**
     * Cheap to call on every track: it starts background work only when some is due.
     */
    public void requestDatabase() {
        Utils.verifyOnMainThread();
        long nowMillis = System.currentTimeMillis();
        if (isWorking || (isCacheRead && nowMillis < nextAttemptMillis)) {
            return;
        }
        isWorking = true;
        nextAttemptMillis = nowMillis + RETRY_INTERVAL_MILLIS;
        boolean shouldReadCache = !isCacheRead;
        Utils.runOnBackgroundThread(() -> loadInBackground(shouldReadCache));
    }

    private void loadInBackground(boolean shouldReadCache) {
        try {
            if (shouldReadCache) {
                readCache();
            }
            refreshIfDue();
        } finally {
            Utils.runOnMainThread(() -> {
                isCacheRead = true;
                isWorking = false;
            });
        }
    }

    private void readCache() {
        try {
            store.loadCached().ifPresent(this::publish);
        } catch (IOException ex) {
            Logger.printInfo(() -> "SoundProof cache unreadable, waiting for a download", ex);
        }
    }

    private void refreshIfDue() {
        if (!store.isRefreshDue(System.currentTimeMillis())) {
            return;
        }
        try {
            publish(store.download());
            Logger.printDebug(() -> "SoundProof database downloaded");
        } catch (IOException ex) {
            Logger.printInfo(() -> "SoundProof download failed, keeping the local copy", ex);
        }
    }

    private void publish(ArtistDirectory directory) {
        Utils.runOnMainThread(() -> onDatabaseLoaded.accept(directory));
    }
}
