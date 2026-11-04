/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.components.SongMenuFilter;
import app.morphe.extension.music.patches.downloads.LocalDownloadManager;
import app.morphe.extension.music.patches.downloads.PlaylistDownloader;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    /**
     * Interface to use obfuscated fields.
     */
    public interface ProtocolBufferFieldInterface {
        // Exposes non-obfuscated method on an obfuscated class.
        byte[] toByteArray();
    }

    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final int IGNORE_DOUBLE_CLICK_DURATION_MS = 1000;

    private static volatile String downloadButtonLabel = "";

    /** Command field of the 'ypc/get_offline_upsell' endpoint. */
    private static final int OFFLINE_UPSELL_ENDPOINT = 133724106;
    /**
     * Command field of the offline playlist endpoint, with the playlist id in field 1 and the
     * action in field 2.
     */
    private static final int OFFLINE_PLAYLIST_ENDPOINT = 73082583;
    /** Action of the offline playlist endpoint that saves the playlist. The others remove or refresh it. */
    private static final long OFFLINE_PLAYLIST_ACTION_ADD = 1;
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
    private static final Pattern PLAYLIST_ID = Pattern.compile(
            "(VL)?(LM|PL[A-Za-z0-9_-]{16,}|OLAK5uy_[A-Za-z0-9_-]{11,}|RD[A-Za-z0-9_-]{10,})"
                    + "|MPRE[A-Za-z0-9_-]{8,}");

    private static volatile long lastFlyoutDownloadTime;
    private static volatile long lastPlaylistDownloadTime;
    private static volatile long lastMainPlayerDownloadTime;
    /** When the player button was last left to the app, because only menus are overridden. */
    private static volatile long lastStockPlayerButtonTime;
    private static volatile long lastLocalDownloadsOpenTime;
    /** Browse id of the stock offline tab, which the local catalogue replaces. */
    private static final ByteArrayFilterGroup OFFLINE_BROWSE_COMMAND =
            new ByteArrayFilterGroup(null, "FEmusic_offline");
    /**
     * Menu Download action of accounts offered Premium, which opens the Premium sheet
     * and has no video ID.
     */
    private static final ByteArrayFilterGroup PREMIUM_UPSELL_COMMAND =
            new ByteArrayFilterGroup(null, "premium_upsell");

    /**
     * Injection point.
     * Usually is called of the main thread.
     */
    public static void onLithoTextLoaded(ContextInterface conversionContext, CharSequence original) {
        try {
            if (isFlyoutMenuOverridden()
                    && downloadButtonLabel.isEmpty()
                    && Utils.contains(conversionContext.patch_getPathBuilder(),
                    "music_download_button.")) {
                downloadButtonLabel = original.toString();
                Logger.printDebug(() -> "Found download button label: " + downloadButtonLabel);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "onLithoTextLoaded failure", ex);
        }
    }

    /**
     * In app downloads and the external downloader always replace the Download button.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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

    private static boolean inAppDownloads() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP;
    }

    private static boolean flyoutMenuUsesExternalDownloader() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.EXTERNAL
                || Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    private static void startDownload(String videoId, boolean externalDownloader) {
        if (!externalDownloader) {
            LocalDownloadManager.enqueue(videoId);
            return;
        }

        ExternalDownloaderPreference.launchExternalDownloader(
                videoId, Utils.getActivity(), "https://music.youtube.com/watch?v=" + videoId);
    }

    private static void openLocalDownloads() {
        Activity activity = Utils.getActivity();
        if (activity == null) return;

        // A single tap on the offline chip resolves its command twice, which would otherwise
        // stack a second copy of the screen on top of the first.
        final long now = System.currentTimeMillis();
        if (now - lastLocalDownloadsOpenTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return;
        lastLocalDownloadsOpenTime = now;
        Logger.printDebug(() -> "Offline tab opened, showing the local downloads");

        Intent intent = new Intent();
        intent.setClassName(activity, "com.google.android.gms.common.api.GoogleApiActivity");
        intent.setPackage(activity.getPackageName());
        intent.setData(Uri.parse(BaseActivityHook.MORPHE_DOWNLOADS_INTENT));
        activity.startActivity(intent);
    }

    /**
     * Determines if the clicked view is inside a Dialog/BottomSheet by comparing
     * its Window root to the main Activity's Window root.
     */
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

    /**
     * The player button is handled by the command resolver, so a Download command that reaches
     * the app is from a menu, unless the player button was just left to the app.
     */
    private static boolean onMenuDownload(String videoId) {
        if (!isFlyoutMenuOverridden()) return false;

        final long now = System.currentTimeMillis();
        if (now - lastStockPlayerButtonTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return false;
        // The song menu stays open after Download, so repeated taps can repeat the request.
        // Keep this timer separate from the player button so a menu download of another song is not blocked.
        if (now - lastFlyoutDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return true;
        lastFlyoutDownloadTime = now;

        Logger.printDebug(() -> "Menu download of " + videoId);
        startDownload(videoId, flyoutMenuUsesExternalDownloader());
        return true;
    }

    /**
     * @param parameters The encoded parameters of the offline upsell endpoint.
     * @return The video ID in field 4, or null if the parameters are of an album or a playlist.
     */
    @Nullable
    private static String getOfflineUpsellVideoId(ProtoMessage parameters) {
        try {
            String videoId = parameters.stringField(4);
            return VIDEO_ID.matcher(videoId).matches() ? videoId : null;
        } catch (IllegalArgumentException ex) {
            // The parameters of an album or a playlist hold other fields at this number.
            Logger.printDebug(() -> "Offline upsell parameters are not those of a song", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * Called for the Download button of the playlists the app offers to save itself, such as
     * Liked music, which it otherwise queues as an offline playlist whose tracks all need Premium.
     */
    public static boolean offlinePlaylistEndpointOnClick(@Nullable Object command) {
        try {
            if (!isActionButtonOverridden()
                    || !(command instanceof ProtocolBufferFieldInterface message)) {
                return false;
            }
            Utils.verifyOnMainThread();

            ProtoMessage endpoint = new ProtoMessage(message.toByteArray())
                    .messageField(OFFLINE_PLAYLIST_ENDPOINT);
            String playlistId = endpoint.stringField(1);
            final long action = endpoint.varintField(2);
            Logger.printDebug(() -> "Offline playlist endpoint of " + playlistId + ", action: " + action);

            // Removing or refreshing a playlist the app saved is left to the app.
            if (action != OFFLINE_PLAYLIST_ACTION_ADD || !PLAYLIST_ID.matcher(playlistId).matches()) {
                return false;
            }
            startPlaylistDownload(playlistId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "offlinePlaylistEndpointOnClick failure", ex);
            return false;
        }
    }

    /**
     * Downloads the tracks of a playlist or an album with the local downloads, or hands its page
     * to the external downloader, as the download mode says. Only used if the mode is not the default.
     */
    private static void startPlaylistDownload(String playlistId) {
        // A single tap can resolve the command twice.
        final long now = System.currentTimeMillis();
        if (now - lastPlaylistDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return;
        lastPlaylistDownloadTime = now;

        Logger.printDebug(() -> "Playlist download of " + playlistId);
        if (inAppDownloads()) {
            PlaylistDownloader.start(playlistId);
        } else {
            ExternalDownloaderPreference.launchExternalDownloader(playlistId, Utils.getActivity(),
                    playlistUrl(playlistId));
        }
    }

    private static String playlistUrl(String playlistId) {
        if (playlistId.startsWith("MPRE")) return "https://music.youtube.com/browse/" + playlistId;
        return "https://music.youtube.com/playlist?list=" + playlistId.replaceFirst("^VL", "");
    }

    /**
     * Injection point.
     * Called for accounts that are allowed to save the song.
     */
    public static boolean offlineVideoEndpointOnClick(String videoId) {
        try {
            Utils.verifyOnMainThread();
            return onMenuDownload(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "offlineVideoEndpointOnClick failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * Called for accounts that are offered Premium instead of saving the song.
     */
    public static boolean offlineUpsellOnClick(@Nullable Object command) {
        try {
            if (!isFlyoutMenuOverridden()
                    || !(command instanceof ProtocolBufferFieldInterface message)) {
                return false;
            }
            Utils.verifyOnMainThread();

            // The endpoint holds the video ID of a song, or encoded parameters.
            String value = new ProtoMessage(message.toByteArray())
                    .messageField(OFFLINE_UPSELL_ENDPOINT).stringField(1);
            if (value.isEmpty()) return false;
            if (VIDEO_ID.matcher(value).matches()) return onMenuDownload(value);

            ProtoMessage parameters = new ProtoMessage(Base64.decode(Uri.decode(value), Base64.URL_SAFE));
            String videoId = getOfflineUpsellVideoId(parameters);
            if (videoId != null) return onMenuDownload(videoId);

            // Albums and playlists are downloaded track by track with the local downloads, or
            // handed whole to the external downloader.
            if (!isActionButtonOverridden()) return false;
            String playlistId = parameters.messagePath(UPSELL_PARAMETERS_PLAYLIST, UPSELL_PLAYLIST_ITEM)
                    .stringField(UPSELL_PLAYLIST_ID);
            if (!PLAYLIST_ID.matcher(playlistId).matches()) return false;

            startPlaylistDownload(playlistId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "offlineUpsellOnClick failure", ex);
            return false;
        }
    }

    /**
     * The label is the localized download text litho gave us, so no hard coded language is needed.
     */
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

        int actionBarId = ResourceUtils.getIdIdentifier("video_action_bar");
        View view = senderViewGroup;
        while (view.getParent() instanceof View parent) {
            if (parent.getId() == actionBarId) return true;
            view = parent;
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean commandResolverOnClick(ProtocolBufferFieldInterface p1, Map<Object, Object> map) {
        try {
            if (!isFlyoutMenuOverridden()
                    || p1 == null || map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            byte[] commandBytes = p1.toByteArray();
            final boolean insideDialog = isViewInsideDialog(map.get(ELEMENTS_SENDER_VIEW));

            if (insideDialog && PREMIUM_UPSELL_COMMAND.check(commandBytes).isFiltered()) {
                // The song is found by the menu header. Albums and playlists are left to the app.
                String videoId = SongMenuFilter.getMenuVideoId();
                if (videoId == null) return false;

                return onMenuDownload(videoId);
            }

            if (inAppDownloads()) {
                if (OFFLINE_BROWSE_COMMAND.check(commandBytes).isFiltered()) {
                    openLocalDownloads();
                    // The local screen is opened on top of the stock one rather than in place of
                    // it. Consuming the command instead leaves the app with a navigation it never
                    // finished, which it replays on the next start and cancels again.
                    return false;
                }
            }

            // Song menus in search results, playlists and the queue share the player's Download label.
            // Only the player action bar button should use the playing song's ID.
            if (!insideDialog && isPlayerDownloadButton(map)) {
                if (!isActionButtonOverridden()) {
                    lastStockPlayerButtonTime = System.currentTimeMillis();
                    return false;
                }

                String videoId = VideoInformation.getVideoId();
                if (videoId == null || videoId.isEmpty()) return false;

                final long now = System.currentTimeMillis();
                if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                    return true;
                }
                lastMainPlayerDownloadTime = now;
                startDownload(videoId, !inAppDownloads());
                return true;
            }
            // Other menu Download commands have no sender view, and are handled when they
            // reach the offline video endpoint or the offline upsell.
        } catch (Exception ex) {
            Logger.printException(() -> "commandResolverOnClick failure", ex);
        }
        return false;
    }
}
