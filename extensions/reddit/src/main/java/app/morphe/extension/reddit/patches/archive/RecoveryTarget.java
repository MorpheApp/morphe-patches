package app.morphe.extension.reddit.patches.archive;

/** Only text fields actually represented by this host presentation model participate. */
public final class RecoveryTarget {
    final int kind;
    final String id, parent, title, body, author, authorPrefix;
    final boolean removed;
    public RecoveryTarget(int kind, String id, String parent, String title, String body, String author, boolean removed) {
        this.kind = kind;
        this.id = ArchiveRecovery.fullname(id, kind == 6 || kind == 8 || kind == 9);
        this.parent = parent;
        this.title = kind == 1 || kind == 4 || kind == 10 || kind == 11 || kind == 12 ? title : "\u0000";
        this.body = kind == 2 || kind == 5 || kind == 6 || kind == 7 || kind == 8 || kind == 9 || kind == 10 || kind == 11
                ? kind == 5 ? ArchiveRichText.plain(body) : body : "\u0000";
        authorPrefix = author != null && author.startsWith("u/") ? "u/" : "";
        this.author = kind == 3 || kind == 9 || kind == 10
                ? author == null ? null : author.substring(authorPrefix.length()) : "\u0000";
        this.removed = removed;
    }
}
