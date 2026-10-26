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
