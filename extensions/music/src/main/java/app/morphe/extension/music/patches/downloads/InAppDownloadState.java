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

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;

/** Reads the current account's removable songs and reuses the app's removal confirmation. */
@SuppressWarnings("unused")
public final class InAppDownloadState {
    private static final long DOWNLOAD_REQUEST_TIMEOUT_MS = 10_000;
    private static final long SAME_MENU_RELAYOUT_MS = 1_000;
    private static final AtomicReference<DownloadRequest> lastRequest = new AtomicReference<>();
    private static volatile WeakReference<Resolver> resolver = new WeakReference<>(null);
    @Nullable
    private static volatile MenuState menu;

    public interface Resolver {
        Controller patch_downloadController();
    }

    public interface Controller {
        ListenableFuture<?> patch_readDownloadedSongs();

        boolean patch_containsDownloadedSong(Object snapshot, String videoId);

        ListenableFuture<?> patch_readTrackState(String videoId);

        boolean patch_isTrackDownloadComplete(Object trackState);

        void patch_promptRemoval(String videoId);
    }

    public interface SaveCallback {
        Controller patch_downloadController();

        String patch_videoId();
    }

    private record DownloadRequest(String videoId, long requestedAt) {}

    private static final class MenuState implements LithoRelayoutPatch.RelayoutSpan {
        final String videoId;
        volatile long reuseUntil;
        volatile boolean downloaded;

        MenuState(String videoId, long reuseUntil) {
            this.videoId = videoId;
            this.reuseUntil = reuseUntil;
        }

        @Override
        public boolean isOutdated() {
            return downloaded && this == menu && enabled();
        }
    }

    private InAppDownloadState() {}

    private static boolean enabled() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP && Settings.SYNC_IN_APP_DOWNLOADS.get();
    }

    public static void setResolver(Resolver value) {
        resolver = new WeakReference<>(value);
        menu = null;
    }

    public static void onDownloadRequested(String videoId) {
        if (enabled()) {
            lastRequest.set(new DownloadRequest(videoId, SystemClock.uptimeMillis()));
        }
    }

    /**
     * Called where the app would show "Video already saved". The first result is the
     * same local playlist snapshot the app uses to choose a removal target.
     */
    public static boolean onAlreadySaved(SaveCallback callback, Object saveResult) {
        try {
            if (!enabled()) return false;
            DownloadRequest request = lastRequest.get();
            if (request == null || !request.videoId().equals(callback.patch_videoId())
                    || SystemClock.uptimeMillis() - request.requestedAt() > DOWNLOAD_REQUEST_TIMEOUT_MS
                    || !lastRequest.compareAndSet(request, null)) {
                return false;
            }
            Controller controller = callback.patch_downloadController();
            if (!(saveResult instanceof List<?> results) || results.isEmpty()
                    || !controller.patch_containsDownloadedSong(results.get(0), request.videoId())) {
                return false;
            }
            menu = null;
            Utils.runOnMainThreadNowOrLater(() -> controller.patch_promptRemoval(request.videoId()));
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not open the downloaded song removal dialog", ex);
            return false;
        }
    }

    public static void onMenuHeader(@Nullable String videoId) {
        if (!enabled() || Settings.EXTERNAL_DOWNLOADER_MENU.get() || videoId == null) {
            menu = null;
            return;
        }
        final long now = SystemClock.uptimeMillis();
        MenuState previous = menu;
        if (previous != null && videoId.equals(previous.videoId) && now < previous.reuseUntil) return;
        Resolver source = resolver.get();
        if (source == null) {
            menu = null;
            return;
        }

        MenuState current = new MenuState(videoId, now + SAME_MENU_RELAYOUT_MS);
        menu = current;
        try {
            Controller controller = source.patch_downloadController();
            if (controller == null) return;
            onMenuStateRead(current, controller.patch_readDownloadedSongs(), playlists -> {
                if (controller.patch_containsDownloadedSong(playlists, current.videoId)) {
                    onMenuStateRead(current, controller.patch_readTrackState(current.videoId), track -> {
                        if (!controller.patch_isTrackDownloadComplete(track)) return;
                        // Litho may convert the menu header again while applying the new label.
                        current.reuseUntil = SystemClock.uptimeMillis() + SAME_MENU_RELAYOUT_MS;
                        current.downloaded = true;
                        LithoRelayoutPatch.relayoutOutdatedTexts();
                    });
                }
            });
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not query the downloaded song status", ex);
        }
    }

    private static void onMenuStateRead(MenuState current, ListenableFuture<?> future, Consumer<Object> callback) {
        future.addListener(() -> {
            if (menu != current || !enabled()) return;
            try {
                callback.accept(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printDebug(() -> "Could not read the downloaded song status", ex);
            }
        }, Runnable::run);
    }

    public static CharSequence menuLabel(CharSequence original, String playerDownloadLabel) {
        MenuState current = menu;
        if (current == null || !enabled()) return original;

        if (!TextUtils.equals(original, playerDownloadLabel)
                && !TextUtils.equals(original, ResourceUtils.getString("action_add_to_offline_songs"))) {
            return original;
        }
        if (current.downloaded) {
            String downloaded = ResourceUtils.getString("notification_offline_video_completed");
            return new SpannableStringBuilder(original).replace(0, original.length(), downloaded);
        }
        SpannableString pending = new SpannableString(original);
        pending.setSpan(current, 0, pending.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return pending;
    }
}
