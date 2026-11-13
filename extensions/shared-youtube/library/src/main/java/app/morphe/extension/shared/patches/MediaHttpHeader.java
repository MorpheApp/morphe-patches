/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

/**
 * Added to the header class of the media requests of the app, whose fields are obfuscated.
 */
public interface MediaHttpHeader {
    // Methods are added during patching.
    @Nullable
    String patch_getName();

    @Nullable
    String patch_getValue();
}
