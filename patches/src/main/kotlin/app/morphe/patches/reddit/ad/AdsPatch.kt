package app.morphe.patches.reddit.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.utils.settings.enableExtensionPatch
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/GeneralAdsPatch;"

@Suppress("unused")
val adsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to hide ads."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // region Filter promoted ads (does not work in popular or latest feed)
        listOf(
            listingFingerprint,
            submittedListingFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val targetIndex = indexOfFirstInstructionOrThrow {
                    getReference<FieldReference>()?.name == "children"
                }
                val targetRegister = getInstruction<TwoRegisterInstruction>(targetIndex).registerA

                addInstructions(
                    targetIndex, """
                    invoke-static {v$targetRegister}, $EXTENSION_CLASS_DESCRIPTOR->hideOldPostAds(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$targetRegister
                    """
                )
            }
        }

        val immutableListBuilderReference = immutableListBuilderFingerprint.instructionMatches
            .last().getInstruction<ReferenceInstruction>().reference

        adPostSectionConstructorFingerprint.match(
            mutableClassDefBy(adPostSectionToStringFingerprint.classDef)
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

        // region Filter comment ads
        fun MutableMethod.hook() =
            addInstructionsWithLabels(
                0, """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideCommentAds()Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                    :show
                    nop
                    """
            )
        commentsViewModelConstructorFingerprint.classDef.let {
            it.methods.filter { method ->
                method.indexOfFirstInstruction {
                    opcode == Opcode.INVOKE_DIRECT &&
                            getReference<MethodReference>()?.toString()
                                ?.endsWith("<init>(ZI)V") == true
                } >= 0
            }.forEach { method ->
                it.findMutableMethodOf(method).hook()
            }
        }

        postDetailAdLoaderFingerprint.method.apply {
            implementation!!.instructions
                .withIndex()
                .filter { (_, instruction) ->
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference
                    reference is MethodReference &&
                            reference.toString() == "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                }
                .map { (index, _) -> index }
                .reversed()
                .forEach { index ->
                    val instruction =
                        getInstruction<FiveRegisterInstruction>(index)

                    // TODO: Look at this later, because the return type of this call is weird (it can return either the map or the value put into the map).
                    replaceInstruction(
                        index,
                        "invoke-static { v${instruction.registerC}, v${instruction.registerD}, v${instruction.registerE} }, " +
                                "$EXTENSION_CLASS_DESCRIPTOR->hideCommentAdMap(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                    )
                }
        }

        enableExtensionPatch(
            EXTENSION_CLASS_DESCRIPTOR
        )
    }
}
