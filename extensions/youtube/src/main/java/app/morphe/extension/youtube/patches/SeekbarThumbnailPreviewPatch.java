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
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public class SeekbarThumbnailPreviewPatch {

    private record SeekbarViews(
            FrameLayout previewFrame,
            ImageView thumbnailPreview,
            TextView timestampPreview,
            TextView heatMapPeakPointPreview,
            TextView chapterPreview,
            PopupWindow thumbnailPreviewPopup
    ) {}

    private static final int DIP15 = Dim.dp(15);
    private static final int THUMBNAIL_PREVIEW_LONG_SIDE = Dim.dp(160);
    private static final int THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE = Dim.dp(160 * 9.0f / 16);
    private static final int THUMBNAIL_PREVIEW_DISTANCE_FULLSCREEN_DP = Dim.dp10;
    private static final int THUMBNAIL_PREVIEW_DISTANCE_PORTRAIT_DP = -1 * Dim.dp20;
    private static final int THUMBNAIL_PREVIEW_TEXT_ONLY_HEIGHT_DP = Dim.dp(30);
    private static final int THUMBNAIL_PREVIEW_TEXT_WITH_CHAPTER_HEIGHT_DP =
            THUMBNAIL_PREVIEW_TEXT_ONLY_HEIGHT_DP * 2;
    private static final int THUMBNAIL_PREVIEW_CORNER_RADIUS_DP = Dim.dp8;
    private static final int THUMBNAIL_PREVIEW_BORDER_WIDTH_DP = Dim.dp2;
    private static final int THUMBNAIL_PREVIEW_INNER_RADIUS_DP = Math.max(
            0,
            THUMBNAIL_PREVIEW_CORNER_RADIUS_DP - THUMBNAIL_PREVIEW_BORDER_WIDTH_DP
    );
    private static final String heatMapPeakPointDescription =
            ResourceUtils.getString("morphe_seekbar_thumbnail_heatmap_peak_point");
    private static GradientDrawable labelBackground() {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.argb(153, 0, 0, 0));
        shape.setCornerRadius(Dim.dp(100));
        return shape;
    }
    private static final int semitransparentWhiteBackgroundColor =
            Color.argb(180, 255, 255, 255);
    private static final ColorDrawable transparentBackgroundDrawable =
            new ColorDrawable(Color.TRANSPARENT);
    private static final int semitransparentBlackBackgroundColor =
            Color.argb(180, 0, 0, 0);
    private static boolean preciseSeekingVisible = false;

    @SuppressLint("StaticFieldLeak")
    private static SeekbarViews seekbarViews;
    private static Bitmap fineScrubbingPreviewBitmap;
    private static boolean isFineScrubbingStarted;
    private static Rect seekbarRectangle;
    private static int previewWidthPx = -1;
    private static int previewHeightPx = -1;
    private static Bitmap lastAppliedBitmap;
    private static int lastX = -1;
    private static float touchEventInitialX;

    /**
     * Injection point.
     */
    public static void setFineScrubbingPreviewBitmap(Bitmap bitmap) {
        if (!Settings.THUMBNAIL_PREVIEW.get() ||
                !PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                ShortsPlayerState.isOpen() ||
                bitmap == null) {
            lastAppliedBitmap = null;
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

    private static SeekbarViews setThumbnailPreviewRef(View trackBall) {
        final Context context = trackBall.getRootView().getContext();

        if (seekbarViews != null &&
                seekbarViews.previewFrame().getContext() == context) {
            return seekbarViews;
        }

        seekbarViews = null;

        final LinearLayout containerLayout = new LinearLayout(context);

        containerLayout.setOrientation(LinearLayout.VERTICAL);
        containerLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        final FrameLayout previewFrame = createPreviewFrame(context);
        final ImageView thumbnailPreview = createThumbnailImageView(context);

        previewFrame.setLayoutParams(
            new LinearLayout.LayoutParams(
                THUMBNAIL_PREVIEW_LONG_SIDE,
                THUMBNAIL_PREVIEW_DEFAULT_SHORT_SIDE
            )
        );
        previewFrame.addView(thumbnailPreview);
        containerLayout.addView(previewFrame);

        final TextView timestampPreview = createTimestampPreview(context);
        containerLayout.addView(timestampPreview);

        final TextView heatMapPeakPoint = createHeatMapPeakPointPreview(context);
        previewFrame.addView(heatMapPeakPoint);

        final TextView chapterPreview = createChapterPreview(context);
        containerLayout.addView(chapterPreview);

        final PopupWindow thumbnailPreviewPopup = new PopupWindow(containerLayout,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, false);
        thumbnailPreviewPopup.setTouchable(false);
        thumbnailPreviewPopup.setBackgroundDrawable(transparentBackgroundDrawable);

        return seekbarViews = new SeekbarViews(
                previewFrame,
                thumbnailPreview,
                timestampPreview,
                heatMapPeakPoint,
                chapterPreview,
                thumbnailPreviewPopup
        );
    }

    // Border is a filled rounded rect + padding (not a stroke) to keep outer/inner corners concentric.
    @SuppressWarnings({"SameParameterValue", "SuspiciousNameCombination"})
    private static FrameLayout createPreviewFrame(Context context) {
        final GradientDrawable frameBackground = new GradientDrawable();
        frameBackground.setColor(semitransparentWhiteBackgroundColor);
        frameBackground.setCornerRadius(THUMBNAIL_PREVIEW_CORNER_RADIUS_DP);

        final FrameLayout previewFrame = new FrameLayout(context);
        previewFrame.setBackground(frameBackground);
        previewFrame.setPadding(
                THUMBNAIL_PREVIEW_BORDER_WIDTH_DP,
                THUMBNAIL_PREVIEW_BORDER_WIDTH_DP,
                THUMBNAIL_PREVIEW_BORDER_WIDTH_DP,
                THUMBNAIL_PREVIEW_BORDER_WIDTH_DP
        );

        return previewFrame;
    }

    @SuppressWarnings("SameParameterValue")
    private static ImageView createThumbnailImageView(Context context) {
        final ImageView thumbnailPreview = new ImageView(context);
        thumbnailPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnailPreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(
                    0,
                    0,
                    view.getWidth(),
                    view.getHeight(),
                    THUMBNAIL_PREVIEW_INNER_RADIUS_DP
                );
            }
        });
        thumbnailPreview.setClipToOutline(true);
        thumbnailPreview.setLayoutParams(
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );

        return thumbnailPreview;
    }

    private static TextView createHeatMapPeakPointPreview(Context context) {
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.BOTTOM;

        final TextView heatMapPeakPoint = new TextView(context);
        heatMapPeakPoint.setText(heatMapPeakPointDescription);
        heatMapPeakPoint.setTextColor(Color.WHITE);
        heatMapPeakPoint.setTextSize(12);
        heatMapPeakPoint.setGravity(Gravity.CENTER);
        heatMapPeakPoint.setBackgroundColor(semitransparentBlackBackgroundColor);
        heatMapPeakPoint.setSingleLine(true);
        heatMapPeakPoint.setEllipsize(TextUtils.TruncateAt.END);
        heatMapPeakPoint.setHorizontallyScrolling(false);
        heatMapPeakPoint.setPadding(0, Dim.dp4, 0, Dim.dp4);
        heatMapPeakPoint.setOutlineProvider(
            new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                outline.setRoundRect(
                    0,
                    -THUMBNAIL_PREVIEW_INNER_RADIUS_DP,
                    view.getWidth(),
                    view.getHeight(),
                    THUMBNAIL_PREVIEW_INNER_RADIUS_DP
                );
                }
            }
        );
        heatMapPeakPoint.setClipToOutline(true);
        heatMapPeakPoint.setLayoutParams(params);

        return heatMapPeakPoint;
    }

    private static TextView createTimestampPreview(Context context) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp4;

        final TextView timestampPreview = new TextView(context);
        timestampPreview.setTextColor(Color.WHITE);
        timestampPreview.setTextSize(12);
        timestampPreview.setPadding(Dim.dp6, Dim.dp2, Dim.dp6, Dim.dp2);
        timestampPreview.setShadowLayer(0.1f, 1.5f, 1.5f, Color.BLACK);
        timestampPreview.setBackground(labelBackground());
        timestampPreview.setLayoutParams(params);

        return timestampPreview;
    }

    private static TextView createChapterPreview(Context context) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp4;

        final TextView chapterPreview = new TextView(context);
        chapterPreview.setTextColor(Color.WHITE);
        chapterPreview.setTextSize(12);
        chapterPreview.setPadding(Dim.dp6, Dim.dp2, Dim.dp6, Dim.dp2);
        chapterPreview.setMaxWidth(THUMBNAIL_PREVIEW_LONG_SIDE);
        chapterPreview.setShadowLayer(0.1f, 1.5f, 1.5f, Color.BLACK);
        chapterPreview.setSingleLine(true);
        chapterPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chapterPreview.setGravity(Gravity.CENTER_HORIZONTAL);
        chapterPreview.setBackground(labelBackground());
        chapterPreview.setLayoutParams(params);

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

        final ViewGroup.LayoutParams frameParams = previewFrame.getLayoutParams();
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
    public static void updateHandlerThumbnailPreview(
            View trackBall,
            MotionEvent trackBallMotionEvent,
            Point trackballPos) {
        updateThumbnailPreview(trackBall, trackBallMotionEvent, trackballPos);
    }

    /**
     * Injection point.
     */
    public static void updateSlideThumbnailPreview(
            View trackBall,
            MotionEvent trackBallMotionEvent,
            Point trackballPos) {
        if (Settings.SLIDE_TO_SEEK.get()) {
            updateThumbnailPreview(trackBall, trackBallMotionEvent, trackballPos);
        }
    }

    private static void updateThumbnailPreview(
            View trackBall,
            MotionEvent trackBallMotionEvent,
            Point trackballPos) {
        try {
            if (!Settings.THUMBNAIL_PREVIEW.get() ||
                    !PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                    ShortsPlayerState.isOpen()) {
                return;
            }

            final int actionMasked = trackBallMotionEvent.getActionMasked();

            SeekbarViews views = setThumbnailPreviewRef(trackBall);

            if (actionMasked == MotionEvent.ACTION_DOWN) {
                isFineScrubbingStarted = false;
                touchEventInitialX = trackBallMotionEvent.getX();
                return;
            }

            if (trackBallMotionEvent.getPointerCount() > 1
                    || actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL
                    || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                lastX = -1;
                touchEventInitialX = -1;
                fineScrubbingPreviewBitmap = null;
                isFineScrubbingStarted = false;
                lastAppliedBitmap = null;
                previewWidthPx = -1;
                previewHeightPx = -1;
                if (views != null) {
                    if (views.timestampPreview != null) {
                        views.timestampPreview.setVisibility(View.GONE);
                    }
                    if (views.heatMapPeakPointPreview != null) {
                        views.heatMapPeakPointPreview.setVisibility(View.GONE);
                    }
                    if (views.chapterPreview != null) {
                        views.chapterPreview.setVisibility(View.GONE);
                    }
                    if (views.thumbnailPreviewPopup != null) {
                        if (views.thumbnailPreviewPopup.isShowing()) {
                            views.thumbnailPreviewPopup.dismiss();
                        }
                    }
                }
                return;
            }

            if (actionMasked == MotionEvent.ACTION_MOVE &&
                    views != null &&
                    touchEventInitialX > -1) {
                if (!isFineScrubbingStarted) {
                    final float deltaTouchX = Math.abs(trackBallMotionEvent.getX() - touchEventInitialX);

                    if (deltaTouchX > DIP15) {
                        isFineScrubbingStarted = true;
                    } else {
                        return;
                    }
                }

                final int trackballPosX = trackballPos.x;
                final int trackballPosY = trackballPos.y;

                if (trackballPosX == lastX || (trackballPosX < 0 && trackballPosY < 0)) {
                    return;
                }
                lastX = trackballPosX;

                final Bitmap currentScrubbedPreviewBitmap = fineScrubbingPreviewBitmap;
                if (currentScrubbedPreviewBitmap != null && currentScrubbedPreviewBitmap != lastAppliedBitmap) {
                    views.thumbnailPreview.setImageBitmap(currentScrubbedPreviewBitmap);
                    lastAppliedBitmap = currentScrubbedPreviewBitmap;
                    applyBitmapAspectRatio(views.previewFrame, currentScrubbedPreviewBitmap);

                    final ViewGroup.LayoutParams previewParams = views.previewFrame.getLayoutParams();
                    if (previewWidthPx < 0) {
                        previewWidthPx = previewParams.width;
                    }
                    if (previewHeightPx < 0) {
                        previewHeightPx = previewParams.height;
                    }
                }

                final int seekbarWidth = seekbarRectangle.width();
                final long totalVideoMillis = VideoInformation.getVideoLength();

                if (totalVideoMillis > 0 && seekbarWidth > 0) {
                    final int relativeTrackballPosX = trackballPosX - seekbarRectangle.left;
                    final long currentMillis = (((long) relativeTrackballPosX) * totalVideoMillis) / seekbarWidth;
                    final int currentSeconds = Math.round((float) currentMillis / 1000.0f);
                    final int totalVideoSeconds = Math.round((float) totalVideoMillis / 1000.0f);
                    final String timestampText =
                            formatSeekTime(currentSeconds) +
                            " / " +
                            formatSeekTime(totalVideoSeconds);

                    views.timestampPreview.setText(timestampText);
                    views.timestampPreview.setVisibility(
                            !preciseSeekingVisible ? View.VISIBLE : View.GONE
                    );

                    views.heatMapPeakPointPreview.setVisibility(
                            ChaptersHookPatch.getHeatMapPeakPoint() ? View.VISIBLE : View.GONE
                    );

                    final CharSequence chapterTitle = ChaptersHookPatch.getChapterTitleAtTime(currentMillis);
                    if (chapterTitle != null && !preciseSeekingVisible) {
                        views.chapterPreview.setText(chapterTitle);
                        views.chapterPreview.setVisibility(View.VISIBLE);
                    } else {
                        views.chapterPreview.setVisibility(View.GONE);
                    }
                }

                final int previewDistance = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                        ? THUMBNAIL_PREVIEW_DISTANCE_FULLSCREEN_DP
                        : THUMBNAIL_PREVIEW_DISTANCE_PORTRAIT_DP;

                final int textHeight;
                if (views.chapterPreview.getVisibility() == View.VISIBLE) {
                    textHeight = THUMBNAIL_PREVIEW_TEXT_WITH_CHAPTER_HEIGHT_DP;
                } else {
                    textHeight = THUMBNAIL_PREVIEW_TEXT_ONLY_HEIGHT_DP;
                }

                // Wait until the first bitmap so the previewFrame shows immediately with the correct
                // aspect ratio and Y offset, avoiding a jump from a default 16:9 position.
                views.previewFrame.setVisibility(
                        lastAppliedBitmap != null && !preciseSeekingVisible
                                ? View.VISIBLE
                                : View.INVISIBLE
                );

                final PopupWindow thumbnailPreviewPopup = views.thumbnailPreviewPopup;
                final View rootView = trackBall.getRootView();
                final int targetX = trackballPosX - (previewWidthPx / 2);
                final int targetY = trackballPosY -
                        previewHeightPx -
                        previewDistance -
                        textHeight;

                if (!thumbnailPreviewPopup.isShowing() && rootView.getWindowToken() != null) {
                    thumbnailPreviewPopup.showAtLocation(rootView, Gravity.NO_GRAVITY, targetX, targetY);
                }

                if (thumbnailPreviewPopup.isShowing()) {
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

    /**
     * Injection point.
     */
    public static boolean disableShortsSeekbarThumbnails(boolean original) {
        if (Settings.THUMBNAIL_PREVIEW.get()) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     * The following method is called once, to initialize the precise seekbar container.
     */
    public static void setPreciseSeekingVisible(RecyclerView recyclerView) {
        if (recyclerView != null) {
            ViewParent recyclerViewParent = recyclerView.getParent();

            while (recyclerViewParent instanceof View recyclerViewParentView) {
                if (recyclerViewParentView.toString().contains("android.support.constraint.ConstraintLayout")) {
                    recyclerViewParentView.getViewTreeObserver().addOnPreDrawListener(() -> {
                        preciseSeekingVisible =
                                recyclerView.getVisibility() == View.VISIBLE &&
                                        recyclerViewParentView.getAlpha() > 0;
                        return true;
                    });
                    break;
                }
                recyclerViewParent = recyclerViewParentView.getParent();
            }
        }
    }
}
