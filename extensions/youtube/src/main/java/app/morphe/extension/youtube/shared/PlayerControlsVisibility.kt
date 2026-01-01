package app.morphe.extension.youtube.shared

import app.morphe.extension.shared.Logger

/**
 * PlayerControls visibility state.
 */
enum class PlayerControlsVisibility {
    PLAYER_CONTROLS_VISIBILITY_UNKNOWN,
    PLAYER_CONTROLS_VISIBILITY_WILL_HIDE,
    PLAYER_CONTROLS_VISIBILITY_HIDDEN,
    PLAYER_CONTROLS_VISIBILITY_WILL_SHOW,
    PLAYER_CONTROLS_VISIBILITY_SHOWN;

    companion object {

        private val nameToPlayerControlsVisibility = PlayerControlsVisibility.entries.associateBy { it.name }

        @JvmStatic
        fun setFromString(enumName: String) {
            val newType = nameToPlayerControlsVisibility[enumName]
            if (newType == null) {
                Logger.printException { "Unknown PlayerControlsVisibility encountered: $enumName" }
            } else {
                current = newType
            }
        }

        @JvmStatic
        var current
            get() = currentPlayerControlsVisibility
            private set(type) {
                if (currentPlayerControlsVisibility != type) {
                    Logger.printDebug { "Changed to: $type" }

                    currentPlayerControlsVisibility = type
                    onChange(type)
                }
            }

        @Volatile // Read/write from different threads.
        private var currentPlayerControlsVisibility = PLAYER_CONTROLS_VISIBILITY_UNKNOWN

        @JvmStatic
        val onChange = Event<PlayerControlsVisibility>()
    }
}