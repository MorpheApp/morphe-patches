package app.morphe.extension.reddit.patches.archive;

/** Delegates rendering to the exact Reddit section (including its QSF tracking wrapper). */
public final class ArchiveNativeSection {
    public final Object original;
    final ArchiveController controller;
    private final String key;
    ArchiveNativeSection(ArchiveController controller, Object original, String key) {
        this.controller = controller; this.original = original; this.key = key;
    }
    public String key() { return key; }
}
