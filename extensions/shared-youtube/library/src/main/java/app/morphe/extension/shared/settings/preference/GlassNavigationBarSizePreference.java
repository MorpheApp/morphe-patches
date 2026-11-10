/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4424
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.RectF;
import android.preference.Preference;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;

/**
 * Sets the size and style of the glass navigation bar or glass miniplayer,
 * with a live preview that follows the sliders. Shared by the apps, which give the settings,
 * the strings and the buttons.
 */
@SuppressWarnings({"unused", "deprecation"})
public abstract class GlassNavigationBarSizePreference extends Preference {

    public GlassNavigationBarSizePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * @return The settings controlled by the sliders in the dialog.
     */
    protected abstract IntegerSetting[] getSizeSettings();

    /**
     * @return The keys of the titles of the sliders.
     */
    protected abstract String[] getSizeTitleKeys();

    /**
     * @return The key of the text that shows how many buttons fit, with the shown and the total count.
     */
    protected abstract String getButtonsShownKey();

    /**
     * @return The buttons of the last navigation bar, or of all the buttons seen if none was shown yet.
     */
    protected abstract PreviewButtons getButtons();

    /**
     * @return If this dialog previews only the glass miniplayer (with miniplayer-specific sliders).
     */
    protected boolean isMiniplayerMode() {
        return false;
    }

    /**
     * @return The custom tint color of the glass in the preview, or 0 for theme default.
     */
    protected int getPreviewTintColor() {
        return 0;
    }

    /**
     * @return If the light glass tint is enabled.
     */
    protected boolean isLightGlass() {
        return false;
    }

    /**
     * @return If the selected tab indicator is hidden in the navigation bar preview or progress bar is hidden in miniplayer preview.
     */
    protected boolean isSecondaryElementHidden() {
        return false;
    }

    /**
     * @return If the miniplayer artwork is circular (spinning vinyl mode).
     */
    protected boolean isCircularArtwork() {
        return false;
    }

    /**
     * The icons, the labels and the selected button of the navigation bar, for the preview.
     */
    public static final class PreviewButtons {
        final List<String> labels = new ArrayList<>();
        /**
         * The icons of the buttons, null for a button without a recorded icon.
         */
        final List<Picture> icons = new ArrayList<>();
        int selected;
        boolean showLabels;

        public boolean isEmpty() {
            return labels.isEmpty();
        }

        /**
         * Adds a button without an icon, such as a button seen before the bar was shown.
         */
        public void add(String label) {
            labels.add(label);
            icons.add(null);
        }

        public void setShowLabels(boolean showLabels) {
            this.showLabels = showLabels;
        }

        /**
         * @return The icons, the labels and the selected button of the last glass navigation bar,
         *         as the bar shows them, such as without labels.
         */
        public static PreviewButtons fromLastBar(int iconId, int labelId) {
            PreviewButtons buttons = new PreviewButtons();
            for (WeakReference<View> reference : GlassNavigationBarLayout.lastButtons) {
                if (!(reference.get() instanceof ViewGroup button)) continue;
                View icon = iconId == 0 ? null : button.findViewById(iconId);
                Picture picture = null;
                if (icon != null && icon.getWidth() > 0 && icon.getHeight() > 0) {
                    // Recorded, not drawn to a bitmap, so the hardware bitmaps of the icons (the account avatar) work.
                    picture = new Picture();
                    icon.draw(picture.beginRecording(icon.getWidth(), icon.getHeight()));
                    picture.endRecording();
                }
                View label = labelId == 0 ? null : button.findViewById(labelId);
                if (button.isSelected()) buttons.selected = buttons.labels.size();
                if (label instanceof TextView text && text.getVisibility() == View.VISIBLE) buttons.showLabels = true;
                buttons.labels.add(label instanceof TextView text ? text.getText().toString() : "");
                buttons.icons.add(picture);
            }
            return buttons;
        }
    }

    /**
     * @return The least room between the ends of the capsule and the edges of the screen.
     */
    protected abstract int getHorizontalMargin();

