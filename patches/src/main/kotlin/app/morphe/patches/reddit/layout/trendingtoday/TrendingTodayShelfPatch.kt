package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.extension.Constants.PATCHES_PATH
import app.morphe.patches.reddit.utils.patch.PatchList.HIDE_TRENDING_TODAY_SHELF
import app.morphe.patches.reddit.utils.settings.is_2025_45_or_greater
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.patches.reddit.utils.settings.updatePatchStatus
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_PATH/TrendingTodayShelfPatch;"

@Suppress("unused")
val trendingTodayShelfPatch = bytecodePatch(
    HIDE_TRENDING_TODAY_SHELF.title,
    HIDE_TRENDING_TODAY_SHELF.summary,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(settingsPatch)

    execute {

        // region patch for hide trending today title.
        if (!is_2025_45_or_greater) {
            trendingTodayTitleFingerprint.match().let {
                it.method.apply {
                    val stringIndex = it.stringMatches.first().index
                    val relativeIndex =
                        indexOfFirstInstructionReversedOrThrow(stringIndex, Opcode.AND_INT_LIT8)
                    val insertIndex = indexOfFirstInstructionReversedOrThrow(
                        relativeIndex + 1,
                        Opcode.MOVE_OBJECT_FROM16
                    )
                    val insertRegister = getInstruction<TwoRegisterInstruction>(insertIndex).registerA
                    val jumpOpcode = if (returnType == "V") Opcode.RETURN_VOID else Opcode.SGET_OBJECT
                    var jumpIndex = indexOfFirstInstructionReversedOrThrow(jumpOpcode)

                    if (jumpOpcode == Opcode.SGET_OBJECT && getInstruction(jumpIndex + 1).opcode != Opcode.RETURN_OBJECT) {
                        jumpIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
                    }

                    addInstructionsWithLabels(
                        insertIndex, """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideTrendingTodayShelf()Z
                        move-result v$insertRegister
                        if-nez v$insertRegister, :hidden
                        """, ExternalLabel("hidden", getInstruction(jumpIndex))
                    )
                }
            }
        }

        searchTypeaheadListDefaultPresentationConstructorFingerprint.match(
            mutableClassDefBy(searchTypeaheadListDefaultPresentationToStringFingerprint.classDef)
        ).method.addInstructions(
            1, """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->removeTrendingLabel(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
                """
        )

        // endregion

        // region patch for hide trending today contents.

        val trendingTodayItems = listOf(
            trendingTodayItemFingerprint,
            trendingTodayItemLegacyFingerprint
        )

        trendingTodayItems.forEach { fingerprint ->
            fingerprint.method.addInstructionsWithLabels(
                0, """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideTrendingTodayShelf()Z
                move-result v0
                if-eqz v0, :ignore
                return-void
                :ignore
                nop
                """
            )
        }

        // endregion

        updatePatchStatus(
            "enableTrendingTodayShelf",
            HIDE_TRENDING_TODAY_SHELF
        )
    }
}
