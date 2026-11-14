/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4659
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

/**
 * Autoplay control rule: whether YouTube may go to the next video of a list.
 * No Android code, so it can be tested on its own.
 *
 * Playlists and queues go on. A Mix (list id RD...) stops after a number of videos.
 * A suggested video (AUTONAV) never plays. Mode OFF stops every list.
 */
public final class AutoplayRule {
    public enum Mode {
        AUTO,
        SINGLE,
        OFF
    }

    private String mixId = "";
    private String lastVideoId = "";
    /** Videos of this Mix that have started, the current one included. */
    private int count;
    /** A user pick (next, previous, a tap in the list) starts the count again. */
    private boolean pick;

    public static boolean isMix(String listId) {
        return listId != null && listId.startsWith("RD");
    }

    /** Called for every loaded video, more than once for the same one. */
    public synchronized void videoLoaded(String listId, String videoId) {
        if (videoId == null || videoId.isEmpty() || videoId.equals(lastVideoId)) {
            return;
        }
        lastVideoId = videoId;
        if (!isMix(listId)) {
            mixId = "";
            count = 0;
            pick = false;
            return;
        }
        if (!listId.equals(mixId) || pick) {
            mixId = listId;
            count = 1;
        } else {
            count++;
        }
        pick = false;
    }

    /**
     * @param intent YouTube navigation intent: NEXT, PREVIOUS, AUTOPLAY, AUTONAV or JUMP.
     * @param stopAfter Mix videos to play in mode AUTO, 0 for no limit.
     * @return True to stop YouTube from going to the next video.
     */
    public synchronized boolean shouldSkip(String intent, String listId, Mode mode, int stopAfter) {
        if ("AUTONAV".equals(intent)) {
            return true;
        }
        if (!"AUTOPLAY".equals(intent)) {
            pick = true;
            return false;
        }
        if (mode == Mode.OFF) {
            return true;
        }
        if (!isMix(listId)) {
            return false;
        }

        final int limit = mode == Mode.SINGLE ? 1 : stopAfter;
        if (limit > 0 && listId.equals(mixId) && count >= limit) {
            // The Mix ends here. Playing it again starts a new count.
            mixId = "";
            return true;
        }
        return false;
    }

    /** @return The position in the Mix, or 0 outside a Mix. */
    public synchronized int mixCount() {
        return mixId.isEmpty() ? 0 : count;
    }
}
