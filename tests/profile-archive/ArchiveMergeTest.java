package app.morphe.extension.reddit.patches.archive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;

public final class ArchiveMergeTest {
    private static int assertions;
    public static void main(String[] args) {
        List<ArchiveItem> archive = List.of(post("a", 300), post("b", 250), post("c", 200), post("d", 100));
        List<ArchiveNativeItem> live = List.of(nativePost("a", 300), nativePost("c", 200));
        expect("n0,a1,n1", describe(ArchiveMerge.merge(live, archive, true, true)));
        expect("n0,a1,n1,a3", describe(ArchiveMerge.merge(live, archive, false, true)));
        expect("n0,n1", describe(ArchiveMerge.merge(live, archive, false, false)));
        expect("", describe(ArchiveMerge.merge(List.of(), archive, true, true)));
        expect("a0,a1,a2,a3", describe(ArchiveMerge.merge(List.of(), archive, false, true)));
        expect("n0,n1", describe(ArchiveMerge.merge(live, List.of(), false, true)));

        List<ArchiveNativeItem> deleted = List.of(new ArchiveNativeItem("t3_a", 300, false, "Deleted"), nativePost("c", 200));
        List<ArchiveMerge.Entry> result = ArchiveMerge.merge(deleted, archive, false, true);
        expect("a0,a1,n1,a3", describe(result));
        expect(0, result.get(0).nativeIndex);
        expect("Deleted", result.get(0).status);
        expect("Archived", result.get(1).status);
        List<ArchiveNativeItem> removed = List.of(new ArchiveNativeItem("t3_a", 300, false, "Removed"));
        expect("Removed", ArchiveMerge.merge(removed, archive, false, true).get(0).status);
        expect("n0", describe(ArchiveMerge.merge(List.of(new ArchiveNativeItem("t3_a", 300, false,
                "Removed", false)), List.of(post("a", 300)), false, true)));
        expect("n1,n0", describe(ArchiveMerge.merge(List.of(nativePost("b", 100), nativePost("a", 100)), List.of(), false, true)));
        expect("n1,n0", describe(ArchiveMerge.merge(List.of(nativePost("a", 300), new ArchiveNativeItem("t3_d", 100, true, "")), List.of(), false, true)));
        expect("n1", describe(ArchiveMerge.merge(List.of(new ArchiveNativeItem("t3_a", 300, false, "Deleted"), nativePost("a", 300)), List.of(post("a", 300)), false, true)));

        List<ArchiveNativeItem> pinned = Arrays.asList(null, new ArchiveNativeItem("t3_d", 100, true, ""), nativePost("a", 300), null, nativePost("c", 200));
        expect("n0,n1,n2,n3,a1,n4", describe(ArchiveMerge.merge(pinned, archive, true, true)));
        expect("n0,n1,n2,n3,a1,n4", describe(ArchiveMerge.merge(pinned, archive, false, true)));
        expect("n0,n1", describe(ArchiveMerge.merge(List.of(nativePost("a", 0), nativePost("c", 200)), archive, true, true)));
        // Pinned old content must not widen the loaded chronological window.
        expect("n0,n1", describe(ArchiveMerge.merge(List.of(new ArchiveNativeItem("t3_d", 100, true, ""), nativePost("a", 300)), archive, true, true)));
        expect("n0", describe(ArchiveMerge.merge(List.of(nativePost("a", 300), nativePost("a", 300)), List.of(post("a", 300)), false, true)));
        expect("a0", describe(ArchiveMerge.merge(List.of(), List.of(post("a", 300), post("a", 300)), false, true)));
        expect("a2,a1,a0", describe(ArchiveMerge.merge(List.of(), List.of(post("c", 100), post("b", 100), post("a", 100)), false, true)));
        expect("n0", describe(ArchiveMerge.merge(List.of(nativePost("b", 100)), List.of(post("a", 100)), true, true)));
        expect("a0,n0", describe(ArchiveMerge.merge(List.of(nativePost("b", 100)), List.of(post("a", 100)), false, true)));

        ArchiveItem comment = comment("a", "Recovered reply");
        expect("n0,a0", describe(ArchiveMerge.merge(List.of(nativePost("a", 300)), List.of(comment), false, true)));
        expect("n0", describe(ArchiveMerge.merge(List.of(new ArchiveNativeItem("t1_a", 100, false, "")), List.of(comment), false, true)));
        for (String unavailable : List.of("", "[deleted]", "[removed]")) {
            expect("n0", describe(ArchiveMerge.merge(List.of(new ArchiveNativeItem("t1_a", 100, false, "Deleted")), List.of(comment("a", unavailable)), false, true)));
        }
        expect("", ArchiveNativeItem.status("A post mentioning [deleted]"));
        expect("Deleted", ArchiveNativeItem.status("[deleted]"));
        expect("Removed", ArchiveNativeItem.status("[removed]"));

        // Incremental windows with overlapping providers: no duplicates or premature old inserts.
        List<ArchiveItem> history = new ArrayList<>();
        for (int i = 1; i <= 120; i++) history.add(post(Integer.toString(i, 36), 10000 - i * 10));
        for (int page = 1; page <= 12; page++) {
            List<ArchiveNativeItem> current = new ArrayList<>();
            for (int i = 0; i < page * 10; i++) if (i % 3 != 1) current.add(nativePost(history.get(i).id, history.get(i).createdUtc));
            List<ArchiveMerge.Entry> merged = ArchiveMerge.merge(current, history, page != 12, true);
            HashSet<String> ids = new HashSet<>(); long time = Long.MAX_VALUE;
            for (ArchiveMerge.Entry entry : merged) {
                String id = entry.archiveIndex < 0 ? current.get(entry.nativeIndex).fullname : ArchiveMerge.fullname(history.get(entry.archiveIndex));
                long timestamp = entry.archiveIndex < 0 ? current.get(entry.nativeIndex).createdUtc : history.get(entry.archiveIndex).createdUtc;
                expect(true, ids.add(id)); expect(true, timestamp <= time); time = timestamp;
            }
            for (ArchiveNativeItem item : current) expect(true, ids.contains(item.fullname));
            if (page == 12) expect(120, merged.size());
        }
        System.out.println("Archive merge checks passed (" + assertions + " assertions)");
    }
    private static ArchiveItem post(String id, long time) {
        return new ArchiveItem(id, "example", "test", time, "Recovered title", "Body", "", "self", 1L, false);
    }
    private static ArchiveItem comment(String id, String body) {
        return new ArchiveItem(id, "example", "test", 100, "", body, "b", "", 1L, true);
    }
    private static ArchiveNativeItem nativePost(String id, long time) { return new ArchiveNativeItem("t3_" + id, time, false, ""); }
    private static String describe(List<ArchiveMerge.Entry> entries) {
        List<String> values = new ArrayList<>();
        for (ArchiveMerge.Entry entry : entries) values.add(entry.archiveIndex < 0 ? "n" + entry.nativeIndex : "a" + entry.archiveIndex);
        return String.join(",", values);
    }
    private static void expect(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
