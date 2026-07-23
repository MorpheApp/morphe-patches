/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SeekbarThumbnailPreviewPatch {

    private static PopupWindow thumbnailPreviewPopup = null;
    private static WeakReference<ImageView> thumbnailPreviewRef = new WeakReference<>(null);
    private static WeakReference<TextView> thumbnailPreviewTimestampRef = new WeakReference<>(null);
    private static WeakReference<View> trackBallRef = new WeakReference<>(null);
    private static WeakReference<Bitmap> fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
    private static int fineScrubbingTimeMillis = 0;
    private static final ColorDrawable previewPopupBackGroundDrawable = new ColorDrawable(Color.TRANSPARENT);
    private static final int THUMBNAIL_PREVIEW_SIZE_DP = 120;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_DP = 10;
    private static int lastX = -1;

    /**
     * Injection point.
     */
    public static void initializeThumbnailPreviewContainer(View trackBall) {
        if (!Settings.THUMBNAIL_PREVIEW.get()) {
            return;
        }

        trackBallRef = new WeakReference<>(trackBall);

        final View rootView = trackBall.getRootView();
        final Context context = rootView.getContext();
        ImageView thumbnailPreview = thumbnailPreviewRef.get();
        TextView timestampPreview = thumbnailPreviewTimestampRef.get();

        final int sizeInPixels = Dim.dp(THUMBNAIL_PREVIEW_SIZE_DP);

        if (thumbnailPreview == null || timestampPreview == null) {
            final LinearLayout containerLayout = new LinearLayout(context);
            containerLayout.setOrientation(LinearLayout.VERTICAL);
            containerLayout.setGravity(Gravity.CENTER_HORIZONTAL);

            thumbnailPreview = new ImageView(context);
            thumbnailPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnailPreview.setBackgroundColor(Color.BLACK);
            final LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(sizeInPixels, sizeInPixels);
            thumbnailPreview.setLayoutParams(imgParams);
            thumbnailPreviewRef = new WeakReference<>(thumbnailPreview);
            containerLayout.addView(thumbnailPreview);

            timestampPreview = new TextView(context);
            timestampPreview.setTextColor(Color.WHITE);
            timestampPreview.setTextSize(12);
            timestampPreview.setPadding(0, Dim.dp(4), 0, 0);
            timestampPreview.setShadowLayer(3, 1, 1, Color.BLACK);
            final LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            timestampPreview.setLayoutParams(textParams);
            thumbnailPreviewTimestampRef = new WeakReference<>(timestampPreview);
            containerLayout.addView(timestampPreview);

            if (thumbnailPreviewPopup == null) {
                thumbnailPreviewPopup = new PopupWindow(containerLayout, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false);
                thumbnailPreviewPopup.setTouchable(false);
                thumbnailPreviewPopup.setBackgroundDrawable(previewPopupBackGroundDrawable);
            } else {
                thumbnailPreviewPopup.setContentView(containerLayout);
            }
        }
    }

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

        Log.d("LOLOLOLOLO", String.valueOf(newlyTimeMillis));

        fineScrubbingTimeMillis = newlyTimeMillis;
    }

    /**
     * Injection point.
     */
    public static void updateThumbnailPreview(MotionEvent trackBallMotionEvent, int trackBallPosX) {
        if (!Settings.THUMBNAIL_PREVIEW.get()) {
            return;
        }

        final int action = trackBallMotionEvent.getAction();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lastX = -1;
            fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
            if (thumbnailPreviewPopup != null && thumbnailPreviewPopup.isShowing()) {
                thumbnailPreviewPopup.dismiss();
            }
            return;
        }

        final View trackBall = trackBallRef.get();
        if (trackBall == null) {
            return;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            initializeThumbnailPreviewContainer(trackBall);
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (trackBallPosX == lastX) {
                return;
            }
            lastX = trackBallPosX;

            final View rootView = trackBall.getRootView();
            final Context context = rootView.getContext();
            final int sizeInPixels = Dim.dp(THUMBNAIL_PREVIEW_SIZE_DP);

            if (thumbnailPreviewRef.get() == null || thumbnailPreviewTimestampRef.get() == null || thumbnailPreviewPopup == null) {
                initializeThumbnailPreviewContainer(trackBall);
            }

            final ImageView thumbnailPreview = thumbnailPreviewRef.get();
            final TextView timestampPreview = thumbnailPreviewTimestampRef.get();
            final Bitmap currentScrubbedPreviewBitmap = fineScrubbingPreviewBitmapRef.get();

            if (thumbnailPreview != null && currentScrubbedPreviewBitmap != null) {
                thumbnailPreview.setImageBitmap(currentScrubbedPreviewBitmap);
            }

            if (timestampPreview != null && fineScrubbingTimeMillis > 0) {
                timestampPreview.setText(formatTime(fineScrubbingTimeMillis));
            }

            final int[] locationOnScreen = new int[2];
            trackBall.getLocationOnScreen(locationOnScreen);
            if (locationOnScreen[0] == 0 && locationOnScreen[1] == 0) {
                return;
            }

            int targetX = locationOnScreen[0] + trackBallPosX - (sizeInPixels / 2);
            int targetY = locationOnScreen[1] - sizeInPixels - Dim.dp(THUMBNAIL_PREVIEW_DISTANCE_DP) - Dim.dp(24);

            final int screenWidth = Dim.getScreenWidth();
            if (targetX < 0) {
                targetX = 0;
            }
            if (targetX + sizeInPixels > screenWidth) {
                targetX = screenWidth - sizeInPixels;
            }

            if (!thumbnailPreviewPopup.isShowing()) {
                if (rootView.getWindowToken() != null) {
                    thumbnailPreviewPopup.showAtLocation(rootView, Gravity.NO_GRAVITY, targetX, targetY);
                }
            } else {
                thumbnailPreviewPopup.update(targetX, targetY, thumbnailPreviewPopup.getWidth(), thumbnailPreviewPopup.getHeight());
            }
        }
    }

    /**
     * Injection point.
     */
    public static boolean disableBigBoardUpdate() {
        return Settings.THUMBNAIL_PREVIEW.get();
    }

    private static String formatTime(int millis) {
        final int seconds = (millis / 1000) % 60;
        final int minutes = (millis / (1000 * 60)) % 60;
        final int hours = (millis / (1000 * 60 * 60)) % 24;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
}
