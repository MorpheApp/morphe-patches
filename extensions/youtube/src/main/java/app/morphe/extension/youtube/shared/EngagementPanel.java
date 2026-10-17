package app.morphe.extension.youtube.shared;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class EngagementPanel {

    private static final ConcurrentLinkedDeque<String> engagementPanelIds = new ConcurrentLinkedDeque<>();
    // The closing of certain panels (such as the one for reporting a comment) is not being
    // recorded. Therefore, a queue is needed to track the closed highest hierarchy
    // panels, in order to automatically close the panels lower in the hierarchy.
    private static final List<String> videoDescriptionPanelName = List.of("video-description-ep-identifier");

    /**
     * Injection point.
     */
    public static void close(@Nullable String panelId) {
        if (Utils.isNotEmpty(panelId)) {
            if (engagementPanelIds.contains(panelId)) {
                while (!engagementPanelIds.isEmpty()) {
                    String panelToRemove = engagementPanelIds.pollLast();

                    if (panelToRemove != null) {
                        Logger.printDebug(() -> "EngagementPanel closed: " + panelToRemove);
                        if (panelToRemove.equals(panelId)) {
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Injection point.
     */
    public static void open(@Nullable String panelId) {
        if (Utils.isNotEmpty(panelId)) {
            engagementPanelIds.addLast(panelId);
            Logger.printDebug(() -> "EngagementPanel open: " + panelId);
        }
    }

    public static boolean isDescription() {
        return checkIdsInQueue(videoDescriptionPanelName);
    }

    public static boolean checkIdsInQueue(List<String> ids) {
        if (ids == null) {
            return false;
        }
        for (String id : ids) {
            if (engagementPanelIds.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
