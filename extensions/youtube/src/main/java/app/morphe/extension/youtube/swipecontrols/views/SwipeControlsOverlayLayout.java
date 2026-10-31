package app.morphe.extension.youtube.swipecontrols.views;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.ViewAnimations;
import app.morphe.extension.youtube.patches.SoundBoostPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeOverlayStyle;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsOverlay;
import app.morphe.extension.youtube.videoplayer.PlayerIcons;

/**
 * Main overlay layout for displaying volume, brightness, and playback speed level with circular, horizontal and vertical progress bars.
 */
@SuppressLint("ViewConstructor")
public final class SwipeControlsOverlayLayout extends RelativeLayout implements SwipeControlsOverlay {

    private static final int VOLUME_BOOST_PERCENT_PER_STEP = 25;
    private static final int VOLUME_BOOST_PROGRESS_COLOR = 0xBFFF7043;
    private static final long FADE_DURATION_MILLIS = 100;

    /**
     * Convert dp to pixels based on system display density.
     */
    private static float toDisplayPixels(float dp) {
        return dp * Resources.getSystem().getDisplayMetrics().density;
    }

    private final SwipeControlsConfigurationProvider config;

    // Drawable icons for brightness and volume.
    private final Drawable autoBrightnessIcon;
    private final Drawable lowBrightnessIcon;
    private final Drawable mediumBrightnessIcon;
    private final Drawable highBrightnessIcon;
    private final Drawable fullBrightnessIcon;
    private final Drawable mutedVolumeIcon;
    private final Drawable lowVolumeIcon;
    private final Drawable normalVolumeIcon;
    private final Drawable fullVolumeIcon;
    private final Drawable speedIcon;

    // Progress bars.
    private final CircularProgressView circularProgressView;
    private final HorizontalProgressView horizontalProgressView;
    private final VerticalProgressView verticalBrightnessProgressView;
    private final VerticalProgressView verticalVolumeProgressView;

    // Handler and callback for hiding progress bars.
    private final Handler feedbackHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable feedbackHideCallback;

