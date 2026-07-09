/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * One caption line, post-translation. Holds the original video timing plus the (possibly
 * adjusted) window during which the TTS audio is allowed to play.
 */
public final class TranscriptSegment {
    /** BCP-47 language tag of {@link #text} (e.g. {@code uk-UA}). */
    public final String lang;
    /** Translated caption text to be spoken. */
    public final String text;
    /** Original caption start/end times (video ms). Immutable - matches the source captions. */
    public final long startMs;
    public final long endMs;

    /**
     * Playback window for the TTS audio. Initially equal to {@link #startMs}/{@link #endMs}
     * but may be shifted by {@link TtsEngine#adjustPlaybackTimes} once the actual audio
     * length is known, so longer-than-slot speech can borrow time from neighboring gaps.
     */
    public volatile long playbackStartMs;
    public volatile long playbackEndMs;
    /** Actual TTS audio duration in ms; -1 until the audio has been synthesized. */
    public volatile long durationMs;

    public TranscriptSegment(long startMs, long endMs, String text, String lang) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.lang = lang;
        this.playbackStartMs = startMs;
        this.playbackEndMs = endMs;
        this.durationMs = -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TranscriptSegment that = (TranscriptSegment) o;
        return startMs == that.startMs && endMs == that.endMs &&
                Objects.equals(text, that.text) && Objects.equals(lang, that.lang);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startMs, endMs, text, lang);
    }

    @NonNull
    @Override
    public String toString() {
        final long duration = durationMs;
        final long playbackEnd = playbackEndMs;
        final long playbackStart = playbackStartMs;
        return "TranscriptSegment{" +
                "lang=" + lang +
                ", startMs=" + startMs +
                ", endMs=" + endMs +
                ", playbackStartMs=" + playbackStart +
                ", playbackEndMs=" + playbackEnd +
                ", durationMs=" + duration +
                ", playbackRate=" + (duration > 0 ? (duration / (float) (playbackEnd - playbackStart)) : 0) +
                ", text='" + text + '\'' +
                '}';
    }
}
