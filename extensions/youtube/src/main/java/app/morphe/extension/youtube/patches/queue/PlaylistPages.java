/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;

/** Read-only access to YouTube's authenticated watch-page service, without player callbacks. */
@SuppressWarnings("unused")
public final class PlaylistPages {
    private static final long REQUEST_TIMEOUT_SECONDS = 15;

    private PlaylistPages() {
    }

    public interface Source {
        @Nullable
        PlaylistPage.Account patch_getPlaylistAccount();
        @Nullable
        Future<?> patch_requestPlaylistPage(String playlistId, @Nullable String videoId, int index,
                String params, String entryId, byte[] tracking, PlaylistPage.Account account);
        @Nullable
        MessageLite patch_getPlaylistPage(Object response);
        @Nullable
        MessageLite patch_getPlaylistNavigation(Object response);
    }

    private static volatile WeakReference<Source> source = new WeakReference<>(null);

    /**
     * Injection point.
     * <p>
     * Capture after native initialization. Neither this callback nor the bridge publishes a watch response.
     */
    public static void attach(Source owner) {
        source = new WeakReference<>(owner);
    }

    @Nullable
    static Source current() {
        return source.get();
    }

    record Result(PlaylistPage page, boolean end) {}

    /** Load the first native window without publishing a watch response or loading streams. */
    static PlaylistPage first(Source owner, PlaylistQueue.Source expected) throws Exception {
        Utils.verifyOffMainThread();
        return page(owner, expected, request(owner, expected, null, 0, "", "", new byte[0]), "first page");
    }

    static Result next(Source owner, PlaylistQueue.Source expected) throws Exception {
        var anchor = expected.anchor();
        if (anchor == null) {
            throw new IllegalArgumentException("Missing playlist boundary");
        }
        return window(owner, expected, anchor);
    }

    /** Refresh around the playing source occurrence, rather than the loaded tail. */
    static Result window(Source owner, PlaylistQueue.Source expected, PlaylistPage.Anchor anchor) throws Exception {
        Utils.verifyOffMainThread();
        if (expected.anchor() == null || !expected.playlistId().equals(anchor.playlistId())) {
            throw new IllegalArgumentException("Playing occurrence belongs to another playlist");
        }
        Object response = request(owner, expected, anchor.videoId(), anchor.index(),
                anchor.params(), anchor.entryId(), anchor.tracking().toByteArray());
        PlaylistPage page = page(owner, expected, response, "next page");
        position(page, anchor);
        int position = page.find(expected.anchor());
        if (position >= 0 && position + 1 < page.rows().size()) {
            return new Result(page, false);
        }
        if (!anchor.equals(expected.anchor())) {
            // An earlier playback window can end before the already loaded tail.
            // No new rows does not prove that the playlist has ended.
            return new Result(page, false);
        }
        if (page.selectedIndex() != position) {
            throw new IOException("Playlist window selected a different occurrence");
        }
        MessageLite nativeNavigation = owner.patch_getPlaylistNavigation(response);
        if (nativeNavigation == null) {
            throw new IOException("Playlist navigation is unavailable");
        }
        var navigation = PlaylistQueueRenderer.PlaylistNavigation.parseFrom(nativeNavigation.toByteArray());
        var target = PlaylistPage.nextTarget(navigation);
        if (target == null) {
            throw new IOException("Unrecognized playlist navigation");
        }
        if (PlaylistPage.endsSource(target, expected.playlistId())) {
            return new Result(page, true);
        }
        if (target.hasWatchPlaylistEndpoint()) {
            var watch = target.getWatchPlaylistEndpoint();
            if (!expected.playlistId().equals(watch.getPlaylistId()) || !watch.hasIndex()
                    || watch.getIndex() <= anchor.index()) {
                throw new IOException("Playlist position does not follow its source boundary");
            }
            Object following = request(owner, expected, null, watch.getIndex(), watch.getParams(), "",
                    target.getClickTrackingParams().toByteArray());
            PlaylistPage continued = page(owner, expected, following, "next position");
            int boundary = position(continued, anchor);
            if (boundary + 1 < continued.rows().size()) {
                return new Result(continued, false);
            }
            if (continued.selectedIndex() >= 0 && continued.selectedIndex() < boundary) {
                return new Result(continued, true);
            }
        }
        throw new IOException("Native playlist window did not advance or end");
    }

    private static Object request(Source owner, PlaylistQueue.Source expected, @Nullable String videoId,
                                  int index, String params, String entryId, byte[] tracking) throws Exception {
        if (owner != current() || !expected.account().equals(owner.patch_getPlaylistAccount())) {
            throw new IOException("Playlist account changed");
        }
        Future<?> future = owner.patch_requestPlaylistPage(expected.playlistId(), videoId, index,
                params, entryId, tracking, expected.account());
        if (future == null) {
            throw new IOException("Playlist account is unavailable");
        }
        Object response;
        try {
            response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            // A timed-out request must not retain its callback indefinitely.
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        if (owner != current() || !expected.account().equals(owner.patch_getPlaylistAccount())) {
            throw new IOException("Playlist account changed during request");
        }
        return response;
    }

    private static PlaylistPage page(Source owner, PlaylistQueue.Source expected, Object response, String request)
            throws Exception {
        MessageLite panel = owner.patch_getPlaylistPage(response);
        if (panel == null) {
            throw new IOException("Missing playlist panel at " + request);
        }
        try {
            return PlaylistPage.readPanel(panel, expected.playlistId());
        } catch (PlaylistPage.InvalidPanel ex) {
            throw new IOException("Invalid playlist panel at " + request, ex);
        }
    }

    private static int position(PlaylistPage page, PlaylistPage.Anchor anchor) throws IOException {
        int position = page.find(anchor);
        if (position < 0) {
            throw new IOException("Playlist window no longer contains its requested occurrence");
        }
        return position;
    }
}
