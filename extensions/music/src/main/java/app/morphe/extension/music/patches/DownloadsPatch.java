/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.youtube.innertube.Offline.OfflinePlaylistAction.OFFLINE_PLAYLIST_ACTION_ADD;

import android.app.Activity;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.components.PlaylistPageFilter;
import app.morphe.extension.music.patches.components.SongMenuFilter;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.BaseInAppDownloadsPatch;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;

@SuppressWarnings("unused")
public final class DownloadsPatch extends BaseInAppDownloadsPatch {

    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final int IGNORE_DOUBLE_CLICK_DURATION_MS = 1000;

    private static final byte[] EMPTY_COMMAND = new byte[0];

    private static volatile String downloadButtonLabel = "";

    /** Command field of the 'ypc/get_offline_upsell' endpoint. */
    private static final int OFFLINE_UPSELL_ENDPOINT = 133724106;
    /** Command field of the offline video endpoint, with the video id in field 1 and action in field 2. */
    private static final int OFFLINE_VIDEO_ENDPOINT = 73080600;
    private static final long OFFLINE_VIDEO_ACTION_ADD = 1;
    /** Command field of the offline playlist endpoint, with the playlist id in field 1 and action in field 2. */
    private static final int OFFLINE_PLAYLIST_ENDPOINT = 73082583;
    private static final long OFFLINE_PLAYLIST_ACTION_ADD_VALUE = 1;

    /**
     * Fields of the encoded parameters of the offline upsell of an album or a playlist, which
     * hold its id in a nested message. The parameters of a song hold its video ID instead.
     */
    private static final int UPSELL_PARAMETERS_PLAYLIST = 5;
    private static final int UPSELL_PLAYLIST_ITEM = 10;
    private static final int UPSELL_PLAYLIST_ID = 3;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /**
     * Liked music, user and editorial playlists, mixes, album playlists and album pages,
     * with or without the 'VL' prefix of a playlist page.
     */
    public static final Pattern PLAYLIST_ID = Pattern.compile(
            "(VL)?(LM|PL(?:[A-Za-z0-9_-]{11}|[A-Za-z0-9_-]{16,})"
                    + "|OLAK5uy_[A-Za-z0-9_-]{11,}|RD[A-Za-z0-9_-]{10,})|MPRE[A-Za-z0-9_-]{8,}");

    private static volatile long lastFlyoutDownloadTime;
    private static volatile long lastPlaylistDownloadTime;
    private static volatile long lastMainPlayerDownloadTime;

    /**
     * Menu Download action of accounts offered Premium, which opens the Premium sheet
     * and has no video ID.
     */
    private static final ByteArrayFilterGroup PREMIUM_UPSELL_COMMAND =
            new ByteArrayFilterGroup(null, "premium_upsell");

    /**
     * Opening the menu of an item resolves a command that holds the whole menu, so it also
     * holds the Download action of the menu without being that action.
     */
    private static final ByteArrayFilterGroup MENU_COMMAND =
            new ByteArrayFilterGroup(null, "panel_header.", "tile_button_carousel.");

