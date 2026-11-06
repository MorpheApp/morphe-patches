package app.morphe.extension.reddit.patches.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Shared, bounded, memory-only recovery. Each renderer owns a cancellable subscription. */
public final class ArchiveRecoveryStore {
    public interface Source {
        Map<String, ArchiveItem> records(List<String> ids, boolean comments, ArchiveClient.Cancellation cancellation) throws IOException;
    }
    private static final int MAX_ENTRIES = 256, MAX_CHARACTERS = 1024 * 1024;
    private final Source source;
    private final ScheduledExecutorService worker;
    private final Executor delivery;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private final Map<String, Set<Subscription>> subscribers = new LinkedHashMap<>();
    private final Set<String> queue = new LinkedHashSet<>();
    private List<String> active = List.of();
    private ArchiveClient.Cancellation cancellation;
    private boolean scheduled;
    private int characters;
    private long epoch;

    public ArchiveRecoveryStore(Source source, ScheduledExecutorService worker, Executor delivery, LongSupplier clock) {
        this.source = source; this.worker = worker; this.delivery = delivery; this.clock = clock;
    }

    public final class Subscription implements AutoCloseable {
        private final String id;
        private final Consumer<ArchiveItem> callback;
        private final boolean lookup;
        private boolean closed;
        private Subscription(String id, boolean lookup, Consumer<ArchiveItem> callback) { this.id = id; this.lookup = lookup; this.callback = callback; }
        private void send(ArchiveItem value) {
            delivery.execute(() -> {
                synchronized (ArchiveRecoveryStore.this) {
                    if (closed) return;
                    callback.accept(value);
                }
            });
        }
        @Override public void close() {
            synchronized (ArchiveRecoveryStore.this) {
                if (closed) return;
                closed = true;
                Set<Subscription> set = subscribers.get(id);
                if (set != null && set.remove(this) && set.isEmpty()) { subscribers.remove(id); queue.remove(id); }
                if (!requested(id)) queue.remove(id);
                if (cancellation != null && active.stream().noneMatch(ArchiveRecoveryStore.this::requested)) cancellation.cancel();
            }
        }
    }

    public synchronized Subscription subscribe(String id, Consumer<ArchiveItem> callback) {
        return subscribe(id, true, callback);
    }
    public synchronized Subscription subscribe(String id, boolean lookup, Consumer<ArchiveItem> callback) {
        if (id == null || !id.matches("t[13]_[a-z0-9]{1,16}")) throw new IllegalArgumentException("Invalid recovery fullname");
        Subscription subscription = new Subscription(id, lookup, callback);
        subscribers.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(subscription);
        Cached saved = cached(id);
        if (saved != null) subscription.send(saved.item);
        else if (lookup && !active.contains(id)) { queue.add(id); schedule(); }
        return subscription;
    }

    public synchronized ArchiveItem peek(String id) {
        Cached saved = cached(id); return saved == null ? null : saved.item;
    }
    private boolean requested(String id) {
        Set<Subscription> set = subscribers.get(id);
        return set != null && set.stream().anyMatch(subscription -> subscription.lookup && !subscription.closed);
    }

    public synchronized void signal(String id) { notifySubscribers(id, peek(id)); }

    public synchronized void seed(ArchiveItem item) {
        if (item == null) return;
        String id = ArchiveMerge.fullname(item);
        Cached previous = cached(id);
        if (previous != null && previous.item != null && previous.item.title.equals(item.title)
                && previous.item.text.equals(item.text) && previous.item.author.equals(item.author)) return;
        put(id, item, 120000);
        queue.remove(id);
        notifySubscribers(id, item);
    }

    public synchronized void clear() {
        epoch++;
        if (cancellation != null) cancellation.cancel();
        // New observers must queue even if the cancelled generation is still
        // unwinding. Keep cancellation until it finishes to serialize transport.
        active = List.of();
        cache.clear(); characters = 0; queue.clear();
        for (Set<Subscription> set : subscribers.values()) for (Subscription subscription : set) subscription.closed = true;
        subscribers.clear();
    }

    private Cached cached(String id) {
        Cached saved = cache.get(id);
        if (saved != null && saved.expires <= clock.getAsLong()) { cache.remove(id); characters -= saved.characters; return null; }
        return saved;
    }
    private void schedule() {
        if (scheduled || cancellation != null || queue.isEmpty()) return;
        scheduled = true;
        worker.schedule(this::fetch, 40, TimeUnit.MILLISECONDS);
    }
    private void fetch() {
        List<String> batch = new ArrayList<>();
        ArchiveClient.Cancellation request;
        long generation;
        boolean comments;
        synchronized (this) {
            scheduled = false;
            queue.removeIf(id -> !requested(id) || cached(id) != null);
            if (queue.isEmpty()) return;
            comments = queue.iterator().next().startsWith("t1_");
            for (String id : queue) if (id.startsWith(comments ? "t1_" : "t3_") && batch.size() < 8) batch.add(id);
            queue.removeAll(batch); active = batch; generation = epoch;
            cancellation = request = new ArchiveClient.Cancellation();
        }
        Map<String, ArchiveItem> records = Map.of();
        boolean failed = false, cancelled = false;
        try {
            records = source.records(batch.stream().map(id -> id.substring(3)).toList(), comments, request);
            request.check();
        } catch (java.io.InterruptedIOException ignored) { cancelled = true; }
        catch (IOException | RuntimeException ignored) { failed = true; }
        synchronized (this) {
            if (generation == epoch && !cancelled) for (String id : batch) {
                ArchiveItem item = records.get(id);
                if (item != null && !ArchiveMerge.fullname(item).equals(id)) item = null;
                Cached seeded = cached(id);
                if (seeded != null && seeded.item != null) item = seeded.item;
                put(id, item, item != null ? 120000 : failed ? 10000 : 30000);
                notifySubscribers(id, item);
            }
            active = List.of(); cancellation = null;
            // A new observer may have arrived while a now-cancelled request was unwinding.
            if (cancelled && generation == epoch) for (String id : batch) if (requested(id) && cached(id) == null) queue.add(id);
            schedule();
        }
    }
    private void notifySubscribers(String id, ArchiveItem item) {
        Set<Subscription> set = subscribers.get(id);
        if (set != null) for (Subscription subscription : List.copyOf(set)) subscription.send(item);
    }
    private void put(String id, ArchiveItem item, long ttl) {
        Cached saved = new Cached(item, clock.getAsLong() + ttl);
        Cached old = cache.put(id, saved);
        characters += saved.characters - (old == null ? 0 : old.characters);
        while (cache.size() > MAX_ENTRIES || characters > MAX_CHARACTERS) {
            String eldest = cache.keySet().iterator().next(); characters -= cache.remove(eldest).characters;
        }
    }
    private static final class Cached {
        final ArchiveItem item;
        final long expires;
        final int characters;
        Cached(ArchiveItem item, long expires) {
            this.item = item; this.expires = expires;
            characters = item == null ? 0 : item.title.length() + item.text.length() + item.author.length() + item.subreddit.length();
        }
    }
}
