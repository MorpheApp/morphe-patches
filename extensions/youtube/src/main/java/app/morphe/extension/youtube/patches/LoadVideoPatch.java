/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.GuideResponseOuterClass.BrowseEndpoint;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.WatchEndpoint;
import app.morphe.extension.youtube.patches.playback.speed.RememberPlaybackSpeedPatch;
import app.morphe.extension.youtube.patches.queue.NativePlaylistQueuePatch;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class LoadVideoPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlayerInterface {
        // Method is added during patching.
        void patch_dismissPlayer();
    }

    /**
     * Interface to use obfuscated methods of the class that decides if videos in feeds can play.
     */
    public interface InlinePlaybackInterface {
        // Methods are added during patching.
        void patch_blockInlinePlayback(int reason);
        void patch_unblockInlinePlayback(int reason);
    }

    /**
     * Reason added to the reasons YouTube uses to block playback in feeds (such as 3, 4 and 5).
     */
    private static final int INLINE_PLAYBACK_BLOCK_REASON = 6835;

    /**
     * Unblocks playback in feeds if the player does not open again.
     */
    private static final long INLINE_PLAYBACK_BLOCK_TIMEOUT_MILLISECONDS = 10_000;

    /**
     * Time the player needs to close before the video playing can be opened again.
     */
    private static final long RELOAD_VIDEO_DELAY_MILLISECONDS = 500;

    private static WeakReference<PlayerInterface> playerInterfaceRef = new WeakReference<>(null);

    private static WeakReference<InlinePlaybackInterface> inlinePlaybackInterfaceRef = new WeakReference<>(null);

    private static boolean inlinePlaybackBlocked;

    /**
     * Incremented each time playback in feeds is blocked, so the timeout of an older block does nothing.
     */
    private static int inlinePlaybackBlockId;

    private static final Consumer<PlayerType> unblockInlinePlaybackOnPlayerOpen = type -> {
        if (type.isMaximizedOrFullscreen()) {
            unblockInlinePlayback();
        }
    };

    /**
     * Injection point.
     */
    public static void initialize(@NonNull PlayerInterface playerInterfaceInit) {
        playerInterfaceRef = new WeakReference<>(Objects.requireNonNull(playerInterfaceInit));
    }

    /**
     * Injection point.
     */
    public static void initializeInlinePlayback(@NonNull InlinePlaybackInterface inlinePlaybackInterface) {
        inlinePlaybackInterfaceRef = new WeakReference<>(Objects.requireNonNull(inlinePlaybackInterface));
    }

    /**
     * Once the player is dismissed, the video of the feed below it can start playing in the feed.
     * The player opened next then plays that video instead, with a black screen and a page that never loads.
     * Playback in feeds is blocked until the player opens again.
     * Opens that finish minimized can release the returned token once their player is accepted.
     *
     * @return the block generation, or zero if feed playback has not initialized.
     */
    public static int blockInlinePlaybackUntilPlayerOpens() {
        InlinePlaybackInterface inlinePlaybackInterface = inlinePlaybackInterfaceRef.get();
        if (inlinePlaybackInterface == null) {
            Logger.printDebug(() -> "Cannot block playback in feeds, interface is not set");
            return 0;
        }

        if (!inlinePlaybackBlocked) {
            Logger.printDebug(() -> "Blocking playback in feeds");
            inlinePlaybackInterface.patch_blockInlinePlayback(INLINE_PLAYBACK_BLOCK_REASON);
            inlinePlaybackBlocked = true;
            PlayerType.getOnChange().addObserver(unblockInlinePlaybackOnPlayerOpen);
        }

        final int blockId = ++inlinePlaybackBlockId;
        Utils.runOnMainThreadDelayed(() -> {
            if (blockId == inlinePlaybackBlockId) {
                unblockInlinePlayback();
            }
        }, INLINE_PLAYBACK_BLOCK_TIMEOUT_MILLISECONDS);
        return blockId;
    }

    /** A completed or canceled open must not release a newer operation's protection. */
    public static void releaseInlinePlaybackBlock(int blockId) {
        if (blockId != 0 && blockId == inlinePlaybackBlockId) {
            unblockInlinePlayback();
        }
    }

    private static void unblockInlinePlayback() {
        if (!inlinePlaybackBlocked) {
            return;
        }
        inlinePlaybackBlocked = false;
        PlayerType.getOnChange().removeObserver(unblockInlinePlaybackOnPlayerOpen);

        InlinePlaybackInterface inlinePlaybackInterface = inlinePlaybackInterfaceRef.get();
        if (inlinePlaybackInterface != null) {
            Logger.printDebug(() -> "Unblocking playback in feeds");
            inlinePlaybackInterface.patch_unblockInlinePlayback(INLINE_PLAYBACK_BLOCK_REASON);
        }
    }

    @SuppressWarnings("ExtractMethodRecommender")
    public static void initializeReloadVideo() {
        try {
            // If the player is not active, the layout may break.
            // Use it only when it is guaranteed to be used in situations where the player is active.
            if (PlayerType.getCurrent().isNoneOrHidden()) {
                return;
            }

            PlayerInterface playerInterface = playerInterfaceRef.get();
            if (playerInterface == null) {
                Utils.showSnackbarShort(str("morphe_dismiss_player_not_available_toast"));
                return;
            }

            String videoId = VideoInformation.getStartingOrPlayingVideoId();
            String playlistId = VideoInformation.getPlaylistId();
            final long videoTime = VideoInformation.getVideoTime();
            Runnable queueReload = NativePlaylistQueuePatch.isPatchIncluded()
                    ? NativePlaylistQueuePatch.prepareVideoReload(videoId) : null;
            if (queueReload != null) {
                // A converted queue can retain its source playlist in the playing
                // response. Reload only this video, not that replaced playlist.
                playlistId = "";
            }
            String parameterSeparator = "&";
            StringBuilder builder = new StringBuilder(videoId);
            if (!playlistId.isEmpty()) {
                builder.append(parameterSeparator);
                builder.append("list=");
                builder.append(playlistId);
            }
            if (videoTime > 0) {
                builder.append(parameterSeparator);
                builder.append("t=");
                builder.append(videoTime / 1000);
                builder.append('s');
            }
            String builderString = "https://www.youtube.com/watch?v=" + builder;
            Logger.printDebug(() -> "Opening: " + builderString);

            openUrl(builderString, true, queueReload);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to reload video", ex);
        }
    }

    public static PlayerInterface getPlayerInterface() {
        PlayerInterface playerInterface = playerInterfaceRef.get();
        if (playerInterface == null) {
            Utils.showSnackbarShort(str("morphe_dismiss_player_not_available_toast"));
            return null;
        }
        return playerInterface;
    }

    /**
     * Opens a link of YouTube the same as a tap in the app does: the link is turned into the command
     * of the app, such as a video with its playlist and start time, or a playlist page.
     * Other links open the same as a link tapped outside the app.
     * Must be called from the main thread.
     *
     * @param closeCurrentPlayer Closes the player first, so the video playing can be opened again.
     * @return If the link was opened, or is opened once the player is closed.
     */
    public static boolean openUrl(String url, boolean closeCurrentPlayer) {
        return openUrl(url, closeCurrentPlayer, null);
    }

    private static boolean openUrl(String url, boolean closeCurrentPlayer, @Nullable Runnable onDispatch) {
        if (closeCurrentPlayer) {
            RememberPlaybackSpeedPatch.preservePlaybackParametersForReload();

            PlayerInterface playerInterface = getPlayerInterface();
            if (playerInterface != null) {
                blockInlinePlaybackUntilPlayerOpens();
                playerInterface.patch_dismissPlayer();

                Utils.runOnMainThreadDelayed(() -> openUrlNow(url, onDispatch), RELOAD_VIDEO_DELAY_MILLISECONDS);
                return true;
            }
        }

        // A video opened while the miniplayer is being dismissed starts behind the dismissed player,
        // and the screen stays covered by the scrim of the player. Open it once the dismiss ends.
        if (PlayerType.getCurrent() == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED) {
            Logger.printDebug(() -> "Opening once the player is dismissed: " + url);
            PlayerType.getOnChange().addObserver(new Consumer<>() {
                @Override
                public void accept(PlayerType type) {
                    PlayerType.getOnChange().removeObserver(this);
                    // Not while the player changes its state.
                    Utils.runOnMainThreadDelayed(() -> openUrlNow(url, onDispatch), 0);
                }
            });
            return true;
        }

        return openUrlNow(url, onDispatch);
    }

    private static boolean openUrlNow(String url, @Nullable Runnable onDispatch) {
        try {
            if (onDispatch != null) onDispatch.run();

            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String videoId = uri.getQueryParameter("v");
            String playlistId = uri.getQueryParameter("list");

            NavigationEndpoint.Builder command = NavigationEndpoint.newBuilder();
            if ("/watch".equals(path) && videoId != null) {
                WatchEndpoint.Builder watch = WatchEndpoint.newBuilder().setVideoId(videoId);
                if (playlistId != null) {
                    watch.setPlaylistId(playlistId);
                }
                String index = uri.getQueryParameter("index");
                if (index != null) {
                    watch.setIndex(Integer.parseInt(index));
                }
                String time = uri.getQueryParameter("t");
                if (time != null) {
                    watch.setStartTimeSeconds(Long.parseLong(time.endsWith("s")
                            ? time.substring(0, time.length() - 1) : time));
                }
                command.setWatchEndpoint(watch);
            } else if ("/playlist".equals(path) && playlistId != null) {
                command.setBrowseEndpoint(BrowseEndpoint.newBuilder().setBrowseId("VL" + playlistId));
            } else {
                command = null;
            }

            if (command != null) {
                // Android blocks opening links while the app is in the background, such as when
                // a queued video starts. The app runs the command once it is shown again.
                Activity activity = Utils.getActivity();
                final boolean appShown = activity != null && activity.getWindow().getDecorView().isShown();
                if (CommandRouterPatch.executeCommand(command.build().toByteArray(), new HashMap<>(), !appShown)) {
                    Logger.printDebug(() -> "Opened: " + url);
                    return true;
                }
            }

            Context context = Utils.getContext();
            if (context == null) {
                return false;
            }

            Logger.printDebug(() -> "Opening outside the app: " + url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "openUrl failure: " + url, ex);
            return false;
        }
    }
}
