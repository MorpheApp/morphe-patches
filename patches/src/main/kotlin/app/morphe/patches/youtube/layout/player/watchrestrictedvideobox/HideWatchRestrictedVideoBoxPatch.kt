package app.morphe.patches.youtube.layout.player.watchrestrictedvideobox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideWatchRestrictedVideoBoxPatch;"

@Suppress("unused")
val HideWatchRestrictedVideoBoxPatch = bytecodePatch(
    name = "Hide watch restricted video box",
    description = "Prevent the confirmation window from appearing for restricted videos.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_watch_restricted_video_box"),
        )

        AllowControversialContentFingerprint.apply {
            val allowControversialContentMethodRef =
                (method.getInstruction(instructionMatches[2].index) as ReferenceInstruction).reference as MethodReference
            val allowControversialContentClassDef =
                this@execute.mutableClassDefBy { it.type == allowControversialContentMethodRef.definingClass }
            val allowControversialContentMethod =
                allowControversialContentClassDef.methods.find { it.name == allowControversialContentMethodRef.name }
                    ?: throw PatchException("Allow Controversial Content method not found!")

            // Clone the method to add additional registers because the
            // original one has only 1 register and 2 are needed.
            val allowControversialContentCloned = allowControversialContentMethod.cloneMutable(
                additionalRegisters = 1
            )

            allowControversialContentClassDef.methods.apply {
                remove(allowControversialContentMethod)
                add(allowControversialContentCloned)
            }

            allowControversialContentCloned.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideConfirmationBox()Z
                    move-result v0
                    if-eqz v0, :hide_controversial_content_confirmation_box
                    return v0
                    :hide_controversial_content_confirmation_box
                    nop
                """
            )

            val allowAdultContentFingerprint = Fingerprint(
                definingClass = allowControversialContentMethod.definingClass,
                accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
                returnType = "Ljava/lang/Boolean;",
                parameters = listOf(),
                filters = OpcodesFilter.opcodesToFilters(
                    Opcode.CONST_4,
                    Opcode.INVOKE_STATIC,
                    Opcode.MOVE_RESULT_OBJECT,
                    Opcode.IGET_OBJECT,
                    Opcode.INVOKE_INTERFACE,
                    Opcode.MOVE_RESULT
                )
            )

            allowAdultContentFingerprint.apply {
                method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideConfirmationBox()Z
                        move-result v0
                        if-eqz v0, :hide_adult_content_confirmation_box
                        invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                        move-result-object v0
                        return-object v0
                        :hide_adult_content_confirmation_box
                        nop
                    """
                )
            }
        }
    }
}
