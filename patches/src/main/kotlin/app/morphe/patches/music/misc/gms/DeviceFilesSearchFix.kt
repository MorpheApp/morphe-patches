/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4725
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/DeviceFilesSearchPatch;"

/**
 * Device files search queries the Play Services app search index, which GmsCore does not have,
 * so it never finds anything. The media provider is searched instead, on the same executor.
 * The app still turns the returned content uris into the search results.
 */
internal fun searchDeviceFilesWithMediaProvider() {
    DeviceFilesSearchFingerprint.let {
        val executorField = it.instructionMatches[1].getFieldAccessed()

        it.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->search(Ljava/lang/String;[Ljava/lang/String;)Ljava/util/concurrent/Callable;
                move-result-object v0
                move-object/from16 v1, p0
                iget-object v1, v1, $executorField
                invoke-static { v0, v1 }, ${FuturesSubmitFingerprint.originalMethod}
                move-result-object v0
                return-object v0
            """
        )
    }
}
