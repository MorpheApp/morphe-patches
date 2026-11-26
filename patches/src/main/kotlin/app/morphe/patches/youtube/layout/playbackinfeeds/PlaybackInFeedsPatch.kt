/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2261
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playbackinfeeds

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_06_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_24_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PlaybackInFeedsPatch;"
private const val EXTENSION_CONTROLLER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PlaybackInFeedsPatch$PlaybackInFeedsController;"

val playbackInFeedsPatch = patch(
    name = "Playback in feeds",
    description = "Adds the 'Playback in feeds' setting of YouTube to the Morphe settings, " +
            "where it is always available even if YouTube hides it, " +
            "and an option to show the seek buttons of the videos that play in feeds."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            ListPreference(
                key = "morphe_playback_in_feeds",
                tag = "app.morphe.extension.youtube.settings.preference.PlaybackInFeedsListPreference"
            )
        )

        if (is_21_06_or_greater) {
            PreferenceScreen.FEED.addPreferences(
                SwitchPreference("morphe_playback_in_feeds_seek_buttons", summary = true)
            )

            // The seek buttons of a video that plays in a feed are shown only if the server enables them.
            // The field is found by the value appended after its name in the description of the video.
            SimpleAutoplayableToStringFingerprint.let {
                it.method.apply {
                    val appendIndex = it.instructionMatches.last().index
                    val valueRegister = getInstruction<FiveRegisterInstruction>(appendIndex).registerD
                    val fieldIndex = indexOfFirstInstructionReversedOrThrow(appendIndex) {
                        opcode == Opcode.IGET_BOOLEAN && (this as TwoRegisterInstruction).registerA == valueRegister
                    }
                    val fieldName = getInstruction(fieldIndex).getReference<FieldReference>()!!.name

                    enableSeekingUiGetterFingerprint(fieldName).match(it.classDef).let { getter ->
                        getter.method.apply {
                            val returnIndex = getter.instructionMatches.last().index
                            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                            addInstructions(
                                returnIndex,
                                """
                                    invoke-static { v$register }, $EXTENSION_CLASS->showSeekButtons(Z)Z
                                    move-result v$register
                                """
                            )
                        }
                    }
                }
            }

            if (is_21_24_or_greater) {
                // The seek backwards button is drawn over the round background of its container,
                // and YouTube hides only the button, so the background stays as an empty button.
                SeekBackwardsToggleInflateFingerprint.let {
                    it.method.apply {
                        val index = it.instructionMatches.last().index
                        val register = getInstruction<OneRegisterInstruction>(index).registerA

                        addInstruction(
                            index + 1,
                            "invoke-static/range { v$register .. v$register }, " +
                                    "$EXTENSION_CLASS->hideEmptySeekBackwardsButton(Landroid/view/View;)V"
                        )
                    }
                }
            }
        }

        // The class is found using the setting of YouTube itself,
        // since the class and its methods are obfuscated.
        val controllerClassType = PlaybackInFeedsSettingFingerprint.instructionMatches.last()
            .getMethodCalled().definingClass
        val controllerClass = mutableClassDefBy(controllerClassType)

        val getModeMethod = PlaybackInFeedsGetModeFingerprint.match(controllerClass).method
        val setModeMethod = PlaybackInFeedsSetModeFingerprint.match(controllerClass).method

        controllerClass.apply {
            // Add the interface and methods that extension calls.

            addPatchInterface(
                EXTENSION_CONTROLLER_INTERFACE,
                "patch_getPlaybackInFeedsMode",
                """
                    invoke-virtual { p0 }, $getModeMethod
                    move-result v0
                    return v0
                """
            )

            addPatchInterface(
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
