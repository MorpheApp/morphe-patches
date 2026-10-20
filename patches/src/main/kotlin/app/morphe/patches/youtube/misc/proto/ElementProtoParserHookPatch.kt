/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.proto

import app.morphe.patches.shared.misc.proto.createElementProtoParserHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_40_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

val elementProtoParserHookPatch = createElementProtoParserHookPatch(
    sharedExtensionPatch,
    // YouTube 21.40+ can read the inline resources of the responses with the element proto parser.
    readsResourcesWithParser = { is_21_40_or_greater },
) {
    dependsOn(versionCheckPatch)
}
