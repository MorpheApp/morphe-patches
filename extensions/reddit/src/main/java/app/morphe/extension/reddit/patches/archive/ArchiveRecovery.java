package app.morphe.extension.reddit.patches.archive;

import java.util.Locale;

/** A display-only field overlay. Null means preserve the corresponding live value. */
public final class ArchiveRecovery {
    public final String title, body, author;
    public final boolean changed;

    private ArchiveRecovery(String title, String body, String author) {
        this.title = title; this.body = body; this.author = author;
        changed = title != null || body != null || author != null;
    }

    public static boolean placeholder(String value) {
        return "[deleted]".equals(value) || "[removed]".equals(value);
    }

    public static String fullname(String value, boolean comments) {
        if (value == null) return null;
        String prefix = comments ? "t1_" : "t3_";
        if (value.startsWith(prefix)) value = value.substring(3);
        return value.matches("[A-Za-z0-9]{1,16}") ? prefix + value.toLowerCase(Locale.ROOT) : null;
    }

    public static boolean eligible(String title, String body, String author, boolean removed) {
        return placeholder(title) || placeholder(body) || placeholder(author)
                || removed && (blank(title) || blank(body) || blank(author));
    }

    public static ArchiveRecovery resolve(String fullname, String parentPost, String title, String body,
                                          String author, boolean removed, ArchiveItem record) {
        if (record == null || !ArchiveMerge.fullname(record).equals(fullname)
                || record.comments && parentPost != null
                && (fullname(parentPost, false) == null || !fullname(parentPost, false).equals(fullname(record.linkId, false))))
            return new ArchiveRecovery(null, null, null);
        boolean deleted = removed || placeholder(title) || placeholder(body);
        return new ArchiveRecovery(record.comments ? null : replacement(title, record.title, deleted),
                replacement(body, record.text, deleted), replacement(author, record.author, deleted));
    }

    private static String replacement(String live, String archived, boolean deleted) {
        return (placeholder(live) || deleted && blank(live)) && !blank(archived) && !placeholder(archived)
                ? archived : null;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
