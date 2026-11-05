package app.morphe.extension.reddit.patches.archive;

/** Pure policy shared by the runtime hook and desktop regression tests. */
public final class ArchiveEligibility {
    private ArchiveEligibility() {}

    public static String username(String name, String tab, boolean filtered, boolean flairFiltered,
                                  boolean enabled) {
        if (!enabled || !filtered || flairFiltered
                || !("VISITOR_POST".equals(tab) || "VISITOR_COMMENT".equals(tab)) || name == null) return null;
        if (name.startsWith("/u/")) name = name.substring(3);
        else if (name.startsWith("u/")) name = name.substring(2);
        return name.matches("[A-Za-z0-9_-]{3,20}") ? name : null;
    }
}
