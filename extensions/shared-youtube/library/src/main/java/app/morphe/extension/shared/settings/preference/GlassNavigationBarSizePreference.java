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
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;

/**
 * Sets the height, the width and the button spacing of the glass navigation bar,
 * with a preview of the bar that follows the sliders. Shared by the apps, which give the settings,
 * the strings and the buttons.
 */
@SuppressWarnings({"unused", "deprecation"})
public abstract class GlassNavigationBarSizePreference extends Preference {

    public GlassNavigationBarSizePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * @return The settings of the height, the width and the button spacing, in that order.
     */
    protected abstract IntegerSetting[] getSizeSettings();

    /**
     * @return The keys of the titles of the height, the width and the button spacing sliders.
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

        final IntegerSetting[] settings = getSizeSettings();
        final String[] titleKeys = getSizeTitleKeys();
        PreviewButtons previewButtons = getButtons();
        List<String> labels = previewButtons.labels;

        final int[] pending = new int[settings.length];
        PreviewView preview = new PreviewView(context, previewButtons, pending, getHorizontalMargin());
        TextView shownButtons = new TextView(context);
        shownButtons.setGravity(Gravity.CENTER);
        shownButtons.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        Runnable update = () -> {
            preview.invalidate();
            shownButtons.setText(str(getButtonsShownKey(),
                    GlassNavigationBarLayout.getButtonsLayout(screenWidth, labels.size(), preview.getContentWidth(),
                            GlassNavigationBarLayout.lastMinButtonWidth, pending[1], Dim.dp(pending[2]), getHorizontalMargin())[0],
                    labels.size()));
        };

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp8, 0, Dim.dp8);
        content.addView(preview, LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(120));
        LinearLayout.LayoutParams shownParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shownParams.topMargin = Dim.dp4;
        shownParams.bottomMargin = Dim.dp8;
        content.addView(shownButtons, shownParams);

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
     * The bottom of the screen with the glass navigation bar, scaled to the width of the preview.
     * The buttons are laid out as in the navigation bar, see {@link GlassNavigationBarLayout}.
     */
    private static final class PreviewView extends View {
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
         * Height percent, width percent and button spacing in dp.
         */
        private final int[] values;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        /**
         * The least room between the ends of the capsule and the edges of the screen.
         */
        private final int margin;

        PreviewView(Context context, PreviewButtons buttons, int[] values, int margin) {
            super(context);
            this.labels = buttons.labels;
            this.icons = buttons.icons;
            this.selected = buttons.selected;
            this.showLabels = buttons.showLabels;
            this.values = values;
            this.margin = margin;
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP,
                    context.getResources().getDisplayMetrics()));
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
            rect.set(left, top, left + capsuleWidth, bottom);
            final float radius = capsuleHeight / 2;

            paint.setColor(dark ? 0x99383838 : 0x99F7F7F7);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Dim.dp1);
            paint.setColor(dark ? 0x80FFFFFF : 0xF2FFFFFF);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);

            // The buttons that do not fit are hidden from the end, but never the selected one.
            final int selectedButton = Math.min(selected, buttons - 1);
            final int selectedSlot = Math.min(selectedButton, shownButtons - 1);
            final float inset = Dim.dp4 * scale;
            final float selectedLeft = left + buttonWidth * selectedSlot;
            paint.setColor(dark ? 0x26FFFFFF : 0x14000000);
            rect.set(selectedLeft + inset, top + inset, selectedLeft + buttonWidth - inset, bottom - inset);
            canvas.drawRoundRect(rect, (capsuleHeight - 2 * inset) / 2, (capsuleHeight - 2 * inset) / 2, paint);

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
