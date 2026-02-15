package app.morphe.patches.reddit.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.is_2026_04_or_greater
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFieldFromToString
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/HideAdsPatch;"

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to hide ads."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {

        // region Filter promoted ads (does not work in popular or latest feed)

        listOf(
            ListingFingerprint,
            SubmittedListingFingerprint
        ).forEach { fingerprint ->
            fingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->hideOldPostAds(Ljava/util/List;)Ljava/util/List;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        val immutableListBuilderReference = ImmutableListBuilderFingerprint.instructionMatches
            .last().getInstruction<ReferenceInstruction>().reference

        AdPostSectionConstructorFingerprint.match(
            AdPostSectionToStringFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val sectionIndex = it.instructionMatches.first().index
                val sectionRegister = getInstruction<FiveRegisterInstruction>(
                    sectionIndex + 1
                ).registerC

                addInstructionsWithLabels(
                    sectionIndex,
                    """
                        invoke-static { v$sectionRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideNewPostAds(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$sectionRegister
                        if-nez v$sectionRegister, :ignore
                        new-instance v$sectionRegister, Ljava/util/ArrayList;
                        invoke-direct { v$sectionRegister }, Ljava/util/ArrayList;-><init>()V
                        invoke-static { v$sectionRegister }, $immutableListBuilderReference
                        move-result-object v$sectionRegister
                        :ignore
                        nop
                    """
                )
            }
        }

        // endregion

        // region Filter comment ads

        CommentsViewModelAdLoaderFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideCommentAds()Z
                move-result v0
                if-eqz v0, :show
                return-void
                :show
                nop
            """
        )

        // As of Reddit 2026.04+, placeholders are not hidden unless 'adsLoadCompleted' is false.
        // Hide placeholders by overriding 'adsLoadCompleted' to true.
        if (is_2026_04_or_greater) {
            val adsLoadCompletedField = CommentsAdStateToStringFingerprint.method
                .findFieldFromToString(", adsLoadCompleted=")

            val commentsAdStateConstructorFingerprint = Fingerprint(
                name = "<init>",
                returnType = "V",
                filters = listOf(
                    fieldAccess(
                        opcode = Opcode.IPUT_BOOLEAN,
                        smali = adsLoadCompletedField.toString()
                    )
                )
            )

            commentsAdStateConstructorFingerprint.match(
                CommentsAdStateToStringFingerprint.originalClassDef
            ).let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register =
                        getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->hideCommentAds(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
