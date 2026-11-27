/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.effects

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.interaction.downloads.MenuItemClickFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.fix.proto.protoDefaultInstanceFingerprint
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.videoeffects.videoEffectsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/VideoEffectMenuPatch;"

@Suppress("unused")
val videoEffectsPatch = videoEffectsPatch(
    description = "Adds an option to change the colors of videos or add visual effects, such as black and white.",
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            musicVideoInformationPatch,
            fixProtoLibraryPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },
    preferenceScreen = PreferenceScreen.VIDEO,
    hookVideoId = ::musicVideoIdHook,
    executeBlock = {
        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("morphe_music_video_effect_menu", titleKey = "morphe_show_video_effect_menu_title", summary = true)
        )

        // Add the video effect item after the Quality item, which the app adds only while a video plays.
        PlayerMenuAppItemsFingerprint.let {
            val menuItemType = it.instructionMatches.last().methodReference.returnType
            val menuItemDefaultInstance =
                protoDefaultInstanceFingerprint(menuItemType).instructionMatches.first().fieldReference

            val addVideoEffectMenuItemMethod = ImmutableMethod(
                it.classDef.type,
                "patch_addVideoEffectMenuItem",
                listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)),
                "Ljava/util/List;",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->getVideoEffectMenuItem(Ljava/util/List;)[B
                        move-result-object v0
                        if-eqz v0, :no_item
                        sget-object v1, $menuItemDefaultInstance
                        invoke-static { v1, v0 }, ${parseByteArrayMethodRef.get()!!}
                        move-result-object v0
                        invoke-static { p0, v0 }, $EXTENSION_CLASS->addVideoEffectMenuItem(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                        move-result-object p0
                        :no_item
                        return-object p0
                    """
                )
            }
            it.classDef.methods.add(addVideoEffectMenuItemMethod)

            it.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    // The loop jumps to the return when it ends, so the code goes at its label.
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $addVideoEffectMenuItemMethod
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        // The item has no command, so the app only closes the menu when it is clicked.
        MenuItemClickFingerprint.let {
            val menuItemField = it.instructionMatches.first().fieldReference

            it.method.addInstructions(
                0,
                """
                    iget-object v0, p0, $menuItemField
                    invoke-static { v0 }, $EXTENSION_CLASS->onMenuItemClick(Ljava/lang/Object;)V
                """
            )
        }
    }
)
