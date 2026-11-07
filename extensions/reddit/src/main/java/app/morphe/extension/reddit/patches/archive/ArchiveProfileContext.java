package app.morphe.extension.reddit.patches.archive;

import java.lang.ref.WeakReference;

/** Bound to one host feed instance, not to a process-wide current username. */
final class ArchiveProfileContext {
    final String username, owner;
    final boolean comments, ownProfile;
    final WeakReference<Object> host;
    WeakReference<Object> session;
    volatile boolean active = true;
    volatile int generation;
    volatile boolean flairFiltered;
    Object lastRequest;
    WeakReference<Object> sortProvider;
    String sortName;
    Object sortOverride;
    ArchiveProfileContext(Object host, String username, boolean comments, String owner) {
        this.host = new WeakReference<>(host); this.username = username; this.comments = comments;
        this.owner = ArchiveEligibility.normalize(owner);
        ownProfile = ArchiveEligibility.ownProfile(username, owner);
    }

    boolean archiveAllowed() { return active && !ownProfile; }
}
