package app.morphe.patches.youtube.layout.hide.endscreensuggestedvideo

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideEndScreenSuggestedVideoPatch;"

@Suppress("unused")
val hideEndScreenSuggestedVideoPatch = bytecodePatch(
    name = "Hide end screen suggested video",
    description = "Adds an option to hide the suggested video at the end of videos.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_end_screen_suggested_video"),
        )

        val autoNavStatusMethod = AutoNavStatusFingerprint.match(
            AutoNavConstructorFingerprint.originalClassDef
        ).method

        val endScreenMethod = RemoveOnLayoutChangeListenerFingerprint.instructionMatches[1]
            .instruction.getReference<MethodReference>()!!.getMutableMethod()

        val endScreenSuggestedVideoFingerprint = Fingerprint(
            definingClass = endScreenMethod.definingClass,
            name = endScreenMethod.name,
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    definingClass = "this",
                    type = autoNavStatusMethod.definingClass
                ),
                methodCall(
                    opcode = Opcode.INVOKE_VIRTUAL,
                    smali = autoNavStatusMethod.toString(),
                    location = MatchAfterWithin(3)
                )
            )
        )

        endScreenSuggestedVideoFingerprint.let {
            it.method.apply {
                val autoNavField = it.instructionMatches.first().instruction.getReference<FieldReference>()!!

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideEndScreenSuggestedVideo()Z
                        move-result v0
                        if-eqz v0, :ignore

                        iget-object v0, p0, $autoNavField

                        # This reference checks whether autoplay is turned on.
                        invoke-virtual { v0 }, $autoNavStatusMethod
                        move-result v0

                        # Hide suggested video end screen only when autoplay is turned off.
                        if-nez v0, :ignore
                        return-void
                        :ignore
                        nop
                    """
                )
            }
        }
    }
}
