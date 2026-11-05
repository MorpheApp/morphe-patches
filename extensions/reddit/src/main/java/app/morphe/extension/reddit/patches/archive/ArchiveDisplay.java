package app.morphe.extension.reddit.patches.archive;

import java.util.Locale;

final class ArchiveDisplay {
    static String count(Long count, String unknown) {
        if (count == null) return unknown;
        long magnitude = count == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(count);
        if (magnitude < 1000) return Long.toString(count);
        double divisor = magnitude >= 1000000 ? 1000000.0 : 1000.0;
        String value = String.format(Locale.getDefault(), "%.1f", count / divisor);
        String zero = String.format(Locale.getDefault(), "%.1f", 0.0).substring(1);
        if (value.endsWith(zero)) value = value.substring(0, value.length() - zero.length());
        return value + (magnitude >= 1000000 ? "m" : "k");
    }

    static String age(long createdUtc, long nowSeconds) {
        long seconds = Math.max(0, nowSeconds - createdUtc);
        if (seconds < 60) return "now";
        if (seconds < 3600) return seconds / 60 + "m";
        if (seconds < 86400) return seconds / 3600 + "h";
        if (seconds < 604800) return seconds / 86400 + "d";
        if (seconds < 31536000) return seconds / 604800 + "w";
        return seconds / 31536000 + "y";
    }
}
