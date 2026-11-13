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
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_46_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixLikeButtonPatch;"

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/113.
 */
internal val fixLikeButtonPatch = patch{
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        versionCheckPatch,
    )

    execute {
        val lottieAnimationUrlFingerprints = with(LottieAnimationViewTagFingerprint) {
            val index = instructionMatches.first().index
            val reference =
                method.getInstruction<ReferenceInstruction>(index).reference as MethodReference

            fun lottieAnimationUrlFingerprint(custom: (Method, ClassDef) -> Boolean) = Fingerprint(
                interfaces = listOf(reference.definingClass),
                name = reference.name,
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                returnType = "Ljava/lang/String;",
                parameters = listOf(),
                custom = custom
            )

            buildList {
                // Model backed by native memory.
                add(lottieAnimationUrlFingerprint { method, _ ->
                    method.implementation!!.instructions.count() > 7
                })

                // Model that wraps a Java proto. Removed in 21.46.
                if (!is_21_46_or_greater) {
                    add(lottieAnimationUrlFingerprint { method, _ ->
                        method.implementation!!.instructions.count() < 7
                    })
                }
            }
        }

        lottieAnimationUrlFingerprints.forEach { fingerprint ->
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
