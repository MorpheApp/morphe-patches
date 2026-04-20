/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_04_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_16_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFieldFromToString
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideAdsPatch;"

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to hide ads."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {

        // region Filter promoted ads (does not work in popular or latest feed)

        fun hideOldAds(fingerprint: Fingerprint) {
            fingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->hideOldPostAds(Ljava/util/List;)Ljava/util/List;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        hideOldAds(ListingFingerprint)

        if (!is_2026_16_0_or_greater) {
            hideOldAds(SubmittedListingFingerprint)
        }

        val immutableListBuilderReference = ImmutableListBuilderFingerprint.instructionMatches
            .last().getInstruction<ReferenceInstruction>().reference

        AdPostSectionConstructorFingerprint.let {
            it.method.apply {
                val sectionRegister = "p5"

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { $sectionRegister }, $EXTENSION_CLASS->hideNewPostAds(Ljava/util/List;)Ljava/util/List;
                        move-result-object $sectionRegister
                        if-nez $sectionRegister, :ignore
                        new-instance $sectionRegister, Ljava/util/ArrayList;
                        invoke-direct { $sectionRegister }, Ljava/util/ArrayList;-><init>()V
                        invoke-static { $sectionRegister }, $immutableListBuilderReference
                        move-result-object $sectionRegister
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
                invoke-static { }, $EXTENSION_CLASS->hideCommentAds()Z
                move-result v0
                if-eqz v0, :show
                return-void
                :show
                nop
            """
        )

        // As of Reddit 2026.04+, placeholders are not hidden unless 'adsLoadCompleted' is false.
        // Hide placeholders by overriding 'adsLoadCompleted' to true.
        if (is_2026_04_0_or_greater) {
            val adsLoadCompletedField = CommentsAdStateToStringFingerprint.method
                .findFieldFromToString(", adsLoadCompleted=")

            val commentsAdStateConstructorFingerprint = Fingerprint(
                definingClass = CommentsAdStateToStringFingerprint.originalClassDef.type,
                name = "<init>",
                returnType = "V",
                filters = listOf(
                    fieldAccess(
                        opcode = Opcode.IPUT_BOOLEAN,
                        reference = adsLoadCompletedField
                    )
                )
            )

            commentsAdStateConstructorFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register =
                        getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->hideCommentAds(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
