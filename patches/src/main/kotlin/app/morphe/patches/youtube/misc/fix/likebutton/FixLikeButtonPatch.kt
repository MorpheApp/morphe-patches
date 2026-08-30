/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.likebutton

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixLikeButtonPatch;"

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/113.
 */
internal val fixLikeButtonPatch = bytecodePatch{
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
    )

    execute {
        val (lottieAnimationUrlPrimaryFingerprint, lottieAnimationUrlSecondaryFingerprint) =
            with(LottieAnimationViewTagFingerprint) {
                val index = instructionMatches.first().index
                val reference =
                    method.getInstruction<ReferenceInstruction>(index).reference as MethodReference

                val abstractClass = reference.definingClass
                val lottieAnimationUrlMethodName = reference.name

                Pair(
                    Fingerprint(
                        name = lottieAnimationUrlMethodName,
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        returnType = "Ljava/lang/String;",
                        parameters = listOf(),
                        custom = { method, classDef ->
                            method.implementation!!.instructions.count() > 7 &&
                                classDef.interfaces.contains(abstractClass)
                        }
                    ),
                    Fingerprint(
                        name = lottieAnimationUrlMethodName,
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        returnType = "Ljava/lang/String;",
                        parameters = listOf(),
                        custom = { method, classDef ->
                            method.implementation!!.instructions.count() < 7 &&
                                    classDef.interfaces.contains(abstractClass)
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
                        invoke-static { v$register }, $EXTENSION_CLASS->fixThemedLikeAnimations(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }
    }
}
