/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5301
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.descriptioncounts

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixDescriptionCountsPatch;"

val fixDescriptionCountsPatch = patch(
    description = "Fixes the like and view counts of the video description showing an older count.",
) {
    dependsOn(
        sharedExtensionPatch,
        elementProtoParserHookPatch,
    )

    execute {
        // The counts of the description header are in its element.
        hookElement("$EXTENSION_CLASS->onElementLoaded")
    }
}
