package app.morphe.extension.reddit.patches.archive;

/** Run by scripts/test-profile-archive.ps1; no Android runtime or network required. */
public final class ArchiveEligibilityTest {
    public static void main(String[] args) {
        expect("firequak", ArchiveEligibility.username("firequak", "VISITOR_POST", true, false, true));
        expect("firequak", ArchiveEligibility.username("u/firequak", "VISITOR_COMMENT", true, false, true));
        expect("firequak", ArchiveEligibility.username("/u/firequak", "VISITOR_POST", true, false, true));
        for (String tab : new String[]{"OWNER_POST", "OWNER_COMMENT", "OWNER_SAVED", "DELETED", "BLOCKED", "SUSPENDED", "UNKNOWN", null}) {
            expect(null, ArchiveEligibility.username("firequak", tab, true, false, true));
        }
        expect(null, ArchiveEligibility.username("firequak", "VISITOR_POST", false, false, true));
        expect(null, ArchiveEligibility.username("firequak", "VISITOR_POST", true, true, true));
        expect(null, ArchiveEligibility.username("firequak", "VISITOR_COMMENT", true, false, false));
        for (String name : new String[]{null, "", "[deleted]", "a/b", "test?author=other", "not a username", "a".repeat(21)}) {
            expect(null, ArchiveEligibility.username(name, "VISITOR_POST", true, false, true));
        }
        System.out.println("Profile eligibility checks passed");
    }
    private static void expect(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
