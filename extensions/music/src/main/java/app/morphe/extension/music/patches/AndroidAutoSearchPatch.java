/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3852
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoPatch.BACKGROUND_EXECUTOR;
import static app.morphe.extension.music.patches.AndroidAutoPatch.DEADLINE_MS;
import static app.morphe.extension.music.patches.AndroidAutoPatch.createPlayableSearchResults;
import static app.morphe.extension.music.patches.AndroidAutoPatch.mainHandler;

import android.support.v4.media.MediaBrowserCompat;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.patches.AndroidAutoSearchResponseParser.SearchResult;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Supplies playable phone search results while retaining YTM's local Library search and caller checks.
 * Delays query submission by 250 ms so further typing can replace it.
 */
@SuppressWarnings("unused")
public final class AndroidAutoSearchPatch {
    /** Added to YTM's phone search client to select catalog or uploaded music. */
    public interface PhoneSearchClient {
        ListenableFuture<PhoneSearchResponse> patch_search(String query, boolean uploaded,
                                                          @Nullable String params, Executor executor);
    }

    /** Added to YTM's phone search response to read its results as protobuf bytes. */
    public interface PhoneSearchResponse {
        byte[] patch_responseBytes();
    }

    /** Added to YTM's Auto search request to read the query and return results. */
    public interface SearchRequest {
        @Nullable String patch_query();
        /** YTM reports null as a failed search; an empty list means success with no matches. */
        void patch_deliverSearchResults(@Nullable List<MediaBrowserCompat.MediaItem> playableResults);
    }

    private static final long TYPING_DELAY_MS = 250;
    @Nullable
    private static SearchLoad pendingSearch;
    private static final Map<SearchRequest, SearchCompletion> searchCompletions = new WeakHashMap<>();
    @Nullable
    private static volatile PhoneSearchClient phoneSearchClient;

    private AndroidAutoSearchPatch() {}

    /** Injection point. Saves YTM's search client when its Android Auto service starts. */
    public static void setPhoneSearchClient(PhoneSearchClient client) {
        phoneSearchClient = client;
    }

    /** Injection point. Gives YTM-handled searches a deadline so an offline request cannot keep loading indefinitely. */
    public static void onSearchRequest(SearchRequest request) {
        SearchLoad previous;
        synchronized (AndroidAutoSearchPatch.class) {
            previous = pendingSearch;
            if (previous != null && previous.query.equals(request.patch_query())) previous = null;
        }
        // Clearing the query can return through YTM's local-search path before handleSearch runs.
        if (previous != null) previous.finish(Collections.emptyList());
        synchronized (searchCompletions) {
            SearchCompletion completion = searchCompletions.computeIfAbsent(request, ignored -> new SearchCompletion());
            if (completion.delivered || completion.timeout != null) return;
            // A completed request may still receive a late native callback. Keep its completion state until YTM
            // releases it, without letting this timer or the map keep the request alive.
            WeakReference<SearchRequest> reference = new WeakReference<>(request);
            completion.timeout = () -> {
                SearchRequest pending = reference.get();
                if (pending == null) return;
                try { pending.patch_deliverSearchResults(null); }
                catch (Exception ex) { Logger.printException(() -> "Could not finish Android Auto search", ex); }
            };
            mainHandler.postDelayed(completion.timeout, DEADLINE_MS);
        }
    }

    /** Injection point before YTM delivers results. Allows each request to complete only once, including on timeout. */
    public static boolean onSearchResult(SearchRequest request) {
        synchronized (searchCompletions) {
            SearchCompletion completion = searchCompletions.computeIfAbsent(request, ignored -> new SearchCompletion());
            if (completion.delivered) return false;
            completion.delivered = true;
            completion.cancelDeadline();
            return true;
        }
    }

    private static final class SearchCompletion {
        boolean delivered;
        @Nullable
        Runnable timeout;

        void cancelDeadline() {
            if (timeout != null) mainHandler.removeCallbacks(timeout);
            timeout = null;
        }
    }

    // Search submission and typing delay

