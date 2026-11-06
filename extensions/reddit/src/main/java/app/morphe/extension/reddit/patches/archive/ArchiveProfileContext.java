package app.morphe.extension.reddit.patches.archive;

import java.lang.ref.WeakReference;

/** Bound to one host feed instance, not to a process-wide current username. */
final class ArchiveProfileContext {
    final String username;
    final boolean comments;
    final WeakReference<Object> host;
    volatile int generation;
    volatile boolean flairFiltered;
    Object lastRequest;
    WeakReference<Object> sortProvider;
    String sortName;
    Object sortOverride;
    ArchiveProfileContext(Object host, String username, boolean comments) {
        this.host = new WeakReference<>(host); this.username = username; this.comments = comments;
    }
}
