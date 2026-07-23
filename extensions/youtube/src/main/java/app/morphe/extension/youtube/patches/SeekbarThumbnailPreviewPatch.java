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
import android.util.Pair;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SeekbarThumbnailPreviewPatch {

    private static final int THUMBNAIL_PREVIEW_SIZE_DP = 120;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_DP = 10;
    private static final ColorDrawable previewPopupBackGroundDrawable = new ColorDrawable(Color.TRANSPARENT);

    @Nullable
    private static PopupWindow thumbnailPreviewPopup;
    private static WeakReference<ImageView> thumbnailPreviewRef = new WeakReference<>(null);
    private static WeakReference<TextView> thumbnailPreviewTimestampRef = new WeakReference<>(null);
    private static WeakReference<Bitmap> fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
    private static int fineScrubbingTimeMillis;
    private static int lastX = -1;

    private static String formatTime(int millis) {
        final int seconds = (millis / 1000) % 60;
        final int minutes = (millis / (1000 * 60)) % 60;
        final int hours = (millis / (1000 * 60 * 60)) % 24;

        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.US, "%02d:%02d", minutes, seconds);
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

    private static Pair<ImageView, TextView> initializeThumbnailPreviewContainer(View trackBall) {
        ImageView thumbnailPreview = thumbnailPreviewRef.get();
        TextView timestampPreview = thumbnailPreviewTimestampRef.get();

        if (thumbnailPreview != null && timestampPreview != null) {
            return new Pair<>(thumbnailPreview, timestampPreview);
        }

        final int sizeInPixels = Dim.dp(THUMBNAIL_PREVIEW_SIZE_DP);
        View rootView = trackBall.getRootView();
        Context context = rootView.getContext();
        LinearLayout containerLayout = new LinearLayout(context);
        containerLayout.setOrientation(LinearLayout.VERTICAL);
        containerLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        thumbnailPreview = new ImageView(context);
        thumbnailPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnailPreview.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(sizeInPixels, sizeInPixels);
        thumbnailPreview.setLayoutParams(imgParams);
        thumbnailPreviewRef = new WeakReference<>(thumbnailPreview);
        containerLayout.addView(thumbnailPreview);

        timestampPreview = new TextView(context);
        timestampPreview.setTextColor(Color.WHITE);
        timestampPreview.setTextSize(12);
        timestampPreview.setPadding(0, Dim.dp(4), 0, 0);
        timestampPreview.setShadowLayer(3, 1, 1, Color.BLACK);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timestampPreview.setLayoutParams(textParams);
        thumbnailPreviewTimestampRef = new WeakReference<>(timestampPreview);
        containerLayout.addView(timestampPreview);

        if (thumbnailPreviewPopup == null) {
            thumbnailPreviewPopup = new PopupWindow(containerLayout, LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, false);
            thumbnailPreviewPopup.setTouchable(false);
            thumbnailPreviewPopup.setBackgroundDrawable(previewPopupBackGroundDrawable);
        } else {
            thumbnailPreviewPopup.setContentView(containerLayout);
        }
        return new Pair<>(thumbnailPreview, timestampPreview);
    }

    /**
     * Injection point.
     */
    public static void updateThumbnailPreview(View trackBall, MotionEvent trackBallMotionEvent, int trackBallPosX) {
        try {
            if (!Settings.THUMBNAIL_PREVIEW.get()) {
                return;
            }

            Pair<ImageView, TextView> views = initializeThumbnailPreviewContainer(trackBall);

            final int action = trackBallMotionEvent.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                return;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastX = -1;
                fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
                if (thumbnailPreviewPopup != null && thumbnailPreviewPopup.isShowing()) {
                    thumbnailPreviewPopup.dismiss();
                    thumbnailPreviewPopup = null;
                }
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
                    views.first.setImageBitmap(currentScrubbedPreviewBitmap);
                }

                if (fineScrubbingTimeMillis > 0) {
                    views.second.setText(formatTime(fineScrubbingTimeMillis));
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

                if (!Objects.requireNonNull(thumbnailPreviewPopup).isShowing()) {
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
