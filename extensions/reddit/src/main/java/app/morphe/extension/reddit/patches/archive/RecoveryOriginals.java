package app.morphe.extension.reddit.patches.archive;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Weak identity keys: native model equality/hashCode can traverse an entire discussion. */
final class RecoveryOriginals {
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Map<Key, Object> values = new HashMap<>();
    Object getOrDefault(Object model, Object fallback) {
        reap(); return values.getOrDefault(new Key(model, null), fallback);
    }
    void put(Object model, Object original) {
        reap(); values.put(new Key(model, queue), original);
    }
    private void reap() { Key key; while ((key = (Key) queue.poll()) != null) values.remove(key); }
    private static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object model, ReferenceQueue<Object> queue) { super(model, queue); hash = System.identityHashCode(model); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key && get() != null && get() == ((Key) other).get();
        }
    }
}
