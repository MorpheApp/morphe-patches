/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.ambientlight.LightSurface;
import app.morphe.extension.youtube.patches.PostImageDownloadPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.VideoInformation.VideoQualityInterface;

/**
 * Saves the picture that the video shows now as an image, without the player controls
 * and anything else that is drawn over the video.
 */
final class VideoFrameSaver {
    /** How many layouts above a view of the player are searched for the video. */
    private static final int MAX_PLAYER_ANCESTORS = 8;
    /** Longest side of the image. Videos with more than that are rare, and the copy gets slow. */
    private static final int MAX_SIDE = 3840;
    private static final int JPEG_QUALITY = 95;
    /** The surface has no picture for a moment while the player loads. */
    private static final long COPY_AGAIN_AFTER_MS = 250;

    private VideoFrameSaver() {
    }

    /**
     * Must be called on the main thread.
     *
     * @param playerView A view inside of the player.
     * @param onCopied   Called on the main thread as soon as the picture is taken, before it is saved.
     */
    static void save(@Nullable View playerView, Runnable onCopied) {
        final View video = playerView == null ? null : findVideoSurface(playerView);
        if (video == null || video.getWidth() == 0 || video.getHeight() == 0) {
            Logger.printDebug(() -> "Video surface not found");
            failed();
            return;
        }

        final boolean ready = video instanceof TextureView textureView
                ? textureView.isAvailable()
                : ((SurfaceView) video).getHolder().getSurface().isValid();
        if (!ready) {
            Logger.printDebug(() -> "Video surface is not ready");
            failed();
            return;
        }

        final String name = fileName();
        final int[] size = imageSize(video.getWidth(), video.getHeight());
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            Logger.printInfo(() -> "No memory for a frame of " + size[0] + " x " + size[1]);
            failed();
            return;
        }
        Logger.printDebug(() -> "Saving a frame of " + size[0] + " x " + size[1] + " as " + name);

        if (video instanceof TextureView textureView) {
            textureView.getBitmap(bitmap);
            onCopied.run();
            Utils.runOnBackgroundThread(() -> write(bitmap, name));
        } else {
            copySurface((SurfaceView) video, bitmap, name, onCopied, true);
        }
    }

    private static void copySurface(SurfaceView surfaceView, Bitmap bitmap, String name, Runnable onCopied,
                                    boolean tryAgain) {
        final Handler mainThread = new Handler(Looper.getMainLooper());
        // Before Android 14 the request blocks until the copy is finished.
        Utils.runOnBackgroundThread(() -> {
            try {
                PixelCopy.request(surfaceView, bitmap, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        onCopied.run();
                        Utils.runOnBackgroundThread(() -> write(bitmap, name));
                    } else if (tryAgain) {
                        Logger.printDebug(() -> "Could not copy the video frame, trying once more: " + result);
                        mainThread.postDelayed(() -> copySurface(surfaceView, bitmap, name, onCopied, false),
                                COPY_AGAIN_AFTER_MS);
                    } else {
                        // Such as a video that may not be copied.
                        Logger.printDebug(() -> "Could not copy the video frame: " + result);
                        bitmap.recycle();
                        failed();
                    }
                }, mainThread);
            } catch (IllegalArgumentException ex) {
                Logger.printInfo(() -> "Video surface went away during the copy", ex);
                bitmap.recycle();
                failed();
            }
        });
    }

    private static void write(Bitmap bitmap, String name) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
            bitmap.recycle();
            if (compressed && PostImageDownloadPatch.save(bytes.toByteArray(), name)) {
                Utils.showSnackbarShort(str("morphe_hold_gesture_frame_saved",
                        PostImageDownloadPatch.getSaveFolderName()));
                return;
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not save the video frame", ex);
        }
        failed();
    }

    private static void failed() {
        Utils.showSnackbarShort(str("morphe_hold_gesture_frame_failed"));
    }

    /**
     * The video is copied at the size it is played at, which can be more than the size of the player.
     * A player larger than that is not scaled up.
     *
     * @return Width and height.
     */
    private static int[] imageSize(int viewWidth, int viewHeight) {
        int shortSide = Math.min(viewWidth, viewHeight);
        final VideoQualityInterface quality = VideoInformation.getCurrentQuality();
        if (quality != null) {
            // The resolution of a quality is the short side of the video. Not positive for "Auto".
            shortSide = Math.max(shortSide, quality.patch_getResolution());
        }
        float scale = shortSide / (float) Math.min(viewWidth, viewHeight);
        scale = Math.min(scale, MAX_SIDE / (float) Math.max(viewWidth, viewHeight));
        return new int[]{Math.max(1, Math.round(viewWidth * scale)), Math.max(1, Math.round(viewHeight * scale))};
    }

    /**
     * @return The id of the video and the time of the picture, such as "dQw4w9WgXcQ 0.03.32".
     */
    private static String fileName() {
        final long seconds = Math.max(0, VideoInformation.getVideoTime()) / 1000;
        return String.format(Locale.ROOT, "%s %d.%02d.%02d", VideoInformation.getVideoId(),
                seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /**
     * @return The largest shown video surface in the closest layout above the view that has one.
     */
    @Nullable
    private static View findVideoSurface(View playerView) {
        ViewParent parent = playerView.getParent();
        for (int i = 0; i < MAX_PLAYER_ANCESTORS && parent instanceof ViewGroup group;
             i++, parent = parent.getParent()) {
            final View surface = findLargestSurface(group, null);
            if (surface != null) return surface;
        }
        return null;
    }

    @Nullable
    private static View findLargestSurface(View view, @Nullable View best) {
        // YouTube can hide a video surface that is being replaced by setting its alpha to 0.
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0) return best;

        // Ambient light draws on surfaces around the video.
        if ((view instanceof SurfaceView && !(view instanceof LightSurface)) || view instanceof TextureView) {
            final long area = (long) view.getWidth() * view.getHeight();
            if (area > 0 && (best == null || area > (long) best.getWidth() * best.getHeight())) {
                best = view;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                best = findLargestSurface(group.getChildAt(i), best);
            }
        }
        return best;
    }
}
