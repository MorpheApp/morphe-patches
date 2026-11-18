package app.morphe.extension.youtube.shared;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class EngagementPanel {

    /**
     * @param inShorts If the panel was opened in the Shorts player.
     */
    private record Panel(String id, boolean inShorts) {
    }

    private static final ConcurrentLinkedDeque<Panel> engagementPanels = new ConcurrentLinkedDeque<>();
    // The closing of certain panels (such as the one for reporting a comment) is not being
    // recorded. Therefore, a queue is needed to track the closed highest hierarchy
    // panels, in order to automatically close the panels lower in the hierarchy.
    private static final List<String> videoDescriptionPanelName = List.of("video-description-ep-identifier");

    /**
     * Injection point.
     */
    public static void close(@Nullable String panelId) {
        if (Utils.isNotEmpty(panelId)) {
            if (contains(panelId)) {
                while (!engagementPanels.isEmpty()) {
                    Panel panelToRemove = engagementPanels.pollLast();

                    if (panelToRemove != null) {
                        Logger.printDebug(() -> "EngagementPanel closed: " + panelToRemove.id());
                        if (panelToRemove.id().equals(panelId)) {
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
            // A panel can be opened again while it is open, such as the live chat of a live stream,
            // and is then closed only once.
            remove(panelId);
            engagementPanels.addLast(new Panel(panelId, ShortsPlayerState.isOpen()));
            Logger.printDebug(() -> "EngagementPanel open: " + panelId);
        }
    }

    private static boolean contains(String panelId) {
        for (Panel panel : engagementPanels) {
            if (panel.id().equals(panelId)) {
                return true;
            }
        }
        return false;
    }

    private static void remove(String panelId) {
        for (Iterator<Panel> iterator = engagementPanels.iterator(); iterator.hasNext(); ) {
            if (iterator.next().id().equals(panelId)) {
                iterator.remove();
            }
        }
    }

    public static boolean isDescription() {
        return checkIdsInQueue(videoDescriptionPanelName);
    }

    /**
     * @return The id of the panel opened last, or null if no panel is open.
     */
    @Nullable
    public static String getLastOpenedPanelId() {
        Panel panel = engagementPanels.peekLast();
        return panel == null ? null : panel.id();
    }

    /**
     * The closing of a panel of the regular player is not always recorded, such as of the live chat
     * of a live stream that is minimized before the Shorts player opens, so the panels of the
     * regular player are not used for the Shorts player.
     *
     * @return The id of the panel opened last in the Shorts player, or null if no panel is open there.
     */
    @Nullable
    public static String getLastOpenedShortsPanelId() {
        for (Iterator<Panel> iterator = engagementPanels.descendingIterator(); iterator.hasNext(); ) {
            Panel panel = iterator.next();
            if (panel.inShorts()) {
                return panel.id();
            }
        }
        return null;
    }

    public static boolean checkIdsInQueue(List<String> ids) {
        if (ids == null) {
            return false;
        }
        for (String id : ids) {
            if (contains(id)) {
                return true;
            }
        }
        return false;
    }
}
