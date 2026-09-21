package app.morphe.extension.youtube.series;

/** Shared by local playback and imported progress. Settings affect future automatic decisions. */
final class CompletionPolicy {
    static final int DEFAULT_PERCENT = 92, DEFAULT_SECONDS = 30;

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
        return position >= threshold
                || (seconds > 0 && duration > 60_000 && position >= duration - seconds * 1000L);
    }

    private CompletionPolicy() {}
}
