/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2261
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playbackinfeeds

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterfaceMethod
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PlaybackInFeedsPatch;"
private const val EXTENSION_CONTROLLER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PlaybackInFeedsPatch$PlaybackInFeedsController;"

val playbackInFeedsPatch = patch(
    name = "Playback in feeds",
    description = "Adds the 'Playback in feeds' setting of YouTube to the Morphe settings, " +
            "where it is always available even if YouTube hides it."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            ListPreference(
                key = "morphe_playback_in_feeds",
                tag = "app.morphe.extension.youtube.settings.preference.PlaybackInFeedsListPreference"
            )
        )

        // The class is found using the setting of YouTube itself,
        // since the class and its methods are obfuscated.
        val controllerClassType = PlaybackInFeedsSettingFingerprint.instructionMatches.last()
            .getMethodCalled().definingClass
        val controllerClass = mutableClassDefBy(controllerClassType)

        val getModeMethod = PlaybackInFeedsGetModeFingerprint.match(controllerClass).method
        val setModeMethod = PlaybackInFeedsSetModeFingerprint.match(controllerClass).method

        controllerClass.apply {
            // Add the interface and methods that extension calls.

            addPatchInterfaceMethod(
                EXTENSION_CONTROLLER_INTERFACE,
                "patch_getPlaybackInFeedsMode",
                """
                    invoke-virtual { p0 }, $getModeMethod
                    move-result v0
                    return v0
                """
            )

            addPatchInterfaceMethod(
                EXTENSION_CONTROLLER_INTERFACE,
                "patch_setPlaybackInFeedsMode",
                """
                    invoke-virtual { p0, p1 }, $setModeMethod
                    return-void
                """
            )

            // Hook the end of the constructor, since the fields this class uses
            // are not set until then.
            methods.single { MethodUtil.isConstructor(it) }.apply {
                val returnIndex = indexOfFirstInstructionOrThrow(Opcode.RETURN_VOID)

                addInstruction(
                    returnIndex,
                    "invoke-static { p0 }, $EXTENSION_CLASS->" +
                            "setController($EXTENSION_CONTROLLER_INTERFACE)V"
                )
            }
        }
    }
}
