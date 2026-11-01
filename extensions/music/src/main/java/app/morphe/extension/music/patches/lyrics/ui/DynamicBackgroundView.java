/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4208
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Blurred album art background of the lyrics panel. Three rotated copies of the artwork drift
 * around and pulse with the music. Everything is drawn into a tiny buffer and blurred on the CPU,
 * then stretched over the whole view.
 */
final class DynamicBackgroundView extends View {

    private static final int BUFFER_WIDTH = 24;
    private static final int BUFFER_HEIGHT = 48;
    private static final int ARTWORK_SIZE = 48;
    private static final int BLUR_RADIUS_X = 2;
    private static final int BLUR_RADIUS_Y = 4;

    private static final int BACKGROUND_COLOR = 0xFF1E1E28;
    private static final int[] SCRIM_COLORS = {0xCC000000, 0x88000000, 0xCC000000};
    private static final float[] SCRIM_POSITIONS = {0.0f, 0.5f, 1.0f};
    private static final int TINT_COLOR = 0x27505050;

    /** 20 fps is smooth enough for a blurred background. */
    private static final long FRAME_INTERVAL_MS = 50L;
    private static final long MIN_FRAME_DELAY_MS = 16L;
    /** How often a paused track is checked for playback resuming. */
    private static final long PAUSED_POLL_INTERVAL_MS = 250L;
    private static final long ARTWORK_TRANSITION_DURATION_MS = 1000L;
    private static final long ROTATION_TICK_INTERVAL_MS = 50L;
    /** Frames further apart than this, such as after a pause, do not jump the animation ahead. */
    private static final float MAX_FRAME_ELAPSED_SECONDS = 0.1f;

    private static final int THUMBNAIL_TIMEOUT_MS = 4000;
    private static final int THUMBNAIL_CACHE_SIZE = 8;
    private static final int MAX_VISUALIZER_CAPTURE_SIZE = 1024;

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final int LAYER_COUNT = 3;

    // Movement and beat animation of each layer.
    private static final float ROTATION_POWER = 0.8f;
    private static final float[] ROTATION_SPEEDS = {-0.10f, 0.10f, 0.25f};
    private static final float[] INITIAL_ROTATIONS = {0.3f, -2.1f, 2.4f};
    private static final float[] LAYER_SCALES = {1.4f, 1.26f, 1.26f};
    private static final float[] PERIMETER_SPEEDS = {0.09f, 0.006f, 0.01f};
    private static final float[] PERIMETER_DIRECTION = {-1.0f, 1.0f, 1.0f};
    private static final float PERIMETER_PHASE_STEP = 0.33f;
    /** X and Y of each layer, in -1..1 of the buffer. */
    private static final float[] LAYER_BASE_POSITIONS = {0.0f, 0.0f, 0.75f, -0.75f, -0.75f, 0.75f};

    private static final float[] BEAT_ROTATION_BOOST = {0.28f, -0.18f, 0.02f};
    private static final float[] BEAT_SPEED_BOOST = {0.8f, 0.2f, 0.5f};
    private static final float[] BEAT_SCALE_BOOST = {0.2f, 0.34f, 0.39f};
    private static final float BEAT_ATTACK_SPEED = 12.0f;
    private static final float BEAT_DECAY_SPEED = 2.0f;
    private static final float BEAT_BASELINE_SPEED = 0.8f;
    private static final float VISUALIZER_PULSE_DECAY = 0.85f;
    private static final float BEAT_PULSE_DECAY = 0.9f;

    /**
     * Thumbnails downloaded for tracks whose media session has no artwork, already scaled.
     * Only used on the main thread.
     */
    private static final Map<String, Bitmap> thumbnailCache =
            Utils.createSizeRestrictedMap(THUMBNAIL_CACHE_SIZE, true);

    /** Shown by a new panel until it loads its own artwork, so reopening it does not flash. */
    @Nullable
    private static Bitmap lastArtwork;
    /** Shared so a new panel picks up the rotation where the last one left it. */
    private static long rotationTimeMs;

