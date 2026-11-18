/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4477
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.avatars

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.shared.misc.glide.GlideRequestBuilderLoadGenericFingerprint
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HighQualityAvatarsPatch;"

@Suppress("unused")
val highQualityAvatarsPatch = patch(
    name = "High quality avatars",
    description = "Adds an option to show user avatars in full resolution instead of the low resolution version."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // Avatars are loaded with Glide, both in Compose and in views.
        GlideRequestBuilderLoadGenericFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getImageModel(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
