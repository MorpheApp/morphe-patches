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
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public class SeekbarThumbnailPreviewPatch {

    private record SeekbarViews(FrameLayout previewFrame, ImageView thumbnailPreview, TextView timestampPreview,
                                TextView chapterPreview, PopupWindow thumbnailPreviewPopup) {
    }

    private static final int DIP15 = Dim.dp(15);
    private static final int THUMBNAIL_PREVIEW_LONG_SIDE = Dim.dp(160);
    private static final int THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE = Dim.dp(160 * 9.0f / 16);
    private static final int THUMBNAIL_PREVIEW_DISTANCE_FULLSCREEN_DP = Dim.dp10;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_PORTRAIT_DP = -1 * Dim.dp20;
    private static final int THUMBNAIL_PREVIEW_TEXT_ONLY_HEIGHT_DP = Dim.dp24;
    private static final int THUMBNAIL_PREVIEW_TEXT_WITH_CHAPTER_HEIGHT_DP = Dim.dp(44);
    private static final int THUMBNAIL_PREVIEW_CORNER_RADIUS_DP = Dim.dp8;
    private static final int THUMBNAIL_PREVIEW_BORDER_WIDTH_DP = Dim.dp2;
    private static final int THUMBNAIL_PREVIEW_BORDER_COLOR = 0xB3FFFFFF;
    private static final ColorDrawable previewPopupBackgroundDrawable = new ColorDrawable(Color.TRANSPARENT);

    @SuppressLint("StaticFieldLeak")
    private static SeekbarViews seekbarViews;
    private static Bitmap fineScrubbingPreviewBitmap;
    private static boolean isFineScrubbingStarted;
    private static Rect seekbarRectangle;
    private static Bitmap lastAppliedBitmap;
    private static int lastX = -1;
    private static float touchEventInitialX;
    private static float touchEventInitialY = -1;

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

    /**
     * Injection point.
     */
    public static void setSeekbarRectangle(View seekbarView) {
        if (!Settings.THUMBNAIL_PREVIEW.get() ||
                seekbarView == null ||
                !PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                ShortsPlayerState.isOpen()) {
            return;
        }

        seekbarRectangle = new Rect(
                seekbarView.getLeft(),
                seekbarView.getTop(),
                seekbarView.getRight(),
                seekbarView.getBottom()
        );
    }

    private static SeekbarViews initializeThumbnailPreviewContainer(View trackBall) {
        SeekbarViews views = seekbarViews;
        if (views != null) {
            return views;
        }

        Context context = trackBall.getRootView().getContext();
        LinearLayout containerLayout = new LinearLayout(context);
        containerLayout.setOrientation(LinearLayout.VERTICAL);
        containerLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout previewFrame = createPreviewFrame(context,
                THUMBNAIL_PREVIEW_CORNER_RADIUS_DP, THUMBNAIL_PREVIEW_BORDER_WIDTH_DP);
        previewFrame.setLayoutParams(new LinearLayout.LayoutParams(
                THUMBNAIL_PREVIEW_LONG_SIDE, THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE));
        ImageView thumbnailPreview = createThumbnailImageView(context,
                THUMBNAIL_PREVIEW_CORNER_RADIUS_DP, THUMBNAIL_PREVIEW_BORDER_WIDTH_DP);
        previewFrame.addView(thumbnailPreview);
        containerLayout.addView(previewFrame);

        TextView timestampPreview = createTimestampPreview(context);
        containerLayout.addView(timestampPreview);

        TextView chapterPreview = createChapterPreview(context);
        containerLayout.addView(chapterPreview);

        PopupWindow thumbnailPreviewPopup = new PopupWindow(containerLayout,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, false);
        thumbnailPreviewPopup.setTouchable(false);
        thumbnailPreviewPopup.setBackgroundDrawable(previewPopupBackgroundDrawable);

        return seekbarViews = new SeekbarViews(previewFrame, thumbnailPreview, timestampPreview, chapterPreview, thumbnailPreviewPopup);
    }

    // Border is a filled rounded rect + padding (not a stroke) to keep outer/inner corners concentric.
    @SuppressWarnings({"SameParameterValue", "SuspiciousNameCombination"})
    private static FrameLayout createPreviewFrame(Context context, int cornerRadiusPx, int borderWidthPx) {
        FrameLayout previewFrame = new FrameLayout(context);
        GradientDrawable frameBackground = new GradientDrawable();

        frameBackground.setColor(THUMBNAIL_PREVIEW_BORDER_COLOR);
        frameBackground.setCornerRadius(cornerRadiusPx);

        previewFrame.setBackground(frameBackground);
        previewFrame.setPadding(borderWidthPx, borderWidthPx, borderWidthPx, borderWidthPx);

        return previewFrame;
    }

    @SuppressWarnings("SameParameterValue")
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
        chapterPreview.setSingleLine(true);
        chapterPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chapterPreview.setGravity(Gravity.CENTER_HORIZONTAL);
        chapterPreview.setLayoutParams(new LinearLayout.LayoutParams(
                THUMBNAIL_PREVIEW_LONG_SIDE,
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

        final int newWidth;
        final int newHeight;
        if (bitmapWidth >= bitmapHeight) {
            newWidth = THUMBNAIL_PREVIEW_LONG_SIDE;
            newHeight = THUMBNAIL_PREVIEW_LONG_SIDE * bitmapHeight / bitmapWidth;
        } else {
            newHeight = THUMBNAIL_PREVIEW_LONG_SIDE;
            newWidth = THUMBNAIL_PREVIEW_LONG_SIDE * bitmapWidth / bitmapHeight;
        }
        ViewGroup.LayoutParams frameParams = previewFrame.getLayoutParams();
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
    public static void updateThumbnailPreview(
            View container,
            MotionEvent containerMotionEvent,
            Point trackballPos) {
        try {
            if (!Settings.THUMBNAIL_PREVIEW.get() ||
                    !PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                    ShortsPlayerState.isOpen()) {
                return;
            }

            SeekbarViews views = initializeThumbnailPreviewContainer(container);

            final int action = containerMotionEvent.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                touchEventInitialX = containerMotionEvent.getX();
                touchEventInitialY = containerMotionEvent.getY();
                isFineScrubbingStarted = false;
                return;
            }

            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_MOVE
                    && (touchEventInitialY - containerMotionEvent.getY()) > DIP15)) {
                lastX = -1;
                fineScrubbingPreviewBitmap = null;
                isFineScrubbingStarted = false;
                lastAppliedBitmap = null;
                views.timestampPreview.setVisibility(View.GONE);
                views.chapterPreview.setVisibility(View.GONE);
                if (views.thumbnailPreviewPopup.isShowing()) {
                    views.thumbnailPreviewPopup.dismiss();
                }
                seekbarViews = null;
                return;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (!isFineScrubbingStarted) {
                    int touchSlop = ViewConfiguration.get(container.getContext()).getScaledTouchSlop();
                    float deltaTouchX = Math.abs(containerMotionEvent.getX() - touchEventInitialX);

                    if (deltaTouchX > touchSlop) {
                        isFineScrubbingStarted = true;
                    } else {
                        return;
                    }
                }

                final int trackballPosX = trackballPos.x;
                final int trackballPosY = trackballPos.y;

                if (trackballPosX == lastX || (trackballPosX == 0 && trackballPosY == 0)) {
                    return;
                }
                lastX = trackballPosX;

                Bitmap currentScrubbedPreviewBitmap = fineScrubbingPreviewBitmap;
                if (currentScrubbedPreviewBitmap != null && currentScrubbedPreviewBitmap != lastAppliedBitmap) {
                    views.thumbnailPreview.setImageBitmap(currentScrubbedPreviewBitmap);
                    lastAppliedBitmap = currentScrubbedPreviewBitmap;
                    applyBitmapAspectRatio(views.previewFrame, currentScrubbedPreviewBitmap);
                }

                if (trackballPosX >= 0) {
                    final int seekbarWidth = seekbarRectangle.width();
                    final long totalVideoMillis = VideoInformation.getVideoLength();

                    if (totalVideoMillis > 0 && seekbarWidth > 0) {
                        final int relativeTrackballPosX = Utils.clamp(
                                trackballPosX - seekbarRectangle.left,
                                0,
                                seekbarWidth
                        );

                        final long currentMillis = (((long) relativeTrackballPosX) * totalVideoMillis) / seekbarWidth;
                        final int totalSeconds = Math.round((float) currentMillis / 1000.0f);

                        views.timestampPreview.setText(formatSeekTime(totalSeconds));
                        views.timestampPreview.setVisibility(View.VISIBLE);

                        CharSequence chapterTitle = ChaptersHookPatch.getChapterTitleAtTime(currentMillis);
                        if (chapterTitle != null) {
                            views.chapterPreview.setText(chapterTitle);
                            views.chapterPreview.setVisibility(View.VISIBLE);
                        } else {
                            views.chapterPreview.setVisibility(View.GONE);
                        }
                    }
                }

                ViewGroup.LayoutParams previewParams = views.previewFrame.getLayoutParams();
                final int previewWidthPx = previewParams.width;
                final int previewHeightPx = previewParams.height;

                final int targetX = Utils.clamp(
                        trackballPosX - (previewWidthPx / 2),
                        0,
                        Dim.getScreenWidth() - previewWidthPx
                );
                //noinspection ExtractMethodRecommender
                final int previewDistance = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                        ? THUMBNAIL_PREVIEW_DISTANCE_FULLSCREEN_DP
                        : THUMBNAIL_PREVIEW_DISTANCE_PORTRAIT_DP;

                final int textHeight = views.chapterPreview.getVisibility() == View.VISIBLE
                        ? THUMBNAIL_PREVIEW_TEXT_WITH_CHAPTER_HEIGHT_DP
                        : THUMBNAIL_PREVIEW_TEXT_ONLY_HEIGHT_DP;

                final int targetY = trackballPosY -
                                    previewHeightPx -
                                    previewDistance -
                                    textHeight;

                PopupWindow thumbnailPreviewPopup = views.thumbnailPreviewPopup;
                View rootView = container.getRootView();

                // Wait until the first bitmap so the previewFrame shows immediately with the correct
                // aspect ratio and Y offset, avoiding a jump from a default 16:9 position.
                views.previewFrame.setVisibility(
                        lastAppliedBitmap != null
                                ? View.VISIBLE
                                : View.INVISIBLE
                );

                if (!thumbnailPreviewPopup.isShowing()) {
                    if (rootView.getWindowToken() != null) {
                        thumbnailPreviewPopup.showAtLocation(rootView, Gravity.NO_GRAVITY, targetX, targetY);
                    }
                } else {
                    thumbnailPreviewPopup.update(
                            targetX,
                            targetY,
                            thumbnailPreviewPopup.getWidth(),
                            thumbnailPreviewPopup.getHeight()
                    );
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
