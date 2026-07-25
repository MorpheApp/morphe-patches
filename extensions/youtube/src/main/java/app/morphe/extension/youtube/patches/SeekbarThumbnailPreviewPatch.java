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
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public class SeekbarThumbnailPreviewPatch {

    public interface TimelineMarker {
        long patch_getStartMillis();
        long patch_getEndMillis();
        CharSequence patch_getTitle();
    }

    private record SeekbarViews(FrameLayout previewFrame, ImageView thumbnailPreview, TextView timestampPreview,
                                TextView chapterPreview, PopupWindow thumbnailPreviewPopup) {
    }

    private static final int THUMBNAIL_PREVIEW_LONG_SIDE = 160;
    private static final int THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE = THUMBNAIL_PREVIEW_LONG_SIDE * 9 / 16;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_FULLSCREEN_DP = Dim.dp10;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_PORTRAIT_DP = -1 * Dim.dp20;
    private static final int THUMBNAIL_PREVIEW_TEXT_HEIGHT_DP = Dim.dp(44);
    private static final int THUMBNAIL_PREVIEW_CORNER_RADIUS_DP = Dim.dp8;
    private static final int THUMBNAIL_PREVIEW_BORDER_WIDTH_DP = Dim.dp2;
    private static final int THUMBNAIL_PREVIEW_BORDER_COLOR = 0xB3FFFFFF;
    private static final ColorDrawable previewPopupBackgroundDrawable = new ColorDrawable(Color.TRANSPARENT);

    @SuppressLint("StaticFieldLeak")
    private static SeekbarViews seekbarViews;
    private static Bitmap fineScrubbingPreviewBitmap;
    private static Bitmap lastAppliedBitmap;
    private static int lastX = -1;
    private static float touchEventInitialY = -1;

    @Nullable
    private static volatile TimelineMarker[] chapterMarkers;

    /**
     * Injection point.
     */
    public static void setFineScrubbingPreviewBitmap(Bitmap bitmap) {
        if (!Settings.THUMBNAIL_PREVIEW.get() ||
                !PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                ShortsPlayerState.isOpen()) {
            return;
        }

        fineScrubbingPreviewBitmap = bitmap;
    }

    private static SeekbarViews initializeThumbnailPreviewContainer(View trackBall) {
        SeekbarViews views = seekbarViews;
        if (views != null) {
            return views;
        }

        final int longSidePx = Dim.dp(THUMBNAIL_PREVIEW_LONG_SIDE);
        final int shortSidePx = Dim.dp(THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE);
        final int cornerRadiusPx = THUMBNAIL_PREVIEW_CORNER_RADIUS_DP;
        final int borderWidthPx = THUMBNAIL_PREVIEW_BORDER_WIDTH_DP;
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

        TextView chapterPreview = createChapterPreview(context);
        containerLayout.addView(chapterPreview);

        PopupWindow thumbnailPreviewPopup = new PopupWindow(containerLayout, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, false);
        thumbnailPreviewPopup.setTouchable(false);
        thumbnailPreviewPopup.setBackgroundDrawable(previewPopupBackgroundDrawable);

        return seekbarViews = new SeekbarViews(previewFrame, thumbnailPreview, timestampPreview, chapterPreview, thumbnailPreviewPopup);
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

    private static TextView createChapterPreview(Context context) {
        TextView chapterPreview = new TextView(context);
        chapterPreview.setTextColor(Color.WHITE);
        chapterPreview.setTextSize(12);
        chapterPreview.setPadding(0, Dim.dp2, 0, 0);
        chapterPreview.setShadowLayer(3, 1, 1, Color.BLACK);
        chapterPreview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return chapterPreview;
    }

    // Match the preview's aspect ratio to the bitmap (which mirrors the video).
    private static void applyBitmapAspectRatio(FrameLayout previewFrame, Bitmap bitmap) {
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }

        final int longSidePx = Dim.dp(THUMBNAIL_PREVIEW_LONG_SIDE);
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
    public static void updateThumbnailPreview(View container, MotionEvent containerMotionEvent, Point trackballPos) {
        try {
            if (!Settings.THUMBNAIL_PREVIEW.get() ||
                    !PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                    ShortsPlayerState.isOpen()) {
                return;
            }

            SeekbarViews views = initializeThumbnailPreviewContainer(container);

            final int action = containerMotionEvent.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                touchEventInitialY = containerMotionEvent.getY();
                return;
            }

            if (action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL ||
                    (action == MotionEvent.ACTION_MOVE && (touchEventInitialY - containerMotionEvent.getY()) > Dim.dp(15))
            ) {
                if (views.thumbnailPreviewPopup.isShowing()) {
                    views.thumbnailPreviewPopup.dismiss();
                }
                lastX = -1;
                fineScrubbingPreviewBitmap = null;
                lastAppliedBitmap = null;
                seekbarViews = null;
                return;
            }

            final int trackballPosX = trackballPos.x;
            final int trackballPosY = trackballPos.y;

            if (action == MotionEvent.ACTION_MOVE) {
                if (trackballPosX == lastX) {
                    return;
                }
                lastX = trackballPosX;

                View rootView = container.getRootView();

                Bitmap currentScrubbedPreviewBitmap = fineScrubbingPreviewBitmap;
                if (currentScrubbedPreviewBitmap != null && currentScrubbedPreviewBitmap != lastAppliedBitmap) {
                    views.thumbnailPreview.setImageBitmap(currentScrubbedPreviewBitmap);
                    lastAppliedBitmap = currentScrubbedPreviewBitmap;
                    applyBitmapAspectRatio(views.previewFrame, currentScrubbedPreviewBitmap);
                }

                if (trackballPosX >= 0) {
                    final int maxPixel = Dim.getScreenWidth();
                    final long totalVideoMillis = VideoInformation.getVideoLength();

                    if (totalVideoMillis > 0 && maxPixel > 0) {
                        final long currentMillis = ((long) trackballPosX * totalVideoMillis) / maxPixel;
                        final int totalSeconds = (int) (currentMillis / 1000);
                        views.timestampPreview.setText(formatSeekTime(totalSeconds));

                        TimelineMarker[] markers = chapterMarkers;
                        if (markers != null) {
                            views.chapterPreview.setVisibility(View.GONE);
                            for (TimelineMarker marker : markers) {
                                if (currentMillis >= marker.patch_getStartMillis() &&
                                        currentMillis < marker.patch_getEndMillis()) {
                                    CharSequence title = marker.patch_getTitle();
                                    if (title != null && title.length() > 0) {
                                        views.chapterPreview.setText(title);
                                        views.chapterPreview.setVisibility(View.VISIBLE);
                                    }
                                    break;
                                }
                            }
                        } else {
                            views.chapterPreview.setVisibility(View.GONE);
                        }
                    }
                }

                if (trackballPosX == 0 && trackballPosY == 0) {
                    return;
                }

                ViewGroup.LayoutParams previewParams = views.previewFrame.getLayoutParams();
                final int previewWidthPx = previewParams.width;
                final int previewHeightPx = previewParams.height;

                final int targetX = Utils.clamp(
                        trackballPosX - (previewWidthPx / 2),
                        0,
                        Dim.getScreenWidth() - previewWidthPx
                );
                final int previewDistance = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                        ? THUMBNAIL_PREVIEW_DISTANCE_FULLSCREEN_DP
                        : THUMBNAIL_PREVIEW_DISTANCE_PORTRAIT_DP;
                final int targetY = trackballPosY - previewHeightPx
                        - previewDistance - THUMBNAIL_PREVIEW_TEXT_HEIGHT_DP;

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

    /**
     * Injection point.
     */
    public static void newVideoLoaded(@Nullable String videoId) {
        chapterMarkers = null;
    }

    /**
     * Injection point.
     */
    public static void setTimelineMarkers(TimelineMarker[] markers) {
        if (markers.length > 0 && markers[0].patch_getTitle() == null) {
            // Chapters array can alternate between an array with
            // no titles and an identical array with titles.
            // Ignore the no title array as it's of no use here.
            return;
        }

        if (BaseSettings.DEBUG.get() && !Arrays.equals(markers, chapterMarkers)) {
            Logger.printDebug(() -> "TimelineMarkers: " + Arrays.toString(markers));
        }
        chapterMarkers = markers;
    }
}