    private final Bitmap bufferBitmap = Bitmap.createBitmap(BUFFER_WIDTH, BUFFER_HEIGHT, Bitmap.Config.ARGB_8888);
    private final Canvas bufferCanvas = new Canvas(bufferBitmap);
    private final Bitmap blurBitmap = Bitmap.createBitmap(BUFFER_WIDTH, BUFFER_HEIGHT, Bitmap.Config.ARGB_8888);
    private final int[] pixelsSrc = new int[BUFFER_WIDTH * BUFFER_HEIGHT];
    private final int[] pixelsTemp = new int[BUFFER_WIDTH * BUFFER_HEIGHT];
    private final int[] pixelsDst = new int[BUFFER_WIDTH * BUFFER_HEIGHT];

    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint postProcessPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Rect screenRect = new Rect();
    private final Matrix layerMatrix = new Matrix();
    private int scrimHeight = -1;

    private final float[] layerPerimeterTime = new float[LAYER_COUNT];
    private final float[] layerBeatScale = new float[LAYER_COUNT];
    private final float[] layerBeatRotation = new float[LAYER_COUNT];
    private final float[] layerRotation = new float[LAYER_COUNT];
    private final float[] layerSmoothBeatScale = new float[LAYER_COUNT];
    private final float[] layerPositionX = new float[LAYER_COUNT];
    private final float[] layerPositionY = new float[LAYER_COUNT];
    private float beatBaseline;
    private float beatPulse;
    private float visualizerPulse;

    @Nullable
    private Visualizer visualizer;
    /** Set once the visualizer cannot be created, so it is not tried again every time. */
    private boolean visualizerFailed;

    @Nullable
    private Bitmap currentArtwork;
    @Nullable
    private Bitmap previousArtwork;
    /** The media session bitmap the current artwork was made from, null if it is a thumbnail. */
    @Nullable
    private Bitmap currentArtworkSource;
    @Nullable
    private String videoId;
    private float transitionProgress = 1.0f;
    private long transitionStartUptimeMs;
    private long lastDrawUptimeMs;
    private long lastRotationTickUptimeMs;

    private final Runnable frameRunnable = this::onFrameTick;

    DynamicBackgroundView(Context context) {
        super(context);
        setWillNotDraw(false);
        currentArtwork = lastArtwork;
        postProcessPaint.setColorFilter(new ColorMatrixColorFilter(createPostProcessMatrix()));
    }

