/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.reload

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil

private val reloadVideoButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch,
    )

    execute {

        copyResources(
            "reloadbutton",
            ResourceGroup(
                resourceDirectoryName = "drawable",
                "morphe_reload_video_button.xml",
                "morphe_reload_video_button_bold.xml",
            ),
        )
    }
}

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ReloadVideoPatch;"

private const val EXTENSION_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ReloadVideoPatch$PlayerInterface;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/ReloadVideoButton;"

@Suppress("unused")
val reloadVideoButtonPatch = bytecodePatch(
    name = "Reload video",
    description = "Adds an option to display reload video button in the video player.",
) {
    dependsOn(
        reloadVideoButtonResourcePatch,
        legacyPlayerControlsPatch,
        videoInformationPatch,
        playerOverlayButtonsSettingsPatch,
        bytecodePatch {
            finalize {
                addTopControl(
                    "reloadbutton",
                    "@+id/morphe_reload_video_button",
                    "@+id/morphe_reload_video_button"
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_reload_video_button")
        )

        initializeTopControl(EXTENSION_BUTTON)
        injectVisibilityCheckCall(EXTENSION_BUTTON)

        // Main activity is used to launch downloader intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setMainActivity(Landroid/app/Activity;)V"
        )

        val dismissPlayerInnerMethod = MiniAppOpenYtContentCommandEndpointFingerprint
            .instructionMatches.last()
            .getInstruction<ReferenceInstruction>()
            .getReference<MethodReference>()!!

        mutableClassDefBy(dismissPlayerInnerMethod.definingClass).apply {
            // Add interface and helper methods to allow extension code to call obfuscated methods.
            interfaces.add(EXTENSION_PLAYER_INTERFACE)
            // Add methods to access obfuscated player methods.
            methods.add(
                ImmutableMethod(
                    type,
                    "patch_dismissPlayer",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, $dismissPlayerInnerMethod
                            return-void
                        """
                    )
                }
            )

            methods.single { method ->
                MethodUtil.isConstructor(method)
            }.apply {
                val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize($EXTENSION_PLAYER_INTERFACE)V"
                )
            }
        }
    }
}
