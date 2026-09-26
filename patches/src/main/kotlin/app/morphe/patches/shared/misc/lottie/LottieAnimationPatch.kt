/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.lottie

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val SET_ANIMATION_METHOD_NAME = "patch_setAnimation"

/**
 * Adds non obfuscated aliases of `setAnimation(int)` and `setAnimation(InputStream, String)`
 * to the Lottie animation view, so extension code can play an animation of its own.
 */
val lottieAnimationPatch = bytecodePatch {

    execute {
        LottieAnimationViewSetAnimationIntFingerprint.classDef.methods.apply {
            val setAnimationIntName = LottieAnimationViewSetAnimationIntFingerprint
                .originalMethod.name

            add(ImmutableMethod(
                LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
                SET_ANIMATION_METHOD_NAME,
                listOf(ImmutableMethodParameter("I", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0, p1 }, $LOTTIE_ANIMATION_VIEW_CLASS_TYPE->$setAnimationIntName(I)V
                        return-void
                    """
                )
            })

            val factoryStreamClass: String
            val factoryStreamName: String
            val factoryStreamReturnType: String
            LottieCompositionFactoryFromJsonInputStreamFingerprint.originalMethod.apply {
                factoryStreamClass = definingClass
                factoryStreamName = name
                factoryStreamReturnType = returnType
            }

            val setAnimationStreamMethod = Fingerprint(
                classFingerprint = LottieAnimationViewSetAnimationIntFingerprint,
                returnType = "V",
                parameters = listOf(factoryStreamReturnType)
            ).originalMethod

            add(ImmutableMethod(
                LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
                SET_ANIMATION_METHOD_NAME,
                listOf(
                    ImmutableMethodParameter("Ljava/io/InputStream;", null, null),
                    ImmutableMethodParameter("Ljava/lang/String;", null, null)
                ),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(4)
            ).toMutable().apply {
                // A private method cannot be called with invoke-virtual, and its access flags
                // cannot be changed because that breaks unrelated code that calls it directly.
                val methodOpcode = if (AccessFlags.PRIVATE.isSet(setAnimationStreamMethod.accessFlags)) {
                    "invoke-direct"
                } else {
                    "invoke-virtual"
                }

                addInstructions(
                    0,
                    """
                        invoke-static { p1, p2 }, $factoryStreamClass->$factoryStreamName(Ljava/io/InputStream;Ljava/lang/String;)$factoryStreamReturnType
                        move-result-object v0
                        $methodOpcode { p0, v0}, $LOTTIE_ANIMATION_VIEW_CLASS_TYPE->${setAnimationStreamMethod.name}($factoryStreamReturnType)V
                        return-void
                    """
                )
            })
        }
    }
}
