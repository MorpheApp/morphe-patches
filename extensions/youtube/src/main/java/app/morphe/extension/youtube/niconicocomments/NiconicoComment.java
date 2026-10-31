/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable subset of a YouTube live-chat item needed by the overlay. */
final class NiconicoComment {
    final String id;
    final String text;
    final List<Segment> segments;
    final boolean membership;
    final int backgroundColor;
    final long videoOffsetMs;

    NiconicoComment(
            String id,
            String text,
            boolean membership,
            int backgroundColor,
            long videoOffsetMs
    ) {
        this(
                id,
                text,
                Collections.singletonList(Segment.text(text)),
                membership,
                backgroundColor,
                videoOffsetMs
        );
    }

    NiconicoComment(
            String id,
            String text,
            List<Segment> segments,
            boolean membership,
            int backgroundColor,
            long videoOffsetMs
    ) {
        this.id = id;
        this.text = text;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.membership = membership;
        this.backgroundColor = backgroundColor;
        this.videoOffsetMs = videoOffsetMs;
    }

    /**
     * Number of characters shown, counting each emoji image as one character.
     */
    int length() {
        int length = 0;
        for (Segment segment : segments) {
            length += segment.isEmoji() ? 1 : segment.text.codePointCount(0, segment.text.length());
        }
        return length;
    }

    static final class Segment {
        final String text;
        final String imageUrl;

        private Segment(String text, String imageUrl) {
            this.text = text;
            this.imageUrl = imageUrl;
        }

        static Segment text(String text) {
            return new Segment(text, "");
        }

        static Segment emoji(String fallbackText, String imageUrl) {
            return new Segment(fallbackText, imageUrl);
        }

        boolean isEmoji() {
            return !imageUrl.isEmpty();
        }
    }
}
