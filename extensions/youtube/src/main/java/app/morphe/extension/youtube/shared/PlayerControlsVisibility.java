/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.shared;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * PlayerControls visibility state.
 */
public enum PlayerControlsVisibility {
    PLAYER_CONTROLS_VISIBILITY_UNKNOWN,
    PLAYER_CONTROLS_VISIBILITY_WILL_HIDE,
    PLAYER_CONTROLS_VISIBILITY_HIDDEN,
    PLAYER_CONTROLS_VISIBILITY_WILL_SHOW,
    PLAYER_CONTROLS_VISIBILITY_SHOWN;

    private static final Map<String, PlayerControlsVisibility> nameToPlayerControlsVisibility;

    static {
        PlayerControlsVisibility[] values = values();
        nameToPlayerControlsVisibility = new HashMap<>(2 * values.length);
        for (PlayerControlsVisibility visibility : values) {
            nameToPlayerControlsVisibility.put(visibility.name(), visibility);
        }
    }

    private static final Event<PlayerControlsVisibility> onChange = new Event<>();

    private static volatile PlayerControlsVisibility currentPlayerControlsVisibility
            = PLAYER_CONTROLS_VISIBILITY_UNKNOWN; // Read/write from different threads.

    public static Event<PlayerControlsVisibility> getOnChange() {
        return onChange;
    }

    public static void setFromString(String enumName) {
        PlayerControlsVisibility newType = nameToPlayerControlsVisibility.get(enumName);
        if (newType == null) {
            Logger.printException(() -> "Unknown PlayerControlsVisibility encountered: " + enumName);
        } else {
            setCurrent(newType);
        }
    }

    public static PlayerControlsVisibility getCurrent() {
        return currentPlayerControlsVisibility;
    }

    private static void setCurrent(PlayerControlsVisibility type) {
        if (currentPlayerControlsVisibility != type) {
            Logger.printDebug(() -> "Changed to: " + type);

            currentPlayerControlsVisibility = type;
            onChange.invoke(type);
        }
    }
}
