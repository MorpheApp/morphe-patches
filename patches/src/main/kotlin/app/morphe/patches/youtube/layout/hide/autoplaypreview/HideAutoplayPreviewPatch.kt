package app.morphe.patches.youtube.layout.hide.autoplaypreview

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_39_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstResourceIdOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/HideAutoplayPreviewPatch;"

@Suppress("unused")
val hideAutoplayPreviewPatch = bytecodePatch(
    name = "Hide autoplay preview",
    description = "Adds an option to hide the autoplay preview at the end of videos.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_autoplay_preview", summary = true)
        )

        LayoutConstructorFingerprint.clearMatch()
        LayoutConstructorFingerprint.method.apply {
            val constIndex = indexOfFirstResourceIdOrThrow("autonav_preview_stub")
            val constRegister = getInstruction<OneRegisterInstruction>(constIndex).registerA
            var gotoIndex = indexOfFirstInstructionOrThrow(constIndex) {
                val parameterTypes = getReference<MethodReference>()?.parameterTypes
                opcode == Opcode.INVOKE_VIRTUAL &&
                        parameterTypes?.size == 2 &&
                        parameterTypes.first() == "Landroid/view/ViewStub;"
            } + 1

            // Jump past goto instructions.
            if (is_21_39_or_greater) {
                while (getInstruction(gotoIndex).opcode in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)) {
                    gotoIndex++
                }
            }

            addInstructionsWithLabels(
                constIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS->hideAutoplayPreview()Z
                    move-result v$constRegister
                    if-nez v$constRegister, :hidden
                """,
                ExternalLabel("hidden", getInstruction(gotoIndex)),
            )
        }
    }
}
