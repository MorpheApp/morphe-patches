/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoPatch.BACKGROUND_EXECUTOR;
import static app.morphe.extension.music.patches.AndroidAutoPatch.DEADLINE_MS;
import static app.morphe.extension.music.patches.AndroidAutoPatch.createPlayableSearchResults;
import static app.morphe.extension.music.patches.AndroidAutoPatch.mainHandler;
import static app.morphe.extension.music.patches.AndroidAutoPatch.scope;
import static app.morphe.extension.music.patches.AndroidAutoPatch.transformFuture;

import app.morphe.extension.music.patches.AndroidAutoPatch.RequestKey;
import app.morphe.extension.music.patches.AndroidAutoSearchResponseParser.SearchResult;

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
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;

/**
 * Supplies playable phone search results while retaining YTM's local Library search and caller checks.
 * Delays query submission by 250 ms so further typing can replace it.
 */
@SuppressWarnings("unused")
public final class AndroidAutoSearchPatch {
    /** Added to YTM's phone search client to select catalog or uploaded music. */
    public interface PhoneSearchClient {
        /** Current account used to construct YTM's search requests. */
        Object patch_accountScope();
        ListenableFuture<PhoneSearchResponse> patch_search(String query, boolean uploaded,
                                                          @Nullable String params, Executor executor);
    }

    /** Added to YTM's phone search response to read its results as protobuf bytes. */
    public interface PhoneSearchResponse {
        byte[] patch_responseBytes();
    }

    /** Added to YTM's Auto search request to read the query and return results. */
    public interface SearchRequest {
        String patch_query();
        /** YTM reports null as a failed search; an empty list means success with no matches. */
        void patch_deliverSearchResults(@Nullable List<MediaBrowserCompat.MediaItem> playableResults);
    }

