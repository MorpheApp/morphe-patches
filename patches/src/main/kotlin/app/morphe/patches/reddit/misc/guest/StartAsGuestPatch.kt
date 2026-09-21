/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.guest

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/StartAsGuestPatch;"

private val COMPATIBILITY_REDDIT_GUEST = Compatibility(
    name = "Reddit",
    packageName = "com.reddit.frontpage",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0xFF4500,
    signatures = setOf(
        "970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f"
    ),
    targets = listOf(
        AppTarget(
            version = "2026.37.0",
            minSdk = 28,
            isExperimental = true
        )
    )
)

@Suppress("unused")
val startAsGuestPatch = bytecodePatch(
    name = "Start as guest",
    description = "Skips the forced startup login screen using Reddit's native guest browsing mode."
) {
    compatibleWith(COMPATIBILITY_REDDIT_GUEST)

    dependsOn(sharedExtensionPatch)

    execute {
        WelcomeViewModelConstructorFingerprint.let {
            val insertIndex = it.instructionMatches.first().index + 1
            it.method.addInstruction(
                insertIndex,
                "invoke-static { }, $EXTENSION_CLASS->schedule()V"
            )
        }

        StartupCredentialPickerFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                return-object v0
            """
        )
    }
}
