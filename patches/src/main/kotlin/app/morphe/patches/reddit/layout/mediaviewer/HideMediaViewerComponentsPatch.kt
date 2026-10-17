/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_32_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_38_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.removeFlags
import app.morphe.util.returnEarly
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideMediaViewerComponentsPatch;"

@Suppress("unused")
val hideMediaViewerComponentsPatch = bytecodePatch(
    name = "Hide media viewer components",
    description = "Adds options to hide the \"See the conversation\" button and the title, buttons and video controls " +
            "of the media viewer. Hiding the title, buttons and video controls requires Reddit 2026.38.0 or newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Hide media viewer components' requires Reddit 2026.24.0+"
            )
        }

        if (is_2026_32_0_or_greater) {
            // Don't show the dock. Its button is then not drawn, and the media is not padded for it,
            // like when the comments sheet is open.
            JoinConversationDockFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[2].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->showJoinConversationButton(Z)Z
                            move-result v$register
                        """
                    )
                }
            }

            // Without the dock, the media is centered between the top of the screen and the
            // navigation bar, so it is too high. Also pad images and videos for the status bar,
            // so the media is centered between the system bars.
            MediaViewerPageFingerprint.let {
                it.method.apply {
                    // Images and videos skip the padding.
                    val skipIndex = it.instructionMatches[3].index
                    val skipInstruction = getInstruction<BuilderInstruction21t>(skipIndex)
                    val register = skipInstruction.registerA
                    val skipTarget = skipInstruction.target.location.instruction!!

                    // Replace the branch, so branches to it still run the check.
                    replaceInstruction(
                        skipIndex,
                        "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->skipStatusBarPadding(Z)Z"
                    )
                    addInstructionsWithLabels(
                        skipIndex + 1,
                        """
                            move-result v$register
                            if-nez v$register, :skip_padding
                        """,
                        ExternalLabel("skip_padding", skipTarget)
                    )
                }
            }
        } else {
            // Older versions show the button below the video controls, without a dock.
            JoinConversationButtonLegacyFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->showJoinConversationButton(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Older versions hide the overlay from the video player instead of the chrome state.
        if (!is_2026_38_0_or_greater) {
            Logger.getLogger(this::class.java.name).warning(
                "Hiding the media viewer overlay requires Reddit 2026.38.0+"
            )
        } else {
            // The chrome state 'isVisible' field is toggled when tapping the media.
            val visibleField = FullBleedChromeStateToStringFingerprint.instructionMatches.last()
                .getFieldAccessed().apply {
                    // The field is final, and is written from another class.
                    removeFlags(AccessFlags.FINAL)
                }

            // Hide the overlay of each newly created chrome state.
            createFullBleedChromeStateFingerprint(
                FullBleedChromeStateToStringFingerprint.classDef.type
            ).method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val stateRegister = getInstruction<OneRegisterInstruction>(index).registerA
                    val freeRegister = findFreeRegister(index, stateRegister)
                    addInstructionsWithLabels(
                        index,
                        """
                            invoke-static { }, $EXTENSION_CLASS->hideOverlay()Z
                            move-result v$freeRegister
                            if-eqz v$freeRegister, :show_overlay
                            const/4 v$freeRegister, 0x0
                            iput-boolean v$freeRegister, v$stateRegister, $visibleField
                            :show_overlay
                            nop
                        """
                    )
                }
            }

            Fingerprint(
                definingClass = EXTENSION_CLASS,
                name = "isHideOverlayIncluded"
            ).method.returnEarly(true)
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
