package app.morphe.patches.youtube.misc.fix.likebutton

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_34_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/FIxLikeButtonPatch;"

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/113.
 */
internal val fIxLikeButtonPatch = bytecodePatch{
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        versionCheckPatch,
    )

    execute {
        if (!is_20_34_or_greater) {
            return@execute
        }

        val (lottieAnimationUrlPrimaryFingerprint, lottieAnimationUrlSecondaryFingerprint) =
            with(LottieAnimationViewTagFingerprint) {
                val index = instructionMatches.first().index
                val reference =
                    method.getInstruction<ReferenceInstruction>(index).reference as MethodReference

                val abstractClass = reference.definingClass
                val lottieAnimationUrlMethodName = reference.name

                Pair(
                    Fingerprint(
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        returnType = "Ljava/lang/String;",
                        parameters = listOf(),
                        custom = { method, classDef ->
                            classDef.interfaces.contains(abstractClass) &&
                                    method.name == lottieAnimationUrlMethodName &&
                                    method.implementation!!.instructions.count() > 7
                        }
                    ),
                    Fingerprint(
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        returnType = "Ljava/lang/String;",
                        parameters = listOf(),
                        custom = { method, classDef ->
                            classDef.interfaces.contains(abstractClass) &&
                                    method.name == lottieAnimationUrlMethodName &&
                                    method.implementation!!.instructions.count() < 7
                        }
                    )
                )
            }

        setOf(
            lottieAnimationUrlPrimaryFingerprint,
            lottieAnimationUrlSecondaryFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val index = implementation!!.instructions.lastIndex
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->fixThemedLikeAnimations(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }
    }
}