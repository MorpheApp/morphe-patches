/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.music.layout.startpage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object MusicActivityOnCreateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, _ ->
        method.definingClass == "Lcom/google/android/apps/youtube/music/activities/MusicActivity;" &&
                method.name == "onCreate"
    }
)

internal object ColdStartIntentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;"),
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    custom = { method, _ ->
        method.definingClass == "Lcom/google/android/apps/youtube/music/activities/MusicActivity;" &&
                method.name == "p"
    }
)

internal object ColdStartUpFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("FEmusic_library_sideloaded_tracks"),
        string("FEmusic_home")
    )
)