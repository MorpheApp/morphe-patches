package app.morphe.patches.youtube.layout.shortsdoubletaptolike

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableShortsDoubleTapToLikePatch;"

@Suppress("unused")
val disableShortsDoubleTapToLikePatch = bytecodePatch(
    name = "Disable shorts double tap to like",
    description = "Adds an option to disable the double tap to like gesture on Shorts player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_shorts_double_tap_to_like")
        )

        val doubleTapToLikeLogicFingerprint = Fingerprint(
            filters = OpcodesFilter.opcodesToFilters(
                Opcode.IGET,
                Opcode.SUB_FLOAT_2ADDR,
                Opcode.SUB_FLOAT_2ADDR,
                Opcode.IGET,
                Opcode.FLOAT_TO_DOUBLE,
                Opcode.FLOAT_TO_DOUBLE,
                Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_WIDE,
                Opcode.INT_TO_DOUBLE,
                Opcode.CMPG_DOUBLE,
                Opcode.IF_GTZ,
                Opcode.INVOKE_VIRTUAL,
                Opcode.MOVE_RESULT_OBJECT,
                Opcode.IGET_BOOLEAN,
                Opcode.IF_EQZ
            )
        )
        val doubleTapToLikeLogicMatchResult = doubleTapToLikeLogicFingerprint.match(ShortsPlayerOnTouchEventLogicFingerprint.method)

        val patchIndex = doubleTapToLikeLogicMatchResult.instructionMatches.last().index

        doubleTapToLikeLogicMatchResult.method.let {
            val doubleTapToLikeBoolRegister = it.getInstruction(patchIndex).registersUsed[0]

            it.addInstructionsAtControlFlowLabel(
                patchIndex,
                """
                    invoke-static {v${doubleTapToLikeBoolRegister}}, $EXTENSION_CLASS->overrideDoubleTapToLike(Z)Z
                    move-result v${doubleTapToLikeBoolRegister}
                """
            )
        }
    }
}
