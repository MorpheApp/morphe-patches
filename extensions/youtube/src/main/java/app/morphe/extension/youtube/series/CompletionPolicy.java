package app.morphe.extension.youtube.series;

/** Shared by local playback and imported progress. Settings affect future automatic decisions. */
final class CompletionPolicy {
    static final int DEFAULT_PERCENT = 92, DEFAULT_SECONDS = 30;
    static final long MAX_PERCENT_TAIL_MS = 90_000;

    static int percent(int value) {
        return value >= 1 && value <= 100 ? value : DEFAULT_PERCENT;
    }

    static int seconds(int value) {
        return value >= 0 && value <= 300 ? value : DEFAULT_SECONDS;
    }

    static boolean completed(long position, long duration, int percentage, int remainingSeconds) {
        if (position < 0 || duration <= 0) return false;
        int p = percent(percentage), seconds = seconds(remainingSeconds);
        long threshold = duration / 100 * p + (duration % 100 * p + 99) / 100;
        return (position >= threshold && duration - position <= MAX_PERCENT_TAIL_MS)
                || (seconds > 0 && duration > 60_000 && position >= duration - seconds * 1000L);
    }

    // Percentages are usable for completion only, never as invented resume timestamps.
    static boolean completedPercentage(int observed, long duration, int configured) {
        if (observed < percent(configured) || observed > 100 || duration <= 0) return false;
        long remaining =
                duration / 100 * (100 - observed) + (duration % 100 * (100 - observed) + 99) / 100;
        return remaining <= MAX_PERCENT_TAIL_MS;
    }

    private CompletionPolicy() {}
}
