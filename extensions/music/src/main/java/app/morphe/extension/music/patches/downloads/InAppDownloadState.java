/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.os.SystemClock;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.Offline.OfflineCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoAction;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoEndpoint;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;

/**
 * Shows the downloaded songs in their menus, and asks to remove the download
 * when the download button of a downloaded song is tapped.
 */
@SuppressWarnings("unused")
public final class InAppDownloadState {

    /**
     * Interface added to the resolver of the offline video endpoint during patching.
     */
    public interface EndpointResolver {
        /**
         * Resolves a command of the offline video endpoint.
         */
        void patch_resolveCommand(byte[] command);
    }

    /**
     * Interface added to the track store during patching.
     */
    public interface TrackStore {
        /**
         * @return The download state of the song or video.
         */
        ListenableFuture<?> patch_readTrackState(String videoId);

        /**
         * @return If the download of the song or video is complete.
         */
        boolean patch_isDownloadComplete(Object trackState);
    }

    /**
     * Interface added to the callback that shows "Video already saved" during patching.
     */
    public interface AlreadySavedCallback {
        String patch_getVideoId();
    }

    /**
     * Resources of the app, resolved when a label is first updated.
     */
    private static final class AppResources {
        static final int DOWNLOAD_LABEL = ResourceUtils.getStringIdentifier("action_add_to_offline_songs");
        static final int DOWNLOADED_LABEL = ResourceUtils.getStringIdentifier("notification_offline_video_completed");
        static final int DOWNLOADED_ICON = ResourceUtils.getDrawableIdentifier("yt_fill_downloaded_vd_theme_24");
        static final int MENU_ITEM_ICON = ResourceUtils.getIdIdentifier("icon");
    }

    /**
     * Time after a tap of a download button in which the app can answer that the song is already saved.
     */
    private static final long DOWNLOAD_TAP_TIMEOUT_MILLISECONDS = 10_000;

    /**
     * Litho converts the header of a menu again while the menu is shown, such as when its
     * labels change, so the download state is read only once in this time.
     */
    private static final long MENU_HEADER_READ_INTERVAL_MILLISECONDS = 1_000;

    private static volatile WeakReference<EndpointResolver> endpointResolverRef = new WeakReference<>(null);

    /**
     * The app creates a track store for each user of it, and all of them read the same downloads.
     * They hold the application context, so the last one is kept.
     */
    @Nullable
    private static volatile TrackStore trackStore;

    @Nullable
    private static volatile String tappedVideoId;
    private static volatile long tappedTime;

    @Nullable
    private static volatile MenuState menu;

    /**
     * Download state of the song of the open menu. It's also the span of the Download label
     * of the menu, so the label is laid out again once the song is known to be downloaded.
     */
    private static final class MenuState implements LithoRelayoutPatch.RelayoutSpan {
        final String videoId;
        volatile long readTime;
        volatile boolean downloaded;
        volatile WeakReference<TextView> nativeLabelRef = new WeakReference<>(null);

        MenuState(String videoId, long readTime) {
            this.videoId = videoId;
            this.readTime = readTime;
        }

        @Override
        public boolean isOutdated() {
            return downloaded && this == menu && isMenuSyncEnabled();
        }
    }

    private InAppDownloadState() {
    }

