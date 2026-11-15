/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * YouTube Music dark-themed bottom sheet dialog for Sonos speaker selection.
 */
public final class SonosCastBottomSheet {
    public interface VolumeCallback {
        void onVolume(int volume);
    }

    @Nullable
    private static volatile VolumeCallback volumeListener = null;

    private SonosCastBottomSheet() {}

    public static void updateVolume(int vol) {
        final VolumeCallback cb = volumeListener;
        if (cb != null) cb.onVolume(vol);
    }

    public static void show(View view) {
        show(view.getContext(), view);
    }

    public static void show(Context context, @Nullable View hostView) {
        try {
            final Dialog dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setGravity(Gravity.BOTTOM);
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                dialog.getWindow().setDimAmount(0.6f);
                dialog.getWindow().setWindowAnimations(android.R.style.Animation_InputMethod);
            }

            final FrameLayout backdrop = new FrameLayout(context);
            backdrop.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            backdrop.setOnClickListener(v -> dialog.dismiss());

            final LinearLayout sheet = new LinearLayout(context);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setOnClickListener(v -> {});

            final GradientDrawable sheetBg = new GradientDrawable();
            sheetBg.setColor(Color.parseColor("#1F1F1F"));
            final float radius = dpToPx(context, 20);
            sheetBg.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
            sheet.setBackground(sheetBg);
            sheet.setPadding(dpToPx(context, 20), dpToPx(context, 10), dpToPx(context, 20), dpToPx(context, 28));

            final FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sheetLp.gravity = Gravity.BOTTOM;
            sheet.setLayoutParams(sheetLp);
            backdrop.addView(sheet);

            // Drag handle
            final View dragHandle = new View(context);
            final GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(Color.parseColor("#717171"));
            handleBg.setCornerRadius(dpToPx(context, 2));
            dragHandle.setBackground(handleBg);
            final LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dpToPx(context, 36), dpToPx(context, 4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.topMargin = dpToPx(context, 2);
            handleLp.bottomMargin = dpToPx(context, 14);
            dragHandle.setLayoutParams(handleLp);
            sheet.addView(dragHandle);

            // Header
            final TextView titleView = new TextView(context);
            titleView.setText("Connect to a device");
            titleView.setTextSize(19f);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(Color.WHITE);
            sheet.addView(titleView);

            final TextView statusView = new TextView(context);
            final SonosDevice activeDev = SonosCastManager.getActiveDevice();
            statusView.setText(activeDev != null ? "Casting to " + activeDev.getName() : "Searching for speakers on your Wi-Fi...");
            statusView.setTextSize(13f);
            statusView.setTextColor(Color.parseColor("#AAAAAA"));
            statusView.setPadding(0, dpToPx(context, 2), 0, dpToPx(context, 12));
            sheet.addView(statusView);

            // Active device card
            if (activeDev != null) {
                final LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dpToPx(context, 14), dpToPx(context, 14), dpToPx(context, 14), dpToPx(context, 14));
                final GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(Color.parseColor("#282828"));
                cardBg.setCornerRadius(dpToPx(context, 12));
                cardBg.setStroke(dpToPx(context, 1), Color.parseColor("#383838"));
                card.setBackground(cardBg);

                final LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = dpToPx(context, 14);
                card.setLayoutParams(cardLp);

                final LinearLayout topRow = new LinearLayout(context);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                final TextView iconBadge = new TextView(context);
                iconBadge.setText("\uD83D\uDD0A");
                iconBadge.setTextSize(16f);
                iconBadge.setGravity(Gravity.CENTER);
                final GradientDrawable iconBg = new GradientDrawable();
                iconBg.setColor(Color.parseColor("#333333"));
                iconBg.setCornerRadius(dpToPx(context, 18));
                iconBadge.setBackground(iconBg);
                final LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(context, 36), dpToPx(context, 36));
                iconLp.rightMargin = dpToPx(context, 12);
                iconBadge.setLayoutParams(iconLp);
                topRow.addView(iconBadge);

                final LinearLayout infoCol = new LinearLayout(context);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                final TextView devName = new TextView(context);
                devName.setText(activeDev.getName());
                devName.setTextSize(15f);
                devName.setTypeface(null, Typeface.BOLD);
                devName.setTextColor(Color.WHITE);

                final TextView devSub = new TextView(context);
                devSub.setText("Connected \u2022 Lossless stream");
                devSub.setTextSize(12f);
                devSub.setTextColor(Color.parseColor("#3EA6FF"));
                infoCol.addView(devName);
                infoCol.addView(devSub);
                topRow.addView(infoCol);

                final TextView disconnectBtn = new TextView(context);
                disconnectBtn.setText("Disconnect");
                disconnectBtn.setTextSize(12f);
                disconnectBtn.setTypeface(null, Typeface.BOLD);
                disconnectBtn.setTextColor(Color.parseColor("#FF4E45"));
                disconnectBtn.setPadding(dpToPx(context, 12), dpToPx(context, 6), dpToPx(context, 12), dpToPx(context, 6));
                final GradientDrawable discBg = new GradientDrawable();
                discBg.setColor(Color.parseColor("#383838"));
                discBg.setCornerRadius(dpToPx(context, 14));
                disconnectBtn.setBackground(discBg);
                disconnectBtn.setOnClickListener(v -> {
                    SonosCastManager.disconnect();
                    dialog.dismiss();
                });
                topRow.addView(disconnectBtn);
                card.addView(topRow);

                final LinearLayout volRow = new LinearLayout(context);
                volRow.setOrientation(LinearLayout.HORIZONTAL);
                volRow.setGravity(Gravity.CENTER_VERTICAL);
                volRow.setPadding(0, dpToPx(context, 10), 0, 0);

                final TextView volLabel = new TextView(context);
                volLabel.setText("\uD83D\uDD08");
                volLabel.setTextSize(14f);
                volLabel.setPadding(0, 0, dpToPx(context, 6), 0);
                volRow.addView(volLabel);

                final TextView volPercent = new TextView(context);
                volPercent.setText(activeDev.getVolume() + "%");
                volPercent.setTextSize(12f);
                volPercent.setTypeface(null, Typeface.BOLD);
                volPercent.setTextColor(Color.WHITE);
                volPercent.setPadding(dpToPx(context, 8), 0, 0, 0);

                final SeekBar seekBar = new SeekBar(context);
                seekBar.setMax(100);
                seekBar.setProgress(activeDev.getVolume());
                seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                        if (fromUser) {
                            SonosCastManager.setVolume(progress);
                            volPercent.setText(progress + "%");
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {}
                });
                volRow.addView(seekBar);
                volRow.addView(volPercent);
                card.addView(volRow);

