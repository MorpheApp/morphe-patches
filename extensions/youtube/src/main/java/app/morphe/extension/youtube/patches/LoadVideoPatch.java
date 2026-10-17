/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.playback.speed.RememberPlaybackSpeedPatch;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
public final class LoadVideoPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlayerInterface {
        // Method is added during patching.
        void patch_dismissPlayer();
        Parcelable patch_getIntentParcelable(Intent intent);
    }

    /**
     * Interface to deliver intents to the main activity, as {@link Activity#onNewIntent(Intent)} is protected.
     */
    public interface MainActivityInterface {
        // Method is added during patching.
        void patch_onNewIntent(Intent intent);
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

    private static WeakReference<PlayerInterface> playerInterfaceRef = new WeakReference<>(null);

    private static WeakReference<InlinePlaybackInterface> inlinePlaybackInterfaceRef = new WeakReference<>(null);

    private static boolean inlinePlaybackBlocked;

    /**
     * Incremented each time playback in feeds is blocked, so the timeout of an older block does nothing.
     */
    private static int inlinePlaybackBlockId;

    private static final Function1<PlayerType, Unit> unblockInlinePlaybackOnPlayerOpen = type -> {
        if (type.isMaximizedOrFullscreen()) {
            unblockInlinePlayback();
        }
        return Unit.INSTANCE;
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
     */
    private static void blockInlinePlaybackUntilPlayerOpens() {
        InlinePlaybackInterface inlinePlaybackInterface = inlinePlaybackInterfaceRef.get();
        if (inlinePlaybackInterface == null) {
            Logger.printDebug(() -> "Cannot block playback in feeds, interface is not set");
            return;
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

    public static boolean isPlayerInterfaceAvailable() {
        return playerInterfaceRef.get() != null;
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
                Utils.showToastShort(str("morphe_dismiss_player_not_available_toast"));
                return;
            }

            String videoId = VideoInformation.getStartingOrPlayingVideoId();
            String playlistId = VideoInformation.getPlaylistId();
            final long videoTime = VideoInformation.getVideoTime();
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

            openVideoIntent(builderString, true);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to reload video", ex);
        }
    }

    public static PlayerInterface getPlayerInterface() {
        PlayerInterface playerInterface = playerInterfaceRef.get();
        if (playerInterface == null) {
            Utils.showToastShort(str("morphe_dismiss_player_not_available_toast"));
            return null;
        }
        return playerInterface;
    }

    public static void openVideoIntent(String url, boolean closeCurrentPlayerInstance) {
        int loadVideoDelay = 0;

        // Close the current player instance.
        PlayerInterface playerInterface;
        if (closeCurrentPlayerInstance) {
            RememberPlaybackSpeedPatch.preservePlaybackParametersForReload();
        }
        if (closeCurrentPlayerInstance && (playerInterface = getPlayerInterface()) != null) {
            blockInlinePlaybackUntilPlayerOpens();
            playerInterface.patch_dismissPlayer();

            loadVideoDelay = 500;
        }

        // Reopens the video after 0ms or 500ms.
        Utils.runOnMainThreadDelayed(() -> {
            Context context = Utils.getContext();

            if (context == null) {
                return;
            }

            Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(url));
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }, loadVideoDelay);
    }

    // This method opens a video based on hardcoded parameters found in an obfuscated class.
    // It is generally faster than the solution that uses 'Intent.FLAG_ACTIVITY_NEW_TASK'.
    public static void openVideoIntentWithInternalContext(String videoIDWithParams) {
        PlayerInterface playerInterface;
        if ((playerInterface = getPlayerInterface()) != null) {
            Context context = Utils.getActivity();
            // No videoID is needed to put inside the Intent initialization.
            Intent reloadVideoIntent = new Intent();
            reloadVideoIntent.setComponent(new ComponentName(
                    context,
                    "com.google.android.apps.youtube.app.watchwhile.InternalMainActivity"
            ));
            // NEW_TASK intent is not needed by this code.
            reloadVideoIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Always put 'inventory_identifier' putExtra before 'watch'
            // putExtra, to ensure the patch works correctly.
            reloadVideoIntent.putExtra(
                    "android.intent.extra.inventory_identifier", new String[]{"vnd.youtube://" + videoIDWithParams}
            );
            // Get the needed Parcelable object from a static method, which will
            // read inventory_identifier inside the currently built Intent.
            reloadVideoIntent.putExtra("watch", playerInterface.patch_getIntentParcelable(reloadVideoIntent));

            context.startActivity(reloadVideoIntent);
        }
    }

    /**
     * Starts a video by handing the watch intent to the running activity, which does not need the app to be on screen.
     */
    public static boolean deliverVideoIntentInProcess(String videoId) {
        try {
            PlayerInterface playerInterface = playerInterfaceRef.get();
            Activity activity = Utils.getActivity();
            if (playerInterface == null || !(activity instanceof MainActivityInterface mainActivity)) {
                return false;
            }

            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    activity,
                    "com.google.android.apps.youtube.app.watchwhile.InternalMainActivity"
            ));
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("android.intent.extra.inventory_identifier", new String[]{"vnd.youtube://" + videoId});
            intent.putExtra("watch", playerInterface.patch_getIntentParcelable(intent));

            mainActivity.patch_onNewIntent(intent);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "deliverVideoIntentInProcess failure", ex);
            return false;
        }
    }
}
