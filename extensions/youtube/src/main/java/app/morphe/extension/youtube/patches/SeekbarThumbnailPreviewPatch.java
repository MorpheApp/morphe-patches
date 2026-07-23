/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2182
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
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

    private record SeekbarViews(FrameLayout previewFrame, ImageView thumbnailPreview, TextView timestampPreview,
                                PopupWindow thumbnailPreviewPopup) {
    }

    private static final int THUMBNAIL_PREVIEW_LONG_SIDE_DP = 160;
    private static final int THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE_DP = THUMBNAIL_PREVIEW_LONG_SIDE_DP * 9 / 16;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_DP = 10;
    private static final int THUMBNAIL_PREVIEW_TIMESTAMP_HEIGHT_DP = 24;
    private static final int THUMBNAIL_PREVIEW_CORNER_RADIUS_DP = 8;
    private static final int THUMBNAIL_PREVIEW_BORDER_WIDTH_DP = 2;
    private static final int THUMBNAIL_PREVIEW_BORDER_COLOR = 0xB3FFFFFF;
    private static final ColorDrawable previewPopupBackgroundDrawable = new ColorDrawable(Color.TRANSPARENT);

    @SuppressLint("StaticFieldLeak")
    private static SeekbarViews seekbarViews;
    private static WeakReference<Bitmap> fineScrubbingPreviewBitmapRef = new WeakReference<>(null);
    private static Bitmap lastAppliedBitmap;
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

        final int longSidePx = Dim.dp(THUMBNAIL_PREVIEW_LONG_SIDE_DP);
        final int shortSidePx = Dim.dp(THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE_DP);
        final int cornerRadiusPx = Dim.dp(THUMBNAIL_PREVIEW_CORNER_RADIUS_DP);
        final int borderWidthPx = Dim.dp(THUMBNAIL_PREVIEW_BORDER_WIDTH_DP);
        Context context = trackBall.getRootView().getContext();
        LinearLayout containerLayout = new LinearLayout(context);
        containerLayout.setOrientation(LinearLayout.VERTICAL);
        containerLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout previewFrame = createPreviewFrame(context, cornerRadiusPx, borderWidthPx);
        ImageView thumbnailPreview = createThumbnailImageView(context, cornerRadiusPx, borderWidthPx);
        previewFrame.addView(thumbnailPreview);

        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(longSidePx, shortSidePx);
        previewFrame.setLayoutParams(frameParams);
        containerLayout.addView(previewFrame);

        TextView timestampPreview = createTimestampPreview(context);
        containerLayout.addView(timestampPreview);

        PopupWindow thumbnailPreviewPopup = new PopupWindow(containerLayout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, false);
        thumbnailPreviewPopup.setTouchable(false);
        thumbnailPreviewPopup.setBackgroundDrawable(previewPopupBackgroundDrawable);

        return seekbarViews = new SeekbarViews(previewFrame, thumbnailPreview, timestampPreview, thumbnailPreviewPopup);
    }

    // Border is a filled rounded rect + padding (not a stroke) to keep outer/inner corners concentric.
    @SuppressWarnings("SuspiciousNameCombination")
    private static FrameLayout createPreviewFrame(Context context, int cornerRadiusPx, int borderWidthPx) {
        FrameLayout previewFrame = new FrameLayout(context);
        GradientDrawable frameBackground = new GradientDrawable();
        frameBackground.setColor(THUMBNAIL_PREVIEW_BORDER_COLOR);
        frameBackground.setCornerRadius(cornerRadiusPx);
        previewFrame.setBackground(frameBackground);
        previewFrame.setPadding(borderWidthPx, borderWidthPx, borderWidthPx, borderWidthPx);
        return previewFrame;
    }

    private static ImageView createThumbnailImageView(Context context, int cornerRadiusPx, int borderWidthPx) {
        ImageView thumbnailPreview = new ImageView(context);
        thumbnailPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Inner radius = outer radius minus the border width so the clipped image hugs the border.
        final int innerRadiusPx = Math.max(0, cornerRadiusPx - borderWidthPx);
        thumbnailPreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), innerRadiusPx);
            }
        });
        thumbnailPreview.setClipToOutline(true);
        thumbnailPreview.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return thumbnailPreview;
    }

    private static TextView createTimestampPreview(Context context) {
        TextView timestampPreview = new TextView(context);
        timestampPreview.setTextColor(Color.WHITE);
        timestampPreview.setTextSize(12);
        timestampPreview.setPadding(0, Dim.dp4, 0, 0);
        timestampPreview.setShadowLayer(3, 1, 1, Color.BLACK);
        timestampPreview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return timestampPreview;
    }

    // Match the preview's aspect ratio to the bitmap (which mirrors the video).
    private static void applyBitmapAspectRatio(FrameLayout previewFrame, Bitmap bitmap) {
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }
        final int longSidePx = Dim.dp(THUMBNAIL_PREVIEW_LONG_SIDE_DP);
        final int newWidth;
        final int newHeight;
        if (bitmapWidth >= bitmapHeight) {
            newWidth = longSidePx;
            newHeight = (int) ((long) longSidePx * bitmapHeight / bitmapWidth);
        } else {
            newHeight = longSidePx;
            newWidth = (int) ((long) longSidePx * bitmapWidth / bitmapHeight);
        }
        LinearLayout.LayoutParams frameParams = (LinearLayout.LayoutParams) previewFrame.getLayoutParams();
        if (frameParams.width != newWidth || frameParams.height != newHeight) {
            frameParams.width = newWidth;
            frameParams.height = newHeight;
            previewFrame.setLayoutParams(frameParams);
        }
    }

    private static String formatSeekTime(int totalSeconds) {
        final int hours = totalSeconds / 3600;
        final int minutes = (totalSeconds % 3600) / 60;
        final int seconds = totalSeconds % 60;
        return (hours > 0)
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
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
                lastAppliedBitmap = null;
                seekbarViews = null;
                return;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (trackBallPosX == lastX) {
                    return;
                }
                lastX = trackBallPosX;

                View rootView = trackBall.getRootView();

                Bitmap currentScrubbedPreviewBitmap = fineScrubbingPreviewBitmapRef.get();
                if (currentScrubbedPreviewBitmap != null && currentScrubbedPreviewBitmap != lastAppliedBitmap) {
                    views.thumbnailPreview.setImageBitmap(currentScrubbedPreviewBitmap);
                    lastAppliedBitmap = currentScrubbedPreviewBitmap;
                    applyBitmapAspectRatio(views.previewFrame, currentScrubbedPreviewBitmap);
                }

                if (fineScrubbingTimeMillis >= 0) {
                    final int maxPixel = Dim.getScreenWidth();
                    final long totalVideoMillis = VideoInformation.getVideoLength();

                    if (totalVideoMillis > 0 && maxPixel > 0) {
                        final int totalSeconds = (int) ((((long) fineScrubbingTimeMillis * totalVideoMillis) / maxPixel) / 1000);
                        views.timestampPreview.setText(formatSeekTime(totalSeconds));
                    }
                }

                final int[] locationOnScreen = new int[2];
                trackBall.getLocationOnScreen(locationOnScreen);
                if (locationOnScreen[0] == 0 && locationOnScreen[1] == 0) {
                    return;
                }

                final LinearLayout.LayoutParams currentFrameParams =
                        (LinearLayout.LayoutParams) views.previewFrame.getLayoutParams();
                final int previewWidthPx = currentFrameParams.width;
                final int previewHeightPx = currentFrameParams.height;

                int targetX = locationOnScreen[0] + trackBallPosX - (previewWidthPx / 2);
                final int targetY = locationOnScreen[1] - previewHeightPx
                        - Dim.dp(THUMBNAIL_PREVIEW_DISTANCE_DP) - Dim.dp(THUMBNAIL_PREVIEW_TIMESTAMP_HEIGHT_DP);

                final int screenWidth = Dim.getScreenWidth();
                if (targetX < 0) {
                    targetX = 0;
                } else if (targetX + previewWidthPx > screenWidth) {
                    targetX = screenWidth - previewWidthPx;
                }

                PopupWindow thumbnailPreviewPopup = views.thumbnailPreviewPopup;
                if (!thumbnailPreviewPopup.isShowing()) {
                    // Wait until the first bitmap so the popup shows immediately with the correct
                    // aspect ratio and Y offset, avoiding a jump from a default 16:9 position.
                    if (rootView.getWindowToken() != null && lastAppliedBitmap != null) {
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