    public SwipeControlsOverlayLayout(Context context, SwipeControlsConfigurationProvider config) {
        super(context);
        this.config = config;

        autoBrightnessIcon = getDrawable("morphe_ic_sc_brightness_auto");
        lowBrightnessIcon = getDrawable("morphe_ic_sc_brightness_low");
        mediumBrightnessIcon = getDrawable("morphe_ic_sc_brightness_medium");
        highBrightnessIcon = getDrawable("morphe_ic_sc_brightness_high");
        fullBrightnessIcon = getDrawable("morphe_ic_sc_brightness_full");
        mutedVolumeIcon = getDrawable("morphe_ic_sc_volume_mute");
        lowVolumeIcon = getDrawable("morphe_ic_sc_volume_low");
        normalVolumeIcon = getDrawable("morphe_ic_sc_volume_normal");
        fullVolumeIcon = getDrawable("morphe_ic_sc_volume_high");
        speedIcon = getDrawable("morphe_ic_sc_speed");

        SwipeOverlayStyle overlayStyle = config.getOverlayStyle();

        // Initialize circular progress bar.
        circularProgressView = new CircularProgressView(
                context,
                config.getOverlayBackgroundOpacity(),
                overlayStyle.isMinimal(),
                config.getOverlayBrightnessProgressColor(), // Placeholder, updated in showFeedbackView.
                SwipeControlsConfigurationProvider.OVERLAY_FILL_BACKGROUND_COLOR,
                SwipeControlsConfigurationProvider.OVERLAY_TEXT_COLOR,
                config.getOverlayTextSize()
        );
        LayoutParams circularParams = new LayoutParams(
                (int) toDisplayPixels(100f), (int) toDisplayPixels(100f));
        circularParams.addRule(CENTER_IN_PARENT, TRUE);
        circularProgressView.setLayoutParams(circularParams);
        circularProgressView.setVisibility(GONE); // Initially hidden.
        addView(circularProgressView);

        // Initialize horizontal progress bar.
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int layoutWidth = screenWidth * 4 / 5; // Cap at ~360dp.
        horizontalProgressView = new HorizontalProgressView(
                context,
                config.getOverlayBackgroundOpacity(),
                overlayStyle.isMinimal(),
                config.getOverlayBrightnessProgressColor(), // Placeholder, updated in showFeedbackView.
                SwipeControlsConfigurationProvider.OVERLAY_FILL_BACKGROUND_COLOR,
                SwipeControlsConfigurationProvider.OVERLAY_TEXT_COLOR,
                config.getOverlayTextSize()
        );
        LayoutParams horizontalParams = new LayoutParams(layoutWidth, (int) toDisplayPixels(32f));
        horizontalParams.addRule(CENTER_HORIZONTAL);
        if (overlayStyle.isHorizontalMinimalCenter()) {
            horizontalParams.addRule(CENTER_VERTICAL);
        } else {
            horizontalParams.topMargin = (int) toDisplayPixels(20f);
        }
        horizontalProgressView.setLayoutParams(horizontalParams);
        horizontalProgressView.setVisibility(GONE); // Initially hidden.
        addView(horizontalProgressView);

        // Initialize vertical progress bar for brightness (right side).
        verticalBrightnessProgressView = new VerticalProgressView(
                context,
                config.getOverlayBackgroundOpacity(),
                overlayStyle.isMinimal(),
                config.getOverlayBrightnessProgressColor(),
                SwipeControlsConfigurationProvider.OVERLAY_FILL_BACKGROUND_COLOR,
                SwipeControlsConfigurationProvider.OVERLAY_TEXT_COLOR,
                config.getOverlayTextSize()
        );
        LayoutParams verticalBrightnessParams = new LayoutParams(
                (int) toDisplayPixels(40f), (int) toDisplayPixels(150f));
        verticalBrightnessParams.addRule(ALIGN_PARENT_RIGHT);
        verticalBrightnessParams.rightMargin = (int) toDisplayPixels(40f);
        verticalBrightnessParams.addRule(CENTER_VERTICAL);
        verticalBrightnessProgressView.setLayoutParams(verticalBrightnessParams);
        verticalBrightnessProgressView.setVisibility(GONE); // Initially hidden.
        addView(verticalBrightnessProgressView);

        // Initialize vertical progress bar for volume (left side).
        verticalVolumeProgressView = new VerticalProgressView(
                context,
                config.getOverlayBackgroundOpacity(),
                overlayStyle.isMinimal(),
                config.getOverlayVolumeProgressColor(),
                SwipeControlsConfigurationProvider.OVERLAY_FILL_BACKGROUND_COLOR,
                SwipeControlsConfigurationProvider.OVERLAY_TEXT_COLOR,
                config.getOverlayTextSize()
        );
        LayoutParams verticalVolumeParams = new LayoutParams(
                (int) toDisplayPixels(40f), (int) toDisplayPixels(150f));
        verticalVolumeParams.addRule(ALIGN_PARENT_LEFT);
        verticalVolumeParams.leftMargin = (int) toDisplayPixels(40f);
        verticalVolumeParams.addRule(CENTER_VERTICAL);
        verticalVolumeProgressView.setLayoutParams(verticalVolumeParams);
        verticalVolumeProgressView.setVisibility(GONE); // Initially hidden.
        addView(verticalVolumeProgressView);

        feedbackHideCallback = () -> {
            ViewAnimations.fadeOut(circularProgressView, FADE_DURATION_MILLIS);
            ViewAnimations.fadeOut(horizontalProgressView, FADE_DURATION_MILLIS);
            ViewAnimations.fadeOut(verticalBrightnessProgressView, FADE_DURATION_MILLIS);
            ViewAnimations.fadeOut(verticalVolumeProgressView, FADE_DURATION_MILLIS);
        };
    }

    // Function to retrieve drawable resources by name.
    private Drawable getDrawable(String name) {
        Context context = getContext();
        Drawable drawable = getResources().getDrawable(
                ResourceUtils.getIdentifierOrThrow(context, ResourceType.DRAWABLE, PlayerIcons.name(name)),
                context.getTheme()
        );
        drawable.setTint(SwipeControlsConfigurationProvider.OVERLAY_TEXT_COLOR);
        return drawable;
    }

