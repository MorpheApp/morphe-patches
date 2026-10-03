package app.morphe.extension.youtube.series;

import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.util.concurrent.*;

/**
 * Fixed public thumbnail origin; bounded queue/cache, no account credentials, no main-thread I/O.
 */
final class ThumbnailLoader {
    private static final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount();
                }
            };
    private static final ExecutorService worker =
            new ThreadPoolExecutor(
                    2,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    new ThreadPoolExecutor.DiscardPolicy());
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void load(ImageView view, String id) {
        view.setTag(id);
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        Bitmap ready = cache.get(id);
        if (ready != null) {
            view.setImageBitmap(ready);
            return;
        }
        WeakReference<ImageView> target = new WeakReference<>(view);
        worker.execute(
                () -> {
                    HttpURLConnection connection = null;
                    try {
                        connection =
                                (HttpURLConnection)
                                        new URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg")
                                                .openConnection();
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);
                        connection.setInstanceFollowRedirects(false);
                        if (connection.getResponseCode() != 200) return;
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (InputStream in = connection.getInputStream()) {
                            byte[] buffer = new byte[8192];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (bytes.size() + n > 512 * 1024) return;
                                bytes.write(buffer, 0, n);
                            }
                        }
                        byte[] data = bytes.toByteArray();
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                        if (bounds.outWidth <= 0
                                || bounds.outHeight <= 0
                                || bounds.outWidth > 1024
                                || bounds.outHeight > 1024) return;
                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bitmap == null) return;
                        cache.put(id, bitmap);
                        main.post(
                                () -> {
                                    ImageView v = target.get();
                                    if (v != null && id.equals(v.getTag()))
                                        v.setImageBitmap(bitmap);
                                });
                    } catch (IOException ignored) {
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                });
    }

    private ThumbnailLoader() {}
}