    /**
     * Injection point.
     * Usually is called off the main thread, when a litho text is created and its style runs
     * are applied.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface conversionContext, CharSequence original) {
        try {
            //noinspection SizeReplaceableByIsEmpty
            if (original.length() == 0) return original;

            StringBuilder path = conversionContext.patch_getPathBuilder();
            if (isFlyoutMenuOverridden()
                    && downloadButtonLabel.isEmpty()
                    && Utils.contains(path, "music_download_button.")) {
                downloadButtonLabel = original.toString();
                Logger.printDebug(() -> "Found download button label: " + downloadButtonLabel);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    private static boolean isActionButtonOverridden() {
        return Settings.DOWNLOAD_MODE.get() != DownloadMode.DEFAULT;
    }

    /**
     * The download mode replaces the Download menu, and the external downloader
     * can replace it in the other modes too.
     */
    public static boolean isFlyoutMenuOverridden() {
        return Settings.DOWNLOAD_MODE.get() != DownloadMode.DEFAULT
                || Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    private static boolean flyoutMenuUsesExternalDownloader() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.EXTERNAL
                || Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    private static boolean useExternalDownloader(boolean insideDialog) {
        return insideDialog
                ? flyoutMenuUsesExternalDownloader()
                : Settings.DOWNLOAD_MODE.get() == DownloadMode.EXTERNAL;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static byte[] replaceOfflineUpsell(@Nullable MessageLite command, @Nullable Object response) {
        if (!isInAppDownloadMode() || Settings.EXTERNAL_DOWNLOADER_MENU.get()) {
            return null;
        }
        return BaseInAppDownloadsPatch.replaceOfflineUpsell(command, response);
    }

    /**
     * Injection point.
     * <p>
     * Replaces Premium upsell and offline commands in {@code CommandResolver.resolveCommand}
     * with native {@code OfflineCommand} messages so YouTube Music downloads and plays songs,
     * albums, and playlists directly through its built-in offline engine.
     */
    @Nullable
    public static byte[] replaceDownloadCommand(
            @Nullable MessageLite command,
            @Nullable Map<Object, Object> map
    ) {
        try {
            if (!isFlyoutMenuOverridden() || command == null) {
                return null;
            }

            byte[] commandBytes = command.toByteArray();
            if (commandBytes.length == 0) {
                return null;
            }

            final boolean insideDialog = map != null && isViewInsideDialog(map.get(ELEMENTS_SENDER_VIEW));
            final boolean premiumUpsell = PREMIUM_UPSELL_COMMAND.check(commandBytes).isFiltered()
                    && !MENU_COMMAND.check(commandBytes).isFiltered();

            // 1. Flyout menu Download tap on accounts offered Premium.
            if (insideDialog && premiumUpsell) {
                String videoId = SongMenuFilter.getMenuVideoId();
                if (videoId != null && !videoId.isEmpty()) {
                    return dispatchMenuVideoDownload(videoId);
                }

                String playlistId = PlaylistPageFilter.getMenuPlaylistId();
                if (playlistId == null || playlistId.isEmpty()) {
                    playlistId = PlaylistPageFilter.getPagePlaylistId();
                }
                return dispatchPlaylistDownload(playlistId, insideDialog);
            }

            // 2. Player action bar Download button tap.
            if (!insideDialog && isPlayerDownloadButton(map)) {
                if (!isActionButtonOverridden()) {
                    return null;
                }

                String videoId = VideoInformation.getVideoId();
                if (videoId.isEmpty()) {
                    return null;
                }

                final long now = System.currentTimeMillis();
                if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                    return EMPTY_COMMAND;
                }
                lastMainPlayerDownloadTime = now;

                return dispatchVideoDownload(videoId, !isInAppDownloadMode());
            }

            // 3. Playlist or Album page header Download button tap on accounts offered Premium.
            if (!insideDialog && premiumUpsell
                    && map != null
                    && map.get(ELEMENTS_SENDER_VIEW) instanceof View senderView
                    && !isViewInsideActionBar(senderView)) {
                return dispatchPlaylistDownload(PlaylistPageFilter.getPagePlaylistId(), false);
            }

            ProtoMessage proto = new ProtoMessage(commandBytes);

            // 4. 'ypc/get_offline_upsell' endpoint command.
            ProtoMessage upsellEndpoint = proto.messageField(OFFLINE_UPSELL_ENDPOINT);
            if (!upsellEndpoint.isEmpty()) {
                String value = upsellEndpoint.stringField(1);
                if (value.isEmpty()) {
                    return null;
                }
                if (VIDEO_ID.matcher(value).matches()) {
                    return insideDialog
                            ? dispatchMenuVideoDownload(value)
                            : dispatchVideoDownload(value, useExternalDownloader(false));
                }

                ProtoMessage parameters = new ProtoMessage(Base64.decode(Uri.decode(value), Base64.URL_SAFE));
                String videoId = getOfflineUpsellVideoId(parameters);
                if (videoId != null) {
                    return insideDialog
                            ? dispatchMenuVideoDownload(videoId)
                            : dispatchVideoDownload(videoId, useExternalDownloader(false));
                }

                String playlistId = parameters.messagePath(UPSELL_PARAMETERS_PLAYLIST, UPSELL_PLAYLIST_ITEM)
                        .stringField(UPSELL_PLAYLIST_ID);
                if (!PLAYLIST_ID.matcher(playlistId).matches()) {
                    playlistId = insideDialog
                            ? PlaylistPageFilter.getMenuPlaylistId()
                            : PlaylistPageFilter.getPagePlaylistId();
                }
                return dispatchPlaylistDownload(playlistId, insideDialog);
            }

            // 5. Native OfflineVideoEndpoint (e.g., podcast episodes or tracks allowed by server).
            ProtoMessage videoEndpoint = proto.messageField(OFFLINE_VIDEO_ENDPOINT);
            if (!videoEndpoint.isEmpty()) {
                long action = videoEndpoint.varintField(2);
                String videoId = videoEndpoint.stringField(1);
                if (action == OFFLINE_VIDEO_ACTION_ADD && VIDEO_ID.matcher(videoId).matches()) {
                    if (useExternalDownloader(insideDialog)) {
                        return dispatchVideoDownload(videoId, true);
                    }
                    if (isInAppDownloadMode()) {
                        return saveVideoCommand(videoId, List.of()).toByteArray();
                    }
                }
                return null;
            }

            // 6. Native OfflinePlaylistEndpoint (e.g., Liked Music VLLM or user playlists).
            ProtoMessage playlistEndpoint = proto.messageField(OFFLINE_PLAYLIST_ENDPOINT);
            if (!playlistEndpoint.isEmpty()) {
                long action = playlistEndpoint.varintField(2);
                String playlistId = playlistEndpoint.stringField(1);
                if (action == OFFLINE_PLAYLIST_ACTION_ADD_VALUE && PLAYLIST_ID.matcher(playlistId).matches()) {
                    return dispatchPlaylistDownload(playlistId, insideDialog);
                }
                return null;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDownloadCommand failure", ex);
        }
        return null;
    }

    @Nullable
    private static byte[] dispatchMenuVideoDownload(String videoId) {
        if (!isFlyoutMenuOverridden()) {
            return null;
        }

        final long now = System.currentTimeMillis();
        if (now - lastFlyoutDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
            return EMPTY_COMMAND;
        }
        lastFlyoutDownloadTime = now;

        Logger.printDebug(() -> "Menu download of " + videoId);
        return dispatchVideoDownload(videoId, flyoutMenuUsesExternalDownloader());
    }

    @Nullable
    private static byte[] dispatchVideoDownload(String videoId, boolean externalDownloader) {
        if (externalDownloader) {
            Utils.runOnMainThreadNowOrLater(() ->
                    ExternalDownloaderPreference.launchExternalDownloader(
                            videoId,
                            Utils.getActivity(),
                            "https://music.youtube.com/watch?v=" + videoId
                    )
            );
            return EMPTY_COMMAND;
        }
        if (isInAppDownloadMode()) {
            return saveVideoCommand(videoId, List.of()).toByteArray();
        }
        return null;
    }

    @Nullable
    private static byte[] dispatchPlaylistDownload(@Nullable String playlistId, boolean insideDialog) {
        if (playlistId == null || playlistId.isEmpty() || !PLAYLIST_ID.matcher(playlistId).matches()) {
            return null;
        }
        if (!insideDialog && !isActionButtonOverridden()) {
            return null;
        }

        final long now = System.currentTimeMillis();
        if (now - lastPlaylistDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
            return EMPTY_COMMAND;
        }
        lastPlaylistDownloadTime = now;

        Logger.printDebug(() -> "Playlist download of " + playlistId);
        if (useExternalDownloader(insideDialog)) {
            Utils.runOnMainThreadNowOrLater(() ->
                    ExternalDownloaderPreference.launchExternalDownloader(
                            playlistId,
                            Utils.getActivity(),
                            playlistUrl(playlistId)
                    )
            );
            return EMPTY_COMMAND;
        }
        if (isInAppDownloadMode()) {
            return savePlaylistCommand(playlistId, OFFLINE_PLAYLIST_ACTION_ADD, List.of()).toByteArray();
        }
        return null;
    }

    private static String playlistUrl(String playlistId) {
        if (playlistId.startsWith("MPRE")) {
            return "https://music.youtube.com/browse/" + playlistId;
        }
        return "https://music.youtube.com/playlist?list=" + playlistId.replaceFirst("^VL", "");
    }

    @Nullable
    private static String getOfflineUpsellVideoId(ProtoMessage parameters) {
        try {
            String videoId = parameters.stringField(4);
            return VIDEO_ID.matcher(videoId).matches() ? videoId : null;
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "Offline upsell parameters are not those of a song", ex);
            return null;
        }
    }

    private static boolean isViewInsideDialog(@Nullable Object viewObj) {
        if (viewObj instanceof View view) {
            View buttonRoot = view.getRootView();
            Activity activity = Utils.getActivity();
            if (activity != null) {
                View activityRoot = activity.getWindow().getDecorView();
                return buttonRoot != activityRoot;
            }
        }
        return false;
    }

    private static boolean isPlayerDownloadButton(@Nullable Map<Object, Object> map) {
        if (map == null || downloadButtonLabel.isEmpty()
                || !(map.get(ELEMENTS_SENDER_VIEW) instanceof ViewGroup senderViewGroup)) {
            return false;
        }
        CharSequence description = senderViewGroup.getContentDescription();
        //noinspection SizeReplaceableByIsEmpty
        if (description == null || description.length() == 0) return false;

        String value = description.toString().toLowerCase(Locale.ROOT);
        String label = downloadButtonLabel.toLowerCase(Locale.ROOT);
        if (!value.contains(label) && !label.contains(value)) return false;

        return isViewInsideActionBar(senderViewGroup);
    }

    private static boolean isViewInsideActionBar(View view) {
        int actionBarId = ResourceUtils.getIdIdentifier("video_action_bar");
        while (view.getParent() instanceof View parent) {
            if (parent.getId() == actionBarId) return true;
            view = parent;
        }
        return false;
    }
}
