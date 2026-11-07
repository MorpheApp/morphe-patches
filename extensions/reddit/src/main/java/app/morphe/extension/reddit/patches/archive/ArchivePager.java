package app.morphe.extension.reddit.patches.archive;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Timestamp cursor with a bounded tie-resolution query; never silently drops boundary records. */
public final class ArchivePager {
    public static final int PAGE_SIZE = 25;
    interface Source {
        List<ArchiveItem> search(String username, boolean comments, Long before, Long after,
                                 int limit, ArchiveClient.Cancellation cancellation) throws IOException;
    }
    public static final class Page {
        public final List<ArchiveItem> items;
        public final boolean more;
        public final String notice;
        Page(List<ArchiveItem> items, boolean more, String notice) {
            this.items = items; this.more = more; this.notice = notice;
        }
    }
    private Long before;
    private boolean exhausted;
    private String notice = "";
    private final ArrayDeque<ArchiveItem> buffer = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();

    public ArchivePager copy() {
        ArchivePager copy = new ArchivePager();
        copy.before = before; copy.exhausted = exhausted; copy.notice = notice;
        copy.buffer.addAll(buffer); copy.seen.addAll(seen);
        return copy;
    }

    public Page next(Source source, String username, boolean comments,
                     ArchiveClient.Cancellation cancellation) throws IOException {
        cancellation.check();
        if (buffer.size() < PAGE_SIZE && !exhausted) {
            List<ArchiveItem> rows = source.search(username, comments, before, null, PAGE_SIZE, cancellation);
            validateWindow(rows, before, null);
            List<ArchiveItem> combined = new ArrayList<>(rows);
            if (rows.size() == PAGE_SIZE) {
                long boundary = rows.stream().mapToLong(item -> item.createdUtc).min().orElse(0L);
                // Both bounds are exclusive. Fetch the entire oldest second before advancing past it.
                List<ArchiveItem> ties = source.search(username, comments, boundary + 1, boundary - 1, 100, cancellation);
                validateWindow(ties, boundary + 1, boundary - 1);
                if (ties.isEmpty()) throw new IOException("Archive changed while paging. Retry this page.");
                combined.addAll(ties);
                before = boundary;
                if (ties.size() == 100) {
                    exhausted = true;
                    notice = "The archive timestamp limit was reached. Older results stopped to avoid skipping records.";
                }
            } else exhausted = true;
            cancellation.check();
            combined.sort(Comparator.comparingLong((ArchiveItem item) -> item.createdUtc).reversed()
                    .thenComparing(item -> item.id));
            for (ArchiveItem item : combined) if (seen.add(item.id)) buffer.add(item);
        }
        List<ArchiveItem> page = new ArrayList<>();
        while (page.size() < PAGE_SIZE && !buffer.isEmpty()) page.add(buffer.remove());
        cancellation.check();
        return new Page(page, !buffer.isEmpty() || !exhausted, notice);
    }

    private static void validateWindow(List<ArchiveItem> items, Long before, Long after) throws IOException {
        for (ArchiveItem item : items) {
            if ((before != null && item.createdUtc >= before) || (after != null && item.createdUtc <= after)) {
                throw new IOException("Archive returned an inconsistent page. Retry this page.");
            }
        }
    }
}
