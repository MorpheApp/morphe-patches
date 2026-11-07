/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.misc;

import java.util.Locale;

public final class MediaTime {

    private MediaTime() {
    }

    /**
     * @return The time as the video player shows it, such as 0:05, 12:34 or 1:02:03.
     *         A negative time is shown as 0:00.
     */
    public static String format(long milliseconds) {
        final long seconds = Math.max(0, milliseconds) / 1000;
        final long hours = seconds / 3600;
        final long minutes = (seconds / 60) % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60);
    }
}
