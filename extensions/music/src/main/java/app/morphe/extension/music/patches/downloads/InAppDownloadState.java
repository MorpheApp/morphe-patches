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
        volatile WeakReference<TextView> nativeLabel = new WeakReference<>(null);

        MenuState(String videoId, long reuseUntil) {
            this.videoId = videoId;
            this.reuseUntil = reuseUntil;
        }

        @Override
        public boolean isOutdated() {
            return downloaded && this == menu && isMenuSyncEnabled();
        }
    }

    private InAppDownloadState() {}

    private static boolean enabled() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP && Settings.SYNC_IN_APP_DOWNLOADS.get();
    }

    public static boolean isMenuSyncEnabled() {
        return enabled() && !Settings.EXTERNAL_DOWNLOADER_MENU.get();
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
        if (!isMenuSyncEnabled() || videoId == null) {
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
                        if (current.nativeLabel.get() != null) {
                            Utils.runOnMainThreadNowOrLater(() -> updateNativeLabel(current));
                        }
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
            if (menu != current || !isMenuSyncEnabled()) return;
            try {
                callback.accept(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printDebug(() -> "Could not read the downloaded song status", ex);
            }
        }, Runnable::run);
    }

    public static CharSequence markLithoMenuLabel(CharSequence original, String playerDownloadLabel) {
        MenuState current = menu;
        if (current == null || !isMenuSyncEnabled()) return original;

        if (!TextUtils.equals(original, playerDownloadLabel) && !isDownloadLabel(original)) {
            return original;
        }
        return withPendingMenuSpan(original, current);
    }

    /** Called when Litho copies cached text into the menu row's layout. */
    public static CharSequence resolveLithoMenuLabel(CharSequence original) {
        try {
            MenuState current = menu;
            if (current == null || !isMenuSyncEnabled() || !(original instanceof Spanned spanned)) {
                return original;
            }
            MenuState[] markers = spanned.getSpans(0, spanned.length(), MenuState.class);
            if (markers.length == 0 || !current.videoId.equals(markers[0].videoId)) return original;
            if (current.downloaded) {
                SpannableStringBuilder downloaded = downloadedLabel(original);
                for (MenuState marker : markers) downloaded.removeSpan(marker);
                return downloaded;
            }
            return current == markers[0] ? original : withPendingMenuSpan(original, current);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not resolve the downloaded song label", ex);
            return original;
        }
    }

    private static CharSequence withPendingMenuSpan(CharSequence original, MenuState current) {
        SpannableString pending = new SpannableString(original);
        for (MenuState marker : pending.getSpans(0, pending.length(), MenuState.class)) {
            pending.removeSpan(marker);
        }
        pending.setSpan(current, 0, pending.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return pending;
    }

    public static void onNativeDownloadLabelBound(TextView view) {
        try {
            MenuState current = menu;
            if (current == null || !isMenuSyncEnabled() || !isDownloadLabel(view.getText())) return;
            current.nativeLabel = new WeakReference<>(view);
            // The native binder assigns the icon after it assigns the text.
            view.post(() -> updateNativeLabel(current));
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not bind the downloaded song label", ex);
        }
    }

    private static void updateNativeLabel(MenuState current) {
        if (menu != current || !current.downloaded || !isMenuSyncEnabled()) return;
        TextView view = current.nativeLabel.get();
        if (view != null && isDownloadLabel(view.getText())) {
            if (view.getParent() instanceof View row
                    && row.findViewById(ResourceUtils.getIdIdentifier("icon")) instanceof ImageView icon) {
                int downloadedIcon = ResourceUtils.getDrawableIdentifier("yt_fill_downloaded_vd_theme_24");
                if (downloadedIcon != 0) icon.setImageResource(downloadedIcon);
            }
            view.setText(downloadedLabel(view.getText()));
        }
    }

    private static boolean isDownloadLabel(CharSequence label) {
        return TextUtils.equals(label, ResourceUtils.getString("action_add_to_offline_songs"));
    }

    private static SpannableStringBuilder downloadedLabel(CharSequence original) {
        String downloaded = ResourceUtils.getString("notification_offline_video_completed");
        return new SpannableStringBuilder(original).replace(0, original.length(), downloaded);
    }
}
