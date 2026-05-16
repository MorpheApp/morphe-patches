package app.morphe.extension.youtube.shared

import app.morphe.extension.shared.Logger

/**
 * Creator channel state.
 */
class CreatorChannelState {
    companion object {

        @JvmStatic
        fun setOpen(open: Boolean) {
            if (isOpen != open) {
                isOpen = open
                Logger.printDebug { "CreatorChannelState open changed to: $isOpen" }
                onChange(open)
            }
        }

        @Volatile
        private var isOpen = false

        /**
         * Shorts player state change listener.
         */
        @JvmStatic
        val onChange = Event<Boolean>()

        /**
         * If the Shorts player is currently open.
         */
        @JvmStatic
        fun isOpen(): Boolean {
            return isOpen
        }
    }
}