    /**
     * Displays the progress bar with the appropriate value, icon, and type (brightness or volume).
     */
    private void showFeedbackView(String value, int progress, int max, Drawable icon,
                                  boolean isBrightness, boolean isBoosted) {
        feedbackHideHandler.removeCallbacks(feedbackHideCallback);
        feedbackHideHandler.postDelayed(feedbackHideCallback, config.getOverlayShowTimeoutMillis());

        SwipeOverlayStyle overlayStyle = config.getOverlayStyle();
        final AbstractProgressView viewToShow;
        if (overlayStyle.isCircular()) {
            viewToShow = circularProgressView;
        } else if (overlayStyle.isVertical()) {
            viewToShow = isBrightness
                    ? verticalBrightnessProgressView
                    : verticalVolumeProgressView;
        } else {
            viewToShow = horizontalProgressView;
        }

        // Set the appropriate progress color.
        final int progressColor;
        if (isBrightness) {
            progressColor = config.getOverlayBrightnessProgressColor();
        } else if (isBoosted) {
            progressColor = VOLUME_BOOST_PROGRESS_COLOR;
        } else {
            progressColor = config.getOverlayVolumeProgressColor();
        }
        viewToShow.setProgressColor(progressColor);
        viewToShow.setProgress(progress, max, value, isBrightness);
        viewToShow.icon = icon;
        ViewAnimations.fadeIn(viewToShow, FADE_DURATION_MILLIS);
    }

    /**
     * Displays the progress bar for playback speed feedback.
     * Always uses the horizontal bar (or circular for circular styles) to match the horizontal gesture.
     */
    private void showSpeedFeedbackView(float speed) {
        feedbackHideHandler.removeCallbacks(feedbackHideCallback);
        feedbackHideHandler.postDelayed(feedbackHideCallback, config.getOverlayShowTimeoutMillis());

        String displayText = VideoInformation.formatSpeedStringX(speed, 2, false);
        final int progress = Math.max(0, Math.min(100, (int) ((speed - 0.25f) / (8.0f - 0.25f) * 100)));

        AbstractProgressView viewToShow = config.getOverlayStyle().isCircular()
                ? circularProgressView
                : horizontalProgressView;
        viewToShow.setProgressColor(config.getOverlaySpeedProgressColor());
        viewToShow.setProgress(progress, 100, displayText, false);
        viewToShow.icon = speedIcon;
        ViewAnimations.fadeIn(viewToShow, FADE_DURATION_MILLIS);
    }

    // Handle volume change.
    @Override
    public void onVolumeChanged(int newVolume, int maximumVolume, int boostStep) {
        final boolean isBoosted = boostStep > 0;
        final float volumePercentage = ((float) newVolume / maximumVolume) * 100;
        final Drawable icon;
        if (newVolume == 0) {
            icon = mutedVolumeIcon;
        } else if (volumePercentage < 25) {
            icon = lowVolumeIcon;
        } else if (volumePercentage < 50) {
            icon = normalVolumeIcon;
        } else {
            icon = fullVolumeIcon;
        }

        // Levels above 100% only exist when volume boost is allowed, so it is the only case
        // where the level is shown as a percentage, and it fills the bar.
        final String text;
        if (isBoosted) {
            text = (100 + boostStep * VOLUME_BOOST_PERCENT_PER_STEP) + "%";
        } else if (SoundBoostPatch.isBoostAllowed()) {
            text = (int) Math.rint(volumePercentage) + "%";
        } else {
            text = String.valueOf(newVolume);
        }
        showFeedbackView(text, newVolume, maximumVolume, icon, false, isBoosted);
    }

    // Handle brightness change.
    @Override
    public void onBrightnessChanged(double brightness) {
        final boolean isVertical = config.getOverlayStyle().isVertical();
        if (config.shouldLowestValueEnableAutoBrightness() && brightness <= 0) {
            String displayText = isVertical
                    ? "А"
                    : str("morphe_swipe_lowest_value_enable_auto_brightness_overlay_text");
            showFeedbackView(displayText, 0, 100, autoBrightnessIcon, true, false);
        } else {
            final int brightnessValue = (int) Math.rint(brightness);
            final int clampedProgress = Math.max(0, brightnessValue);
            final Drawable icon;
            if (clampedProgress < 25) {
                icon = lowBrightnessIcon;
            } else if (clampedProgress < 50) {
                icon = mediumBrightnessIcon;
            } else if (clampedProgress < 75) {
                icon = highBrightnessIcon;
            } else {
                icon = fullBrightnessIcon;
            }
            String displayText = isVertical
                    ? String.valueOf(clampedProgress)
                    : clampedProgress + "%";
            showFeedbackView(displayText, clampedProgress, 100, icon, true, false);
        }
    }

    // Handle playback speed change.
    @Override
    public void onSpeedChanged(float speed) {
        showSpeedFeedbackView(speed);
    }

