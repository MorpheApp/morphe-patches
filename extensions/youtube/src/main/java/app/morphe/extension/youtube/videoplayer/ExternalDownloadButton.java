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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

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
        launchDownloader(context, videoId, quickDownloadType, null);
    }

    /**
     * @param quickExtras See {@link ExternalDownloaderPreference#launchExternalDownloader(String, Context, String, String, Bundle)}.
     */
    private static void launchDownloader(@Nullable Context context, String videoId,
                                         @Nullable String quickDownloadType, @Nullable Bundle quickExtras) {
        if (ExternalDownloaderPreference.launchExternalDownloader(
                videoId, context, getVideoUrl(videoId), quickDownloadType, quickExtras)
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
        if (ExternalDownloaderPreference.supportsQuickDownloadQuality()) {
            showQualityMenu(context, videoId);
            return;
        }
        try {
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
                    null,
                    () -> {
                        dialog.dismiss();
                        launchDownloader(context, videoId, "video");
                    }));
            mainLayout.addView(createItem(context,
                    "morphe_external_downloader_quick_audio",
                    str("morphe_external_downloader_quick_audio"),
                    null,
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

    /**
     * The quick download menu of downloaders that apply the requested quality (YTindir):
     * the video's thumbnail, title and length, 4K and 2K in the better of AV1 and VP9, 1080p and 720p
     * in H.264, audio only as M4A (the AAC stream as it is) or a 192 kbps MP3, and the JPG thumbnail.
     * Frame rates, codecs and file sizes are filled in once the video's streams are read.
     */
    private static void showQualityMenu(Context context, String videoId) {
        try {
            final int foregroundColor = ThemeUtils.getAppForegroundColor();
            final int secondaryColor = withAlpha(foregroundColor, 0xA6);

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, ThemeUtils.getDialogBackgroundColor());
            mainLayout.setPadding(0, 0, 0, Dim.dp8);

            // The video: thumbnail with its length, title, and the menu's name with the channel.
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp8);

            FrameLayout thumbnailFrame = new FrameLayout(context);
            ImageView thumbnail = new ImageView(context);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setClipToOutline(true);
            thumbnail.setBackground(rounded(Dim.dp8, withAlpha(foregroundColor, 0x1F)));
            thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView length = new TextView(context);
            length.setTextColor(Color.WHITE);
            length.setTextSize(11);
            length.setTypeface(Typeface.DEFAULT_BOLD);
            length.setBackground(rounded(Dim.dp4, 0xCC000000));
            length.setPadding(Dim.dp4, 0, Dim.dp4, 0);
            length.setVisibility(View.GONE);
            FrameLayout.LayoutParams lengthParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            lengthParams.setMargins(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
            thumbnailFrame.addView(length, lengthParams);
            header.addView(thumbnailFrame, new LinearLayout.LayoutParams(Dim.dp(96), Dim.dp(54)));

            LinearLayout headerTexts = new LinearLayout(context);
            headerTexts.setOrientation(LinearLayout.VERTICAL);
            headerTexts.setPadding(Dim.dp12, 0, 0, 0);
            TextView title = new TextView(context);
            title.setText(str("morphe_external_downloader_quick_menu_header"));
            title.setTextColor(foregroundColor);
            title.setTextSize(15);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            headerTexts.addView(title);
            TextView subtitle = new TextView(context);
            subtitle.setText(str("morphe_external_downloader_quick_menu_header"));
            subtitle.setTextColor(secondaryColor);
            subtitle.setTextSize(13);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setVisibility(View.GONE);
            headerTexts.addView(subtitle);
            header.addView(headerTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            mainLayout.addView(header);

            SheetBottomDialog.SlideDialog dialog =
                    SheetBottomDialog.createSlideDialog(context, mainLayout, LegacyPlayerControlButton.fadeInDuration);

            // Video qualities.
            final int count = QuickDownloadFormats.HEIGHTS.length;
            QualityRow[] videoRows = new QualityRow[count];
            for (int i = 0; i < count; i++) {
                final int height = QuickDownloadFormats.HEIGHTS[i];
                final boolean h264 = height <= 1080;
                Bundle extras = new Bundle();
                extras.putInt("VIDEO_QUALITY", height);
                extras.putString("VIDEO_CODEC", h264 ? "h264" : "av1");
                QualityRow row = createQualityRow(context, height + "p", qualityBadge(height),
                        (h264 ? "H.264" : "AV1 / VP9") + " · " + str(qualityHint(height)),
                        "…", () -> {
                            dialog.dismiss();
                            launchDownloader(context, videoId, "video", extras);
                        });
                videoRows[i] = row;
                mainLayout.addView(row.view);
            }

            addDivider(context, mainLayout);

            // Audio only.
            QualityRow m4aRow = createQualityRow(context, "M4A", "≈" + QUICK_AAC_KBPS + " kbps",
                    "AAC · " + str("morphe_external_downloader_quick_source_audio"), "…",
                    audioLauncher(dialog, context, videoId, "m4a"));
            mainLayout.addView(m4aRow.view);
            QualityRow mp3Row = createQualityRow(context, "MP3", QuickDownloadFormats.MP3_KBPS + " kbps",
                    "Opus → MP3 · " + str("morphe_external_downloader_quick_recommended"), "…",
                    audioLauncher(dialog, context, videoId, "mp3"));
            mainLayout.addView(mp3Row.view);

            // Thumbnail.
            QualityRow thumbnailRow = createQualityRow(context,
                    str("morphe_external_downloader_quick_thumbnail"), "JPG",
                    str("morphe_external_downloader_quick_thumbnail_summary"),
                    "≈" + Formatter.formatShortFileSize(context, QUICK_THUMBNAIL_BYTES),
                    () -> {
                        dialog.dismiss();
                        launchDownloader(context, videoId, "thumbnail", null);
                    });
            mainLayout.addView(thumbnailRow.view);

            addDivider(context, mainLayout);

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

            Utils.runOnBackgroundThread(() -> {
                Bitmap bitmap = fetchBitmap("https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
                if (bitmap != null) Utils.runOnMainThread(() -> thumbnail.setImageBitmap(bitmap));
            });
            Utils.runOnBackgroundThread(() -> {
                final long thumbnailBytes = QuickDownloadFormats.fetchThumbnailSize(videoId);
                // The real size, without the approximate sign.
                if (thumbnailBytes > 0) Utils.runOnMainThread(() -> thumbnailRow.size.setText(
                        Formatter.formatShortFileSize(context, thumbnailBytes)));
            });
            Utils.runOnBackgroundThread(() -> {
                QuickDownloadFormats.Sizes sizes = QuickDownloadFormats.fetch(videoId);
                Utils.runOnMainThread(() -> {
                    if (sizes == null) {
                        // Sizes unknown, every choice stays.
                        for (QualityRow row : videoRows) row.size.setText("");
                        m4aRow.size.setText("");
                        mp3Row.size.setText("");
                        return;
                    }
                    if (!TextUtils.isEmpty(sizes.title())) {
                        title.setText(sizes.title());
                        String name = str("morphe_external_downloader_quick_menu_header");
                        subtitle.setText(TextUtils.isEmpty(sizes.author()) ? name : name + " · " + sizes.author());
                        subtitle.setVisibility(View.VISIBLE);
                    }
                    if (sizes.lengthSeconds() > 0) {
                        length.setText(formatLength(sizes.lengthSeconds()));
                        length.setVisibility(View.VISIBLE);
                    }
                    for (int i = 0; i < count; i++) {
                        final long bytes = sizes.videos()[i];
                        QualityRow row = videoRows[i];
                        if (bytes == QuickDownloadFormats.UNAVAILABLE) {
                            row.view.setVisibility(View.GONE);
                            continue;
                        }
                        final int height = QuickDownloadFormats.HEIGHTS[i];
                        row.title.setText(qualityTitle(height, sizes.fps()[i], secondaryColor));
                        if (sizes.codecs()[i] != null) {
                            row.detail.setText(sizes.codecs()[i] + " · " + str(qualityHint(height)));
                        }
                        row.size.setText(formatSize(context, bytes));
                    }
                    // Read from the stream: the real bitrate, without the approximate sign.
                    if (sizes.aacKbps() > 0) m4aRow.badge.setText(sizes.aacKbps() + " kbps");
                    m4aRow.size.setText(formatSize(context, sizes.aac()));
                    // A constant bitrate MP3 of the stream's length.
                    mp3Row.size.setText(formatSize(context, sizes.mp3()));
                });
            });
        } catch (Exception ex) {
            Logger.printException(() -> "showQualityMenu failure", ex);
        }
    }

    /** YouTube's AAC audio (itag 140), shown until the stream is read. */
    private static final int QUICK_AAC_KBPS = 128;
    /** A maxresdefault JPG is usually 100-300 KB. */
    private static final long QUICK_THUMBNAIL_BYTES = 200L * 1024;

    private static Runnable audioLauncher(SheetBottomDialog.SlideDialog dialog, Context context,
                                          String videoId, String audioFormat) {
        return () -> {
            dialog.dismiss();
            Bundle extras = new Bundle();
            extras.putString("AUDIO_FORMAT", audioFormat);
            launchDownloader(context, videoId, "audio", extras);
        };
    }

    @Nullable
    private static String qualityBadge(int height) {
        if (height >= 2160) return "4K";
        if (height >= 1440) return "2K";
        if (height >= 1080) return "FHD";
        if (height >= 720) return "HD";
        return null;
    }

    /** What a quality is good for, next to Full HD, the recommended one. */
    private static String qualityHint(int height) {
        if (height >= 2160) return "morphe_external_downloader_quick_best_quality";
        if (height >= 1440) return "morphe_external_downloader_quick_better_quality";
        if (height >= 1080) return "morphe_external_downloader_quick_recommended";
        return "morphe_external_downloader_quick_smaller_file";
    }

    /** "1080p" with the frame rate after it, smaller and lighter: "1080p 60fps". */
    private static CharSequence qualityTitle(int height, int fps, int secondaryColor) {
        if (fps <= 0) return height + "p";
        String fpsText = " " + fps + "fps";
        SpannableString text = new SpannableString(height + "p" + fpsText);
        final int start = text.length() - fpsText.length();
        text.setSpan(new RelativeSizeSpan(0.75f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(secondaryColor), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static String formatLength(long seconds) {
        final long hours = seconds / 3600;
        final long minutes = (seconds % 3600) / 60;
        final long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    private static String formatSize(Context context, long bytes) {
        return bytes > 0 ? Formatter.formatShortFileSize(context, bytes) : "";
    }

    @Nullable
    private static Bitmap fetchBitmap(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not load the quick download thumbnail", ex);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static GradientDrawable rounded(int radius, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radius);
        shape.setColor(color);
        return shape;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static void addDivider(Context context, LinearLayout layout) {
        View divider = new View(context);
        divider.setBackgroundColor(withAlpha(ThemeUtils.getAppForegroundColor(), 0x1F));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Dim.dp1));
        dividerParams.setMargins(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp4);
        layout.addView(divider, dividerParams);
    }

    /** The views of a row that are filled in once the streams are read. */
    private record QualityRow(View view, TextView title, TextView badge, TextView detail, TextView size) {
    }

    /**
     * A row like the quality list of YouTube's own download menu:
     * quality and badge over the codec, the file size at the end.
     */
    private static QualityRow createQualityRow(Context context, String title, @Nullable String badge,
                                               String detail, String size, Runnable onClick) {
        final int foregroundColor = ThemeUtils.getAppForegroundColor();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48 + Dim.dp4);
        row.setPadding(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp4);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(withAlpha(foregroundColor, 0x33)),
                null,
                new ColorDrawable(0xFFFFFFFF)));
        row.setOnClickListener(v -> onClick.run());

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleLine = new LinearLayout(context);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(foregroundColor);
        titleView.setTextSize(16);
        titleView.setSingleLine(true);
        titleLine.addView(titleView);
        TextView badgeView = new TextView(context);
        badgeView.setTextColor(foregroundColor);
        badgeView.setTextSize(10);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setSingleLine(true);
        badgeView.setBackground(rounded(Dim.dp4, withAlpha(foregroundColor, 0x1F)));
        badgeView.setPadding(Dim.dp4 + Dim.dp1, Dim.dp1, Dim.dp4 + Dim.dp1, Dim.dp1);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.setMarginStart(Dim.dp8);
        titleLine.addView(badgeView, badgeParams);
        if (badge != null) {
            badgeView.setText(badge);
        } else {
            badgeView.setVisibility(View.GONE);
        }
        texts.addView(titleLine);

        TextView detailView = new TextView(context);
        detailView.setText(detail);
        detailView.setTextColor(withAlpha(foregroundColor, 0xA6));
        detailView.setTextSize(13);
        detailView.setSingleLine(true);
        detailView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(detailView);

        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView sizeView = new TextView(context);
        sizeView.setTextColor(foregroundColor);
        sizeView.setTextSize(14);
        sizeView.setSingleLine(true);
        sizeView.setText(size);
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sizeParams.setMarginStart(Dim.dp12);
        row.addView(sizeView, sizeParams);

        return new QualityRow(row, titleView, badgeView, detailView, sizeView);
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
