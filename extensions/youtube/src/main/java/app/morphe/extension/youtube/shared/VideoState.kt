package app.morphe.extension.youtube.shared

import app.morphe.extension.shared.Logger
import app.morphe.extension.youtube.patches.VideoInformation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * VideoState playback state.
 */
enum class VideoState {
    NEW,
    PLAYING,
    PAUSED,
    RECOVERABLE_ERROR,
    UNRECOVERABLE_ERROR,

    /**
     * @see [VideoInformation.isAtEndOfVideo]
     */
    ENDED,

    ;

    companion object {

        private val nameToVideoState = VideoState.entries.associateBy { it.name }

        private val onPlayingListeners = CopyOnWriteArrayList<Runnable>()
        private val onNotPlayingListeners = CopyOnWriteArrayList<Runnable>()

        /** Add a listener that is run when state changes to PLAYING. Used e.g. by VOT to resume translation. */
        @JvmStatic
        fun addOnPlayingListener(listener: Runnable) {
            onPlayingListeners.add(listener)
        }

        /** Add a listener that is run when state changes to non-PLAYING (PAUSED, ENDED, etc). Used e.g. by VOT to pause translation immediately. */
        @JvmStatic
        fun addOnNotPlayingListener(listener: Runnable) {
            onNotPlayingListeners.add(listener)
        }

        @JvmStatic
        fun setFromString(enumName: String) {
            val state = nameToVideoState[enumName]
            if (state == null) {
                Logger.printException { "Unknown VideoState encountered: $enumName" }
                return
            }
            current = state
        }

        /**
         * Depending on which hook this is called from,
         * this value may not be up to date with the actual playback state.
         */
        @JvmStatic
        var current: VideoState?
            get() = currentVideoState
            private set(value) {
                if (currentVideoState != value) {
                    Logger.printDebug { "VideoState changed to: $value" }
                    currentVideoState = value
                    if (value == PLAYING) {
                        onPlayingListeners.forEach {
                            try {
                                it.run()
                            } catch (e: Exception) {
                                Logger.printException { "OnPlaying listener error: ${e.message}" }
                            }
                        }
                    } else if (value != null) {
                        onNotPlayingListeners.forEach {
                            try {
                                it.run()
                            } catch (e: Exception) {
                                Logger.printException { "OnNotPlaying listener error: ${e.message}" }
                            }
                        }
                    }
                }
            }

        @Volatile // Read/write from different threads.
        private var currentVideoState: VideoState? = null
    }
}
