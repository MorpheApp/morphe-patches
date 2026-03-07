/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.shared.patches;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.media.MediaMetadata.Builder;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class FixRecycledBitmapPatch {

    /**
     * Injection point.
     * Fix: <a href="https://github.com/MorpheApp/morphe-patches/issues/686">Cannot obtain size for recycled Bitmap: ARGB_8888</a>.
     */
    public static Builder putBitmap(Builder builder, String key, Bitmap bitmap) {
        if (bitmap != null) {
            if (bitmap.isRecycled()) {
                Logger.printDebug(() -> "Bitmap is recycled, creating a dummy bitmap");
                bitmap = Bitmap.createBitmap(0, 0, Config.ARGB_8888);
            }

            try {
                Config config = bitmap.getConfig();
                if (config == null) config = Config.ARGB_8888;
                builder = builder.putBitmap(key, bitmap.copy(config, false));
                Logger.printDebug(() -> "Bitmap copied");
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to copy bitmap", ex);
            }
        }

        return builder;
    }
}
