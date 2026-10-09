/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.Logger;

/** Account-bound native browse requests. No credentials, HTTP client, or player prefetch. */
public final class NativeHistoryTransport {
    public interface Source {
        Future<?> patch_seriesTrackerHistory(String continuation, Executor executor);

        byte[] patch_seriesTrackerHistoryBytes(Object response);
    }

    public interface Request {
        String patch_seriesTrackerRoute();

        String patch_seriesTrackerContinuation();

        RecordingPrivacy.Identity patch_seriesTrackerRequestIdentity();
    }

    public static final class Ticket {
        final Source source;
        final Request request;
        final long generation, startedAt, elapsed;

        Ticket(Source source, Request request, long generation) {
            this.source = source;
            this.request = request;
            this.generation = generation;
            startedAt = System.currentTimeMillis();
            elapsed = android.os.SystemClock.elapsedRealtime();
        }
    }

    private static final class Observed {
        final Ticket ticket;
        final Future<?> future;

        Observed(Ticket ticket, Future<?> future) {
            this.ticket = ticket;
            this.future = future;
        }
    }

    static final class Page {
        final NativeHistoryPage value;
        final long startedAt;

        Page(NativeHistoryPage value, long startedAt) {
            this.value = value;
            this.startedAt = startedAt;
        }
    }

    private static volatile Source source;
    private static Observed recent;
    private static final Map<Future<?>, Observed> observed = new LinkedHashMap<>();
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    public static void attach(Source current) {
        source = current;
    }

    /** Called before the host consumes/reuses request registers. Never throws into YouTube. */
    public static Ticket before(Source source, Object request) {
        try {
            if (!(request instanceof Request)) return null;
            Request r = (Request) request;
            if (!"FEhistory".equals(r.patch_seriesTrackerRoute())
                    && (r.patch_seriesTrackerContinuation() == null
                            || r.patch_seriesTrackerContinuation().isEmpty())) return null;
            long generation = RecordingPrivacy.requestGeneration(r.patch_seriesTrackerRequestIdentity());
            return generation < 0 ? null : new Ticket(source, r, generation);
        } catch (Exception ex) {
            Logger.printException(() -> "before failure", ex);
            return null;
        }
    }

    public static synchronized void after(Ticket ticket, Future<?> future) {
        if (ticket == null || future == null) return;
        try {
            if (!RecordingPrivacy.acceptsSync(ticket.generation)) return;
            Observed value = new Observed(ticket, future);
            if (observed.size() >= 4) observed.remove(observed.keySet().iterator().next());
            observed.put(future, value);
            if ("FEhistory".equals(ticket.request.patch_seriesTrackerRoute())
                    && (recent == null || ticket.elapsed >= recent.ticket.elapsed)) recent = value;
        } catch (Exception ex) {
            Logger.printException(() -> "after failure", ex);
        }
    }

    /** Reuse a host History request; extra first-page requests are made only when stale. */
    static Page page(String continuation, long generation, long maxAgeMs, long timeoutNanos)
            throws Exception {
        Observed value;
        synchronized (NativeHistoryTransport.class) {
            value = recent;
            if (!continuation.isEmpty()
                    || value == null
                    || value.ticket.generation != generation
                    || android.os.SystemClock.elapsedRealtime() - value.ticket.elapsed > maxAgeMs
                    || value.future.isCancelled()) value = null;
        }
        boolean owned = value == null;
        if (owned) {
            if (!RecordingPrivacy.acceptsSync(generation)) throw new CancellationException();
            Source current = source;
            if (current == null) throw new IllegalStateException("Native History unavailable");
            Future<?> future = current.patch_seriesTrackerHistory(continuation, Runnable::run);
            synchronized (NativeHistoryTransport.class) {
                value = observed.get(future);
            }
            if (value == null || value.ticket.generation != generation) {
                if (future != null && !future.isDone()) future.cancel(true);
                throw new CancellationException("Native History request identity unavailable");
            }
        }
        try {
            Observed selected = value;
            byte[] bytes =
                    await(
                            value.ticket.source,
                            value.future,
                            () ->
                                    RecordingPrivacy.acceptsSync(generation)
                                            && RecordingPrivacy.requestGeneration(
                                                            selected.ticket.request
                                                                    .patch_seriesTrackerRequestIdentity())
                                                    == generation,
                            timeoutNanos,
                            TimeUnit.NANOSECONDS,
                            owned);
            return new Page(NativeHistoryPage.parse(bytes), value.ticket.startedAt);
        } catch (Exception error) {
            synchronized (NativeHistoryTransport.class) {
                if (recent == value) recent = null;
                observed.remove(value.future);
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        }
    }

    private static byte[] bytes(Source current, Object response) {
        byte[] bytes = current.patch_seriesTrackerHistoryBytes(response);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES)
            throw new IllegalStateException("Unsupported native history response");
        return bytes;
    }

    /** Shared wait boundary; an observer never cancels work owned by the host screen. */
    static byte[] await(
            Source current,
            Future<?> request,
            BooleanSupplier authorized,
            long timeout,
            TimeUnit unit,
            boolean owned)
            throws Exception {
        if (request == null) throw new IllegalStateException("Native history request unavailable");
        try {
            if (!authorized.getAsBoolean()) throw new CancellationException("Account changed");
            Object response = request.get(timeout, unit);
            if (!authorized.getAsBoolean()) throw new CancellationException("Account changed");
            return bytes(current, response);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (owned && !request.isDone()) request.cancel(true);
        }
    }

    private NativeHistoryTransport() {}
}
