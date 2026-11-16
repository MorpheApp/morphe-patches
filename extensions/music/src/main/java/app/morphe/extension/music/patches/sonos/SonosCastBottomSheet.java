/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.ui.Dim.dp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * Bottom sheet that lists the speakers, and controls the connected one.
 * <p>
 * YouTube Music is always dark, so the colors are fixed.
 */
final class SonosCastBottomSheet {

    private static final int SHEET_COLOR = 0xFF212121;
    private static final int ROW_COLOR = 0xFF2C2C2C;
    private static final int ICON_BACKGROUND_COLOR = 0xFF3A3A3A;
    private static final int PRIMARY_TEXT_COLOR = Color.WHITE;
    private static final int SECONDARY_TEXT_COLOR = 0xFFAAAAAA;
    private static final int ACCENT_COLOR = 0xFF3EA6FF;
    private static final int DISCONNECT_COLOR = 0xFFFF4E45;
    private static final int SLIDE_ANIMATION_MILLIS = 200;
    private static final int MAX_LIST_HEIGHT_PERCENT = 40;
    private static final int MAX_VOLUME = 100;

    private static final String ICON_SPEAKER = "yt_outline_speaker_vd_theme_24";
    private static final String ICON_VOLUME = "yt_outline_volume_on_vd_theme_24";
    private static final String ICON_CAST = "yt_outline_chromecast_vd_theme_24";
    private static final String ICON_CHEVRON = "yt_outline_chevron_right_vd_theme_24";

    /**
     * Android 17, where apps that target it need a permission to reach devices on the local network.
     */
    private static final int ANDROID_17 = 37;
    private static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int LOCAL_NETWORK_REQUEST_CODE = 0x534F;

    private final Context context;
    private final View castButton;
    private final SheetBottomDialog.SlideDialog dialog;
    private final TextView statusView;
    private final LinearLayout deviceList;
    @Nullable
    private SeekBar volumeSeekBar;
    @Nullable
    private TextView volumeLabel;
    private boolean awaitingPermission;

    static void show(View castButton) {
        new SonosCastBottomSheet(castButton).open();
    }

    private SonosCastBottomSheet(View castButton) {
        this.castButton = castButton;
        context = castButton.getContext();

        LinearLayout sheet = SheetBottomDialog.createMainLayout(context, SHEET_COLOR);
        sheet.setPadding(dp(20), 0, dp(20), dp(24));
        dialog = SheetBottomDialog.createSlideDialog(context, sheet, SLIDE_ANIMATION_MILLIS);
        dialog.setOnDismissListener(d -> {
            SonosDiscovery.setListener(null);
            SonosDiscovery.stopSearch();
            SonosCastManager.setVolumeListener(null);
        });

        sheet.addView(text(str("morphe_music_sonos_cast_sheet_title"), 19, PRIMARY_TEXT_COLOR, true),
                withMargins(dp(16), 0));
        statusView = text("", 13, SECONDARY_TEXT_COLOR, false);
        sheet.addView(statusView, withMargins(dp(2), dp(12)));

        SonosDevice activeDevice = SonosCastManager.getActiveDevice();
        if (activeDevice != null) {
            sheet.addView(activeDeviceCard(activeDevice), withMargins(0, dp(16)));
        }

        TextView header = text(str("morphe_music_sonos_cast_speakers_header"), 12, SECONDARY_TEXT_COLOR, true);
        header.setAllCaps(true);
        sheet.addView(header, withMargins(0, dp(8)));

        deviceList = new LinearLayout(context);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = SheetBottomDialog.createCappedScrollView(context, MAX_LIST_HEIGHT_PERCENT);
        scrollView.addView(deviceList);
        sheet.addView(scrollView, matchWidth());

        View googleCastRow = row(ICON_CAST, str("morphe_music_sonos_cast_google_cast"),
                str("morphe_music_sonos_cast_google_cast_summary"), chevron());
        googleCastRow.setOnClickListener(v -> {
            dialog.dismiss();
            SonosCastManager.disconnect();
            castButton.post(() -> SonosCastPatch.openCastDialog(castButton));
        });
        sheet.addView(googleCastRow, withMargins(dp(8), 0));
    }

    private void open() {
        showDevices(SonosDiscovery.getDevices());
        SonosDiscovery.setListener(devices -> Utils.runOnMainThread(() -> showDevices(devices)));
        SonosCastManager.setVolumeListener(this::showVolume);

        awaitingPermission = requestLocalNetworkPermission();
        if (awaitingPermission) {
            // Search again once the permission dialog closes.
            dialog.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(
                    new ViewTreeObserver.OnWindowFocusChangeListener() {
                        @Override
                        public void onWindowFocusChanged(boolean hasFocus) {
                            if (hasFocus && awaitingPermission) {
                                awaitingPermission = false;
                                SonosDiscovery.startSearch();
                            }
                        }
                    });
        }

        SonosDiscovery.startSearch();
        dialog.show();
    }