    /**
     * Injection point after YTM's caller and local-search checks. Queues a phone search after typing stops.
     * @return true when the patch will deliver results; false to continue YTM's native search.
     */
    public static boolean handleSearch(SearchRequest request) {
        PhoneSearchClient client = phoneSearchClient;
        if (client == null) return false;
        String query = request.patch_query();
        if (query == null || query.trim().isEmpty()) return false;
        synchronized (searchCompletions) {
            SearchCompletion completion = searchCompletions.get(request);
            if (completion != null) completion.cancelDeadline();
        }
        SearchLoad load;
        SearchLoad previous;
        synchronized (AndroidAutoSearchPatch.class) {
            previous = pendingSearch;
            if (previous != null && !previous.finished.get()
                    && previous.client == client && previous.query.equals(query)) {
                // Repeated queries keep the original deadline so they cannot prolong loading indefinitely.
                previous.requests.add(request);
                return true;
            }
            pendingSearch = load = new SearchLoad(request, client, query);
        }
        if (previous != null) previous.finish(Collections.emptyList());
        mainHandler.postDelayed(load.timeout, DEADLINE_MS);
        mainHandler.postDelayed(load.submit, TYPING_DELAY_MS);
        return true;
    }

    /** Identical queries share a pending load and its deadline; each caller completes once. */
    private static final class SearchLoad {
        final PhoneSearchClient client;
        final String query;
        final AtomicBoolean finished = new AtomicBoolean();
        final CompletableFuture<List<SearchResult>> catalog = new CompletableFuture<>();
        final CompletableFuture<List<SearchResult>> uploads = new CompletableFuture<>();
        final List<ListenableFuture<PhoneSearchResponse>> pendingResponses = new ArrayList<>();
        final Runnable timeout = this::finishAvailableResults;
        final List<SearchRequest> requests = new ArrayList<>();
        final Runnable submit;

        SearchLoad(SearchRequest request, PhoneSearchClient client, String query) {
            this.client = client;
            this.query = query;
            requests.add(request);
            submit = () -> BACKGROUND_EXECUTOR.execute(this::start);
        }

        void start() {
            if (finished.get()) return;
            if (client != phoneSearchClient) {
                finish(Collections.emptyList());
                return;
            }
            CompletableFuture.allOf(catalog, uploads).whenComplete((ignored, error) -> finishAvailableResults());
            request(false, null, catalog);
            request(true, null, uploads);
        }

        void finishAvailableResults() {
            if (finished.get()) return;
            try {
                finish(availableResults());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not finish Android Auto search", ex);
                finish(null);
            }
        }

        void finish(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            if (!finished.compareAndSet(false, true)) return;
            mainHandler.removeCallbacks(timeout);
            mainHandler.removeCallbacks(submit);
            synchronized (AndroidAutoSearchPatch.class) {
                if (pendingSearch == this) pendingSearch = null;
            }
            synchronized (pendingResponses) {
                for (var response : pendingResponses) response.cancel(true);
            }
            Utils.runOnMainThread(() -> deliver(playableResults));
        }

        void deliver(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            for (SearchRequest request : requests) {
                try {
                    request.patch_deliverSearchResults(playableResults);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not deliver Android Auto search results", ex);
                }
            }
        }

        private void request(boolean uploaded, @Nullable String params, CompletableFuture<List<SearchResult>> results) {
            if (finished.get()) return;
            try {
                var response = client.patch_search(query, uploaded, params, BACKGROUND_EXECUTOR);
                synchronized (pendingResponses) {
                    if (finished.get()) {
                        response.cancel(true);
                        return;
                    }
                    pendingResponses.add(response);
                }

                response.addListener(() -> {
                    if (finished.get()) return;
                    try {
                        byte[] bytes = response.get().patch_responseBytes();
                        String uploadParams = uploaded && params == null
                                ? AndroidAutoSearchResponseParser.uploadedSearchParams(bytes) : "";
                        // The first response supplies the Uploads tab; follow its parameters to load the results.
                        if (!uploadParams.isEmpty()) {
                            request(true, uploadParams, results);
                        } else {
                            results.complete(AndroidAutoSearchResponseParser.parseSearchResults(bytes, uploaded));
                        }
                    } catch (Exception ex) {
                        results.completeExceptionally(ex);
                    }
                }, BACKGROUND_EXECUTOR);
            } catch (Exception ex) {
                results.completeExceptionally(ex);
            }
        }

        /** Keeps catalog or upload results when the other request fails or exceeds the Auto deadline. */
        @Nullable
        List<MediaBrowserCompat.MediaItem> availableResults() {
            List<SearchResult> combined = new ArrayList<>();
            boolean completed = false;
            for (var response : List.of(catalog, uploads)) {
                if (!response.isDone() || response.isCancelled()) continue;
                try {
                    var results = response.getNow(null);
                    if (results == null) continue;
                    completed = true;
                    combined.addAll(results);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not read Android Auto search response", ex);
                }
            }
            return completed ? createPlayableSearchResults(combined) : null;
        }
    }

}
