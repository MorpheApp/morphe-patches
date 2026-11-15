package app.morphe.extension.reddit.patches.nsfw;

/** A one-way, install-wide latch. Kept outside Morphe's import/export settings. */
public final class PermanentNsfwDisabler {
    public interface Store {
        boolean read();
        boolean enable();
    }

    private final Store store;
    private volatile boolean locked;

    public PermanentNsfwDisabler(Store store) {
        this.store = store;
        locked = store.read();
    }

    public boolean isEnabled() {
        // Another process may have activated the same install-wide preference.
        if (!locked && store.read()) locked = true;
        return locked;
    }

    /** Report success only after the persistent write succeeds. No disable operation exists. */
    public synchronized boolean enable() {
        if (locked) return true;
        if (!store.enable()) return false;
        locked = true;
        return true;
    }
}