    /**
     * @return If the permission was requested.
     */
    private boolean requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < ANDROID_17
                || context.getApplicationInfo().targetSdkVersion < ANDROID_17
                || context.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        Activity activity = Utils.getActivity();
        if (activity == null) {
            Logger.printException(() -> "No activity to request the local network permission");
            return false;
        }
        activity.requestPermissions(new String[]{ACCESS_LOCAL_NETWORK}, LOCAL_NETWORK_REQUEST_CODE);
        return true;
    }

    private void showDevices(List<SonosDevice> devices) {
        SonosDevice activeDevice = SonosCastManager.getActiveDevice();
        if (activeDevice != null) {
            statusView.setText(str("morphe_music_sonos_cast_casting_to", activeDevice.name));
        } else {
            statusView.setText(str(devices.isEmpty()
                    ? "morphe_music_sonos_cast_searching"
                    : "morphe_music_sonos_cast_choose_speaker"));
        }

        deviceList.removeAllViews();
        for (SonosDevice device : devices) {
            final boolean isActive = activeDevice != null && activeDevice.udn.equals(device.udn);
            String subtitle = device.modelName.isEmpty()
                    ? device.ip
                    : device.modelName + " · " + device.ip;
            View trailing = isActive
                    ? text(str("morphe_music_sonos_cast_active"), 12, ACCENT_COLOR, true)
                    : chevron();
            View row = row(ICON_SPEAKER, device.name, subtitle, trailing);
            if (isActive) {
                ((GradientDrawable) row.getBackground()).setStroke(dp(1), ACCENT_COLOR);
            } else {
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    SonosCastManager.connect(device);
                });
            }
            deviceList.addView(row, withMargins(0, dp(8)));
        }
    }

    private View activeDeviceCard(SonosDevice device) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBackground(ROW_COLOR, 12));
        card.setPadding(dp(14), dp(14), dp(14), dp(10));

        TextView disconnect = text(str("morphe_music_sonos_cast_disconnect"), 13, DISCONNECT_COLOR, true);
        disconnect.setBackground(roundedBackground(ICON_BACKGROUND_COLOR, 14));
        disconnect.setPadding(dp(12), dp(6), dp(12), dp(6));
        disconnect.setOnClickListener(v -> {
            dialog.dismiss();
            SonosCastManager.disconnect();
        });

        card.addView(rowContent(ICON_SPEAKER, device.name, str("morphe_music_sonos_cast_connected"),
                ACCENT_COLOR, disconnect), matchWidth());

        LinearLayout volumeRow = new LinearLayout(context);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.addView(icon(ICON_VOLUME, SECONDARY_TEXT_COLOR), new LinearLayout.LayoutParams(dp(20), dp(20)));

        final int volume = Math.max(0, SonosCastManager.getSpeakerVolume());
        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(MAX_VOLUME);
        seekBar.setProgress(volume);
        seekBar.setProgressTintList(ColorStateList.valueOf(PRIMARY_TEXT_COLOR));
        seekBar.setThumbTintList(ColorStateList.valueOf(PRIMARY_TEXT_COLOR));
        volumeRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView label = text(volumeText(volume), 12, PRIMARY_TEXT_COLOR, true);
        label.setMinWidth(dp(36));
        label.setGravity(Gravity.END);
        volumeRow.addView(label);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    label.setText(volumeText(progress));
                    SonosCastManager.setSpeakerVolume(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        volumeSeekBar = seekBar;
        volumeLabel = label;
        card.addView(volumeRow, withMargins(dp(10), 0));
        return card;
    }

    private void showVolume(int volume) {
        if (volumeSeekBar != null && volumeLabel != null) {
            volumeSeekBar.setProgress(volume);
            volumeLabel.setText(volumeText(volume));
        }
    }

    private static String volumeText(int volume) {
        return volume + "%";
    }

    private View row(String iconName, String title, String subtitle, View trailing) {
        LinearLayout row = rowContent(iconName, title, subtitle, SECONDARY_TEXT_COLOR, trailing);
        row.setBackground(roundedBackground(ROW_COLOR, 10));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        return row;
    }

    private LinearLayout rowContent(String iconName, String title, String subtitle, int subtitleColor, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconCircle = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ICON_BACKGROUND_COLOR);
        iconCircle.setBackground(circle);
        iconCircle.addView(icon(iconName, PRIMARY_TEXT_COLOR), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        row.addView(iconCircle, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, PRIMARY_TEXT_COLOR, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        TextView subtitleView = text(subtitle, 12, subtitleColor, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitleView);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textsParams.setMarginStart(dp(12));
        textsParams.setMarginEnd(dp(8));
        row.addView(texts, textsParams);

        row.addView(trailing);
        return row;
    }

    private View chevron() {
        View chevron = icon(ICON_CHEVRON, SECONDARY_TEXT_COLOR);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        return chevron;
    }

    private ImageView icon(String drawableName, int color) {
        Drawable drawable = ResourceUtils.getDrawableOrThrow(drawableName).mutate();
        drawable.setTint(color);
        ImageView view = new ImageView(context);
        view.setImageDrawable(drawable);
        return view;
    }

    private TextView text(String text, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams withMargins(int top, int bottom) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }
}
