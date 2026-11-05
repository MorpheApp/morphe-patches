package app.morphe.extension.reddit.patches.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ArchiveSnapshotTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        List<ArchiveItem> items = new ArrayList<>(ArchiveClient.parse("{\"data\":["
                + "{\"id\":\"abc\",\"author\":\"Some_User\",\"created_utc\":1700000000,\"title\":\"First\"},"
                + "{\"id\":\"def\",\"author\":\"Some_User\",\"created_utc\":1699999999,\"title\":\"Second\"}]}", "Some_User", false, 25));
        ArchiveSnapshot initial = snapshot(List.of(), true, false, true, null);
        check(initial.rows.size() == 1 && initial.rows.get(0).kind == ArchiveSnapshot.STATUS, "Only a loading item before first page");
        check(initial.rows.get(0).loading && !initial.rows.get(0).retry, "Loading is not retryable");
        ArchiveSnapshot page = snapshot(items.subList(0, 1), false, true, true, null);
        ArchiveSnapshot appended = snapshot(items, false, true, false, null);
        check(page.rows.size() == 3 && appended.rows.size() == 4, "Individual native items, including indicator/footer");
        check(page.rows.get(1).key.equals(appended.rows.get(1).key), "Appending retains first card key");
        check(page.rows.get(2).key.equals(appended.rows.get(3).key), "Footer key survives page append");
        check(!initial.rows.get(0).key.equals(page.rows.get(2).key),
                "Initial loading cannot anchor the first page to its new footer");
        check(initial.rows.get(0).itemIndex == -1 && appended.rows.get(3).itemIndex == -1,
                "A stale visible footer cannot trigger another page after append");
        check(appended.rows.stream().map(row -> row.key).distinct().count() == appended.rows.size(), "Unique keys");
        check(appended.rows.get(3).message.equals("End of archived history"), "End state");
        items.clear();
        check(appended.rows.size() == 4 && appended.itemCount == 2, "Input mutations cannot change published snapshots");
        try { appended.rows.clear(); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        ArchiveSnapshot failed = snapshot(List.of(), false, false, true, "Network unavailable");
        check(failed.rows.size() == 1 && failed.rows.get(0).retry, "Initial error contains no archive indicator");
        ArchiveSnapshot empty = snapshot(List.of(), false, true, false, null);
        check(empty.rows.size() == 1 && empty.rows.get(0).message.startsWith("No archived posts"), "Empty-state item");
        String key = appended.rows.get(1).key;
        check(ArchiveSnapshot.matches(key, key), "Exact native key");
        check(ArchiveSnapshot.matches(key + "_123", key), "Native duplicate-section index suffix");
        check(!ArchiveSnapshot.matches(key + "x", key), "No ID prefix collisions");
        check(!ArchiveSnapshot.matches(key + "_", key), "No empty index suffix");
        check(!ArchiveSnapshot.matches(key + "_1x", key), "No malformed index suffix");
        check(!ArchiveSnapshot.matches(null, key), "Null native keys are ordinary unrelated rows");
        check(ArchiveSnapshot.prefix("SOME_USER", false).equals(ArchiveSnapshot.prefix("some_user", false)), "Case-insensitive profile keys");
        check(!ArchiveSnapshot.prefix("some_user", true).equals(ArchiveSnapshot.prefix("some_user", false)), "Independent tab keys");
        check(!ArchiveSnapshot.prefix("other_user", false).equals(ArchiveSnapshot.prefix("some_user", false)), "Independent profile keys");
        System.out.println("Archive native-item checks passed (" + assertions + " assertions)");
    }
    private static ArchiveSnapshot snapshot(List<ArchiveItem> items, boolean loading, boolean loaded, boolean more, String error) {
        return new ArchiveSnapshot("Some_User", false, items, Map.of(), loading, loaded, more, "", error);
    }
}
