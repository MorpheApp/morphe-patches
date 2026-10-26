/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestream;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * In-player danmaku settings, opened from the danmaku player button
 * (see DanmakuButton in the videoplayer package), so the main values
 * can be adjusted while watching a live stream instead of leaving
 * for the app settings. Every change is written to the same settings the
 * Morphe settings screen edits, and takes effect from the next
 * comment that is spawned.
 */
public final class DanmakuSettingsDialog {

    private DanmakuSettingsDialog() {
    }

    public static void show(Context context) {
        try {
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(context, 20);
            layout.setPadding(pad, pad / 2, pad, 0);

            CheckBox enabled = new CheckBox(context);
            enabled.setText(str("morphe_live_chat_danmaku_title"));
            enabled.setChecked(Settings.LIVE_CHAT_DANMAKU.get());
            enabled.setOnCheckedChangeListener(
                    (button, checked) -> Settings.LIVE_CHAT_DANMAKU.save(checked));
            layout.addView(enabled);

            addSeekBarRow(context, layout,
                    "morphe_live_chat_danmaku_crossing_seconds_title",
                    DanmakuLaneAllocator.clampCrossingSeconds(
                            Settings.LIVE_CHAT_DANMAKU_CROSSING_SECONDS.get()),
                    4, 20, "s",
                    value -> Settings.LIVE_CHAT_DANMAKU_CROSSING_SECONDS.save(value));
            addSeekBarRow(context, layout,
                    "morphe_live_chat_danmaku_opacity_title",
                    DanmakuLaneAllocator.clampOpacityPercent(Settings.LIVE_CHAT_DANMAKU_OPACITY.get()),
                    20, 100, "%",
                    value -> Settings.LIVE_CHAT_DANMAKU_OPACITY.save(value));
            addSeekBarRow(context, layout,
                    "morphe_live_chat_danmaku_max_rows_title",
                    DanmakuLaneAllocator.clampRows(Settings.LIVE_CHAT_DANMAKU_MAX_ROWS.get()),
                    3, 30, "",
                    value -> Settings.LIVE_CHAT_DANMAKU_MAX_ROWS.save(value));

            new AlertDialog.Builder(context)
                    .setTitle(str("morphe_live_chat_danmaku_title"))
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku settings dialog failure", ex);
        }
    }

    private static void addSeekBarRow(Context context, LinearLayout parent, String titleKey,
                                      int initialValue, int min, int max, String unit,
                                      IntConsumer onChange) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(context, 8), 0, 0);

        LinearLayout labelRow = new LinearLayout(context);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(context);
        label.setText(str(titleKey));
        labelRow.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueLabel = new TextView(context);
        valueLabel.setText(initialValue + unit);
        labelRow.addView(valueLabel);
        row.addView(labelRow);

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(max - min);
        seekBar.setProgress(initialValue - min);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = min + progress;
                valueLabel.setText(value + unit);
                if (fromUser) {
                    onChange.accept(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        row.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
