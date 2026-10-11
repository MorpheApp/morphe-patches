/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.jam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Updates the existing native artwork view; caches one bounded public thumbnail. */
public final class JamArtwork {

    private static final Map<ImageView, Drawable> originals =
        new WeakHashMap<>();
    private static final Map<ImageView, Drawable> localArtwork =
        new WeakHashMap<>();
    private static final Set<ImageView> views = Collections.newSetFromMap(
        new WeakHashMap<>()
    );
    private static final ExecutorService loader =
        Executors.newSingleThreadExecutor();
    private static String url = "";
    private static long generation;

    @Nullable
    private static Bitmap picture;

    private static void retain(ImageView view) {
        if (!originals.containsKey(view)) originals.put(
            view,
            localArtwork.containsKey(view)
                ? localArtwork.get(view)
                : view.getDrawable()
        );
    }

    @SuppressWarnings("unused")
    public static Bitmap choose(ImageView view, Bitmap local) {
        views.add(view);
        if (!"Participant".equals(JamPanel.role(JamUI.latest))) {
            localArtwork.put(
                view,
                local == null
                    ? null
                    : new BitmapDrawable(view.getResources(), local)
            );
            return local;
        }
        // Native refreshes can now refer to the mirrored item. Keep the pre-Jam image,
        // not the incoming bitmap for that host item, as the restoration source.
        if (!originals.containsKey(view)) originals.put(
            view,
            localArtwork.get(view)
        );
        return picture == null ? local : picture;
    }

    static void clear() {
        if (url.isEmpty() && picture == null && originals.isEmpty()) return;
        generation++;
        url = "";
        picture = null;
        try {
            JamPalette.clear();
        } catch (Exception error) {
            Logger.printInfo(() -> "Could not restore local palette", error);
        }
        for (Map.Entry<ImageView, Drawable> entry : new ArrayList<>(
            originals.entrySet()
        )) {
            try {
                entry.getKey().setImageDrawable(entry.getValue());
            } catch (Exception error) {
                Logger.printInfo(
                    () -> "Could not restore local artwork",
                    error
                );
            }
        }
        originals.clear();
    }

    static void update(String value, String encoded) {
        if (!encoded.isEmpty()) value = "data:" + encoded;
        if (value.equals(url)) return;
        url = value;
        final String requested = value;
        final long token = ++generation;
        picture = null;
        loader.execute(() -> {
            try {
                byte[] bytes;
                if (requested.startsWith("data:")) {
                    if (encoded.length() > 180000) throw new IOException(
                        "Artwork too large"
                    );
                    bytes = android.util.Base64.decode(
                        encoded,
                        android.util.Base64.DEFAULT
                    );
                } else {
                    HttpURLConnection connection = Requester.openConnection(
                        requested
                    );
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setInstanceFollowRedirects(false);
                    try (
                        InputStream input = connection.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream()
                    ) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (
                                out.size() + count > 2097152
                            ) throw new IOException("Artwork too large");
                            out.write(buffer, 0, count);
                        }
                        bytes = out.toByteArray();
                    } finally {
                        connection.disconnect();
                    }
                }

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (
                    options.outWidth <= 0 ||
                    options.outHeight <= 0 ||
                    options.outWidth > 8192 ||
                    options.outHeight > 8192
                ) {
                    return;
                }

                options.inJustDecodeBounds = false;
                options.inSampleSize = Math.max(
                    1,
                    Math.max(options.outWidth, options.outHeight) / 1024
                );
                Bitmap result = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.length,
                    options
                );
                Utils.runOnMainThread(() -> {
                    if (
                        token != generation ||
                        !"Participant".equals(JamPanel.role(JamUI.latest)) ||
                        !JamMirror.active()
                    ) return;
                    picture = result;
                    JamPalette.update(result);
                    for (ImageView view : new ArrayList<>(views)) {
                        retain(view);
                        if (result != null) view.setImageBitmap(result);
                    }
                });
            } catch (Exception e) {
                Logger.printInfo(() -> "Host artwork unavailable", e);
            }
        });
    }
}