    @Override
    protected void onClick() {
        Context context = getContext();
        TypedValue accent = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        final int accentColor = accent.data;

        final boolean miniplayerMode = isMiniplayerMode();
        final IntegerSetting[] settings = getSizeSettings();
        final String[] titleKeys = getSizeTitleKeys();
        PreviewButtons previewButtons = getButtons();
        List<String> labels = previewButtons.labels;

        final int[] pending = new int[settings.length];
        PreviewView preview = new PreviewView(
                context,
                previewButtons,
                pending,
                getHorizontalMargin(),
                miniplayerMode,
                getPreviewTintColor(),
                isLightGlass(),
                isSecondaryElementHidden(),
                isCircularArtwork()
        );

        TextView shownButtons = new TextView(context);
        shownButtons.setGravity(Gravity.CENTER);
        shownButtons.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        Runnable update = () -> {
            preview.invalidate();
            if (!miniplayerMode) {
                shownButtons.setText(str(getButtonsShownKey(),
                        GlassNavigationBarLayout.getButtonsLayout(screenWidth, labels.size(), preview.getContentWidth(),
                                GlassNavigationBarLayout.lastMinButtonWidth, pending[1], Dim.dp(pending[2]), getHorizontalMargin())[0],
                        labels.size()));
            }
        };

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp8, 0, Dim.dp8);
        content.addView(preview, LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(124));

        if (!miniplayerMode) {
            LinearLayout.LayoutParams shownParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            shownParams.topMargin = Dim.dp4;
            shownParams.bottomMargin = Dim.dp8;
            content.addView(shownButtons, shownParams);
        }

        SeekBar[] seekBars = new SeekBar[settings.length];
        for (int i = 0; i < settings.length; i++) {
            final int index = i;
            SeekBarConfig config = SeekBarPreference.configFor(settings[i]);
            pending[i] = SeekBarPreference.clampToRange(settings[i]);

            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            TextView value = new TextView(context);
            value.setTextColor(accentColor);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setText(str(titleKeys[i]));
            value.setText(SeekBarPreference.formatLabel(pending[i], config));

            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            titleRow.addView(value, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

            SeekBar seekBar = new SeekBar(context);
            seekBar.setMax((config.max() - config.min()) / config.step());
            seekBar.setProgress(SeekBarPreference.valueToProgress(config, pending[i]));
            seekBar.setProgressTintList(ColorStateList.valueOf(accentColor));
            seekBar.setThumbTintList(ColorStateList.valueOf(accentColor));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    pending[index] = SeekBarPreference.progressToValue(config, progress);
                    value.setText(SeekBarPreference.formatLabel(pending[index], config));
                    update.run();
                }

                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            seekBars[i] = seekBar;

            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = Dim.dp8;
            content.addView(titleRow, titleParams);
            content.addView(seekBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        update.run();

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null,
                null,
                null,
                () -> {
                    for (int i = 0; i < settings.length; i++) {
                        settings[i].save(pending[i]);
                    }
                },
                () -> {},
                str("morphe_settings_reset"),
                () -> {
                    for (int i = 0; i < settings.length; i++) {
                        seekBars[i].setProgress(SeekBarPreference.valueToProgress(
                                SeekBarPreference.configFor(settings[i]), settings[i].defaultValue));
                    }
                },
                false
        );

        // Insert the content between the title (index 0) and the buttons (index 1).
        dialogPair.second.addView(content, 1);
        dialogPair.first.show();
    }

    /**
     * Live preview canvas for either the glass navigation bar or the glass miniplayer.
     */
    private static final class PreviewView extends View {
        private static final int ICON_SIZE = Dim.dp24;
        private static final float LABEL_SIZE_SP = 12;

        private final List<String> labels;
        private final List<Picture> icons;
        private final int selected;
        private final boolean showLabels;
        private final int[] values;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path iconPath = new Path();
        private final int margin;
        private final boolean miniplayerMode;
        private final int customTint;
        private final boolean lightGlass;
        private final boolean hideSecondary;
        private final boolean circularArtwork;

