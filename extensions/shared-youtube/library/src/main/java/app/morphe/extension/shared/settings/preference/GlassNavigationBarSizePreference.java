/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4424
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;

/**
 * Sets the height, the width and the button spacing of the glass navigation bar, and optionally
 * more of its style, with a preview of the bar that follows the sliders. Shared by the apps, which give the settings,
 * the strings and the buttons.
 */
@SuppressWarnings({"unused", "deprecation"})
public abstract class GlassNavigationBarSizePreference extends SeekBarsPreviewPreference {

    public GlassNavigationBarSizePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * @return The settings of the height, the width and the button spacing, in that order,
     *         followed by any other sliders, such as of the style of the glass.
     */
    @Override
    protected abstract IntegerSetting[] getSettings();

    /**
     * @return The key of the text that shows how many buttons fit, with the shown and the total count.
     */
    protected abstract String getButtonsShownKey();

    /**
     * @return The buttons of the last navigation bar, or of all the buttons seen if none was shown yet.
     */
    protected abstract PreviewButtons getButtons();

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

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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

    /**
     * @param values The values of the sliders.
     * @return How opaque the glass in the preview is: 1 keeps the default alphas, 0 is fully clear, 2 doubles them.
     */
    protected float getOpacityScale(int[] values) {
        return 1;
    }

    /**
     * @param values The values of the sliders.
     * @return How bright the rim of the glass in the preview is: 1 keeps the default alphas, 0 draws no rim.
     */
    protected float getRimScale(int[] values) {
        return 1;
    }

    /**
     * @return If the glass is tinted white with the dark theme.
     */
    protected boolean isLightTint() {
        return false;
    }

    /**
     * @return The color the glass is tinted with, or 0 for the colors of the theme.
     */
    @ColorInt
    protected int getTintColor() {
        return 0;
    }

    /**
     * @return If the selected button has a rounded indicator.
     */
    protected boolean isIndicatorShown() {
        return true;
    }

    /**
     * @return The corner radius of the capsule, in parts of its height: 0.5 is round ends.
     */
    protected float getCornerRadiusFraction() {
        return 0.5f;
    }

    @Override
    protected View createPreview(Context context, int[] values) {
        PreviewView preview = new PreviewView(context, getButtons(), values, getHorizontalMargin(),
                getCornerRadiusFraction());
        TextView shownButtons = new TextView(context);
        shownButtons.setGravity(Gravity.CENTER);
        shownButtons.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(preview, LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(120));
        LinearLayout.LayoutParams shownParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shownParams.topMargin = Dim.dp4;
        shownParams.bottomMargin = Dim.dp8;
        layout.addView(shownButtons, shownParams);
        return layout;
    }

    @Override
    protected void updatePreview(View view, int[] values) {
        ViewGroup layout = (ViewGroup) view;
        PreviewView preview = (PreviewView) layout.getChildAt(0);
        TextView shownButtons = (TextView) layout.getChildAt(1);
        preview.setStyle(getOpacityScale(values), isLightTint(), getRimScale(values), getTintColor(), isIndicatorShown());

        final int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
        final int buttons = preview.labels.size();
        shownButtons.setText(str(getButtonsShownKey(),
                GlassNavigationBarLayout.getButtonsLayout(screenWidth, buttons, preview.getContentWidth(),
                        GlassNavigationBarLayout.lastMinButtonWidth, values[1], Dim.dp(values[2]), getHorizontalMargin())[0],
                buttons));
    }

    /**
     * The bottom of the screen with the glass navigation bar, scaled to the width of the preview.
     * The buttons are laid out as in the navigation bar, see {@link GlassNavigationBarLayout}.
     */
    private static final class PreviewView extends View implements GlassCapsuleDrawable.Host {
        private static final int ICON_SIZE = Dim.dp24;
        private static final float LABEL_SIZE_SP = 12;

        private final List<String> labels;
        /**
         * The icons of the buttons, null for a button without a recorded icon.
         */
        private final List<Picture> icons;
        private final int selected;
        private final boolean showLabels;
        /**
         * Height percent, width percent and button spacing in dp, and what else the app gives.
         */
        private final int[] values;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        /**
         * The least room between the ends of the capsule and the edges of the screen.
         */
        private final int margin;
        private final float cornerRadiusFraction;

