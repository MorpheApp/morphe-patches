package app.morphe.extension.reddit.patches.archive;

/** Eligibility policy for public visitor profile history. */
public final class ArchiveEligibility {
    private ArchiveEligibility() {}

    static String normalize(String name) {
        if (name == null) return null;
        if (name.regionMatches(true, 0, "/u/", 0, 3)) name = name.substring(3);
        else if (name.regionMatches(true, 0, "u/", 0, 2)) name = name.substring(2);
        return name.matches("[A-Za-z0-9_-]{3,20}") ? name : null;
    }

    static boolean ownProfile(String name, String owner) {
        name = normalize(name); owner = normalize(owner);
        return name != null && owner != null && name.equalsIgnoreCase(owner);
    }

    public static String username(String name, String tab, boolean filtered, boolean flairFiltered,
                                  boolean enabled) {
        if (!enabled || !filtered || flairFiltered
                || !("VISITOR_POST".equals(tab) || "VISITOR_COMMENT".equals(tab)) || name == null) return null;
        return normalize(name);
    }
}
