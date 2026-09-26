/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import app.morphe.extension.shared.Utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Updates the existing native artwork view; caches one bounded public thumbnail. */
public final class JamArtwork {

  private static final Map<ImageView, Drawable> originals = new WeakHashMap<>();
  private static final Set<ImageView> views = Collections.newSetFromMap(
    new WeakHashMap<>()
  );
  private static final ExecutorService loader =
    Executors.newSingleThreadExecutor();
  private static String url = "";

  @Nullable
  private static Bitmap picture;

  private static void retain(ImageView view) {
    if (!originals.containsKey(view)) originals.put(view, view.getDrawable());
  }

  public static void bind(ImageView view) {
    views.add(view);
    if (!JamMirror.active()) return;
    retain(view);
    if (picture != null) view.setImageBitmap(picture);
  }

  public static Bitmap choose(ImageView view, Bitmap local) {
    views.add(view);
    if (!JamMirror.active()) return local;
    retain(view);
    return picture == null ? local : picture;
  }

  static void clear() {
    url = "";
    picture = null;
    JamPalette.clear();
    for (Map.Entry<ImageView, Drawable> entry : new ArrayList<>(
      originals.entrySet()
    )) {
      entry.getKey().setImageDrawable(entry.getValue());
    }
    originals.clear();
  }

  static void update(String value) {
    if (value.equals(url)) return;
    url = value;
    picture = null;
    loader.execute(() -> {
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL(
          value
        ).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        byte[] bytes;
        try (
          InputStream input = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > 2097152) throw new IOException(
              "Artwork too large"
            );
            out.write(buffer, 0, count);
          }
          bytes = out.toByteArray();
        } finally {
          connection.disconnect();
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
          if (!value.equals(url) || !JamMirror.active()) return;
          picture = result;
          JamPalette.update(result);
          for (ImageView view : new ArrayList<>(views)) {
            retain(view);
            if (result != null) view.setImageBitmap(result);
          }
        });
      } catch (Exception e) {
        android.util.Log.i("MorpheJam", "Host artwork unavailable");
      }
    });
  }
}
