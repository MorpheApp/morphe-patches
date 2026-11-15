package app.morphe.extension.reddit.patches.nsfw;

/** Host verification of the irreversible preference. Native hooks are checked on patched APKs. */
public final class NsfwTests {
    public static void main(String[] args) {
        class MemoryStore implements PermanentNsfwDisabler.Store {
            boolean value;
            boolean writable;
            int writes;
            public boolean read() { return value; }
            public boolean enable() {
                writes++;
                if (!writable) return false;
                value = true;
                return true;
            }
        }
        MemoryStore store = new MemoryStore();
        PermanentNsfwDisabler disabler = new PermanentNsfwDisabler(store);
        require(!disabler.isEnabled());
        require(!disabler.enable() && !disabler.isEnabled());
        store.writable = true;
        PermanentNsfwDisabler otherHolder = new PermanentNsfwDisabler(store);
        require(disabler.enable() && disabler.isEnabled());
        require(otherHolder.isEnabled());
        require(new PermanentNsfwDisabler(store).isEnabled());
        int writes = store.writes;
        require(disabler.enable() && writes == store.writes);
        // Once activated, a stale read must not undo permanent disabling in this process.
        store.value = false;
        require(disabler.isEnabled());
        System.out.println("PASS: activation, failed persistence, reload, idempotence and irreversible state");
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("Expectation failed");
    }
}