        PreviewView(
                Context context,
                PreviewButtons buttons,
                int[] values,
                int margin,
                boolean miniplayerMode,
                int customTint,
                boolean lightGlass,
                boolean hideSecondary,
                boolean circularArtwork
        ) {
            super(context);
            this.labels = buttons.labels;
            this.icons = buttons.icons;
            this.selected = buttons.selected;
            this.showLabels = buttons.showLabels;
            this.values = values;
            this.margin = margin;
            this.miniplayerMode = miniplayerMode;
            this.customTint = customTint != 0 ? GlassCapsuleDrawable.normalizeTintColor(customTint) : 0;
            this.lightGlass = lightGlass;
            this.hideSecondary = hideSecondary;
            this.circularArtwork = circularArtwork;
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP,
                    context.getResources().getDisplayMetrics()));
        }

        int getContentWidth() {
            int width = GlassNavigationBarLayout.lastContentWidth;
            if (width > 0) return width;
            width = ICON_SIZE;
            if (showLabels) {
                for (String label : labels) {
                    width = Math.max(width, (int) Math.ceil(labelPaint.measureText(label)));
                }
            }
            return width;
        }

        private static int withAlpha(int rgb, float alpha) {
            final int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
            return (rgb & 0x00FFFFFF) | (a << 24);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean dark = Utils.isDarkModeEnabled();
            final int foreground = ThemeUtils.getAppForegroundColor();
            final float width = getWidth();
            final float height = getHeight();
            final float corner = Dim.dp12;

            final int screenWidth = getResources().getDisplayMetrics().widthPixels;
            final float scale = width / screenWidth;

            rect.set(0, 0, width, height);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dark ? Color.rgb(24, 24, 24) : Color.rgb(236, 236, 236));
            canvas.drawRoundRect(rect, corner, corner, paint);

            // Content behind the glass, so translucency is clearly visible.
            paint.setColor(dark ? Color.rgb(70, 90, 130) : Color.rgb(150, 180, 230));
            rect.set(width * 0.06f, height * 0.12f, width * 0.55f, height * 0.92f);
            canvas.drawRoundRect(rect, corner, corner, paint);
            paint.setColor(dark ? Color.rgb(130, 70, 70) : Color.rgb(240, 170, 160));
            rect.set(width * 0.6f, height * 0.12f, width * 0.94f, height * 0.92f);
            canvas.drawRoundRect(rect, corner, corner, paint);

            if (miniplayerMode) {
                drawMiniplayerPreview(canvas, dark, foreground, width, height, scale);
            } else {
                drawNavigationBarPreview(canvas, dark, foreground, width, height, screenWidth, scale);
            }
        }

        private void drawMiniplayerPreview(
                Canvas canvas,
                boolean dark,
                int foreground,
                float width,
                float height,
                float scale
        ) {
            // Supports both 4-slider [cornerRadiusDp, artworkPaddingDp, rimPercent, opacityPercent]
            // and 5-slider [cornerRadiusDp, artworkRadiusDp, artworkPaddingDp, rimPercent, opacityPercent] layouts.
            final float cornerRadius = Math.max(0f, Dim.dp(values[0]) * scale);
            final boolean hasExplicitArtworkRadius = values.length >= 5;
            final float artworkPadding = Math.max(Dim.dp2 * scale,
                    Dim.dp(values[hasExplicitArtworkRadius ? 2 : 1]) * scale);
            final float artworkRadius = hasExplicitArtworkRadius
                    ? Math.max(0f, Dim.dp(values[1]) * scale)
                    : Math.max(Dim.dp(6) * scale, cornerRadius - artworkPadding);
            final float rimScale = values.length > (hasExplicitArtworkRadius ? 3 : 2)
                    ? values[hasExplicitArtworkRadius ? 3 : 2] / 50f : 1f;
            final float opacityScale = values.length > (hasExplicitArtworkRadius ? 4 : 3)
                    ? values[hasExplicitArtworkRadius ? 4 : 3] / 50f : 1f;

            final float miniMargin = margin * scale;
            final float miniHeight = Dim.dp(64) * scale;
            final float miniTop = (height - miniHeight) / 2f;
            final float miniBottom = miniTop + miniHeight;
            final float miniLeft = miniMargin;
            final float miniRight = width - miniMargin;
            final float actualCorner = Math.min(cornerRadius, miniHeight / 2f);

            final int baseRgb = customTint != 0
                    ? customTint
                    : (dark ? (lightGlass ? 0xFFFFFF : 0x383838) : 0xF7F7F7);
            final float baseAlpha = customTint != 0
                    ? GlassCapsuleDrawable.scaledAlpha(0.58f, opacityScale)
                    : GlassCapsuleDrawable.scaledAlpha(dark && lightGlass ? 0.35f : 0.55f, opacityScale);

            rect.set(miniLeft, miniTop, miniRight, miniBottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(baseRgb, baseAlpha));
            canvas.drawRoundRect(rect, actualCorner, actualCorner, paint);

            if (rimScale > 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, Dim.dp(1.5f) * scale));
                paint.setColor(withAlpha(customTint != 0 ? customTint : 0xFFFFFF,
                        (dark ? 0.55f : 0.90f) * rimScale));
                canvas.drawRoundRect(rect, actualCorner, actualCorner, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            // Album artwork inside the capsule, respecting artworkPadding and actualCorner.
            final float artSize = Math.max(Dim.dp(20) * scale, miniHeight - 2f * artworkPadding);
            final float artLeft = miniLeft + Math.max(artworkPadding, actualCorner * 0.35f);
            final float artTop = miniTop + (miniHeight - artSize) / 2f;
            final float effectiveArtRadius = circularArtwork
                    ? artSize / 2f
                    : Math.min(artworkRadius, artSize / 2f);

            rect.set(artLeft, artTop, artLeft + artSize, artTop + artSize);
            paint.setColor(customTint != 0 ? (customTint | 0xFF000000) : (dark ? 0xFF5A78A8 : 0xFF7898C8));
            canvas.drawRoundRect(rect, effectiveArtRadius, effectiveArtRadius, paint);

            // Inner vinyl hole if circular artwork is active.
            if (circularArtwork) {
                paint.setColor(dark ? Color.rgb(24, 24, 24) : Color.rgb(236, 236, 236));
                canvas.drawCircle(rect.centerX(), rect.centerY(), artSize * 0.16f, paint);
            }

            // Song title and artist bars.
            final float textLeft = artLeft + artSize + Dim.dp10 * scale;
            final float titleHeight = Dim.dp6 * scale;
            final float titleTop = miniTop + miniHeight * 0.36f - titleHeight / 2f;
            rect.set(textLeft, titleTop, textLeft + (miniRight - miniLeft) * 0.36f, titleTop + titleHeight);
            paint.setColor(withAlpha(foreground, 0.88f));
            canvas.drawRoundRect(rect, titleHeight / 2f, titleHeight / 2f, paint);

            final float subHeight = Dim.dp(5) * scale;
            final float subTop = miniTop + miniHeight * 0.64f - subHeight / 2f;
            rect.set(textLeft, subTop, textLeft + (miniRight - miniLeft) * 0.24f, subTop + subHeight);
            paint.setColor(withAlpha(foreground, 0.55f));
            canvas.drawRoundRect(rect, subHeight / 2f, subHeight / 2f, paint);

            // Previous, Play/Pause, and Next controls inset from the right border.
            final float rightPad = Math.max(Dim.dp10 * scale, actualCorner * 0.5f);
            final float centerY = (miniTop + miniBottom) / 2f;
            final float iconHalf = Dim.dp(5.5f) * scale;
            paint.setColor(foreground);

            // Next button
            final float nextX = miniRight - rightPad - iconHalf;
            iconPath.reset();
            iconPath.moveTo(nextX - iconHalf, centerY - iconHalf);
            iconPath.lineTo(nextX - iconHalf, centerY + iconHalf);
            iconPath.lineTo(nextX + iconHalf * 0.5f, centerY);
            iconPath.close();
            canvas.drawPath(iconPath, paint);
            rect.set(nextX + iconHalf * 0.5f, centerY - iconHalf, nextX + iconHalf, centerY + iconHalf);
            canvas.drawRect(rect, paint);

            // Pause button
            final float pauseX = nextX - Dim.dp(26) * scale;
            final float barW = Dim.dp(2.5f) * scale;
            rect.set(pauseX - iconHalf * 0.65f, centerY - iconHalf, pauseX - iconHalf * 0.65f + barW, centerY + iconHalf);
            canvas.drawRect(rect, paint);
            rect.set(pauseX + iconHalf * 0.65f - barW, centerY - iconHalf, pauseX + iconHalf * 0.65f, centerY + iconHalf);
            canvas.drawRect(rect, paint);

            // Previous button
            final float prevX = pauseX - Dim.dp(26) * scale;
            rect.set(prevX - iconHalf, centerY - iconHalf, prevX - iconHalf * 0.5f, centerY + iconHalf);
            canvas.drawRect(rect, paint);
            iconPath.reset();
            iconPath.moveTo(prevX + iconHalf, centerY - iconHalf);
            iconPath.lineTo(prevX + iconHalf, centerY + iconHalf);
            iconPath.lineTo(prevX - iconHalf * 0.5f, centerY);
            iconPath.close();
            canvas.drawPath(iconPath, paint);

            // Bottom progress bar (if not hidden), inset by actualCorner so it stays inside the rounded bottom edge.
            if (!hideSecondary) {
                final float progInset = Math.max(Dim.dp12 * scale, actualCorner);
                final float progHeight = Math.max(1.5f, Dim.dp2 * scale);
                final float progLeft = miniLeft + progInset;
                final float progRight = miniRight - progInset;
                if (progRight > progLeft) {
                    rect.set(progLeft, miniBottom - progHeight - Dim.dp1 * scale, progRight, miniBottom - Dim.dp1 * scale);
                    paint.setColor(withAlpha(foreground, 0.22f));
                    canvas.drawRoundRect(rect, progHeight / 2f, progHeight / 2f, paint);
                    rect.right = progLeft + (progRight - progLeft) * 0.45f;
                    paint.setColor(withAlpha(foreground, 0.85f));
                    canvas.drawRoundRect(rect, progHeight / 2f, progHeight / 2f, paint);
                }
            }
        }

        private void drawNavigationBarPreview(
                Canvas canvas,
                boolean dark,
                int foreground,
                float width,
                float height,
                int screenWidth,
                float scale
        ) {
            final int buttons = Math.max(1, labels.size());
            final int contentWidth = getContentWidth();
            final int contentHeight = GlassNavigationBarLayout.lastContentHeight > 0
                    ? GlassNavigationBarLayout.lastContentHeight
                    : ICON_SIZE + (showLabels ? Dim.dp4 + Math.round(labelPaint.getTextSize() * 1.25f) : 0);
            final int baseHeight = GlassNavigationBarLayout.lastBaseHeight > 0 ? GlassNavigationBarLayout.lastBaseHeight : Dim.dp48;
            final int[] layout = GlassNavigationBarLayout.getButtonsLayout(screenWidth, buttons, contentWidth,
                    GlassNavigationBarLayout.lastMinButtonWidth, values[1], Dim.dp(values[2]), margin);
            final int shownButtons = layout[0];
            final float buttonWidth = layout[1] * scale;
            final float capsuleHeight = GlassNavigationBarLayout.getCapsuleHeight(baseHeight, contentHeight, values[0]) * scale;
            final float capsuleWidth = shownButtons * buttonWidth;
            final float opacityScale = values.length > 3 ? values[3] / 50f : 1f;
            final float rimScale = values.length > 4 ? values[4] / 50f : 1f;

            final float left = (width - capsuleWidth) / 2;
            final float bottom = height - Dim.dp10 * scale;
            final float top = bottom - capsuleHeight;
            final float radius = capsuleHeight / 2;

            final int baseRgb = customTint != 0
                    ? customTint
                    : (dark ? (lightGlass ? 0xFFFFFF : 0x383838) : 0xF7F7F7);
            final float baseAlpha = customTint != 0
                    ? GlassCapsuleDrawable.scaledAlpha(0.58f, opacityScale)
                    : GlassCapsuleDrawable.scaledAlpha(dark && lightGlass ? 0.35f : 0.60f, opacityScale);

            rect.set(left, top, left + capsuleWidth, bottom);
            paint.setColor(withAlpha(baseRgb, baseAlpha));
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (rimScale > 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Dim.dp1);
                paint.setColor(withAlpha(customTint != 0 ? customTint : 0xFFFFFF,
                        (dark ? 0.50f : 0.95f) * rimScale));
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            final int selectedButton = Math.min(selected, buttons - 1);
            final int selectedSlot = Math.min(selectedButton, shownButtons - 1);
            final float inset = Dim.dp4 * scale;
            if (!hideSecondary) {
                final float selectedLeft = left + buttonWidth * selectedSlot;
                paint.setColor(customTint != 0
                        ? withAlpha(customTint, 0.32f)
                        : (dark ? 0x26FFFFFF : 0x14000000));
                rect.set(selectedLeft + inset, top + inset, selectedLeft + buttonWidth - inset, bottom - inset);
                canvas.drawRoundRect(rect, (capsuleHeight - 2 * inset) / 2, (capsuleHeight - 2 * inset) / 2, paint);
            }

            final float iconSize = ICON_SIZE * scale;
            final float contentTop = top + (capsuleHeight - contentHeight * scale) / 2;
            labelPaint.setColor(foreground);
            final float labelSize = labelPaint.getTextSize();
            labelPaint.setTextSize(labelSize * scale);
            paint.setColor(foreground);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, Dim.dp2 * scale));
            for (int i = 0; i < shownButtons; i++) {
                final int button = i == selectedSlot ? selectedButton : i;
                final float center = left + buttonWidth * (i + 0.5f);
                Picture icon = button < icons.size() ? icons.get(button) : null;
                if (icon != null) {
                    final float iconWidth = icon.getWidth() * scale;
                    rect.set(center - iconWidth / 2, contentTop, center + iconWidth / 2,
                            contentTop + icon.getHeight() * scale);
                    canvas.drawPicture(icon, rect);
                } else {
                    rect.set(center - iconSize / 2, contentTop, center + iconSize / 2, contentTop + iconSize);
                    rect.inset(iconSize / 8, iconSize / 8);
                    canvas.drawRoundRect(rect, iconSize / 6, iconSize / 6, paint);
                }
                if (showLabels && button < labels.size()) {
                    canvas.drawText(TextUtils.ellipsize(labels.get(button), labelPaint, buttonWidth - 2 * inset,
                                    TextUtils.TruncateAt.END).toString(),
                            center, contentTop + contentHeight * scale - labelPaint.descent(), labelPaint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
            labelPaint.setTextSize(labelSize);
        }
    }
}
