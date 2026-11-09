package app.morphe.extension.youtube.shared;

import app.morphe.extension.shared.Logger;

/**
 * Shorts player state.
 */
public final class ShortsPlayerState {

    private static volatile boolean open;

    /**
     * Shorts player state change listener.
     */
    private static final Event<Boolean> onChange = new Event<>();

    public static void setOpen(boolean isOpen) {
        if (open != isOpen) {
            open = isOpen;
            Logger.printDebug(() -> "ShortsPlayerState open changed to: " + isOpen);
            onChange.invoke(isOpen);
        }
    }

    public static Event<Boolean> getOnChange() {
        return onChange;
    }

    /**
     * If the Shorts player is currently open.
     */
    public static boolean isOpen() {
        return open;
    }
}