    private static final long TYPING_DELAY_MS = 250;
    @Nullable
    private static SearchLoad pendingSearch;
    private static final Map<SearchRequest, SearchCompletion> searchCompletions = new WeakHashMap<>();
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
            if (previous != null && previous.key.input().equals(request.patch_query())) previous = null;
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
        RequestKey key = new RequestKey(query, scope(client));
        SearchLoad load;
        SearchLoad previous;
        synchronized (AndroidAutoSearchPatch.class) {
            previous = pendingSearch;
            if (previous != null && !previous.finished.get() && key.scope().account() != null && previous.key.equals(key)) {
                // Repeated queries keep the original deadline so they cannot prolong loading indefinitely.
                previous.requests.add(request);
                return true;
            }
            pendingSearch = load = new SearchLoad(request, key);
        }
        if (previous != null) previous.finish(Collections.emptyList());
        mainHandler.postDelayed(load.timeout, DEADLINE_MS);
        mainHandler.postDelayed(load.submit, TYPING_DELAY_MS);
        return true;
    }

    /** Identical queries share a pending load and its deadline; each caller completes once. */
    private static final class SearchLoad {
        final RequestKey key;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile SearchResponses responses;
        final Runnable timeout = () -> {
            var pending = responses;
            try { finish(pending == null ? null : pending.availableResults()); }
            catch (Exception ex) {
                Logger.printException(() -> "Could not finish Android Auto search", ex);
                finish(null);
            }
        };
        final List<SearchRequest> requests = new ArrayList<>();
        final Runnable submit;

        SearchLoad(SearchRequest request, RequestKey key) {
            this.key = key;
            requests.add(request);
            submit = () -> BACKGROUND_EXECUTOR.execute(this::start);
        }

        void start() {
            if (finished.get()) return;
            try {
                if (!current(key)) { finish(Collections.emptyList()); return; }
                listenForResults(new SearchResponses((PhoneSearchClient) key.scope().client(), key.input()));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto search", ex);
                finish(null);
            }
        }

        void listenForResults(SearchResponses pending) {
            responses = pending;
            if (finished.get()) { pending.resultsFuture.cancel(true); return; }
            pending.resultsFuture.whenComplete((playableResults, error) -> {
                if (finished.get()) return;
                if (error != null) Logger.printException(() -> "Could not load Android Auto search results", error);
                finish(error == null ? playableResults : null);
            });
        }

        void finish(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            if (!finished.compareAndSet(false, true)) return;
            mainHandler.removeCallbacks(timeout);
            mainHandler.removeCallbacks(submit);
            synchronized (AndroidAutoSearchPatch.class) {
                if (pendingSearch == this) pendingSearch = null;
            }
            var pending = responses;
            if (pending != null) pending.resultsFuture.cancel(true);
            mainHandler.post(() -> {
                try { deliver(playableResults); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto search results", ex); }
            });
        }

        void deliver(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            // Account or service changes can happen while results wait on the main thread.
            if (!current(key)) playableResults = Collections.emptyList();
            for (SearchRequest request : requests) {
                try { request.patch_deliverSearchResults(playableResults); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto search results", ex); }
            }
        }
    }

    // Request scope and completion

    private static boolean current(RequestKey key) {
        Object client = key.scope().client();
        return client == phoneSearchClient && key.scope().equals(scope(client));
    }

    /** Keeps catalog or upload results when the other request fails or exceeds the Auto deadline. */
    private static final class SearchResponses {
        final CompletableFuture<List<SearchResult>> catalog;
        final CompletableFuture<List<SearchResult>> uploads;
        final CompletableFuture<List<MediaBrowserCompat.MediaItem>> resultsFuture;

        SearchResponses(PhoneSearchClient client, String query) {
            catalog = request(client, query, false);
            uploads = request(client, query, true);
            resultsFuture = CompletableFuture.allOf(catalog, uploads).handle((ignored, error) -> availableResults());
            resultsFuture.whenComplete((results, error) -> {
                if (resultsFuture.isCancelled()) {
                    catalog.cancel(true);
                    uploads.cancel(true);
                }
            });
        }

        private static CompletableFuture<List<SearchResult>> request(
                PhoneSearchClient client, String query, boolean uploaded) {
            try {
                var initialResponse = transformFuture(
                        client.patch_search(query, uploaded, null, BACKGROUND_EXECUTOR),
                        PhoneSearchResponse::patch_responseBytes, BACKGROUND_EXECUTOR);
                AtomicReference<CompletableFuture<List<SearchResult>>> uploadResultsRequest =
                        new AtomicReference<>();
                AtomicBoolean cancelled = new AtomicBoolean();
                var results = initialResponse.thenCompose(response -> {
                    String params = uploaded ? AndroidAutoSearchResponseParser.uploadedSearchParams(response) : "";
                    if (params.isEmpty()) return CompletableFuture.completedFuture(
                            AndroidAutoSearchResponseParser.parseSearchResults(response, uploaded));
                    // The first response supplies the Uploads tab; follow its search parameters to load the account's results.
                    var uploadResults = transformFuture(
                            client.patch_search(query, true, params, BACKGROUND_EXECUTOR),
                            uploadResponse -> AndroidAutoSearchResponseParser.parseSearchResults(uploadResponse.patch_responseBytes(), true),
                            BACKGROUND_EXECUTOR);
                    uploadResultsRequest.set(uploadResults);
                    if (cancelled.get()) uploadResults.cancel(true);
                    return uploadResults;
                });
                results.whenComplete((value, error) -> {
                    if (!results.isCancelled()) return;
                    cancelled.set(true);
                    initialResponse.cancel(true);
                    var uploadResults = uploadResultsRequest.get();
                    if (uploadResults != null) uploadResults.cancel(true);
                });
                return results;
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto search scope", ex);
                return CompletableFuture.completedFuture(null);
            }
        }

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
                    Logger.printException(() -> "Could not load Android Auto search scope", ex);
                }
            }
            return completed ? createPlayableSearchResults(combined) : null;
        }
    }

}
