package app.morphe.extension.reddit.patches.archive;

/** Patch-time bridge adds Reddit's RememberObserver; disposed rows release their lookup. */
public final class RecoveryBinding {
    final String id;
    final int generation;
    final boolean manual;
    final Object state = ProfileArchivePatch.newState(0);
    private ArchiveRecoveryStore.Subscription subscription;
    private boolean remembered, lookup;
    private int revision;
    RecoveryBinding(String id, boolean lookup, int generation, boolean manual) {
        this.id = id; this.lookup = lookup; this.generation = generation; this.manual = manual;
    }
    public void onRemembered() { remembered = true; subscribe(); }
    public void onForgotten() { remembered = false; if (subscription != null) subscription.close(); subscription = null; }
    public void onAbandoned() { onForgotten(); }
    void lookup(boolean value) {
        if (value && !lookup) {
            lookup = true;
            RecoveryPatch.MAIN.post(() -> { if (remembered) { if (subscription != null) subscription.close(); subscribe(); } });
        }
    }
    private void subscribe() {
        subscription = RecoveryPatch.STORE.subscribe(id, lookup, record -> {
            if (remembered && RecoveryPatch.enabled()) ProfileArchivePatch.writeState(state, ++revision);
        });
    }
}
