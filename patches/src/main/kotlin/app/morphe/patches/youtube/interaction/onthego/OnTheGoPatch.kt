/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4511
 * https://github.com/MorpheApp/morphe-patches/pull/4570
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.onthego

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.commandrouter.CommandRouterExecuteCommandFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/onthego/OnTheGoPatch;"

private const val EXTENSION_FILTER = "Lapp/morphe/extension/youtube/patches/onthego/OnTheGoFilter;"

private const val EXTENSION_STATE_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/onthego/OnTheGoPatch$OnTheGoState;"

private const val EXTENSION_SEEK_LENGTH_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/onthego/OnTheGoPatch$SeekLength;"

val onTheGoPatch = patch(
    name = "On-the-go player",
    description = "Adds an option to open a panel with large playback controls " +
            "from the On-the-go item of the video player menu. " +
            "While the panel is open, the lock screen media controls have seek buttons."
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
        settingsPatch,
        elementProtoParserHookPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        lithoFilterPatch,
        flyoutPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_on_the_go", summary = true)
        )

        // The menu item of an account without Premium has the request of the upsell,
        // which is saved to find the command of the menu item when it runs.
        hookElement("$EXTENSION_CLASS->onElementLoaded")

        // Some accounts get a video player menu without the On-the-go item,
        // so the item is added to the menu if the filter finds no item of the app.
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
        addLithoFilter(EXTENSION_FILTER)

        // Commands of Elements components, such as of the menu item, are run by the command router.
        CommandRouterExecuteCommandFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onCommand(Lcom/google/protobuf/MessageLite;)Z
                move-result v0
                if-eqz v0, :run_command
                return-void
                :run_command
                nop
            """
        )

        if (!is_21_05_or_greater) {
            // Older versions have no On-the-go media session actions.
            return@execute
        }

        // While On-the-go is active, the media controls of the lock screen and notification
        // have seek back and seek forward buttons. The Morphe panel turns this on while it is open.
        val stateClass = OnTheGoSkipToNextActionConstructorFingerprint.method.parameterTypes.first().toString()

        getOnTheGoStateConstructorFingerprint(stateClass).let {
            it.method.addInstruction(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0 }, $EXTENSION_CLASS->setOnTheGoState($EXTENSION_STATE_INTERFACE)V"
            )
        }

        getOnTheGoStateSetterFingerprint(stateClass).let {
            it.classDef.apply {
                interfaces.add(EXTENSION_STATE_INTERFACE)

                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_setOnTheGoActive",
                        listOf(ImmutableMethodParameter("Z", null, null)),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-virtual { p0, p1 }, ${it.method}
                                return-void
                            """
                        )
                    }
                )
            }
        }

        // The seek buttons of the panel use the double tap to seek length of the app settings.
        SeekLengthConstructorFingerprint.let {
            it.method.apply {
                val durationType = it.instructionMatches[1].methodReference.definingClass
                val seekLengthField = it.instructionMatches.last().fieldReference
                val fieldIndex = it.instructionMatches.last().index

                addInstruction(
                    fieldIndex + 1,
                    "invoke-static { p0 }, $EXTENSION_CLASS->setSeekLength($EXTENSION_SEEK_LENGTH_INTERFACE)V"
                )

                it.classDef.apply {
                    interfaces.add(EXTENSION_SEEK_LENGTH_INTERFACE)

                    methods.add(
                        ImmutableMethod(
                            type,
                            "patch_getSeekLengthMilliseconds",
                            listOf(),
                            "J",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(3),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    iget-object v0, p0, $seekLengthField
                                    check-cast v0, $durationType
                                    invoke-virtual { v0 }, $durationType->toMillis()J
                                    move-result-wide v0
                                    return-wide v0
                                """
                            )
                        }
                    )
                }
            }
        }
    }
}
