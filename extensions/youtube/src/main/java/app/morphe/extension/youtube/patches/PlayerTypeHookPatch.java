package app.morphe.extension.youtube.patches;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.shared.VideoState;

@SuppressWarnings("unused")
public class PlayerTypeHookPatch {

    /**
     * Number of Shorts player views attached to a window. Only accessed on the main thread.
     */
    private static int attachedShortsPlayers;

    /**
     * Injection point.
     */
    public static void setPlayerType(@Nullable Enum<?> youTubePlayerType) {
        if (youTubePlayerType == null) return;

        PlayerType.setFromString(youTubePlayerType.name());
    }

    /**
     * Injection point.
     */
    public static void setVideoState(@Nullable Enum<?> youTubeVideoState) {
        if (youTubeVideoState == null) return;

        VideoState.setFromString(youTubeVideoState.name());
    }

    /**
     * Injection point.
     * <p>
     * Add a listener to the shorts player overlay View.
     * Triggered when a shorts player is attached or detached to Windows.
     *
     * @param view shorts player overlay (R.id.reel_watch_player).
     */
    public static void onShortsCreate(View view) {
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@Nullable View v) {
                attachedShortsPlayers++;
                ShortsPlayerState.setOpen(true);
            }

            @Override
            public void onViewDetachedFromWindow(@Nullable View v) {
                // More than one Shorts player can be attached at the same time,
                // such as a Shorts live stream opened from the Shorts feed.
                // The Shorts player is closed only when the last one is detached.
                attachedShortsPlayers = Math.max(0, attachedShortsPlayers - 1);
                ShortsPlayerState.setOpen(attachedShortsPlayers > 0);
            }
        });
    }
}
