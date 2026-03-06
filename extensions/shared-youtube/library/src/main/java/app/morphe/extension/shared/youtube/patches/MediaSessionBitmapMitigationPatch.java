package app.morphe.extension.shared.youtube.patches;

import android.graphics.Bitmap;
import android.media.MediaMetadata;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class MediaSessionBitmapMitigationPatch {

    private static Bitmap copyBitmapIfSafe(Bitmap original) {
        if (original == null) return null;
        if (original.isRecycled()) {
            Logger.printDebug(() -> "MediaSessionBitmapMitigation: Intercepted recycled bitmap");
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
        
        try {
            Bitmap.Config config = original.getConfig();
            return original.copy(config != null ? config : Bitmap.Config.ARGB_8888, false);
        } catch (Exception e) {
            Logger.printException(() -> "MediaSessionBitmapMitigation: Could not copy bitmap", e);
            return original; // Fallback
        }
    }

    /**
     * Injection point
     */
    public static MediaMetadata.Builder putBitmap(
            MediaMetadata.Builder builder,
            String key,
            Bitmap bitmap) {
        return builder.putBitmap(key, copyBitmapIfSafe(bitmap));
    }
}
