/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.LocalQueuePatch;

/**
 * Small anonymous thumbnails for the queue sheet. Must be used from the main thread.
 */
final class QueueThumbnails {

    private static final int CACHE_BYTES = 4 * 1024 * 1024;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final Set<String> loading = new HashSet<>();

    @Nullable
    static Bitmap get(String videoId) {
        return cache.get(videoId);
    }

    static void load(String videoId, Consumer<String> onLoaded) {
        if (cache.get(videoId) != null || !loading.add(videoId)) {
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = download(videoId);
            Utils.runOnMainThread(() -> {
                loading.remove(videoId);
                if (bitmap != null) {
                    cache.put(videoId, bitmap);
                    onLoaded.accept(videoId);
                }
            });
        });
    }

    @Nullable
    private static Bitmap download(String videoId) {
        try {
            HttpURLConnection connection = Requester.openConnection(
                    "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
            connection.setConnectTimeout(LocalQueuePatch.CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(LocalQueuePatch.CONNECTION_TIMEOUT_MILLISECONDS);
            try (InputStream stream = connection.getInputStream()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(stream, null, options);
            } finally {
                connection.disconnect();
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not load thumbnail of: " + videoId, ex);
        } catch (Exception ex) {
            Logger.printException(() -> "download failure", ex);
        }
        return null;
    }
}
