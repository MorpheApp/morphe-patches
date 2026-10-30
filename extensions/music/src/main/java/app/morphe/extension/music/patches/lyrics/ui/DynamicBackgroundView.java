/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Dynamic blurred background view for player lyrics screen.
 * It move around gently and react to song beat so background not boring.
 * Uses tiny 24x48 pixel buffer and cpu box blur so device dont lag.
 */
public final class DynamicBackgroundView extends View {

    // Buffer size for rendering blurred layers
    public static final int BUFFER_WIDTH = 24;
    public static final int BUFFER_HEIGHT = 48;
    private static final int ARTWORK_SIZE = 48;
    private static final int BLUR_RADIUS_X = 2;
    private static final int BLUR_RADIUS_Y = 4;
    public static final long FRAME_INTERVAL_MS = 50L; // 20 FPS cap so battery dont drain

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final long ARTWORK_TRANSITION_DURATION_MS = 1000L;
    private static final long ROTATION_TICK_INTERVAL_MS = 50L;

    // Movement and beat animation constants
    private static final float ROTATION_POWER = 0.8f;
    private static final float[] ROTATION_SPEEDS = new float[]{-0.10f, 0.10f, 0.25f};
    private static final float[] INITIAL_ROTATIONS = new float[]{0.3f, -2.1f, 2.4f};
    private static final float[] LAYER_SCALES = new float[]{1.4f, 1.26f, 1.26f};
    private static final float[] PERIMETER_SPEEDS = new float[]{0.09f, 0.006f, 0.01f};
    private static final float[] PERIMETER_DIRECTION = new float[]{-1.0f, 1.0f, 1.0f};
    private static final float[] LAYER_BASE_POSITIONS = new float[]{0.0f, 0.0f, 0.75f, -0.75f, -0.75f, 0.75f};

    private static final float[] BEAT_ROT_BOOST = new float[]{0.28f, -0.18f, 0.02f};
    private static final float[] BEAT_SPD_BOOST = new float[]{0.8f, 0.2f, 0.5f};
    private static final float[] BEAT_SCALE_BOOST = new float[]{0.2f, 0.34f, 0.39f};
    private static final float BEAT_SCALE_DECAY = 2.0f;

