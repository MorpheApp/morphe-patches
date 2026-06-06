/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.request.buildRequestPatch
import app.morphe.patches.youtube.misc.request.hookBuildRequest

private const val EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/innertube/utils/AuthUtils;"

internal val authHookPatch = bytecodePatch(
    description = "authHookPatch"
) {
    dependsOn(
        sharedExtensionPatch,
        buildRequestPatch,
    )

    execute {
        AccountIdentityFingerprint.methodOrNull?.addInstruction(
            1,
            "invoke-static { p3, p4 }, $EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setAccountIdentity(Ljava/lang/String;Z)V"
        )

        hookBuildRequest("$EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V")
    }
}
