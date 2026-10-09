/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Loads public video thumbnails. No account credentials are sent.
 */
final class ThumbnailLoader {
    private static final int TIMEOUT_MILLISECONDS = 5000;
    private static final int CACHE_SIZE_BYTES = 8 * 1024 * 1024;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(CACHE_SIZE_BYTES) {
        @Override
        protected int sizeOf(String videoId, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    static void load(ImageView view, @Nullable String videoId) {
        view.setTag(videoId);
        // The id is part of the url.
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return;

        Bitmap cached = cache.get(videoId);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        WeakReference<ImageView> viewRef = new WeakReference<>(view);
        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = download(videoId);
            if (bitmap == null) return;

            cache.put(videoId, bitmap);
            Utils.runOnMainThread(() -> {
                ImageView target = viewRef.get();
                // The view may have been reused for a different video.
                if (target != null && videoId.equals(target.getTag())) {
                    target.setImageBitmap(bitmap);
                }
            });
        });
    }

    /**
     * @return The thumbnail, or null if it could not be loaded.
     */
    @Nullable
    private static Bitmap download(String videoId) {
        try {
            HttpURLConnection connection = Requester.openConnection(
                    "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
            connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(TIMEOUT_MILLISECONDS);
            if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            // Not disconnected, as more thumbnails are loaded from the same host.
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not load thumbnail: " + videoId, ex);
            return null;
        }
    }

    private ThumbnailLoader() {}
}
