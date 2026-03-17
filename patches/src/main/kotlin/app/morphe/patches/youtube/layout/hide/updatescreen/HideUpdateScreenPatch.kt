/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.updatescreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags

val hideUpdateScreenPatch = bytecodePatch(
    description = "Hides the YouTube undismissable 'Update your app' dialog if " +
            "patching old versions of YouTube",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(versionCheckPatch)

    execute {
        // Only disable update screen for 20.21 and lower. This can be later adjusted if desired.
        if (!is_20_22_or_greater) {
            Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
                parameters = listOf("Landroid/content/Intent;", "Z")
            ).match(
                AppBlockingCheckResultToStringFingerprint.originalClassDef
            ).method.addInstructions(
                1,
                "const/4 p1, 0x0"
            )
        }
    }
}