        private final GlassCapsuleDrawable glass;
        private final Rect capsule = new Rect();
        private boolean indicatorShown = true;

        PreviewView(Context context, PreviewButtons buttons, int[] values, int margin, float cornerRadiusFraction) {
            super(context);
            this.labels = buttons.labels;
            this.icons = buttons.icons;
            this.selected = buttons.selected;
            this.showLabels = buttons.showLabels;
            this.values = values;
            this.margin = margin;
            this.cornerRadiusFraction = cornerRadiusFraction;
            labelPaint.setTextAlign(Paint.Align.CENTER);
            // Also the custom font of the Custom font patch, which a paint without a typeface does not use.
            labelPaint.setTypeface(Typeface.DEFAULT);
            labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP,
                    context.getResources().getDisplayMetrics()));
            glass = new GlassCapsuleDrawable(this, this, 1);
        }

        void setStyle(float opacityScale, boolean lightTint, float rimScale, @ColorInt int tintColor,
                      boolean indicatorShown) {
            glass.setStyle(opacityScale, lightTint, rimScale);
            glass.setTintColor(tintColor);
            this.indicatorShown = indicatorShown;
            invalidate();
        }

        @Override
        public void getCapsule(Rect bounds, RectF out) {
            out.set(bounds);
        }

        @Nullable
        @Override
        public View getSelectedTabView() {
            // The indicator is drawn by the preview, which has no buttons.
            return null;
        }

        @Override
        public float getCornerRadius(RectF capsule) {
            return capsule.height() * cornerRadiusFraction;
        }

        /**
         * The content width of the buttons, as measured in the navigation bar, or of the widest label.
         */
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

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean dark = Utils.isDarkModeEnabled();
            final int foreground = ThemeUtils.getAppForegroundColor();
            final float width = getWidth();
            final float height = getHeight();
            final float corner = Dim.dp12;

            // The screen, scaled to the width of the preview.
            final int screenWidth = getResources().getDisplayMetrics().widthPixels;
            final float scale = width / screenWidth;
            rect.set(0, 0, width, height);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dark ? Color.rgb(24, 24, 24) : Color.rgb(236, 236, 236));
            canvas.drawRoundRect(rect, corner, corner, paint);

            // Content behind the bar, so the translucency of the glass shows.
            paint.setColor(dark ? Color.rgb(70, 90, 130) : Color.rgb(150, 180, 230));
            rect.set(width * 0.06f, height * 0.12f, width * 0.55f, height * 0.95f);
            canvas.drawRoundRect(rect, corner, corner, paint);
            paint.setColor(dark ? Color.rgb(130, 70, 70) : Color.rgb(240, 170, 160));
            rect.set(width * 0.6f, height * 0.12f, width * 0.94f, height * 0.95f);
            canvas.drawRoundRect(rect, corner, corner, paint);

            // The layout of the navigation bar.
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
            final float left = (width - capsuleWidth) / 2;
            final float bottom = height - Dim.dp10 * scale;
            final float top = bottom - capsuleHeight;
            capsule.set(Math.round(left), Math.round(top), Math.round(left + capsuleWidth), Math.round(bottom));
            glass.setBounds(capsule);
            glass.draw(canvas);

            // The buttons that do not fit are hidden from the end, but never the selected one.
            final int selectedButton = Math.min(selected, buttons - 1);
            final int selectedSlot = Math.min(selectedButton, shownButtons - 1);
            final float inset = Dim.dp4 * scale;
            if (indicatorShown) {
                final float selectedLeft = left + buttonWidth * selectedSlot;
                paint.setColor(dark ? 0x26FFFFFF : 0x14000000);
                rect.set(selectedLeft + inset, top + inset, selectedLeft + buttonWidth - inset, bottom - inset);
                final float indicatorRadius = GlassCapsuleDrawable.getIndicatorRadius(
                        capsuleHeight * cornerRadiusFraction, capsuleHeight - 2 * inset);
                canvas.drawRoundRect(rect, indicatorRadius, indicatorRadius, paint);
            }

            // The icon and the label of each button, in the middle of the capsule.
            // Buttons without a recorded icon, such as before the bar was shown, get a placeholder.
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
                    // The app cuts the labels that are longer than the button.
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