    private static boolean isEnabled() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP && Settings.SYNC_IN_APP_DOWNLOADS.get();
    }

    /**
     * The menu Download of the external downloader menu setting does not save in-app.
     */
    public static boolean isMenuSyncEnabled() {
        return isEnabled() && !Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    /**
     * Injection point.
     * Called when the resolver of the offline video endpoint is created.
     */
    public static void setEndpointResolver(EndpointResolver resolver) {
        endpointResolverRef = new WeakReference<>(resolver);
    }

    /**
     * Injection point.
     * Called when a track store is created.
     */
    public static void setTrackStore(TrackStore store) {
        trackStore = store;
    }

    /**
     * Called when the download button of a song is tapped.
     */
    public static void onDownloadTapped(String videoId) {
        if (isEnabled()) {
            tappedTime = SystemClock.uptimeMillis();
            tappedVideoId = videoId;
        }
    }

    /**
     * Injection point.
     * Called instead of showing "Video already saved".
     *
     * @return If the removal of the download is asked instead.
     */
    public static boolean onAlreadySaved(AlreadySavedCallback callback) {
        try {
            if (!isEnabled()) return false;

            String videoId = callback.patch_getVideoId();
            if (!videoId.equals(tappedVideoId)
                    || SystemClock.uptimeMillis() - tappedTime > DOWNLOAD_TAP_TIMEOUT_MILLISECONDS) {
                return false;
            }
            EndpointResolver resolver = endpointResolverRef.get();
            if (resolver == null) return false;

            tappedVideoId = null;
            menu = null;

            // The app asks to remove the download, and the dialog can be canceled.
            OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                    .setVideoId(videoId)
                    .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_REMOVE)
                    .build();
            byte[] command = OfflineCommand.newBuilder().setOfflineVideoEndpoint(endpoint).build().toByteArray();
            Utils.runOnMainThreadNowOrLater(() -> resolver.patch_resolveCommand(command));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onAlreadySaved failure", ex);
            return false;
        }
    }

    /**
     * Called when the header of a song menu is shown.
     */
    public static void onMenuHeader(@Nullable String videoId) {
        try {
            if (videoId == null || !isMenuSyncEnabled()) {
                menu = null;
                return;
            }

            final long now = SystemClock.uptimeMillis();
            MenuState previous = menu;
            if (previous != null && videoId.equals(previous.videoId)
                    && now - previous.readTime < MENU_HEADER_READ_INTERVAL_MILLISECONDS) {
                return;
            }

            TrackStore store = trackStore;
            if (store == null) {
                menu = null;
                return;
            }

            MenuState state = new MenuState(videoId, now);
            menu = state;

            ListenableFuture<?> future = store.patch_readTrackState(videoId);
            future.addListener(() -> onTrackStateRead(state, store, future), Runnable::run);
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuHeader failure", ex);
        }
    }

    private static void onTrackStateRead(MenuState state, TrackStore store, ListenableFuture<?> future) {
        try {
            if (state != menu || !isMenuSyncEnabled()
                    || !store.patch_isDownloadComplete(future.get())) {
                return;
            }

            // Litho can convert the menu header again while the new label is laid out.
            state.readTime = SystemClock.uptimeMillis();
            state.downloaded = true;
            Logger.printDebug(() -> "Menu song is downloaded: " + state.videoId);

            if (state.nativeLabelRef.get() != null) {
                Utils.runOnMainThreadNowOrLater(() -> updateNativeLabel(state));
            }
            LithoRelayoutPatch.relayoutOutdatedTexts();
        } catch (Exception ex) {
            Logger.printException(() -> "onTrackStateRead failure", ex);
        }
    }

    /**
     * Called when a Litho text of a menu is created.
     *
     * @return The text, with the state of the menu if it's the Download label.
     */
    public static CharSequence onLithoMenuTextLoaded(CharSequence original, String playerDownloadLabel) {
        MenuState state = menu;
        if (state == null || !isMenuSyncEnabled()) return original;

        if (!TextUtils.equals(original, playerDownloadLabel) && !isDownloadLabel(original)) {
            return original;
        }
        return withMenuState(original, state);
    }

    /**
     * Injection point.
     * Called when Litho binds a text, which can be a cached Download label of an earlier menu.
     */
    public static CharSequence onLithoMenuLabelBound(CharSequence original) {
        try {
            MenuState state = menu;
            if (state == null || !(original instanceof Spanned spanned) || !isMenuSyncEnabled()) {
                return original;
            }

            MenuState[] labelStates = spanned.getSpans(0, spanned.length(), MenuState.class);
            if (labelStates.length == 0 || !state.videoId.equals(labelStates[0].videoId)) {
                return original;
            }
            if (state.downloaded) {
                SpannableStringBuilder downloaded = downloadedLabel(original);
                for (MenuState labelState : labelStates) {
                    downloaded.removeSpan(labelState);
                }
                return downloaded;
            }
            return state == labelStates[0] ? original : withMenuState(original, state);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoMenuLabelBound failure", ex);
            return original;
        }
    }

    private static CharSequence withMenuState(CharSequence original, MenuState state) {
        SpannableString text = new SpannableString(original);
        for (MenuState labelState : text.getSpans(0, text.length(), MenuState.class)) {
            text.removeSpan(labelState);
        }
        text.setSpan(state, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /**
     * Injection point.
     * Called on the main thread when a native menu item sets its label.
     */
    public static void onNativeMenuLabelBound(TextView view) {
        try {
            MenuState state = menu;
            if (state == null || !isMenuSyncEnabled() || !isDownloadLabel(view.getText())) return;

            state.nativeLabelRef = new WeakReference<>(view);
            // The menu item sets its icon after its label.
            view.post(() -> updateNativeLabel(state));
        } catch (Exception ex) {
            Logger.printException(() -> "onNativeMenuLabelBound failure", ex);
        }
    }

    private static void updateNativeLabel(MenuState state) {
        if (state != menu || !state.downloaded || !isMenuSyncEnabled()) return;

        TextView view = state.nativeLabelRef.get();
        if (view == null || !isDownloadLabel(view.getText())) return;

        if (view.getParent() instanceof View row
                && row.findViewById(AppResources.MENU_ITEM_ICON) instanceof ImageView icon) {
            icon.setImageResource(AppResources.DOWNLOADED_ICON);
        }
        view.setText(downloadedLabel(view.getText()));
    }

    private static boolean isDownloadLabel(CharSequence label) {
        return TextUtils.equals(label, Utils.getContext().getString(AppResources.DOWNLOAD_LABEL));
    }

    /**
     * @return The Downloaded label, with the style of the Download label.
     */
    private static SpannableStringBuilder downloadedLabel(CharSequence original) {
        String downloaded = Utils.getContext().getString(AppResources.DOWNLOADED_LABEL);
        return new SpannableStringBuilder(original).replace(0, original.length(), downloaded);
    }
}
