package app.morphe.extension.reddit.patches.archive;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.morphe.extension.reddit.settings.Settings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One remembered profile/tab session; patching adds Reddit's RememberObserver interface. */
public final class ArchiveController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "Reddit-profile-archive"); thread.setDaemon(true); return thread;
            });
    private static final LinkedHashMap<String, Cached> CACHE = new LinkedHashMap<>(8, .75f, true);
    private static final List<WeakReference<ArchiveController>> ACTIVE = new ArrayList<>();
    final String username, key;
    final boolean comments;
    private final Object state;
    private final ArchiveClient client = new ArchiveClient();
    private final ArrayList<ArchiveItem> items = new ArrayList<>();
    private final Map<String, ArchivePostDetails> details = new LinkedHashMap<>();
    private final Set<String> attemptedDetails = new HashSet<>();
    private final Runnable enrich = this::enrichVisible;
    private final Runnable applyVisibility = this::applyVisibility;
    private ArchivePager pager = new ArchivePager();
    private ArchiveClient.Cancellation cancellation, detailCancellation;
    private Future<?> pending, detailPending;
    private boolean remembered, visible, loading, loaded, more = true;
    private int generation, firstVisible = -1, lastVisible = -1;
    private String notice = "", error;
    private ArchiveSnapshot snapshot;
    private ArchiveSnapshot renderedSnapshot;
    private List<?> renderedOriginal;
    private List<?> renderedSections;

    ArchiveController(String username, boolean comments) {
        this.username = username; this.comments = comments; key = ArchiveSnapshot.prefix(username, comments);
        Cached cached = CACHE.get(key);
        if (cached != null && SystemClock.elapsedRealtime() - cached.savedAt < 120000) {
            items.addAll(cached.items); details.putAll(cached.details); pager = cached.pager.copy();
            loaded = true; more = cached.more; notice = cached.notice;
        } else CACHE.remove(key);
        snapshot = snapshot();
        state = ProfileArchivePatch.newState(snapshot);
    }

    public void onRemembered() {
        remembered = true;
        ACTIVE.add(new WeakReference<>(this));
        MAIN.post(applyVisibility);
    }
    public void onForgotten() {
        remembered = false; cancel();
        ACTIVE.removeIf(reference -> reference.get() == null || reference.get() == this);
    }
    public void onAbandoned() { onForgotten(); }

    void visibility(boolean next) {
        if (visible == next) return;
        visible = next;
        // A background result is rejected immediately; work/state updates run after composition.
        MAIN.removeCallbacks(applyVisibility); MAIN.post(applyVisibility);
    }
    private void applyVisibility() {
        if (!active()) { cancel(); return; }
        if (error == null && (!loaded || lastVisible >= items.size() - 3)) load();
        scheduleEnrichment();
    }
    private boolean active() { return remembered && visible && Settings.PROFILE_ARCHIVE.get(); }
    private boolean accepts(int request) { return request == generation && active(); }

    List<?> sections(List<?> original, int replacedIndex) {
        ArchiveSnapshot observed = (ArchiveSnapshot) ProfileArchivePatch.readState(state);
        if (renderedOriginal == original && renderedSnapshot == observed) return renderedSections;
        ArrayList<Object> result = new ArrayList<>(original.size() + observed.rows.size());
        result.addAll(original.subList(0, replacedIndex));
        for (ArchiveSnapshot.Row row : observed.rows) result.add(new ArchiveSection(this, row));
        result.addAll(original.subList(replacedIndex + 1, original.size()));
        renderedOriginal = original; renderedSnapshot = observed;
        renderedSections = ProfileArchivePatch.immutableList(result);
        return renderedSections;
    }

    static void visibleItems(List<String> nativeKeys) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            List<String> copy = new ArrayList<>(nativeKeys);
            MAIN.post(() -> visibleItems(copy));
            return;
        }
        ACTIVE.removeIf(reference -> reference.get() == null);
        for (WeakReference<ArchiveController> reference : ACTIVE) {
            ArchiveController controller = reference.get();
            if (controller == null || !controller.active()) continue;
            int first = Integer.MAX_VALUE, last = -1;
            for (String nativeKey : nativeKeys) {
                if (nativeKey == null || !nativeKey.startsWith(controller.key)) continue;
                for (ArchiveSnapshot.Row row : controller.snapshot.rows) {
                    if (row.itemIndex >= 0 && ArchiveSnapshot.matches(nativeKey, row.key)) {
                        first = Math.min(first, row.itemIndex); last = Math.max(last, row.itemIndex); break;
                    }
                }
            }
            // Other feeds can emit visibility events too; they must not clear this feed's range.
            if (last < 0) continue;
            boolean changed = controller.firstVisible != first || controller.lastVisible != last;
            controller.firstVisible = first; controller.lastVisible = last;
            // Repeated host events for the same cards must not starve the debounce timer.
            if (changed) controller.scheduleEnrichment();
            if (controller.loaded && !controller.items.isEmpty() && controller.error == null
                    && last >= controller.items.size() - 3) MAIN.post(controller::load);
        }
    }

    void retry() { if (active() && !loading) { error = null; load(); } }
    private void load() {
        if (!active() || loading || !more || error != null) return;
        final int request = generation;
        final ArchivePager next = pager.copy();
        final ArchiveClient.Cancellation token = new ArchiveClient.Cancellation();
        cancellation = token; loading = true; publish();
        try {
            pending = WORKERS.submit(() -> {
                try {
                    ArchivePager.Page page = next.next(client, username, comments, token); token.check();
                    deliver(() -> {
                        if (!accepts(request)) return;
                        loading = false; pending = null; cancellation = null;
                        pager = next; items.addAll(page.items); loaded = true; more = page.more; notice = page.notice;
                        remember(); publish(); scheduleEnrichment();
                    });
                } catch (InterruptedIOException ignored) {
                    // Lifecycle cancellation is not a visible network error.
                } catch (IOException | RuntimeException failure) {
                    deliver(() -> {
                        if (!accepts(request)) return;
                        loading = false; pending = null; cancellation = null;
                        error = failure instanceof IOException ? failure.getMessage() : "Archive unavailable. Try again later.";
                        publish();
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            loading = false; cancellation = null; error = "Archive requests are busy. Tap Retry in a moment."; publish();
        }
    }
    private void scheduleEnrichment() {
        MAIN.removeCallbacks(enrich);
        if (active() && firstVisible >= 0) MAIN.postDelayed(enrich, 120);
    }
    private void enrichVisible() {
        if (!active() || detailPending != null || firstVisible < 0) return;
        List<String> ids = new ArrayList<>();
        for (int i = firstVisible; i < items.size() && i <= lastVisible && ids.size() < 8; i++) {
            ArchiveItem item = items.get(i); String id = item.comments ? item.linkId : item.id;
            if (!details.containsKey(id) && !attemptedDetails.contains(id) && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) return;
        final int request = generation;
        final ArchiveClient.Cancellation token = new ArchiveClient.Cancellation(); detailCancellation = token;
        attemptedDetails.addAll(ids);
        try {
            detailPending = WORKERS.submit(() -> {
                Map<String, ArchivePostDetails> result;
                try { result = client.details(ids, token); token.check(); }
                catch (IOException | RuntimeException ignored) { result = java.util.Collections.emptyMap(); }
                final Map<String, ArchivePostDetails> fetched = result;
                deliver(() -> {
                    if (!accepts(request)) return;
                    detailPending = null; detailCancellation = null; details.putAll(fetched);
                    attemptedDetails.removeAll(fetched.keySet());
                    while (details.size() > 200) details.remove(details.keySet().iterator().next());
                    if (!fetched.isEmpty()) { remember(); publish(); }
                    scheduleEnrichment();
                });
            });
        } catch (RejectedExecutionException ignored) {
            attemptedDetails.removeAll(ids); detailPending = null; detailCancellation = null;
        }
    }
    private void cancel() {
        generation++;
        if (cancellation != null) cancellation.cancel();
        if (detailCancellation != null) detailCancellation.cancel();
        if (pending != null) pending.cancel(true);
        if (detailPending != null) detailPending.cancel(true);
        WORKERS.purge(); pending = null; detailPending = null; cancellation = null; detailCancellation = null;
        boolean wasLoading = loading; loading = false; attemptedDetails.clear();
        MAIN.removeCallbacksAndMessages(this); MAIN.removeCallbacks(enrich); MAIN.removeCallbacks(applyVisibility);
        if (wasLoading) publish();
    }
    private void deliver(Runnable runnable) { MAIN.postAtTime(runnable, this, SystemClock.uptimeMillis()); }
    private ArchiveSnapshot snapshot() {
        return new ArchiveSnapshot(username, comments, items, details, loading, loaded, more, notice, error);
    }
    private void publish() { snapshot = snapshot(); ProfileArchivePatch.writeState(state, snapshot); }
    private void remember() {
        if (items.size() <= 200) CACHE.put(key, new Cached(this)); else CACHE.remove(key);
        while (!CACHE.isEmpty() && (CACHE.size() > 8 || cachedCharacters() > 500000)) CACHE.remove(CACHE.keySet().iterator().next());
    }
    private static long cachedCharacters() {
        long size = 0;
        for (Cached cached : CACHE.values()) {
            for (ArchiveItem item : cached.items) size += item.text.length() + item.title.length() + item.flair.length();
            for (ArchivePostDetails detail : cached.details.values()) size += detail.characters();
        }
        return size;
    }
    private static final class Cached {
        final long savedAt = SystemClock.elapsedRealtime();
        final List<ArchiveItem> items;
        final Map<String, ArchivePostDetails> details;
        final ArchivePager pager;
        final boolean more;
        final String notice;
        Cached(ArchiveController owner) {
            items = new ArrayList<>(owner.items); details = new LinkedHashMap<>(owner.details);
            pager = owner.pager.copy(); more = owner.more; notice = owner.notice;
        }
    }
}
