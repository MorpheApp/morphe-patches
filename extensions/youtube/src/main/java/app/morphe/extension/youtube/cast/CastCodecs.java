/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

/**
 * Video codecs offered to the Cast device. Every Cast device decodes H.264, but YouTube offers it
 * only up to 1080p. Higher qualities need VP9 or AV1, which not every device decodes, and a device can
 * report a codec it cannot play.
 */
public enum CastCodecs {
    H264("avc1"),
    VP9("avc1", "vp9", "vp09"),
    AV1("avc1", "vp9", "vp09", "av01");

    private final String[] codecPrefixes;

    CastCodecs(String... codecPrefixes) {
        this.codecPrefixes = codecPrefixes;
    }

    boolean allows(String codecs) {
        for (String prefix : codecPrefixes) {
            if (codecs.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
