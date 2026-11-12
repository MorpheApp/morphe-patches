/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4725
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.devicefilessearch

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC

@Suppress("unused")
val deviceFilesSearchPatch = patch(
    name = "Fix device files search",
    description = "Uses Android's media provider for Device Files search instead of the Google Play services search index."
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        installDeviceFilesSearch()
    }
}

internal fun installDeviceFilesSearch() {
    val search = DeviceFilesSearchFingerprint.method
    val executor = classDefBy(search.definingClass).instanceFields.single {
        it.type == "Ljava/util/concurrent/Executor;"
    }
    val submit = SubmitSearchFingerprint.originalMethod
    // Keep the app's executor, futures, URI-to-model loading and result renderers.
    search.removeInstructions(0, search.implementation!!.instructions.size)
    search.addInstructions(0, """
        invoke-static/range { p1 .. p2 }, Lapp/morphe/extension/music/patches/DeviceFilesSearchPatch;->createSearch(Ljava/lang/String;[Ljava/lang/String;)Ljava/util/concurrent/Callable;
        move-result-object v0
        move-object/from16 v1, p0
        iget-object v1, v1, $executor
        invoke-static { v0, v1 }, $submit
        move-result-object v0
        return-object v0
    """)
}
