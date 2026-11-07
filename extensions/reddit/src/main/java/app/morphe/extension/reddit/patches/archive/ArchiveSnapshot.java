package app.morphe.extension.reddit.patches.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable, keyed items for Reddit's own lazy feed. No view or scroll state lives here. */
final class ArchiveSnapshot {
    static final int INDICATOR = 0, CARD = 1, STATUS = 2, SORT = 3;
    final List<Row> rows;
    final int itemCount;

    ArchiveSnapshot(String username, boolean comments, List<ArchiveItem> items,
                    Map<String, ArchivePostDetails> details, boolean loading, boolean loaded,
                    boolean more, String notice, String error) {
        String prefix = prefix(username, comments);
        ArrayList<Row> result = new ArrayList<>();
        itemCount = items.size();
        if (!items.isEmpty()) result.add(new Row(prefix + "indicator", INDICATOR, -1, null, null, "", false, false));
        for (int i = 0; i < items.size(); i++) {
            ArchiveItem item = items.get(i);
            ArchivePostDetails detail = details.get(item.comments ? item.linkId : item.id);
            if (!item.comments && detail != null && !item.author.equalsIgnoreCase(detail.author)) detail = null;
            result.add(new Row(prefix + (comments ? "t1_" : "t3_") + item.id, CARD, i, item, detail, "", false, false));
        }
        String message = loading ? (items.isEmpty() ? "Loading archived history…" : "Loading older history…")
                : error != null ? error
                : loaded && items.isEmpty() ? "No archived " + (comments ? "comments" : "posts")
                    + " found. The archive may not have captured this history."
                : !notice.isEmpty() ? notice
                : loaded && !more ? "End of archived history" : "";
        // Keep a loaded feed's footer key even while idle. Initial loading/error has a
        // different key so the first page does not anchor the viewport to its new end.
        // Footer visibility can briefly describe the previous layout after a page arrives.
        // Only actual archive cards may trigger paging or preview enrichment.
        result.add(new Row(prefix + (items.isEmpty() ? "initial_status" : "status"), STATUS, -1,
                null, null, message, loading, !loading && error != null));
        rows = Collections.unmodifiableList(result);
    }

    static String prefix(String username, boolean comments) {
        return "morphe_archive|" + username.toLowerCase(Locale.ROOT) + "|" + (comments ? "comments" : "posts") + "|";
    }

    /** Reddit optionally appends the section index to its lazy-item key. */
    static boolean matches(String nativeKey, String rowKey) {
        if (nativeKey == null || !nativeKey.startsWith(rowKey)) return false;
        if (nativeKey.length() == rowKey.length()) return true;
        int start = rowKey.length();
        if (nativeKey.charAt(start++) != '_' || start == nativeKey.length()) return false;
        for (int i = start; i < nativeKey.length(); i++) if (!Character.isDigit(nativeKey.charAt(i))) return false;
        return true;
    }

    static final class Row {
        final String key;
        final int kind, itemIndex;
        final ArchiveItem item;
        final ArchivePostDetails details;
        final String message;
        final String provenance;
        final boolean loading, retry;
        Row(String key, int kind, int itemIndex, ArchiveItem item, ArchivePostDetails details,
            String message, boolean loading, boolean retry) {
            this(key, kind, itemIndex, item, details, message, loading, retry, "Archived");
        }
        Row(String key, int kind, int itemIndex, ArchiveItem item, ArchivePostDetails details,
            String message, boolean loading, boolean retry, String provenance) {
            this.key = key; this.kind = kind; this.itemIndex = itemIndex; this.item = item;
            this.details = details; this.message = message; this.loading = loading; this.retry = retry;
            this.provenance = provenance;
        }
    }
}
