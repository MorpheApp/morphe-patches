/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.contentprovider

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

internal object UnstableContentProviderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/content/ContentResolver;", "[Ljava/lang/String;"),
    filters = listOf(
        // Early targets use HashMap and later targets use ConcurrentMap.
        methodCall(
            name = "putAll",
            parameters = listOf("Ljava/util/Map;")
        )
    ),
    strings = listOf("ContentProvider query returned null cursor")
)
