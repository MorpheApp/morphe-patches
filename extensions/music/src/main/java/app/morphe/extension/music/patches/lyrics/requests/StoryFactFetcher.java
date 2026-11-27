/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.scrobbling.lastfm.LastFM;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fetches facts in parallel; one failing source never blocks the others. Fallbacks only label
 * real data, never invent it.
 */
public final class StoryFactFetcher {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "story-facts");
        thread.setDaemon(true);
        return thread;
    });

    /** Full passages are preferable; these are only safety caps for the prompt budget. */
    static final int MAX_SOURCE_CHARS = 2200;
    static final int MAX_FACTS_CHARS = 6000;
    private static final long FACT_DEADLINE_MS = 20_000L;

    public record Contribution(String text, String label, boolean verified) {
    }

    public record Facts(String text, String sourceLabel, boolean verified) {
    }

    private StoryFactFetcher() {
    }

    @NonNull
    public static Facts fetch(@NonNull TrackInfo track, @NonNull String language) {
        String artist = track.artist();
        String title = track.title();
        CompletionService<Contribution> completion = new ExecutorCompletionService<>(POOL);
        List<Future<Contribution>> futures = new ArrayList<>();
        futures.add(completion.submit(() -> fromGenius(track)));
        futures.add(completion.submit(() -> text(
                LastFM.fetchTrackWiki(artist, title), "Last.fm")));
        futures.add(completion.submit(() -> text(
                StoryWikipedia.fetch(artist, title, language), "Wikipedia")));
        futures.add(completion.submit(() -> text(
                LyricsRequests.MusicBrainzClient.fetchRecordingFacts(artist, title), "MusicBrainz")));

        List<Contribution> found = new ArrayList<>();
        long deadline = System.currentTimeMillis() + FACT_DEADLINE_MS;
        for (int i = 0; i < futures.size(); i++) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                Future<Contribution> future = remaining > 0
                        ? completion.poll(remaining, TimeUnit.MILLISECONDS)
                        : completion.poll();
                if (future == null) {
                    break;
                }
                Contribution contribution = future.get();
                if (contribution != null && !contribution.text().isEmpty()) {
                    found.add(contribution);
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Story fact source failed", ex);
            }
        }
        for (Future<Contribution> future : futures) {
            future.cancel(true);
        }
        return merge(found);
    }

    @androidx.annotation.Nullable
    private static Contribution fromGenius(TrackInfo track) {
        try {
            JSONObject record = GeniusProvider.storyRecord(track);
            if (record == null) {
                return null;
            }
            String description = stripTags(record.optString("description", "")).trim();
            String credits = stripTags(record.optString("credits", "")).trim();
            String text = joinNonEmpty(description, credits);
            if (text.isEmpty()) {
                return null;
            }
            boolean verified = record.optBoolean("verified", false);
            // Only a positive verified signal earns the suffix; otherwise it is plain Genius.
            String label = verified ? "Genius Verified" : "Genius";
            return new Contribution(text, label, verified);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Genius story contribution failed", ex);
            return null;
        }
    }

    @androidx.annotation.Nullable
    private static Contribution text(@androidx.annotation.Nullable String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return new Contribution(value.trim(), label, false);
    }

    @NonNull
    static Facts merge(List<Contribution> contributions) {
        List<String> parts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean verified = false;
        // Collect every successful source's attribution first; the prompt budget only limits text.
        for (Contribution c : contributions) {
            if (c == null || c.text().isEmpty()) {
                continue;
            }
            if (!labels.contains(c.label())) {
                labels.add(c.label());
            }
            verified |= c.verified();
        }
        int total = 0;
        for (Contribution c : contributions) {
            if (c == null || c.text().isEmpty()) {
                continue;
            }
            String line = c.label() + ": " + clipAtSentence(c.text(), MAX_SOURCE_CHARS);
            if (total + line.length() > MAX_FACTS_CHARS) {
                int room = MAX_FACTS_CHARS - total;
                if (room > 80) {
                    String clipped = clipAtSentence(line, room);
                    if (!clipped.isEmpty()) {
                        parts.add(clipped);
                    }
                }
                break;
            }
            parts.add(line);
            total += line.length() + 1;
        }
        String label = labels.isEmpty() ? "" : String.join(" · ", labels);
        return new Facts(String.join("\n", parts), label, verified);
    }

    /** Keeps as much of a passage as fits, ending on a sentence or word instead of mid-word. */
    static String clipAtSentence(@androidx.annotation.Nullable String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        String prefix = normalized.substring(0, maxChars);
        int boundary = -1;
        for (int i = prefix.length() - 1; i >= maxChars / 2; i--) {
            char ch = prefix.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？') {
                boundary = i + 1;
                break;
            }
        }
        if (boundary < 0) {
            int space = prefix.lastIndexOf(' ');
            boundary = space > maxChars / 2 ? space : maxChars;
        }
        return prefix.substring(0, boundary).trim();
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "");
    }

    private static String joinNonEmpty(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "\n" + b;
    }
}