    // Begin swipe session.
    @SuppressWarnings("deprecation")
    @Override
    public void onEnterSwipeSession() {
        if (config.shouldEnableHapticFeedback()) {
            performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            );
        }
    }

    /**
     * Abstract base class for progress views.
     */
    @SuppressLint("ViewConstructor")
    private abstract static class AbstractProgressView extends View {

        protected final boolean isMinimalStyle;

        protected final Paint backgroundPaint;
        protected final Paint progressPaint;
        protected final Paint fillBackgroundPaint;
        protected final Paint textPaint;

        // Rect for text measurement.
        protected final Rect textBounds = new Rect();

        protected int progress = 0;
        protected int maxProgress = 100;
        protected String displayText = "0";
        protected boolean isBrightness = true;
        @Nullable
        protected Drawable icon;

        AbstractProgressView(Context context, int overlayBackgroundOpacity, boolean isMinimalStyle,
                             int overlayProgressColor, int overlayFillBackgroundPaint,
                             int overlayTextColor, int overlayTextSize) {
            super(context);
            this.isMinimalStyle = isMinimalStyle;

            backgroundPaint = createPaint(overlayBackgroundOpacity, Paint.Style.FILL, Paint.Cap.BUTT, 0f);
            progressPaint = createPaint(overlayProgressColor, Paint.Style.STROKE, Paint.Cap.ROUND, toDisplayPixels(6f));
            fillBackgroundPaint = createPaint(overlayFillBackgroundPaint, Paint.Style.FILL, Paint.Cap.BUTT, 0f);

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(overlayTextColor);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(toDisplayPixels(overlayTextSize));
        }

        // Combined paint creation function for both fill and stroke styles.
        private static Paint createPaint(int color, Paint.Style style, Paint.Cap strokeCap, float strokeWidth) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(style);
            paint.setColor(color);
            paint.setStrokeCap(strokeCap);
            paint.setStrokeWidth(strokeWidth);
            return paint;
        }

        void setProgress(int value, int max, String text, boolean isBrightnessMode) {
            progress = value;
            maxProgress = max;
            displayText = text;
            isBrightness = isBrightnessMode;
            invalidate();
        }

        void setProgressColor(int color) {
            progressPaint.setColor(color);
            invalidate();
        }

        protected int measureTextWidth(String text, Paint paint) {
            paint.getTextBounds(text, 0, text.length(), textBounds);
            return textBounds.width();
        }
    }

    /**
     * Custom view for rendering a circular progress indicator with icons and text.
     */
    @SuppressLint("ViewConstructor")
    private static final class CircularProgressView extends AbstractProgressView {
        private final RectF rectF = new RectF();

        CircularProgressView(Context context, int overlayBackgroundOpacity, boolean isMinimalStyle,
                             int overlayProgressColor, int overlayFillBackgroundPaint,
                             int overlayTextColor, int overlayTextSize) {
            super(context, overlayBackgroundOpacity, isMinimalStyle, overlayProgressColor,
                    overlayFillBackgroundPaint, overlayTextColor, overlayTextSize);

            progressPaint.setStrokeWidth(toDisplayPixels(6f));
            fillBackgroundPaint.setStrokeWidth(toDisplayPixels(6f));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            fillBackgroundPaint.setStrokeCap(Paint.Cap.BUTT);
            progressPaint.setStyle(Paint.Style.STROKE);
            fillBackgroundPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            final int width = getWidth();
            final int height = getHeight();
            final float size = Math.min(width, height);
            final float inset = toDisplayPixels(6f);
            rectF.set(inset, inset, size - inset, size - inset);

            canvas.drawOval(rectF, fillBackgroundPaint); // Draw the outer ring.
            canvas.drawCircle(width / 2f, height / 2f, size / 3, backgroundPaint); // Draw the inner circle.

            // Select the paint for drawing based on whether it's brightness or volume.
            final float sweepAngle = ((float) progress / maxProgress) * 360;
            canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint); // Draw the progress arc.

            // Draw the icon in the center.
            if (icon != null) {
                final int iconSize = (int) toDisplayPixels(isMinimalStyle ? 36f : 24f);
                final int iconX = (width - iconSize) / 2;
                final int iconY = isMinimalStyle
                        ? (height - iconSize) / 2
                        : (height / 2) - (int) toDisplayPixels(24f);
                icon.setBounds(iconX, iconY, iconX + iconSize, iconY + iconSize);
                icon.draw(canvas);
            }

            // If not a minimal style mode, draw the text inside the ring.
            if (!isMinimalStyle) {
                canvas.drawText(displayText, width / 2f, height / 2f + toDisplayPixels(20f), textPaint);
            }
        }

        @Override
        void setProgress(int value, int max, String text, boolean isBrightnessMode) {
            super.setProgress(value, max, text, isBrightnessMode);
            requestLayout();
        }
    }

    /**
     * Custom view for rendering a rectangular progress bar with icons and text.
     */
    @SuppressLint("ViewConstructor")
    private static final class HorizontalProgressView extends AbstractProgressView {
        private final float iconSize = toDisplayPixels(20f);
        private final float padding = toDisplayPixels(12f);
        private float textWidth = 0f;
        private final float progressBarHeight = toDisplayPixels(3f);
        private final float progressBarWidth;

        HorizontalProgressView(Context context, int overlayBackgroundOpacity, boolean isMinimalStyle,
                               int overlayProgressColor, int overlayFillBackgroundPaint,
                               int overlayTextColor, int overlayTextSize) {
            super(context, overlayBackgroundOpacity, isMinimalStyle, overlayProgressColor,
                    overlayFillBackgroundPaint, overlayTextColor, overlayTextSize);

            progressBarWidth = getResources().getDisplayMetrics().widthPixels / 4f;

            progressPaint.setStrokeWidth(0f);
            progressPaint.setStrokeCap(Paint.Cap.BUTT);
            progressPaint.setStyle(Paint.Style.FILL);
            fillBackgroundPaint.setStyle(Paint.Style.FILL);
        }

        /**
         * Calculate required width based on content.
         *
         * @return Required width to display all elements.
         */
        private float calculateRequiredWidth() {
            textWidth = measureTextWidth(displayText, textPaint);

            return !isMinimalStyle
                    ? padding + iconSize + padding + progressBarWidth + padding + textWidth + padding
                    : padding + iconSize + padding + textWidth + padding;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            final int suggestedWidth = MeasureSpec.getSize(widthMeasureSpec);
            final int suggestedHeight = MeasureSpec.getSize(heightMeasureSpec);

            final int requiredWidth = (int) calculateRequiredWidth();
            final int width = Math.min(Math.max(100, requiredWidth), suggestedWidth);

            setMeasuredDimension(width, suggestedHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            final float viewWidth = getWidth();
            final float viewHeight = getHeight();
            final float viewHeightHalf = viewHeight / 2;

            textWidth = measureTextWidth(displayText, textPaint);

            final float cornerRadius = viewHeightHalf;

            final float startX = padding;
            final float iconEndX = startX + iconSize;

            final float textStartX = viewWidth - 1.5f * padding - textWidth;

            canvas.drawRoundRect(
                    0f, 0f, viewWidth, viewHeight,
                    cornerRadius, cornerRadius, backgroundPaint
            );

            if (icon != null) {
                final float iconY = viewHeightHalf - iconSize / 2;
                icon.setBounds(
                        (int) startX,
                        (int) iconY,
                        (int) (startX + iconSize),
                        (int) (iconY + iconSize)
                );
                icon.draw(canvas);
            }

            final float textY = viewHeightHalf + textPaint.getTextSize() / 3;
            textPaint.setTextAlign(Paint.Align.LEFT);

            if (isMinimalStyle) {
                canvas.drawText(displayText, textStartX, textY, textPaint);
            } else {
                final float progressStartX = iconEndX + padding;
                final float progressEndX = textStartX - padding;
                final float progressWidth = progressEndX - progressStartX;

                if (progressWidth > 50) {
                    final float progressBarHeightHalf = progressBarHeight / 2.0f;
                    final float viewHeightHalfMinusProgressBarHeightHalf = viewHeightHalf - progressBarHeightHalf;
                    final float viewHeightHalfPlusProgressBarHeightHalf = viewHeightHalf + progressBarHeightHalf;

                    canvas.drawRoundRect(
                            progressStartX,
                            viewHeightHalfMinusProgressBarHeightHalf,
                            progressEndX,
                            viewHeightHalfPlusProgressBarHeightHalf,
                            progressBarHeightHalf,
                            progressBarHeightHalf,
                            fillBackgroundPaint
                    );

                    final float progressValue = ((float) progress / maxProgress) * progressWidth;
                    canvas.drawRoundRect(
                            progressStartX,
                            viewHeightHalfMinusProgressBarHeightHalf,
                            progressStartX + progressValue,
                            viewHeightHalfPlusProgressBarHeightHalf,
                            progressBarHeightHalf,
                            progressBarHeightHalf,
                            progressPaint
                    );
                }

                canvas.drawText(displayText, textStartX, textY, textPaint);
            }
        }

        @Override
        void setProgress(int value, int max, String text, boolean isBrightnessMode) {
            super.setProgress(value, max, text, isBrightnessMode);
            requestLayout();
        }
    }

    /**
     * Custom view for rendering a vertical progress bar with icons and text.
     */
    @SuppressLint("ViewConstructor")
    private static final class VerticalProgressView extends AbstractProgressView {
        private final float iconSize = toDisplayPixels(20f);
        private final float padding = toDisplayPixels(12f);
        private final float progressBarWidth = toDisplayPixels(3f);
        private final float progressBarHeight;

        VerticalProgressView(Context context, int overlayBackgroundOpacity, boolean isMinimalStyle,
                             int overlayProgressColor, int overlayFillBackgroundPaint,
                             int overlayTextColor, int overlayTextSize) {
            super(context, overlayBackgroundOpacity, isMinimalStyle, overlayProgressColor,
                    overlayFillBackgroundPaint, overlayTextColor, overlayTextSize);

            progressBarHeight = getResources().getDisplayMetrics().widthPixels / 3f;

            progressPaint.setStrokeWidth(0f);
            progressPaint.setStrokeCap(Paint.Cap.BUTT);
            progressPaint.setStyle(Paint.Style.FILL);
            fillBackgroundPaint.setStyle(Paint.Style.FILL);
        }

        /**
         * Calculate required height based on content.
         *
         * @return Required height to display all elements.
         */
        private float calculateRequiredHeight() {
            return !isMinimalStyle
                    ? padding + iconSize + padding + progressBarHeight + padding + textPaint.getTextSize() + padding
                    : padding + iconSize + padding + textPaint.getTextSize() + padding;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            final int suggestedWidth = MeasureSpec.getSize(widthMeasureSpec);
            final int suggestedHeight = MeasureSpec.getSize(heightMeasureSpec);

            final int requiredHeight = (int) calculateRequiredHeight();
            final int height = Math.min(Math.max(100, requiredHeight), suggestedHeight);

            setMeasuredDimension(suggestedWidth, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            final float viewWidth = getWidth();
            final float viewHeight = getHeight();
            final float viewWidthHalf = viewWidth / 2;
            final float cornerRadius = viewWidthHalf;

            final float startY = padding;
            final float iconEndY = startY + iconSize;

            final float textStartY = viewHeight - padding - textPaint.getTextSize() / 2;

            canvas.drawRoundRect(
                    0f, 0f, viewWidth, viewHeight,
                    cornerRadius, cornerRadius, backgroundPaint
            );

            if (icon != null) {
                final float iconX = viewWidthHalf - iconSize / 2;
                icon.setBounds(
                        (int) iconX,
                        (int) startY,
                        (int) (iconX + iconSize),
                        (int) (startY + iconSize)
                );
                icon.draw(canvas);
            }

            final float textX = viewWidthHalf;
            textPaint.setTextAlign(Paint.Align.CENTER);

            if (isMinimalStyle) {
                canvas.drawText(displayText, textX, textStartY, textPaint);
            } else {
                final float progressStartY = iconEndY + padding;
                final float progressEndY = textStartY - textPaint.getTextSize() - padding;
                final float progressHeight = progressEndY - progressStartY;

                if (progressHeight > 50) {
                    final float progressBarWidthHalf = progressBarWidth / 2;
                    final float viewHeightHalfMinusProgressBarHeightHalf = viewWidthHalf - progressBarWidthHalf;
                    final float viewHeightHalfPlusProgressBarHeightHalf = viewWidthHalf + progressBarWidthHalf;

                    canvas.drawRoundRect(
                            viewHeightHalfMinusProgressBarHeightHalf,
                            progressStartY,
                            viewHeightHalfPlusProgressBarHeightHalf,
                            progressEndY,
                            progressBarWidthHalf,
                            progressBarWidthHalf,
                            fillBackgroundPaint
                    );

                    final float progressValue = ((float) progress / maxProgress) * progressHeight;
                    canvas.drawRoundRect(
                            viewHeightHalfMinusProgressBarHeightHalf,
                            progressEndY - progressValue,
                            viewHeightHalfPlusProgressBarHeightHalf,
                            progressEndY,
                            progressBarWidthHalf,
                            progressBarWidthHalf,
                            progressPaint
                    );
                }
                canvas.drawText(displayText, textX, textStartY, textPaint);
            }
        }

        @Override
        void setProgress(int value, int max, String text, boolean isBrightnessMode) {
            super.setProgress(value, max, text, isBrightnessMode);
            requestLayout();
        }
    }
}
