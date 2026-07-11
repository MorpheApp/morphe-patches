/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.returnyoutubedislike;

/**
 * Vote type shared by the YouTube and YouTube Music Return YouTube Dislike implementations.
 */
public class ReturnYouTubeDislike {

    public enum Vote {
        LIKE("like/like", 1),
        DISLIKE("like/dislike", -1),
        LIKE_REMOVE("like/removelike", 0);

        public final String endpoint;
        public final int value;

        Vote(String endpoint, int value) {
            this.endpoint = endpoint;
            this.value = value;
        }
    }

    private ReturnYouTubeDislike() {
    }
}
