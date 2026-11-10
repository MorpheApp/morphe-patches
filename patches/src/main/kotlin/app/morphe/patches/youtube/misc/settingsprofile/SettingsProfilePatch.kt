/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.settingsprofile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import java.io.File
import java.util.Base64

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/SettingsProfilePatch;"

private object SettingsProfileFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "profile",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
)

@Suppress("unused")
val settingsProfilePatch = bytecodePatch(
    name = "Settings profile",
    description = "Applies a Morphe settings export on the first launch, so a new install starts configured.",
    default = false,
) {
    val profileOption = stringOption(
        key = "settingsProfile",
        default = "",
        title = "Settings profile",
        description = "A Morphe settings export (Settings > Import / Export), as text or as the path of a file " +
            "holding it. Applied once on the first launch, and again only when a build carries a different profile.",
    )

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val value = profileOption.value?.trim().orEmpty()
        val profile = File(value).takeIf { value.isNotEmpty() && !value.startsWith("{") && it.isFile }
            ?.readText()?.trim()
            ?: value
        if (profile.isEmpty()) throw PatchException("No settings profile given (option settingsProfile)")

        // Base64 has no quotes, backslashes or line breaks, so it goes into a smali string as is.
        SettingsProfileFingerprint.method.returnEarly(
            Base64.getEncoder().encodeToString(profile.toByteArray(Charsets.UTF_8))
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onCreate(Landroid/app/Activity;)V",
        )
    }
}
