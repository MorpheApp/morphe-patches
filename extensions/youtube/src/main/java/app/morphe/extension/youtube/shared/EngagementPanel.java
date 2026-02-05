package app.morphe.extension.youtube.shared;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class EngagementPanel {
    private static final AtomicReference<String> lastEngagementPanelId = new AtomicReference<>("");

    /**
     * Injection point.
     */
    public static void close() {
        String panelId = getId();
        if (!panelId.isEmpty()) {
            lastEngagementPanelId.set("");
            Logger.printDebug(() -> "EngagementPanel closed, Last panel id: " + panelId);
        }
    }

    /**
     * Injection point.
     */
    public static void open(@Nullable String panelId) {
        if (Utils.isNotEmpty(panelId)) {
            lastEngagementPanelId.set(panelId);
            Logger.printDebug(() -> "EngagementPanel open, New panel id: " + panelId);
        }
    }

    public static boolean isDescription() {
        return getId().equals("video-description-ep-identifier");
    }

    private static String getId() {
        return lastEngagementPanelId.get();
    }

}
