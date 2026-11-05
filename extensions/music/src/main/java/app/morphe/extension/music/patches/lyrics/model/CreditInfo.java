/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

/**
 * Transient credit info from the panel's AI processing state, passed to the
 * TTML generator at save time. Not persisted in the Lyrics model.
 *
 * @param translator "Google", "AI:gpt-4", or null
 * @param romanizer  "Google", "AI:gpt-4", "Local:Tanaka", or null
 */
public record CreditInfo(
        @Nullable String translator,
        @Nullable String romanizer
) {
}
