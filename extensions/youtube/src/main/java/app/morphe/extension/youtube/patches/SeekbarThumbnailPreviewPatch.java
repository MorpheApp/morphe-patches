/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SeekbarThumbnailPreviewPatch {

    private record SeekbarViews(ImageView thumbnailPreview, TextView timestampPreview,
                                PopupWindow thumbnailPreviewPopup) {
    }

    private static final int THUMBNAIL_PREVIEW_SIZE_DP = 120;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_DP = 10;
    private static final ColorDrawable previewPopupBackGroundDrawable = new ColorDrawable(Color.TRANSPARENT);

    @SuppressLint("StaticFieldLeak")
    private static SeekbarViews seekbarViews;
    private static WeakReference<Bitmap> fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
    private static int fineScrubbingTimeMillis;
    private static int lastX = -1;

    /**
     * Injection point.
     */
    public static void setFineScrubbingPreviewBitmap(Bitmap bitmap) {
        if (!Settings.THUMBNAIL_PREVIEW.get()) {
            return;
        }

        fineScrubbingPreviewBitmapRef = new WeakReference<>(bitmap);
    }

    /**
     * Injection point.
     */
    public static void setFineScrubbingTimeMillis(int newlyTimeMillis) {
        if (!Settings.THUMBNAIL_PREVIEW.get()) {
            return;
        }

        fineScrubbingTimeMillis = newlyTimeMillis;
    }

    private static SeekbarViews initializeThumbnailPreviewContainer(View trackBall) {
        SeekbarViews views = seekbarViews;
        if (views != null) {
            return views;
        }

        final int sizeInPixels = Dim.dp(THUMBNAIL_PREVIEW_SIZE_DP);
        View rootView = trackBall.getRootView();
        Context context = rootView.getContext();
        LinearLayout containerLayout = new LinearLayout(context);
        containerLayout.setOrientation(LinearLayout.VERTICAL);
        containerLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView thumbnailPreview = new ImageView(context);
        thumbnailPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnailPreview.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(sizeInPixels, sizeInPixels);
        thumbnailPreview.setLayoutParams(imgParams);
        containerLayout.addView(thumbnailPreview);

        TextView timestampPreview = new TextView(context);
        timestampPreview.setTextColor(Color.WHITE);
        timestampPreview.setTextSize(12);
        timestampPreview.setPadding(0, Dim.dp(4), 0, 0);
        timestampPreview.setShadowLayer(3, 1, 1, Color.BLACK);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timestampPreview.setLayoutParams(textParams);
        containerLayout.addView(timestampPreview);

        PopupWindow thumbnailPreviewPopup = new PopupWindow(containerLayout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, false);
        thumbnailPreviewPopup.setTouchable(false);
        thumbnailPreviewPopup.setBackgroundDrawable(previewPopupBackGroundDrawable);

        return seekbarViews = new SeekbarViews(thumbnailPreview, timestampPreview, thumbnailPreviewPopup);
    }

    /**
     * Injection point.
     */
    public static void updateThumbnailPreview(View trackBall, MotionEvent trackBallMotionEvent, int trackBallPosX) {
        try {
            if (!Settings.THUMBNAIL_PREVIEW.get()) {
                return;
            }

            SeekbarViews views = initializeThumbnailPreviewContainer(trackBall);

            final int action = trackBallMotionEvent.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                return;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastX = -1;
                if (views.thumbnailPreviewPopup.isShowing()) {
                    views.thumbnailPreviewPopup.dismiss();
                }
                fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
                seekbarViews = null;
                return;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (trackBallPosX == lastX) {
                    return;
                }
                lastX = trackBallPosX;

                View rootView = trackBall.getRootView();
                final int sizeInPixels = Dim.dp(THUMBNAIL_PREVIEW_SIZE_DP);

                Bitmap currentScrubbedPreviewBitmap = fineScrubbingPreviewBitmapRef.get();
                if (currentScrubbedPreviewBitmap != null) {
                    views.thumbnailPreview.setImageBitmap(currentScrubbedPreviewBitmap);
                }

                if (fineScrubbingTimeMillis >= 0) {
                    final int maxPixel = Dim.getScreenWidth();
                    final long totalVideoMillis = VideoInformation.getVideoLength();

                    if (totalVideoMillis > 0 && maxPixel > 0) {
                        final int totalSeconds = (int) ((((long) fineScrubbingTimeMillis * totalVideoMillis) / maxPixel) / 1000);
                        final int hours = totalSeconds / 3600;
                        final int minutes = (totalSeconds % 3600) / 60;
                        final int seconds = totalSeconds % 60;

                        String currentSeekTime = (hours > 0)
                                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                                : String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);

                        views.timestampPreview.setText(currentSeekTime);
                    }
                }

                final int[] locationOnScreen = new int[2];
                trackBall.getLocationOnScreen(locationOnScreen);
                if (locationOnScreen[0] == 0 && locationOnScreen[1] == 0) {
                    return;
                }

                int targetX = locationOnScreen[0] + trackBallPosX - (sizeInPixels / 2);
                final int targetY = locationOnScreen[1] - sizeInPixels
                        - Dim.dp(THUMBNAIL_PREVIEW_DISTANCE_DP) - Dim.dp(24);

                final int screenWidth = Dim.getScreenWidth();
                if (targetX < 0) {
                    targetX = 0;
                } else if (targetX + sizeInPixels > screenWidth) {
                    targetX = screenWidth - sizeInPixels;
                }

                PopupWindow thumbnailPreviewPopup = views.thumbnailPreviewPopup;
                if (!thumbnailPreviewPopup.isShowing()) {
                    if (rootView.getWindowToken() != null) {
                        thumbnailPreviewPopup.showAtLocation(rootView, Gravity.NO_GRAVITY, targetX, targetY);
                    }
                } else {
                    thumbnailPreviewPopup.update(targetX, targetY, thumbnailPreviewPopup.getWidth(),
                            thumbnailPreviewPopup.getHeight());
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateThumbnailPreview failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static boolean disableBigBoardUpdate() {
        return Settings.THUMBNAIL_PREVIEW.get();
    }
}
