package app.morphe.extension.reddit.patches.archive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure merge: indices refer to immutable inputs, never to positions in the merged list. */
public final class ArchiveMerge {
    private ArchiveMerge() {}
    public static final class Entry {
        public final int nativeIndex, archiveIndex;
        public final String status;
        Entry(int nativeIndex, int archiveIndex, String status) {
            this.nativeIndex = nativeIndex; this.archiveIndex = archiveIndex; this.status = status;
        }
    }
    public static String fullname(ArchiveItem item) { return (item.comments ? "t1_" : "t3_") + item.id; }
    public static boolean recoverable(ArchiveItem item) {
        return !item.text.isBlank() && ArchiveNativeItem.status(item.text).isEmpty()
                || !item.comments && !item.title.isBlank() && ArchiveNativeItem.status(item.title).isEmpty();
    }

    public static List<Entry> merge(List<ArchiveNativeItem> nativeItems, List<ArchiveItem> archive,
                                     boolean nativeMore, boolean nativeReady) {
        Map<String, Integer> live = new HashMap<>();
        Map<String, Integer> archived = new HashMap<>();
        long oldest = Long.MAX_VALUE;
        boolean unknownTime = false;
        for (int i = 0; i < nativeItems.size(); i++) {
            ArchiveNativeItem item = nativeItems.get(i);
            if (item == null) continue;
            Integer previous = live.get(item.fullname);
            if (previous == null || nativeItems.get(previous).placeholder && !item.placeholder
                    || nativeItems.get(previous).placeholder == item.placeholder && !nativeItems.get(previous).pinned && item.pinned)
                live.put(item.fullname, i);
            if (!item.pinned) {
                if (item.createdUtc <= 0) unknownTime = true;
                else oldest = Math.min(oldest, item.createdUtc);
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < archive.size(); i++) {
            ArchiveItem item = archive.get(i);
            String id = fullname(item);
            if (!recoverable(item) || archived.putIfAbsent(id, i) != null || live.containsKey(id)) continue;
            // Do not cross an unobserved native page boundary, including timestamp ties.
            if (nativeReady && (!nativeMore || !unknownTime && oldest != Long.MAX_VALUE && item.createdUtc > oldest)) missing.add(i);
        }
        missing.sort(Comparator.comparingLong((Integer i) -> archive.get(i).createdUtc).reversed()
                .thenComparing(i -> fullname(archive.get(i))));
        List<Entry> result = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        int next = 0;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < nativeItems.size(); i++) order.add(i);
        // Keep headers/ads/unknown metadata in their native slots. Order each known card run.
        for (int start = 0; start < order.size();) {
            ArchiveNativeItem first = nativeItems.get(start);
            if (first == null || first.createdUtc <= 0) { start++; continue; }
            int end = start + 1;
            while (end < order.size() && nativeItems.get(end) != null && nativeItems.get(end).createdUtc > 0) end++;
            order.subList(start, end).sort(Comparator.comparing((Integer i) -> !nativeItems.get(i).pinned)
                    .thenComparing(Comparator.comparingLong((Integer i) -> nativeItems.get(i).createdUtc).reversed())
                    .thenComparing(i -> nativeItems.get(i).fullname));
            start = end;
        }
        for (int i : order) {
            ArchiveNativeItem item = nativeItems.get(i);
            if (item != null && live.get(item.fullname) != i) continue;
            if (item != null && !emitted.add(item.fullname)) continue;
            if (item != null && !item.pinned && item.createdUtc > 0) {
                while (next < missing.size()) {
                    ArchiveItem candidate = archive.get(missing.get(next));
                    if (candidate.createdUtc < item.createdUtc || candidate.createdUtc == item.createdUtc
                            && fullname(candidate).compareTo(item.fullname) > 0) break;
                    result.add(new Entry(-1, missing.get(next++), "Archived"));
                }
            }
            Integer recovered = item == null ? null : archived.get(item.fullname);
            if (item != null && item.placeholder && !item.status.isEmpty() && recovered != null && recoverable(archive.get(recovered)))
                result.add(new Entry(i, recovered, item.status));
            else result.add(new Entry(i, -1, ""));
        }
        while (next < missing.size()) result.add(new Entry(-1, missing.get(next++), "Archived"));
        return java.util.Collections.unmodifiableList(result);
    }
}
