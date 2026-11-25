/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4931
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.cloneMutable

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AndroidAutoThirdPartyAppsPatch;"

@Suppress("unused")
val androidAutoThirdPartyAppsPatch = patch(
    name = "Android Auto third-party apps",
    description = "Allows third-party Android Auto apps, such as DriveDeck, Gearslip, AARE and Flywheel, " +
            "to browse Home and Library. Any installed app can then browse YouTube Music.",
) {
    dependsOn(androidAutoPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // YTM only answers allowlisted apps. Allow the rest in onGetRoot, then let them browse.
        val allowAppMethod = MediaBrowserGetRootFingerprint.instructionMatches.last().getMethodCalled()
        val callerType = allowAppMethod.parameterTypes[0].toString()
        val clientType = allowAppMethod.parameterTypes[1].toString()

        allowAppMethod.alsoAllowThirdPartyApps(callerType, "allowMediaBrowser")
        mediaBrowserClientAllowlistFingerprint(allowAppMethod.definingClass, clientType).method
            .alsoAllowThirdPartyApps(clientType, "isAllowedMediaBrowser")
    }
}

/**
 * Keeps YTM's allowlist check and asks the extension only when it fails.
 * The first parameter is YTM's caller or client object, which stores the app's package name.
 */
private fun MutableMethod.alsoAllowThirdPartyApps(packageNameHolderType: String, extensionMethodName: String) {
    val packageNameField = mediaBrowserPackageNameFingerprint(packageNameHolderType)
        .instructionMatches.first().fieldReference
    val originalMethod = cloneMutable(name = "patch_${name}_original")
    mutableClassDefBy(definingClass).methods.add(originalMethod)

    addInstructionsWithLabels(
        0,
        """
            invoke-virtual/range { p0 .. p${numberOfParameterRegisters - 1} }, $originalMethod
            move-result p0
            if-nez p0, :allowed
            iget-object p0, p1, $packageNameField
            invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->$extensionMethodName(Ljava/lang/String;)Z
            move-result p0
            :allowed
            return p0
        """
    )
}
