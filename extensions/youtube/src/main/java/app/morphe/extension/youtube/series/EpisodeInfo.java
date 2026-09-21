package app.morphe.extension.youtube.series;

import android.content.Context;
import android.icu.text.CompactDecimalFormat;
import android.icu.text.RelativeDateTimeFormatter;
import android.icu.util.ULocale;

import org.json.*;

import java.util.*;
import java.util.regex.*;

/**
 * Optional public playlist metadata. Missing or unknown values never become invented statistics.
 */
final class EpisodeInfo {
    private static final Pattern VIEWS = Pattern.compile("(?i)^(no|[0-9][0-9,.]*)([KMB]?) views?$");
    private static final Pattern AGE =
            Pattern.compile(
                    "(?i)^(?:streamed |premiered )?([0-9]+)"
                            + " (second|minute|hour|day|week|month|year)s? ago$");
    private static final String[] UNITS = {
        "second", "minute", "hour", "day", "week", "month", "year"
    };
    private static final long[] SECONDS = {1, 60, 3600, 86400, 604800, 2592000, 31536000};
    private static final RelativeDateTimeFormatter.RelativeUnit[] RELATIVE = {
        RelativeDateTimeFormatter.RelativeUnit.SECONDS,
                RelativeDateTimeFormatter.RelativeUnit.MINUTES,
        RelativeDateTimeFormatter.RelativeUnit.HOURS, RelativeDateTimeFormatter.RelativeUnit.DAYS,
        RelativeDateTimeFormatter.RelativeUnit.WEEKS, RelativeDateTimeFormatter.RelativeUnit.MONTHS,
        RelativeDateTimeFormatter.RelativeUnit.YEARS
    };

    static String encode(List<TrackerModels.Episode> episodes) {
        JSONObject result = new JSONObject();
        try {
            for (TrackerModels.Episode e : episodes)
                if (!e.videoInfo.isEmpty()) result.put(e.videoId, e.videoInfo);
        } catch (JSONException ignored) {
            return "{}";
        }
        return result.toString();
    }

    static JSONObject decode(String value) {
        try {
            return new JSONObject(value);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    static double views(String part) {
        Matcher match = VIEWS.matcher(part.trim());
        if (!match.matches()) return -1;
        if (match.group(1).equalsIgnoreCase("no")) return 0;
        try {
            double number = Double.parseDouble(match.group(1).replace(",", ""));
            String suffix = match.group(2).toUpperCase(Locale.ROOT);
            number *=
                    suffix.equals("K")
                            ? 1000
                            : suffix.equals("M") ? 1000000 : suffix.equals("B") ? 1000000000 : 1;
            return Double.isFinite(number) && number >= 0 ? number : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static String age(String part, Locale locale, long fetchedAt, long now) {
        Matcher match = AGE.matcher(part.trim());
        if (!match.matches()) return "";
        try {
            long quantity = Long.parseLong(match.group(1));
            if (quantity > 1000000) return "";
            int unit = Arrays.asList(UNITS).indexOf(match.group(2).toLowerCase(Locale.ROOT));
            long elapsed = fetchedAt > 0 && now > fetchedAt ? (now - fetchedAt) / 1000 : 0;
            long seconds = quantity * SECONDS[unit] + elapsed;
            // The source age is rounded, not an exact publication timestamp.
            while (unit < SECONDS.length - 1
                    && seconds >= (unit == 3 ? 2 * SECONDS[unit + 1] : SECONDS[unit + 1])) unit++;
            return RelativeDateTimeFormatter.getInstance(locale)
                    .format(
                            seconds / SECONDS[unit],
                            RelativeDateTimeFormatter.Direction.LAST,
                            RELATIVE[unit]);
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    /** Release age only, without the view count used by episode-list metadata. */
    static String releaseAge(String raw, Locale locale, long fetchedAt, long now) {
        for (String part : raw.split("[•·]")) {
            String relative = age(part, locale, fetchedAt, now);
            if (!relative.isEmpty()) return relative;
        }
        return "";
    }

    static String releaseAge(Context context, String raw, long fetchedAt) {
        return releaseAge(
                raw,
                context.getResources().getConfiguration().getLocales().get(0),
                fetchedAt,
                System.currentTimeMillis());
    }

    static String format(Context context, String raw, long fetchedAt) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String views = "", age = "";
        for (String part : raw.split("[•·]")) {
            double count = views(part);
            if (count >= 0) {
                CompactDecimalFormat formatter =
                        CompactDecimalFormat.getInstance(
                                ULocale.forLocale(locale), CompactDecimalFormat.CompactStyle.SHORT);
                formatter.setMaximumSignificantDigits(3);
                views =
                        UiText.format(
                                context,
                                count == 1
                                        ? "series_tracker_view_count_one"
                                        : count >= 1000000
                                                ? "series_tracker_view_count_large"
                                                : "series_tracker_view_count",
                                formatter.format(count));
            }
            String relative = age(part, locale, fetchedAt, System.currentTimeMillis());
            if (!relative.isEmpty()) age = relative;
        }
        return views.isEmpty() ? age : age.isEmpty() ? views : views + " · " + age;
    }

    private EpisodeInfo() {}
}
