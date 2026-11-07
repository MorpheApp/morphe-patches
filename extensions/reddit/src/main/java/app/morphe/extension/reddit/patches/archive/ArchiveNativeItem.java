package app.morphe.extension.reddit.patches.archive;

import java.util.Locale;

/** Minimal projection of a Reddit-owned card. The native section itself stays untouched. */
public final class ArchiveNativeItem {
    public final String fullname, status;
    public final long createdUtc;
    public final boolean pinned, placeholder;

    public ArchiveNativeItem(String fullname, long createdUtc, boolean pinned, String status) {
        this(fullname, createdUtc, pinned, status, !status.isEmpty());
    }
    public ArchiveNativeItem(String fullname, long createdUtc, boolean pinned, String status, boolean placeholder) {
        this.fullname = fullname.toLowerCase(Locale.ROOT);
        this.createdUtc = createdUtc;
        this.pinned = pinned;
        this.status = status;
        this.placeholder = placeholder;
    }

    public static String status(String text) {
        return "[deleted]".equals(text) ? "Deleted" : "[removed]".equals(text) ? "Removed" : "";
    }
}