    // Small memory cache so we dont decode artwork bitmap repeatedly
    private static final LinkedHashMap<String, Bitmap> LIQUID_ART_CACHE =
            new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 8;
                }
            };

    @Nullable
    private static volatile Bitmap lastRenderedArt = null;
    private static volatile long globalLiquidRotationTimeMs = 0L;

    @Nullable
    private static Bitmap getCachedLiquidArt(@Nullable String key) {
        if (key == null) return null;
        synchronized (LIQUID_ART_CACHE) {
            return LIQUID_ART_CACHE.get(key);
        }
    }

    private static void putCachedLiquidArt(@Nullable String key, @NonNull Bitmap bmp) {
        if (key != null && !key.isEmpty()) {
            synchronized (LIQUID_ART_CACHE) {
                LIQUID_ART_CACHE.put(key, bmp);
            }
        }
        lastRenderedArt = bmp;
    }

    private static final ExecutorService COLOR_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Bitmap bufferBitmap;
    private final Canvas bufferCanvas;
    private final Bitmap blurBitmap;
    private final int[] pixelsSrc = new int[BUFFER_WIDTH * BUFFER_HEIGHT];
    private final int[] pixelsDst = new int[BUFFER_WIDTH * BUFFER_HEIGHT];
    private final int[] pixelsTemp = new int[BUFFER_WIDTH * BUFFER_HEIGHT];
    private final Bitmap defaultArtwork;

    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint postProcessPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect screenRect = new Rect();
    private final Matrix layerMatrix = new Matrix();

    private LinearGradient scrimGradient;
    private int lastGradientHeight = -1;

    private final float[] layerPerimTime = new float[3];
    private final float[] layerBeatScale = new float[3];
    private final float[] layerBeatRot = new float[3];
    private float beatEnergyBaseline = 0.0f;

    private final float[] cachedLayerRots = new float[3];
    private final float[] cachedLayerSmoothBS = new float[3];
    private final float[] cachedLayerPosX = new float[3];
    private final float[] cachedLayerPosY = new float[3];

    private float artworkTransitionProgress = 1.0f;
    private long artworkTransitionStartUptimeMs = 0L;
    private long lastDrawTimeMs = 0L;
    private long lastRotationTickUptimeMs = 0L;
    private boolean nextFrameScheduled = false;

    private float beatPulse = 0.0f;
    private float visualizerPulse = 0.0f;
    private Visualizer visualizer;

    @Nullable
    private Bitmap currentArtwork;
    @Nullable
    private Bitmap previousArtwork;
    @Nullable
    private String currentVideoId;
    private boolean isAttached;

    private final Runnable frameTickRunnable = new Runnable() {
        @Override
        public void run() {
            nextFrameScheduled = false;
            if (isAttached && getVisibility() == VISIBLE && Settings.LYRICS_DYNAMIC_BACKGROUND.get()) {
                invalidate();
            }
        }
    };

    public DynamicBackgroundView(Context context) {
        super(context);
        setWillNotDraw(false);

        bufferBitmap = Bitmap.createBitmap(BUFFER_WIDTH, BUFFER_HEIGHT, Bitmap.Config.ARGB_8888);
        bufferCanvas = new Canvas(bufferBitmap);
        blurBitmap = Bitmap.createBitmap(BUFFER_WIDTH, BUFFER_HEIGHT, Bitmap.Config.ARGB_8888);

        defaultArtwork = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888);
        Canvas defCanvas = new Canvas(defaultArtwork);
        defCanvas.drawColor(0xFF1E1E28);

        currentArtwork = (lastRenderedArt != null && !lastRenderedArt.isRecycled())
                ? lastRenderedArt
                : defaultArtwork;

        postProcessPaint.setColorFilter(new ColorMatrixColorFilter(createPostProcessMatrix()));
    }

    // Boost saturation and contrast so color look rich on dark background
    private static ColorMatrix createPostProcessMatrix() {
        ColorMatrix satMatrix = new ColorMatrix();
        satMatrix.setSaturation(3.0f);

        ColorMatrix cbMatrix = new ColorMatrix();
        float contrast = 0.95f;
        float brightness = 0.70f;
        float scale = brightness * contrast;
        float translate = 0.5f * (1.0f - contrast) * 255.0f;
        cbMatrix.set(new float[]{
                scale, 0, 0, 0, translate,
                0, scale, 0, 0, translate,
                0, 0, scale, 0, translate,
                0, 0, 0, 1, 0
        });

        ColorMatrix finalMatrix = new ColorMatrix();
        finalMatrix.postConcat(satMatrix);
        finalMatrix.postConcat(cbMatrix);
        return finalMatrix;
    }

    public void onSettingsChanged() {
        if (!Settings.LYRICS_DYNAMIC_BACKGROUND.get()) {
            cancelScheduledFrame();
            releaseVisualizer();
            invalidate();
            return;
        }

        // When user turn on dynamic background, fetch artwork right away if still default
        if (currentVideoId != null && !currentVideoId.isEmpty() && (currentArtwork == null || currentArtwork == defaultArtwork)) {
            Bitmap art = LyricsManager.getInstance().getCurrentArtworkBitmap();
            setArtwork(art, currentVideoId, null, null);
        }

        if (!Settings.LYRICS_LIGHTWEIGHT_MODE.get() && isAttached && getVisibility() == VISIBLE) {
            tryInitVisualizer();
        } else {
            releaseVisualizer();
            beatPulse = 0.0f;
            visualizerPulse = 0.0f;
        }
        scheduleNextFrame(0L);
    }

    private void scheduleNextFrame(long delayMs) {
        if (!isAttached || getVisibility() != VISIBLE || !Settings.LYRICS_DYNAMIC_BACKGROUND.get()) {
            return;
        }
        if (delayMs <= 0L) {
            cancelScheduledFrame();
            invalidate();
            return;
        }
        if (!nextFrameScheduled) {
            nextFrameScheduled = true;
            mainHandler.postDelayed(frameTickRunnable, delayMs);
        }
    }

    private void cancelScheduledFrame() {
        if (nextFrameScheduled) {
            mainHandler.removeCallbacks(frameTickRunnable);
            nextFrameScheduled = false;
        }
    }

    public void setArtwork(@Nullable Bitmap bitmap, @Nullable String videoId, @Nullable String title, @Nullable String artist) {
        if (Objects.equals(videoId, currentVideoId) && bitmap == null && currentArtwork != null && currentArtwork != defaultArtwork) {
            return;
        }
        currentVideoId = videoId;

        if (bitmap != null && !bitmap.isRecycled()) {
            applyNewArtwork(bitmap, videoId);
            return;
        }

        if (videoId != null && !videoId.isEmpty()) {
            Bitmap cached = getCachedLiquidArt(videoId);
            if (cached != null && !cached.isRecycled()) {
                applyNewArtwork(cached, videoId);
                return;
            }
        }

        if (videoId == null || videoId.isEmpty()) {
            applyNewArtwork(defaultArtwork, null);
            return;
        }

        // If dynamic background disabled, dont waste network downloading thumbnail
        if (!Settings.LYRICS_DYNAMIC_BACKGROUND.get()) {
            applyNewArtwork(defaultArtwork, null);
            return;
        }

        final String targetVid = videoId;
        COLOR_EXECUTOR.execute(() -> {
            try {
                String urlStr = "https://i.ytimg.com/vi/" + targetVid + "/hqdefault.jpg";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoInput(true);
                conn.connect();

                try (InputStream is = conn.getInputStream()) {
                    Bitmap downloaded = BitmapFactory.decodeStream(is);
                    if (downloaded != null) {
                        Bitmap scaled = Bitmap.createScaledBitmap(downloaded, ARTWORK_SIZE, ARTWORK_SIZE, true);
                        if (scaled != downloaded) {
                            downloaded.recycle();
                        }
                        putCachedLiquidArt(targetVid, scaled);
                        mainHandler.post(() -> {
                            if (Objects.equals(currentVideoId, targetVid)) {
                                applyNewArtwork(scaled, targetVid);
                            }
                        });
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "DynamicBackground: Failed fetch artwork thumbnail", ex);
            }
        });
    }

    public void setVideoId(@Nullable String videoId, @Nullable String title, @Nullable String artist) {
        setArtwork(null, videoId, title, artist);
    }

    private void applyNewArtwork(@NonNull Bitmap newBmp, @Nullable String cacheKey) {
        Bitmap normalized;
        if (newBmp == defaultArtwork) {
            normalized = defaultArtwork;
        } else if (newBmp.getWidth() == ARTWORK_SIZE && newBmp.getHeight() == ARTWORK_SIZE) {
            normalized = newBmp;
        } else {
            normalized = Bitmap.createScaledBitmap(newBmp, ARTWORK_SIZE, ARTWORK_SIZE, true);
        }

        if (normalized != defaultArtwork) {
            putCachedLiquidArt(cacheKey, normalized);
        }

        if (currentArtwork != null && currentArtwork != normalized) {
            previousArtwork = currentArtwork;
            currentArtwork = normalized;
            artworkTransitionProgress = 0.0f;
            artworkTransitionStartUptimeMs = SystemClock.uptimeMillis();
        } else {
            currentArtwork = normalized;
            artworkTransitionProgress = 1.0f;
        }
        scheduleNextFrame(0L);
    }

    public void onBeatTrigger(float strength) {
        if (Settings.LYRICS_DYNAMIC_BACKGROUND.get() && !Settings.LYRICS_LIGHTWEIGHT_MODE.get()) {
            visualizerPulse = Math.max(visualizerPulse, Math.max(0f, Math.min(1f, strength)));
        }
    }

    // Init visualizer to capture audio waveform
    private void tryInitVisualizer() {
        if (!Settings.LYRICS_DYNAMIC_BACKGROUND.get() || Settings.LYRICS_LIGHTWEIGHT_MODE.get()) {
            releaseVisualizer();
            return;
        }
        if (visualizer != null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        try {
            visualizer = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = (range != null && range.length >= 2) ? Math.min(range[1], 1024) : 512;
            visualizer.setCaptureSize(captureSize);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    if (!Settings.LYRICS_DYNAMIC_BACKGROUND.get() || Settings.LYRICS_LIGHTWEIGHT_MODE.get()) {
                        visualizerPulse = 0.0f;
                        return;
                    }
                    if (waveform != null && waveform.length > 0) {
                        int maxDiff = 0;
                        for (byte b : waveform) {
                            int val = b & 0xFF;
                            int diff = Math.abs(val - 128);
                            if (diff > maxDiff) maxDiff = diff;
                        }
                        visualizerPulse = (float) maxDiff / 128.0f;
                    }
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate(), true, false);
            visualizer.setEnabled(true);
        } catch (Throwable ex) {
            Logger.printException(() -> "DynamicBackground: Visualizer init failed", ex);
            releaseVisualizer();
        }
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable ignored) {
            }
            visualizer = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttached = true;
        lastDrawTimeMs = 0L;
        lastRotationTickUptimeMs = 0L;
        if (getVisibility() == VISIBLE && Settings.LYRICS_DYNAMIC_BACKGROUND.get()) {
            if (!Settings.LYRICS_LIGHTWEIGHT_MODE.get()) {
                tryInitVisualizer();
            }
            scheduleNextFrame(0L);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttached = false;
        cancelScheduledFrame();
        releaseVisualizer();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && getVisibility() == VISIBLE && isAttached && Settings.LYRICS_DYNAMIC_BACKGROUND.get()) {
            if (!Settings.LYRICS_LIGHTWEIGHT_MODE.get()) {
                tryInitVisualizer();
            }
            scheduleNextFrame(0L);
        } else {
            cancelScheduledFrame();
            releaseVisualizer();
        }
    }

    // Process audio pulse from visualizer so layer bounce to music
    private void processAudioPulse() {
        if (!Settings.LYRICS_DYNAMIC_BACKGROUND.get() || Settings.LYRICS_LIGHTWEIGHT_MODE.get()) {
            beatPulse = 0.0f;
            visualizerPulse = 0.0f;
            if (visualizer != null) {
                releaseVisualizer();
            }
            return;
        }
        if (visualizer == null && isAttached && getVisibility() == VISIBLE) {
            tryInitVisualizer();
        }
        boolean isPlaying = LyricsManager.getInstance().isPlaying();
        if (isPlaying && visualizerPulse > 0.001f) {
            beatPulse = visualizerPulse;
            visualizerPulse *= 0.85f;
        } else {
            beatPulse = (beatPulse > 0.0001f) ? beatPulse * 0.9f : 0.0f;
        }
    }

    // Scale layer size according to aspect ratio so texture not stretch weird
    private static float computeLayerSizeFactor(int layerIndex, float aspectScaleX, float aspectScaleY, float smoothBeatScale) {
        float bx = Math.abs(LAYER_BASE_POSITIONS[layerIndex * 2]);
        float by = Math.abs(LAYER_BASE_POSITIONS[layerIndex * 2 + 1]);
        float reachX = 1.0f + bx;
        float reachY = 1.0f + by;
        float hypot = (float) Math.hypot(reachX * aspectScaleX, reachY * aspectScaleY);
        return hypot * LAYER_SCALES[layerIndex] * (1.0f + smoothBeatScale * BEAT_SCALE_BOOST[layerIndex]);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!Settings.LYRICS_DYNAMIC_BACKGROUND.get()) return;

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final long now = SystemClock.uptimeMillis();
        final boolean isLightweight = Settings.LYRICS_LIGHTWEIGHT_MODE.get();
        final boolean isPlaying = LyricsManager.getInstance().isPlaying();
        final float elapsedSec = updateDeltaTime(now);

        updateRotationTicks(now, isLightweight, isPlaying);
        updateTransition(now);
        processAudioPulse();

        final float currentTime = isLightweight ? 0.0f : (globalLiquidRotationTimeMs / 1000.0f);
        updateLayerKinematics(currentTime, elapsedSec, isLightweight, isPlaying);

        float screenAspect = (float) w / (float) h;
        renderToBuffer(screenAspect);
        applyBlurAndDraw(canvas, w, h);

        scheduleNextFrameIfNeeded(now, isLightweight, isPlaying);
    }

    private float updateDeltaTime(long now) {
        float elapsedSec = (lastDrawTimeMs > 0)
                ? Math.min((now - lastDrawTimeMs) / 1000.0f, 0.1f)
                : (FRAME_INTERVAL_MS / 1000.0f);
        lastDrawTimeMs = now;
        return elapsedSec;
    }

    private void updateRotationTicks(long now, boolean isLightweight, boolean isPlaying) {
        if (!isLightweight && isPlaying) {
            if (lastRotationTickUptimeMs == 0L) {
                lastRotationTickUptimeMs = now;
            } else if (now - lastRotationTickUptimeMs >= ROTATION_TICK_INTERVAL_MS) {
                long ticks = (now - lastRotationTickUptimeMs) / ROTATION_TICK_INTERVAL_MS;
                globalLiquidRotationTimeMs += ticks * ROTATION_TICK_INTERVAL_MS;
                lastRotationTickUptimeMs += ticks * ROTATION_TICK_INTERVAL_MS;
            }
        } else {
            lastRotationTickUptimeMs = now;
        }
    }

    private void updateTransition(long now) {
        if (artworkTransitionProgress < 1.0f) {
            if (artworkTransitionStartUptimeMs > 0L) {
                artworkTransitionProgress = Math.min(1.0f,
                        (float) (now - artworkTransitionStartUptimeMs) / (float) ARTWORK_TRANSITION_DURATION_MS);
            } else {
                artworkTransitionProgress = 1.0f;
            }
        }
    }

    // Calculate rotation, scale, and orbital position for all 3 layers
    private void updateLayerKinematics(float currentTime, float elapsedSec, boolean isLightweight, boolean isPlaying) {
        final float pulse = beatPulse;
        beatEnergyBaseline += (pulse - beatEnergyBaseline) * Math.min(1.0f, 0.8f * elapsedSec);
        final float relativePulse = Math.max(0.0f, pulse - beatEnergyBaseline);

        final float attackSpeed = 12.0f;
        final float decaySpeed = BEAT_SCALE_DECAY;

        for (int i = 0; i < 3; i++) {
            if (!isLightweight && isPlaying) {
                layerPerimTime[i] += elapsedSec * (1.0f + pulse * BEAT_SPD_BOOST[i]);
            }

            float speed = relativePulse > layerBeatScale[i] ? attackSpeed : decaySpeed;
            float delta = Math.min(1.0f, speed * elapsedSec);
            layerBeatScale[i] += (relativePulse - layerBeatScale[i]) * delta;
            layerBeatRot[i] += (relativePulse - layerBeatRot[i]) * delta;

            float bs = Math.max(0.0f, Math.min(1.0f, layerBeatScale[i]));
            float smoothBS = bs * bs * (3.0f - 2.0f * bs);

            float br = Math.max(0.0f, Math.min(1.0f, layerBeatRot[i]));
            float smoothBR = br * br * (3.0f - 2.0f * br);

            float rot = INITIAL_ROTATIONS[i] + (ROTATION_SPEEDS[i] * currentTime * ROTATION_POWER)
                    + smoothBR * BEAT_ROT_BOOST[i];

            float bx = LAYER_BASE_POSITIONS[i * 2];
            float by = LAYER_BASE_POSITIONS[i * 2 + 1];

            float px;
            float py;
            if (isLightweight) {
                px = bx;
                py = by;
            } else {
                float offset = i * 0.33f;
                float t = ((offset + PERIMETER_DIRECTION[i] * PERIMETER_SPEEDS[i] * layerPerimTime[i]) % 1.0f);
                if (t < 0.0f) t += 1.0f;
                float angle = t * TWO_PI;
                px = Math.abs(bx) * (float) Math.cos(angle);
                py = Math.abs(by) * (float) Math.sin(angle);
            }

            cachedLayerRots[i] = rot;
            cachedLayerSmoothBS[i] = smoothBS;
            cachedLayerPosX[i] = px;
            cachedLayerPosY[i] = py;
        }
    }

    // Render layers onto tiny buffer and handle crossfade transition
    private void renderToBuffer(float screenAspect) {
        bufferCanvas.drawColor(0xFF1E1E28);

        if (artworkTransitionProgress < 1.0f && previousArtwork != null && !previousArtwork.isRecycled()) {
            renderLayers(bufferCanvas, previousArtwork, 1.0f - artworkTransitionProgress, screenAspect);
        } else if (artworkTransitionProgress >= 1.0f) {
            previousArtwork = null;
        }

        Bitmap activeArt = (currentArtwork != null && !currentArtwork.isRecycled())
                ? currentArtwork
                : defaultArtwork;
        renderLayers(bufferCanvas, activeArt, (previousArtwork != null) ? artworkTransitionProgress : 1.0f, screenAspect);
    }

    // Blur low resolution buffer and draw to canvas with scrim gradient
    private void applyBlurAndDraw(Canvas canvas, int w, int h) {
        bufferBitmap.getPixels(pixelsSrc, 0, BUFFER_WIDTH, 0, 0, BUFFER_WIDTH, BUFFER_HEIGHT);
        fastSeparableBlur24x48(pixelsSrc, pixelsTemp, pixelsDst, BUFFER_WIDTH, BUFFER_HEIGHT, BLUR_RADIUS_X, BLUR_RADIUS_Y);
        blurBitmap.setPixels(pixelsDst, 0, BUFFER_WIDTH, 0, 0, BUFFER_WIDTH, BUFFER_HEIGHT);

        screenRect.set(0, 0, w, h);
        canvas.drawBitmap(blurBitmap, null, screenRect, postProcessPaint);

        if (scrimGradient == null || lastGradientHeight != h) {
            lastGradientHeight = h;
            scrimGradient = new LinearGradient(
                    0f, 0f, 0f, (float) h,
                    new int[]{0xCC000000, 0x88000000, 0xCC000000},
                    new float[]{0.0f, 0.5f, 1.0f},
                    Shader.TileMode.CLAMP
            );
            scrimPaint.setShader(scrimGradient);
        }
        canvas.drawRect(0, 0, w, h, scrimPaint);
        canvas.drawColor(0x27505050);
    }

    private void scheduleNextFrameIfNeeded(long now, boolean isLightweight, boolean isPlaying) {
        boolean needsNextFrame = (artworkTransitionProgress < 1.0f)
                || (!isLightweight && isPlaying);
        if (isAttached && getVisibility() == VISIBLE && needsNextFrame) {
            long renderCostMs = Math.max(0L, SystemClock.uptimeMillis() - now);
            long delayMs = Math.max(16L, FRAME_INTERVAL_MS - renderCostMs);
            scheduleNextFrame(delayMs);
        }
    }

    // Draw 3 rotating layers onto tiny buffer
    private void renderLayers(Canvas c, Bitmap bmp, float alpha, float screenAspect) {
        if (bmp == null || bmp.isRecycled() || alpha <= 0.001f) return;
        layerPaint.setAlpha((int) (255 * Math.min(1.0f, Math.max(0.0f, alpha))));

        float safeAspect = Math.max(screenAspect, 1e-4f);
        float aspectScaleX = screenAspect >= 1.0f ? 1.0f : (1.0f / safeAspect);
        float aspectScaleY = screenAspect >= 1.0f ? screenAspect : 1.0f;

        float bmpW = bmp.getWidth();
        float bmpH = bmp.getHeight();

        for (int i = 0; i < 3; i++) {
            float sizeFactor = computeLayerSizeFactor(i, aspectScaleX, aspectScaleY, cachedLayerSmoothBS[i]);
            float uvScaleX = sizeFactor / aspectScaleX;
            float uvScaleY = sizeFactor / aspectScaleY;

            layerMatrix.reset();
            layerMatrix.postTranslate(-bmpW * 0.5f, -bmpH * 0.5f);
            layerMatrix.postScale(1.0f / bmpW, 1.0f / bmpH);
            layerMatrix.postRotate((float) -Math.toDegrees(cachedLayerRots[i]));
            layerMatrix.postScale(uvScaleX * BUFFER_WIDTH * 0.5f, uvScaleY * BUFFER_HEIGHT * 0.5f);

            float transX = (0.5f + 0.5f * cachedLayerPosX[i]) * BUFFER_WIDTH;
            float transY = (0.5f - 0.5f * cachedLayerPosY[i]) * BUFFER_HEIGHT;
            layerMatrix.postTranslate(transX, transY);

            c.drawBitmap(bmp, layerMatrix, layerPaint);
        }
    }

    // Multi pass separable box blur on 24x48 pixel array
    public static void fastSeparableBlur24x48(
            int[] src, int[] temp, int[] dst, int w, int h, int radiusX, int radiusY) {
        if (radiusX < 1 && radiusY < 1) {
            System.arraycopy(src, 0, dst, 0, w * h);
            return;
        }

        int rx = Math.max(1, radiusX);
        int ry = Math.max(1, radiusY);

        // Pass 1
        boxBlurH(src, temp, w, h, rx);
        boxBlurV(temp, dst, w, h, ry);

        // Pass 2
        boxBlurH(dst, temp, w, h, rx);
        boxBlurV(temp, dst, w, h, ry);

        // Pass 3 so blur look smooth like gaussian
        boxBlurH(dst, temp, w, h, rx);
        boxBlurV(temp, dst, w, h, ry);
    }

    private static void boxBlurH(int[] src, int[] dst, int w, int h, int radius) {
        final int div = 2 * radius + 1;
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            final int firstPixel = src[row];
            final int fr = (firstPixel >> 16) & 0xFF;
            final int fg = (firstPixel >> 8) & 0xFF;
            final int fb = firstPixel & 0xFF;

            int rSum = fr * (radius + 1);
            int gSum = fg * (radius + 1);
            int bSum = fb * (radius + 1);

            for (int i = 1; i <= radius; i++) {
                int p = src[row + Math.min(i, w - 1)];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int x = 0; x < w; x++) {
                dst[row + x] = 0xFF000000
                        | ((rSum / div) << 16)
                        | ((gSum / div) << 8)
                        | (bSum / div);

                int pIn = src[row + Math.min(x + radius + 1, w - 1)];
                int pOut = src[row + Math.max(x - radius, 0)];

                rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bSum += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void boxBlurV(int[] src, int[] dst, int w, int h, int radius) {
        final int div = 2 * radius + 1;
        for (int x = 0; x < w; x++) {
            final int firstPixel = src[x];
            final int fr = (firstPixel >> 16) & 0xFF;
            final int fg = (firstPixel >> 8) & 0xFF;
            final int fb = firstPixel & 0xFF;

            int rSum = fr * (radius + 1);
            int gSum = fg * (radius + 1);
            int bSum = fb * (radius + 1);

            for (int i = 1; i <= radius; i++) {
                int p = src[Math.min(i, h - 1) * w + x];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | ((rSum / div) << 16)
                        | ((gSum / div) << 8)
                        | (bSum / div);

                int pIn = src[Math.min(y + radius + 1, h - 1) * w + x];
                int pOut = src[Math.max(y - radius, 0) * w + x];

                rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bSum += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }
}
