/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.MarkWatchedRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.ButtonVisibility;

@SuppressWarnings("unused")
public class ExternalDownloadButton {

    static {
        if (isEnabled()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_external_download_button",
                    null,
                    "morphe_yt_download_button",
                    () -> isEnabled()
                            ? ButtonVisibility.ENABLED
                            : ButtonVisibility.DISABLED,
                    ExternalDownloadButton::onDownloadClick,
                    ExternalDownloadButton::onDownloadLongClick
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static boolean isEnabled() {
        return Settings.EXTERNAL_DOWNLOADER_OVERLAY_BUTTON.get();
    }

    private static String getVideoUrl(String videoId) {
        return "https://youtu.be/" + videoId;
    }

    /**
     * @return If a tap on a download button opens the quick download menu.
     */
    public static boolean quickMenuOnTap() {
        return Settings.EXTERNAL_DOWNLOADER_QUICK_MENU.get()
                && ExternalDownloaderPreference.supportsQuickDownload();
    }

    /**
     * Opens the external downloader, and marks the video as watched if that is on.
     * The video of the open player is not marked, the app adds it to the watch history itself.
     *
     * @param quickDownloadType See {@link ExternalDownloaderPreference#launchExternalDownloader(String, Context, String, String)}.
     */
    public static void launchDownloader(@Nullable Context context, String videoId,
                                        @Nullable String quickDownloadType) {
        if (ExternalDownloaderPreference.launchExternalDownloader(
                videoId, context, getVideoUrl(videoId), quickDownloadType)
                && Settings.EXTERNAL_DOWNLOADER_MARK_WATCHED.get()
                && !isOpenInPlayer(videoId)) {
            MarkWatchedRequest.markWatched(videoId);
        }
    }

    private static boolean isOpenInPlayer(String videoId) {
        return !PlayerType.getCurrent().isNoneOrHidden()
                && videoId.equals(VideoInformation.getVideoId());
    }

    private static void openDownloader(Context context) {
        launchDownloader(context, VideoInformation.getVideoId(), null);
    }

    private static void onDownloadClick(View view) {
        if (quickMenuOnTap()) {
            showQuickDownloadMenu(view.getContext(), VideoInformation.getVideoId());
        } else {
            openDownloader(view.getContext());
        }
    }

    /**
     * Tap and hold does what a tap does not:
     * the quick download menu, or all options of the downloader if the menu opens on tap.
     */
    private static boolean onDownloadLongClick(View view) {
        if (!ExternalDownloaderPreference.supportsQuickDownload()) {
            return false;
        }
        if (quickMenuOnTap()) {
            openDownloader(view.getContext());
        } else {
            showQuickDownloadMenu(view.getContext(), VideoInformation.getVideoId());
        }
        return true;
    }

    /**
     * Shows the quick download menu for a video.
     * Also used by the YouTube download button and the feed 'Download' menu item
     * in External downloader mode.
     *
     * @param videoId The video to download. Captured by the caller, the player may move on
     *                while the menu is open, and feed menus are not for the playing video.
     */
    public static void showQuickDownloadMenu(Context context, String videoId) {
        try {
            final boolean showQuality = ExternalDownloaderPreference.supportsQuickDownloadQuality();
            final int foregroundColor = ThemeUtils.getAppForegroundColor();

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, LegacyPlayerControlButton.getDialogBackgroundColor());
            mainLayout.setPadding(0, 0, 0, Dim.dp8);

            TextView header = new TextView(context);
            header.setText(str("morphe_external_downloader_quick_menu_header"));
            header.setTextColor(foregroundColor);
            header.setTextSize(16);
            header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            header.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp8);
            mainLayout.addView(header);

            SheetBottomDialog.SlideDialog dialog =
                    SheetBottomDialog.createSlideDialog(context, mainLayout, LegacyPlayerControlButton.fadeInDuration);

            mainLayout.addView(createItem(context,
                    "morphe_external_downloader_quick_video",
                    str("morphe_external_downloader_quick_video"),
                    showQuality ? str("morphe_external_downloader_quick_video_quality") : null,
                    () -> {
                        dialog.dismiss();
                        launchDownloader(context, videoId, "video");
                    }));
            mainLayout.addView(createItem(context,
                    "morphe_external_downloader_quick_audio",
                    str("morphe_external_downloader_quick_audio"),
                    showQuality ? str("morphe_external_downloader_quick_audio_quality") : null,
                    () -> {
                        dialog.dismiss();
                        launchDownloader(context, videoId, "audio");
                    }));

            View divider = new View(context);
            divider.setBackgroundColor((foregroundColor & 0x00FFFFFF) | 0x1F000000);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Dim.dp1));
            dividerParams.setMargins(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp4);
            mainLayout.addView(divider, dividerParams);

            mainLayout.addView(createItem(context,
                    "morphe_settings_screen_05_player",
                    str("morphe_external_downloader_quick_options"),
                    null,
                    () -> {
                        dialog.dismiss();
                        launchDownloader(context, videoId, null);
                    }));

            PipDismissHelper.dismissOnPip(dialog);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showQuickDownloadMenu failure", ex);
        }
    }

    private static View createItem(Context context, String iconName, String title,
                                   @Nullable String detail, Runnable onClick) {
        final int foregroundColor = ThemeUtils.getAppForegroundColor();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48 + Dim.dp4);
        row.setPadding(Dim.dp16, 0, Dim.dp16, 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf((foregroundColor & 0x00FFFFFF) | 0x33000000),
                null,
                new ColorDrawable(0xFFFFFFFF)));
        row.setOnClickListener(v -> onClick.run());

        Drawable icon = getIcon(context, iconName);
        if (icon != null) {
            icon.setTint(foregroundColor);
            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(icon);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
            iconParams.setMarginEnd(Dim.dp24);
            row.addView(iconView, iconParams);
        }

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(foregroundColor);
        titleView.setTextSize(16);
        titleView.setSingleLine(true);
        row.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (detail != null) {
            TextView detailView = new TextView(context);
            detailView.setText(detail);
            detailView.setTextColor((foregroundColor & 0x00FFFFFF) | 0xA6000000);
            detailView.setTextSize(14);
            detailView.setSingleLine(true);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            detailParams.setMarginStart(Dim.dp12);
            row.addView(detailView, detailParams);
        }

        return row;
    }

    @Nullable
    private static Drawable getIcon(Context context, String baseName) {
        final String name = Utils.appIsUsingBoldIcons() ? baseName + "_bold" : baseName;
        try {
            final int id = ResourceUtils.getDrawableIdentifier(name);
            if (id == 0) return null;
            Drawable drawable = context.getDrawable(id);
            return drawable == null ? null : drawable.mutate();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Icon not found: " + name);
            return null;
        }
    }
}
