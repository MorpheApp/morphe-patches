/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4621
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * A dialog with a slider for each of several settings, below a preview that follows the sliders.
 * The settings are saved together when the dialog is confirmed.
 * Each setting needs a {@link SeekBarConfig} registered with {@link SeekBarPreference#register}.
 */
@SuppressWarnings({"unused", "deprecation"})
public abstract class SeekBarsPreviewPreference extends Preference {

    public SeekBarsPreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * @return The settings of the sliders, in the order they are shown.
     */
    protected abstract IntegerSetting[] getSettings();

    /**
     * @return The keys of the titles of the sliders, in the same order as the settings.
     */
    protected abstract String[] getTitleKeys();

    /**
     * @param values The values of the sliders, in the same order as the settings,
     *               changed in place as the sliders move.
     * @return The preview shown above the sliders, with the width of the dialog.
     */
    protected abstract View createPreview(Context context, int[] values);

    /**
     * Called when the dialog opens and when a slider moves.
     */
    protected void updatePreview(View preview, int[] values) {
        preview.invalidate();
    }

    @Override
    protected void onClick() {
        Context context = getContext();

        TypedValue accent = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        final int accentColor = accent.data;

        final IntegerSetting[] settings = getSettings();
        final String[] titleKeys = getTitleKeys();
        final int[] pending = new int[settings.length];
        for (int i = 0; i < settings.length; i++) {
            pending[i] = SeekBarPreference.clampToRange(settings[i]);
        }

        View preview = createPreview(context, pending);
        Runnable update = () -> updatePreview(preview, pending);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp8, 0, Dim.dp8);
        content.addView(preview, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        SeekBar[] seekBars = new SeekBar[settings.length];
        for (int i = 0; i < settings.length; i++) {
            final int index = i;
            SeekBarConfig config = SeekBarPreference.configFor(settings[i]);

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
}