                final Handler mainHandler = new Handler(Looper.getMainLooper());
                volumeListener = vol -> mainHandler.post(() -> {
                    seekBar.setProgress(vol);
                    volPercent.setText(vol + "%");
                });

                sheet.addView(card);
            }

            // Available Speakers section
            final TextView sectionHeader = new TextView(context);
            sectionHeader.setText("SPEAKERS ON WI-FI");
            sectionHeader.setTextSize(11f);
            sectionHeader.setTypeface(null, Typeface.BOLD);
            sectionHeader.setTextColor(Color.parseColor("#8E8E93"));
            sectionHeader.setPadding(dpToPx(context, 2), 0, 0, dpToPx(context, 6));
            sheet.addView(sectionHeader);

            final ListView deviceListView = new ListView(context);
            deviceListView.setDivider(null);
            deviceListView.setDividerHeight(dpToPx(context, 8));
            deviceListView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(context, 120)));
            sheet.addView(deviceListView);

            final List<SonosDevice> devices = new ArrayList<>(SonosDiscovery.getDevices());
            final ArrayAdapter<SonosDevice> adapter = new ArrayAdapter<SonosDevice>(context, 0, devices) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    final SonosDevice item = getItem(position);
                    if (item == null) return new View(context);

                    final SonosDevice currentActive = SonosCastManager.getActiveDevice();
                    final boolean isCurrent = (currentActive != null && currentActive.getUdn().equals(item.getUdn()));

                    final LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dpToPx(context, 14), dpToPx(context, 10), dpToPx(context, 14), dpToPx(context, 10));

                    final GradientDrawable rowBg = new GradientDrawable();
                    rowBg.setColor(Color.parseColor(isCurrent ? "#2C2C2E" : "#242426"));
                    rowBg.setCornerRadius(dpToPx(context, 10));
                    if (isCurrent) {
                        rowBg.setStroke(dpToPx(context, 1), Color.parseColor("#3EA6FF"));
                    }
                    row.setBackground(rowBg);

                    final TextView icon = new TextView(context);
                    icon.setText("\uD83D\uDD0A");
                    icon.setTextSize(15f);
                    icon.setGravity(Gravity.CENTER);
                    final GradientDrawable iconBg = new GradientDrawable();
                    iconBg.setColor(Color.parseColor("#2F2F32"));
                    iconBg.setCornerRadius(dpToPx(context, 16));
                    icon.setBackground(iconBg);
                    final LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(context, 32), dpToPx(context, 32));
                    iconLp.rightMargin = dpToPx(context, 12);
                    icon.setLayoutParams(iconLp);
                    row.addView(icon);

                    final LinearLayout textCol = new LinearLayout(context);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    final TextView nameView = new TextView(context);
                    nameView.setText(item.getName());
                    nameView.setTextSize(14f);
                    nameView.setTypeface(null, Typeface.BOLD);
                    nameView.setTextColor(Color.WHITE);

                    final TextView ipView = new TextView(context);
                    ipView.setText("Sonos speaker \u2022 " + item.getIp());
                    ipView.setTextSize(12f);
                    ipView.setTextColor(Color.parseColor("#8E8E93"));

                    textCol.addView(nameView);
                    textCol.addView(ipView);
                    row.addView(textCol);

                    if (isCurrent) {
                        final TextView badge = new TextView(context);
                        badge.setText("ACTIVE");
                        badge.setTextSize(11f);
                        badge.setTypeface(null, Typeface.BOLD);
                        badge.setTextColor(Color.parseColor("#3EA6FF"));
                        row.addView(badge);
                    } else {
                        final TextView arrow = new TextView(context);
                        arrow.setText("\u2794");
                        arrow.setTextSize(14f);
                        arrow.setTextColor(Color.parseColor("#555555"));
                        row.addView(arrow);
                    }

                    row.setOnClickListener(v -> {
                        SonosCastManager.connectToDevice(item);
                        dialog.dismiss();
                    });

                    return row;
                }
            };
            deviceListView.setAdapter(adapter);

            // Native Google Cast Row
            final LinearLayout googleCastRow = new LinearLayout(context);
            googleCastRow.setOrientation(LinearLayout.HORIZONTAL);
            googleCastRow.setGravity(Gravity.CENTER_VERTICAL);
            googleCastRow.setPadding(dpToPx(context, 14), dpToPx(context, 10), dpToPx(context, 14), dpToPx(context, 10));
            final GradientDrawable gcastBg = new GradientDrawable();
            gcastBg.setColor(Color.parseColor("#242426"));
            gcastBg.setCornerRadius(dpToPx(context, 10));
            googleCastRow.setBackground(gcastBg);
            final LinearLayout.LayoutParams gcastLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gcastLp.topMargin = dpToPx(context, 10);
            googleCastRow.setLayoutParams(gcastLp);

            final TextView gcastIcon = new TextView(context);
            gcastIcon.setText("\u23DA");
            gcastIcon.setTextSize(16f);
            gcastIcon.setGravity(Gravity.CENTER);
            gcastIcon.setTextColor(Color.WHITE);
            final GradientDrawable giconBg = new GradientDrawable();
            giconBg.setColor(Color.parseColor("#2F2F32"));
            giconBg.setCornerRadius(dpToPx(context, 16));
            gcastIcon.setBackground(giconBg);
            final LinearLayout.LayoutParams giconLp = new LinearLayout.LayoutParams(dpToPx(context, 32), dpToPx(context, 32));
            giconLp.rightMargin = dpToPx(context, 12);
            gcastIcon.setLayoutParams(giconLp);
            googleCastRow.addView(gcastIcon);

            final LinearLayout gcastTextCol = new LinearLayout(context);
            gcastTextCol.setOrientation(LinearLayout.VERTICAL);
            gcastTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView gcastTitle = new TextView(context);
            gcastTitle.setText("Google Cast");
            gcastTitle.setTextSize(14f);
            gcastTitle.setTypeface(null, Typeface.BOLD);
            gcastTitle.setTextColor(Color.WHITE);

            final TextView gcastSub = new TextView(context);
            gcastSub.setText("Chromecast, Nest Hub & Google TV");
            gcastSub.setTextSize(12f);
            gcastSub.setTextColor(Color.parseColor("#8E8E93"));

            gcastTextCol.addView(gcastTitle);
            gcastTextCol.addView(gcastSub);
            googleCastRow.addView(gcastTextCol);

            final TextView gcastArrow = new TextView(context);
            gcastArrow.setText("\u2794");
            gcastArrow.setTextSize(14f);
            gcastArrow.setTextColor(Color.parseColor("#3EA6FF"));
            googleCastRow.addView(gcastArrow);

            googleCastRow.setOnClickListener(v -> {
                dialog.dismiss();
                if (hostView != null) {
                    SonosCastManager.launchGoogleCast(hostView);
                } else {
                    SonosCastManager.launchGoogleCast(context);
                }
            });
            sheet.addView(googleCastRow);

            dialog.setContentView(backdrop);
            dialog.show();

            final SonosDiscovery.DiscoveryListener discoveryListener = list -> sheet.post(() -> {
                devices.clear();
                devices.addAll(list);
                adapter.notifyDataSetChanged();
                final int visibleItems = Math.max(1, Math.min(4, devices.size()));
                deviceListView.getLayoutParams().height = visibleItems * dpToPx(context, 60);
                deviceListView.requestLayout();
                if (!devices.isEmpty() && !SonosCastManager.isConnected()) {
                    statusView.setText("Found " + devices.size() + " speaker(s) on Wi-Fi");
                }
            });

            SonosDiscovery.startDiscovery();
            SonosDiscovery.addListener(discoveryListener);

            dialog.setOnDismissListener(d -> {
                volumeListener = null;
                SonosDiscovery.removeListener(discoveryListener);
                SonosDiscovery.stopDiscovery();
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Error showing SonosCastBottomSheet", ex);
        }
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
