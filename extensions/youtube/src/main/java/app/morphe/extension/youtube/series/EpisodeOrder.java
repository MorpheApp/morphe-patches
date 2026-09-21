package app.morphe.extension.youtube.series;

import java.util.*;
import java.util.regex.*;

/** Conservative direction inference for a new catalog; never sorts individual episodes. */
final class EpisodeOrder {
    enum Direction {
        PLAYLIST,
        REVERSE,
        UNKNOWN
    }

    private static final Pattern COMPACT =
            Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])S(\\d{1,3})[ ._-]*E(\\d{1,4})(?!\\d)");
    private static final Pattern EPISODE =
            Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}_])(?:episode|épisode|ep\\.?)\\s*#?\\s*(\\d{1,4})(?!\\d)");
    private static final Pattern SEASON =
            Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:season|saison)\\s*(\\d{1,3})(?!\\d)");
    private static final Pattern AGE =
            Pattern.compile(
                    "(?iu)^(?:streamed |premiered )?(\\d{1,6})"
                            + " (second|minute|hour|day|week|month|year)s? ago$");

    static Direction infer(List<TrackerModels.Episode> catalog) {
        List<TrackerModels.Episode> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TrackerModels.Episode e : catalog)
            if (e.available && seen.add(e.videoId)) episodes.add(e);
        if (episodes.size() < 2) return Direction.UNKNOWN;
        List<Long> numbers = new ArrayList<>();
        Boolean seasonal = null;
        boolean mixed = false;
        for (TrackerModels.Episode e : episodes) {
            long[] number = number(e.title);
            if (number == null) continue;
            boolean hasSeason = number[0] >= 0;
            if (seasonal != null && seasonal != hasSeason) mixed = true;
            seasonal = hasSeason;
            numbers.add(Math.max(0, number[0]) * 10000 + number[1]);
        }
        // Recognizable episode numbers outrank upload dates, including conflicting numbers.
        if (numbers.size() >= 2 && numbers.size() * 5 >= episodes.size() * 3) {
            if (mixed) return Direction.UNKNOWN;
            int direction = 0;
            for (int i = 1; i < numbers.size(); i++) {
                int next = Long.compare(numbers.get(i), numbers.get(i - 1));
                if (next == 0 || (direction != 0 && next != direction)) return Direction.UNKNOWN;
                direction = next;
            }
            return direction > 0 ? Direction.PLAYLIST : Direction.REVERSE;
        }
        int dates = 0, votes = 0, direction = 0;
        long earliestUpper = Long.MAX_VALUE, latestLower = Long.MIN_VALUE;
        for (TrackerModels.Episode e : episodes) {
            long[] age = age(e.videoInfo);
            if (age == null) continue;
            dates++;
            // Compare with all earlier rows via their extremes. Overlapping rounded ages
            // do not provide evidence; conflicting pairs reject the whole suggestion.
            boolean older = age[0] >= earliestUpper;
            boolean newer = age[1] <= latestLower;
            if (older && newer) return Direction.UNKNOWN;
            int next = older ? -1 : newer ? 1 : 0;
            if (next != 0) {
                if (direction != 0 && next != direction) return Direction.UNKNOWN;
                direction = next;
                votes++;
            }
            earliestUpper = Math.min(earliestUpper, age[1]);
            latestLower = Math.max(latestLower, age[0]);
        }
        if (dates < 3 || dates * 5 < episodes.size() * 3 || votes < 2) return Direction.UNKNOWN;
        return direction > 0 ? Direction.PLAYLIST : Direction.REVERSE;
    }

    private static long[] number(String title) {
        Matcher compact = COMPACT.matcher(title);
        if (compact.find()) {
            long[] value = {Long.parseLong(compact.group(1)), Long.parseLong(compact.group(2))};
            return compact.find() ? null : value;
        }
        Matcher episode = EPISODE.matcher(title);
        if (!episode.find()) return null;
        long n = Long.parseLong(episode.group(1));
        if (episode.find()) return null; // Episode ranges and compilations are ambiguous.
        Matcher season = SEASON.matcher(title);
        return new long[] {season.find() ? Long.parseLong(season.group(1)) : -1, n};
    }

    private static long[] age(String metadata) {
        for (String part : metadata.split("[•·]")) {
            Matcher match = AGE.matcher(part.trim());
            if (!match.matches()) continue;
            long count = Long.parseLong(match.group(1));
            String unit = match.group(2).toLowerCase(Locale.ROOT);
            long low, high;
            switch (unit) {
                case "second":
                    low = high = 1;
                    break;
                case "minute":
                    low = high = 60;
                    break;
                case "hour":
                    low = high = 3600;
                    break;
                case "day":
                    low = high = 86400;
                    break;
                case "week":
                    low = high = 604800;
                    break;
                case "month":
                    low = 28 * 86400L;
                    high = 31 * 86400L;
                    break;
                default:
                    low = 365 * 86400L;
                    high = 366 * 86400L;
            }
            return new long[] {count * low, (count + 1) * high};
        }
        return null;
    }

    static TrackerModels.Episode first(List<TrackerModels.Episode> episodes, boolean reverse) {
        for (int i = 0; i < episodes.size(); i++) {
            TrackerModels.Episode e = episodes.get(reverse ? episodes.size() - 1 - i : i);
            if (e.available) return e;
        }
        return null;
    }

    private EpisodeOrder() {}
}
