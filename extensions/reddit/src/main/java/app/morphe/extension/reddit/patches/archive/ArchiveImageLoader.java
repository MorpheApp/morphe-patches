package app.morphe.extension.reddit.patches.archive;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, credential-free, memory-only thumbnail loading. Never downloads video. */
final class ArchiveImageLoader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(24), runnable -> {
                Thread thread = new Thread(runnable, "Reddit-archive-preview"); thread.setDaemon(true); return thread;
            });
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    interface Callback { void complete(Bitmap bitmap); }

    static final class Request {
        final ArchiveClient.Cancellation cancellation = new ArchiveClient.Cancellation();
        Future<?> future;
        volatile boolean cancelled;
        void cancel() {
            cancelled = true; cancellation.cancel();
            if (future != null) future.cancel(true);
            MAIN.removeCallbacksAndMessages(this); WORKERS.purge();
        }
    }

    static Request load(String url, Callback callback) {
        Request request = new Request();
        Bitmap cached = CACHE.get(url);
        if (cached != null) { callback.complete(cached); return request; }
        try {
            request.future = WORKERS.submit(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = download(url, request.cancellation);
                    request.cancellation.check();
                    if (bitmap != null) CACHE.put(url, bitmap);
                } catch (IOException | RuntimeException ignored) { }
                final Bitmap result = bitmap;
                if (!request.cancelled) MAIN.postAtTime(() -> {
                    if (!request.cancelled) callback.complete(result);
                }, request, SystemClock.uptimeMillis());
            });
        } catch (RejectedExecutionException ignored) { callback.complete(null); }
        return request;
    }

    private static Bitmap download(String url, ArchiveClient.Cancellation cancellation) throws IOException {
        if (ArchivePostDetails.imageUrl(url) == null) throw new IOException("Unsupported preview URL");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            cancellation.attach(connection);
            connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Cookie", ""); connection.setRequestProperty("Authorization", "");
            connection.setRequestProperty("Accept", "image/*");
            if (connection.getResponseCode() != 200) throw new IOException("Preview unavailable");
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) throw new IOException("Not an image");
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    cancellation.check();
                    if (output.size() + read > 6 * 1024 * 1024) throw new IOException("Preview too large");
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 32768 || options.outHeight > 32768) return null;
            options.inSampleSize = 1;
            while ((long) (options.outWidth / options.inSampleSize) * (options.outHeight / options.inSampleSize) > 1200000
                    || options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            cancellation.check();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } finally { cancellation.detach(); connection.disconnect(); }
    }
}
