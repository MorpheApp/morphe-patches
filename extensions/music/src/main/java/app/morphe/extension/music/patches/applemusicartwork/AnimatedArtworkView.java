/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5082
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.applemusicartwork;

import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

import app.morphe.extension.shared.Logger;

/**
 * Plays an artwork video in a loop over the player artwork, while the player artwork is shown
 * and the music plays.
 */
@SuppressLint("ViewConstructor")
final class AnimatedArtworkView extends TextureView
        implements TextureView.SurfaceTextureListener, ViewTreeObserver.OnPreDrawListener {

    private final ImageView thumbnail;
    private final File file;

    @Nullable
    private MediaPlayer player;
    @Nullable
    private Surface surface;
    private boolean prepared;
    /**
     * If the video shows its first frame. Until then the view is transparent.
     */
    private boolean rendering;
    private int videoWidth;
    private int videoHeight;

    /**
     * Adds a view that plays the artwork file over the player artwork, below the player controls.
     */
    static AnimatedArtworkView addAbove(ImageView thumbnail, File file) {
        AnimatedArtworkView view = new AnimatedArtworkView(thumbnail, file);
        ViewGroup parent = (ViewGroup) thumbnail.getParent();
        parent.addView(view, parent.indexOfChild(thumbnail) + 1,
                new ViewGroup.LayoutParams(thumbnail.getLayoutParams()));
        return view;
    }

    private AnimatedArtworkView(ImageView thumbnail, File file) {
        super(thumbnail.getContext());
        this.thumbnail = thumbnail;
        this.file = file;

        setAlpha(0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setSurfaceTextureListener(this);
    }

    /**
     * @return If this view plays the file over the thumbnail.
     */
    boolean shows(@Nullable ImageView thumbnail, @Nullable File file) {
        return this.thumbnail == thumbnail && this.file.equals(file) && getParent() == thumbnail.getParent();
    }

    void remove() {
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
        releasePlayer();
    }

    /**
     * Plays the video while the music plays and the view is shown, and pauses it otherwise.
     */
    void updatePlayback() {
        MediaPlayer player = this.player;
        if (player == null || !prepared) return;

        final boolean play = AppleMusicArtworkPatch.isMusicPlaying() && getVisibility() == VISIBLE;
        if (play != player.isPlaying()) {
            if (play) {
                player.start();
            } else {
                player.pause();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(this);
        super.onDetachedFromWindow();
    }

    /**
     * Follows the visibility and the transparency of the player artwork,
     * which is hidden while the player shows a video.
     */
    @Override
    public boolean onPreDraw() {
        try {
            final int visibility = thumbnail.getVisibility();
            if (getVisibility() != visibility) {
                setVisibility(visibility);
                updatePlayback();
            }

            final float alpha = rendering ? thumbnail.getAlpha() : 0;
            if (getAlpha() != alpha) {
                setAlpha(alpha);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPreDraw failure", ex);
        }
        return true;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        try {
            startPlayer(surfaceTexture);
        } catch (Exception ex) {
            Logger.printException(() -> "onSurfaceTextureAvailable failure", ex);
            AppleMusicArtworkPatch.onArtworkPlaybackError(this);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        applyCenterCrop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    private void startPlayer(SurfaceTexture surfaceTexture) throws IOException {
        releasePlayer();

        MediaPlayer player = new MediaPlayer();
        this.player = player;
        surface = new Surface(surfaceTexture);
        player.setSurface(surface);
        player.setDataSource(file.getAbsolutePath());
        player.setVolume(0, 0);
        player.setLooping(true);
        player.setOnVideoSizeChangedListener((mediaPlayer, width, height) -> {
            videoWidth = width;
            videoHeight = height;
            applyCenterCrop();
        });
        player.setOnPreparedListener(mediaPlayer -> {
            prepared = true;
            updatePlayback();
        });
        player.setOnInfoListener((mediaPlayer, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                rendering = true;
                invalidate();
            }
            return false;
        });
        player.setOnErrorListener((mediaPlayer, what, extra) -> {
            Logger.printInfo(() -> "Artwork playback error: " + what + " extra: " + extra + " file: " + file);
            AppleMusicArtworkPatch.onArtworkPlaybackError(this);
            return true;
        });
        player.prepareAsync();
    }

    private void releasePlayer() {
        MediaPlayer player = this.player;
        if (player != null) {
            this.player = null;
            player.release();
        }

        Surface surface = this.surface;
        if (surface != null) {
            this.surface = null;
            surface.release();
        }

        prepared = false;
        rendering = false;
    }

    /**
     * Scales the video to fill the view, like the center crop of the player artwork.
     */
    private void applyCenterCrop() {
        final int width = getWidth();
        final int height = getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return;

        final float scale = Math.max((float) width / videoWidth, (float) height / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f);
        setTransform(matrix);
    }
}