    /** Boosts saturation and contrast, so the colors stay rich under the dark scrim. */
    private static ColorMatrix createPostProcessMatrix() {
        final float saturation = 3.0f;
        final float contrast = 0.95f;
        final float brightness = 0.70f;
        final float scale = brightness * contrast;
        final float translate = 0.5f * (1.0f - contrast) * 255.0f;

        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation);
        matrix.postConcat(new ColorMatrix(new float[]{
                scale, 0, 0, 0, translate,
                0, scale, 0, 0, translate,
                0, 0, scale, 0, translate,
                0, 0, 0, 1, 0
        }));
        return matrix;
    }

    private static boolean isAnimated() {
        return Settings.LYRICS_DYNAMIC_BACKGROUND_ANIMATED.get();
    }

    private boolean isActive() {
        return isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
    }

    /**
     * Shows the artwork of the current track: the media session artwork, or the video thumbnail
     * if the session has none.
     */
    void setVideoId(@Nullable String videoId) {
        this.videoId = videoId;
        if (getVisibility() == VISIBLE) {
            loadArtwork();
        }
    }

    void onSettingsChanged() {
        visualizerFailed = false;
        updateActive();
    }

    private void loadArtwork() {
        Bitmap source = LyricsManager.getInstance().getCurrentArtworkBitmap();
        if (source != null) {
            if (source != currentArtworkSource) {
                currentArtworkSource = source;
                showArtwork(cropAndScale(source, source.getHeight()));
            }
            return;
        }

        currentArtworkSource = null;
        final String id = videoId;
        if (id == null || id.isEmpty()) {
            return;
        }
        Bitmap cached = thumbnailCache.get(id);
        if (cached != null) {
            showArtwork(cached);
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            Bitmap thumbnail = downloadThumbnail(id);
            if (thumbnail == null) {
                return;
            }
            Utils.runOnMainThread(() -> {
                thumbnailCache.put(id, thumbnail);
                if (id.equals(videoId) && currentArtworkSource == null) {
                    showArtwork(thumbnail);
                }
            });
        });
    }

    @Nullable
    private static Bitmap downloadThumbnail(String videoId) {
        final String url = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        HttpURLConnection connection = null;
        try {
            connection = Requester.openConnection(url);
            connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MS);
            connection.setReadTimeout(THUMBNAIL_TIMEOUT_MS);
            try (InputStream input = connection.getInputStream()) {
                Bitmap downloaded = BitmapFactory.decodeStream(input);
                if (downloaded == null) {
                    return null;
                }
                // The 4:3 thumbnail letterboxes a 16:9 frame, and a square cover sits in the
                // middle of that frame, so the center square of the frame is the cover itself.
                Bitmap artwork = cropAndScale(downloaded, downloaded.getWidth() * 9 / 16);
                if (artwork != downloaded) {
                    downloaded.recycle();
                }
                return artwork;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch thumbnail: " + url, ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Scales the centered square of at most {@code side} pixels to the artwork size. */
    private static Bitmap cropAndScale(Bitmap source, int side) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        side = Math.min(side, Math.min(width, height));
        if (side == ARTWORK_SIZE && width == side && height == side) {
            return source;
        }
        Matrix scale = new Matrix();
        scale.setScale((float) ARTWORK_SIZE / side, (float) ARTWORK_SIZE / side);
        return Bitmap.createBitmap(source, (width - side) / 2, (height - side) / 2,
                side, side, scale, true);
    }

    private void showArtwork(Bitmap artwork) {
        if (artwork == currentArtwork) {
            return;
        }
        lastArtwork = artwork;
        previousArtwork = currentArtwork;
        currentArtwork = artwork;
        transitionProgress = 0.0f;
        transitionStartUptimeMs = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastDrawUptimeMs = 0;
        lastRotationTickUptimeMs = 0;
        updateActive();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(frameRunnable);
        releaseVisualizer();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateActive();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateActive();
    }

    private void updateActive() {
        if (!isActive()) {
            removeCallbacks(frameRunnable);
            releaseVisualizer();
            return;
        }
        if (isAnimated()) {
            initVisualizer();
        } else {
            releaseVisualizer();
            beatPulse = 0.0f;
            visualizerPulse = 0.0f;
        }
        loadArtwork();
        invalidate();
    }

    /** Listens to the audio output to pulse with the music. Needs the microphone permission. */
    private void initVisualizer() {
        if (visualizer != null || visualizerFailed) {
            return;
        }
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            visualizer = new Visualizer(0);
            visualizer.setCaptureSize(Math.min(Visualizer.getCaptureSizeRange()[1],
                    MAX_VISUALIZER_CAPTURE_SIZE));
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    int peak = 0;
                    for (byte sample : waveform) {
                        peak = Math.max(peak, Math.abs((sample & 0xFF) - 128));
                    }
                    visualizerPulse = peak / 128.0f;
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate(), true, false);
            visualizer.setEnabled(true);
        } catch (Exception ex) {
            // Some devices do not allow capturing the output mix.
            Logger.printDebug(() -> "Could not create visualizer", ex);
            visualizerFailed = true;
            releaseVisualizer();
        }
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            visualizer.release();
            visualizer = null;
        }
    }

    private boolean needsNextFrame() {
        return transitionProgress < 1.0f
                || (isAnimated() && LyricsManager.getInstance().isPlaying());
    }

    private void onFrameTick() {
        if (!isActive()) {
            return;
        }
        if (needsNextFrame()) {
            invalidate();
        } else if (isAnimated()) {
            postDelayed(frameRunnable, PAUSED_POLL_INTERVAL_MS);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final boolean animated = isAnimated();
        final boolean moving = animated && LyricsManager.getInstance().isPlaying();

        final float elapsedSeconds = lastDrawUptimeMs > 0
                ? Math.min((now - lastDrawUptimeMs) / 1000.0f, MAX_FRAME_ELAPSED_SECONDS)
                : FRAME_INTERVAL_MS / 1000.0f;
        lastDrawUptimeMs = now;

        updateRotationTime(now, moving);
        updateTransition(now);
        updateBeatPulse(animated);
        updateLayers(animated ? rotationTimeMs / 1000.0f : 0.0f, elapsedSeconds, animated, moving);

        renderToBuffer((float) width / height);
        drawBlurred(canvas, width, height);

        removeCallbacks(frameRunnable);
        if (needsNextFrame()) {
            final long renderMs = SystemClock.uptimeMillis() - now;
            postDelayed(frameRunnable, Math.max(MIN_FRAME_DELAY_MS, FRAME_INTERVAL_MS - renderMs));
        } else if (animated) {
            postDelayed(frameRunnable, PAUSED_POLL_INTERVAL_MS);
        }
    }

    /** The rotation advances in fixed ticks, and only while the track plays. */
    private void updateRotationTime(long now, boolean moving) {
        if (!moving || lastRotationTickUptimeMs == 0) {
            lastRotationTickUptimeMs = now;
            return;
        }
        final long ticks = (now - lastRotationTickUptimeMs) / ROTATION_TICK_INTERVAL_MS;
        rotationTimeMs += ticks * ROTATION_TICK_INTERVAL_MS;
        lastRotationTickUptimeMs += ticks * ROTATION_TICK_INTERVAL_MS;
    }

    private void updateTransition(long now) {
        if (transitionProgress < 1.0f) {
            transitionProgress = Math.min(1.0f,
                    (float) (now - transitionStartUptimeMs) / ARTWORK_TRANSITION_DURATION_MS);
            if (transitionProgress >= 1.0f) {
                previousArtwork = null;
            }
        }
    }

    private void updateBeatPulse(boolean animated) {
        if (!animated) {
            beatPulse = 0.0f;
            return;
        }
        if (LyricsManager.getInstance().isPlaying() && visualizerPulse > 0.001f) {
            beatPulse = visualizerPulse;
            visualizerPulse *= VISUALIZER_PULSE_DECAY;
        } else {
            beatPulse = beatPulse > 0.0001f ? beatPulse * BEAT_PULSE_DECAY : 0.0f;
        }
    }

    private static float smoothStep(float value) {
        final float x = Math.max(0.0f, Math.min(1.0f, value));
        return x * x * (3.0f - 2.0f * x);
    }

    /** Rotation, beat scale and orbit position of each layer. */
    private void updateLayers(float time, float elapsedSeconds, boolean animated, boolean moving) {
        final float pulse = beatPulse;
        beatBaseline += (pulse - beatBaseline) * Math.min(1.0f, BEAT_BASELINE_SPEED * elapsedSeconds);
        final float relativePulse = Math.max(0.0f, pulse - beatBaseline);

        for (int i = 0; i < LAYER_COUNT; i++) {
            if (moving) {
                layerPerimeterTime[i] += elapsedSeconds * (1.0f + pulse * BEAT_SPEED_BOOST[i]);
            }

            final float speed = relativePulse > layerBeatScale[i] ? BEAT_ATTACK_SPEED : BEAT_DECAY_SPEED;
            final float delta = Math.min(1.0f, speed * elapsedSeconds);
            layerBeatScale[i] += (relativePulse - layerBeatScale[i]) * delta;
            layerBeatRotation[i] += (relativePulse - layerBeatRotation[i]) * delta;

            layerSmoothBeatScale[i] = smoothStep(layerBeatScale[i]);
            layerRotation[i] = INITIAL_ROTATIONS[i] + ROTATION_SPEEDS[i] * time * ROTATION_POWER
                    + smoothStep(layerBeatRotation[i]) * BEAT_ROTATION_BOOST[i];

            final float baseX = LAYER_BASE_POSITIONS[i * 2];
            final float baseY = LAYER_BASE_POSITIONS[i * 2 + 1];
            if (animated) {
                float t = (i * PERIMETER_PHASE_STEP
                        + PERIMETER_DIRECTION[i] * PERIMETER_SPEEDS[i] * layerPerimeterTime[i]) % 1.0f;
                if (t < 0.0f) t += 1.0f;
                final float angle = t * TWO_PI;
                layerPositionX[i] = Math.abs(baseX) * (float) Math.cos(angle);
                layerPositionY[i] = Math.abs(baseY) * (float) Math.sin(angle);
            } else {
                layerPositionX[i] = baseX;
                layerPositionY[i] = baseY;
            }
        }
    }

    /** Draws the layers into the buffer, fading the new artwork in over the previous one. */
    private void renderToBuffer(float screenAspect) {
        bufferCanvas.drawColor(BACKGROUND_COLOR);
        if (previousArtwork != null) {
            renderLayers(previousArtwork, 1.0f, screenAspect);
        }
        if (currentArtwork != null) {
            renderLayers(currentArtwork, transitionProgress, screenAspect);
        }
    }

    private void renderLayers(Bitmap artwork, float alpha, float screenAspect) {
        if (alpha <= 0.001f) {
            return;
        }
        layerPaint.setAlpha((int) (255 * Math.min(1.0f, alpha)));

        // Scale each layer to the screen aspect ratio, so the artwork does not look stretched.
        final float aspectScaleX = screenAspect >= 1.0f ? 1.0f : 1.0f / screenAspect;
        final float aspectScaleY = screenAspect >= 1.0f ? screenAspect : 1.0f;
        final float artworkWidth = artwork.getWidth();
        final float artworkHeight = artwork.getHeight();

        for (int i = 0; i < LAYER_COUNT; i++) {
            final float reachX = 1.0f + Math.abs(LAYER_BASE_POSITIONS[i * 2]);
            final float reachY = 1.0f + Math.abs(LAYER_BASE_POSITIONS[i * 2 + 1]);
            final float size = (float) Math.hypot(reachX * aspectScaleX, reachY * aspectScaleY)
                    * LAYER_SCALES[i] + layerSmoothBeatScale[i] * BEAT_SCALE_BOOST[i];

            layerMatrix.reset();
            layerMatrix.postTranslate(-artworkWidth * 0.5f, -artworkHeight * 0.5f);
            layerMatrix.postScale(1.0f / artworkWidth, 1.0f / artworkHeight);
            layerMatrix.postRotate((float) -Math.toDegrees(layerRotation[i]));
            layerMatrix.postScale(size / aspectScaleX * BUFFER_WIDTH * 0.5f,
                    size / aspectScaleY * BUFFER_HEIGHT * 0.5f);
            layerMatrix.postTranslate((0.5f + 0.5f * layerPositionX[i]) * BUFFER_WIDTH,
                    (0.5f - 0.5f * layerPositionY[i]) * BUFFER_HEIGHT);

            bufferCanvas.drawBitmap(artwork, layerMatrix, layerPaint);
        }
    }

    private void drawBlurred(Canvas canvas, int width, int height) {
        bufferBitmap.getPixels(pixelsSrc, 0, BUFFER_WIDTH, 0, 0, BUFFER_WIDTH, BUFFER_HEIGHT);
        BoxBlur.blur(pixelsSrc, pixelsTemp, pixelsDst, BUFFER_WIDTH, BUFFER_HEIGHT,
                BLUR_RADIUS_X, BLUR_RADIUS_Y);
        blurBitmap.setPixels(pixelsDst, 0, BUFFER_WIDTH, 0, 0, BUFFER_WIDTH, BUFFER_HEIGHT);

        screenRect.set(0, 0, width, height);
        canvas.drawBitmap(blurBitmap, null, screenRect, postProcessPaint);

        if (scrimHeight != height) {
            scrimHeight = height;
            scrimPaint.setShader(new LinearGradient(0, 0, 0, height,
                    SCRIM_COLORS, SCRIM_POSITIONS, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(screenRect, scrimPaint);
        canvas.drawColor(TINT_COLOR);
    }
}
