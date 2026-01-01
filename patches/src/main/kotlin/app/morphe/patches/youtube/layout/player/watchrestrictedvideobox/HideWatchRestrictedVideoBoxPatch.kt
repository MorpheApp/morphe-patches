package app.morphe.patches.youtube.layout.player.watchrestrictedvideobox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

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

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.37.48"
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_watch_restricted_video_box"),
        )

        RestrictionsTypeVideoFingerprint.apply {
            val showBoxMethodRef =
                (method.getInstruction(instructionMatches[2].index) as ReferenceInstruction).reference as MethodReference
            val showBoxMethodClassDef =
                this@execute.mutableClassDefBy { it.type == showBoxMethodRef.definingClass }
            val showBoxMethod =
                showBoxMethodClassDef.methods.find { it.name == showBoxMethodRef.name }
                    ?: throw PatchException("Show Box method not found!")

            Logger.getLogger(this::class.java.name).info(
                showBoxMethod.name
            )

            Logger.getLogger(this::class.java.name).info(
                showBoxMethod.definingClass
            )

            showBoxMethod.addInstructions(
                0,

                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideConfirmationBox()Z
                    move-result v0
                    return v0
                    nop
                """
            )
        }
    }
}
