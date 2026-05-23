/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * SeekBar preference that opens a dialog on click.
 * Register a {@link SeekBarConfig} for each preference key via {@link #register(SeekBarConfig)}.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SeekBarPreference extends Preference {

    public record SeekBarConfig(IntegerSetting setting, int min, int max, int step, String unit) { }

    private static final Map<String, SeekBarConfig> REGISTRY = new HashMap<>();

    public static void register(SeekBarConfig config) {
        REGISTRY.put(config.setting.key, config);
    }

    public SeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public SeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SeekBarPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        SeekBarConfig config = REGISTRY.get(getKey());
        if (config == null) {
            throw new IllegalStateException("SeekBarPreference: no Config registered for key '" + getKey() + "'");
        }

        Context context = getContext();

        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true);
        int colorAccent = tv.data;

        int[] pending = {config.setting.get()};

        TextView currentLabel = new TextView(context);
        currentLabel.setGravity(Gravity.CENTER);
        currentLabel.setTextColor(colorAccent);
        currentLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        updateLabel(currentLabel, pending[0], config.unit);

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax((config.max - config.min) / config.step);
        seekBar.setProgress(valueToProgress(config, pending[0]));
        seekBar.setProgressTintList(ColorStateList.valueOf(colorAccent));
        seekBar.setThumbTintList(ColorStateList.valueOf(colorAccent));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                pending[0] = progressToValue(config, progress);
                updateLabel(currentLabel, pending[0], config.unit);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        // Center column: value label above, seekbar below.
        LinearLayout seekCenter = new LinearLayout(context);
        seekCenter.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Dim.dp8;
        labelParams.bottomMargin = Dim.dp8;
        seekCenter.addView(currentLabel, labelParams);
        seekCenter.addView(seekBar,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        // SeekBar row: min label — [value label + seekbar] — max label, all bottom-aligned.
        LinearLayout seekRow = new LinearLayout(context);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.BOTTOM);

        TextView minLabel = new TextView(context);
        minLabel.setText(String.format(Locale.ROOT, "%d%s", config.min, config.unit));
        minLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        seekRow.addView(minLabel,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        centerParams.setMarginStart(Dim.dp8);
        centerParams.setMarginEnd(Dim.dp8);
        seekRow.addView(seekCenter, centerParams);

        TextView maxLabel = new TextView(context);
        maxLabel.setText(String.format(Locale.ROOT, "%d%s", config.max, config.unit));
        maxLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        seekRow.addView(maxLabel,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp8, 0, Dim.dp8);
        content.addView(seekRow,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle(),
                null,
                null,
                null,
                () -> config.setting.save(pending[0]),
                () -> {},
                StringRef.str("morphe_settings_reset"),
                () -> {
                    Integer defaultValue = config.setting.defaultValue;
                    pending[0] = defaultValue;
                    seekBar.setProgress(valueToProgress(config, defaultValue));
                    updateLabel(currentLabel, defaultValue, config.unit);
                },
                false
        );

        // Insert content between title (index 0) and buttons (index 1).
        dialogPair.second.addView(content, 1);
        dialogPair.first.show();
    }

    private static void updateLabel(TextView label, int value, String unit) {
        label.setText(String.format(Locale.ROOT, "%d%s", value, unit));
    }

    private static int valueToProgress(SeekBarConfig config, int value) {
        return (Math.max(config.min, Math.min(config.max, value)) - config.min) / config.step;
    }

    private static int progressToValue(SeekBarConfig config, int progress) {
        return config.min + progress * config.step;
    }
}
