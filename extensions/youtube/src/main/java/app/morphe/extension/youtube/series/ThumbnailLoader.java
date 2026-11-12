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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Loads public video thumbnails. No account credentials are sent.
 */
final class ThumbnailLoader {
    private static final int TIMEOUT_MILLISECONDS = 5000;

    /**
     * More than a page of 100 episodes. A page is redrawn every few seconds while one of its
     * episodes plays, and a page that does not fit loads most of its thumbnails again each time.
     */
    private static final int CACHE_SIZE_BYTES = 32 * 1024 * 1024;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(CACHE_SIZE_BYTES) {
        @Override
        protected int sizeOf(String videoId, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    /**
     * Views waiting for a thumbnail that is loading. A redrawn page asks again for the thumbnails
     * still loading for the previous one, and these are loaded only once. Main thread only.
     */
    private static final Map<String, List<WeakReference<ImageView>>> waiting = new HashMap<>();

    static void load(ImageView view, @Nullable String videoId) {
        Utils.verifyOnMainThread();
        view.setTag(videoId);
        // The id is part of the url.
        if (!VideoInformation.isVideoId(videoId)) return;

        Bitmap cached = cache.get(videoId);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        List<WeakReference<ImageView>> views = waiting.computeIfAbsent(videoId, id -> new ArrayList<>());
        views.add(new WeakReference<>(view));
        // Already loading.
        if (views.size() > 1) return;

        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = download(videoId);
                if (bitmap != null) cache.put(videoId, bitmap);
            } catch (Exception ex) {
                Logger.printException(() -> "load failure", ex);
            } finally {
                // Always remove the waiting views, otherwise this thumbnail is never loaded again.
                final Bitmap result = bitmap;
                Utils.runOnMainThread(() -> showLoaded(videoId, result));
            }
        });
    }

    private static void showLoaded(String videoId, @Nullable Bitmap bitmap) {
        List<WeakReference<ImageView>> targets = waiting.remove(videoId);
        if (bitmap == null || targets == null) return;

        for (WeakReference<ImageView> viewRef : targets) {
            ImageView target = viewRef.get();
            // The view may have been reused for a different video.
            if (target != null && videoId.equals(target.getTag())) {
                target.setImageBitmap(bitmap);
            }
        }
    }

    /**
     * Lets go of the cached thumbnails, for when the page showing them closes. The player keeps the
     * app running in the background, where the thumbnails would otherwise stay for as long as it
     * plays.
     */
    static void evictAll() {
        cache.evictAll();
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
