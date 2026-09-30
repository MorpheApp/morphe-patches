/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.lottie

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal const val LOTTIE_ANIMATION_VIEW_CLASS_TYPE = "Lcom/airbnb/lottie/LottieAnimationView;"

internal object LottieAnimationViewSetAnimationIntFingerprint : Fingerprint(
    definingClass = LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("I"),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "this", name = "isInEditMode")
    )
)

private object LottieCompositionFactoryZipFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/util/zip/ZipInputStream;", "Ljava/lang/String;"),
    returnType = "L",
    filters = listOf(
        string("Unable to parse composition"),
        string(" however it was not found in the animation.")
    )
)

/**
 * [Original method](https://github.com/airbnb/lottie-android/blob/26ad8bab274eac3f93dccccfa0cafc39f7408d13/lottie/src/main/java/com/airbnb/lottie/LottieCompositionFactory.java#L386)
 */
internal object LottieCompositionFactoryFromJsonInputStreamFingerprint : Fingerprint(
    classFingerprint = LottieCompositionFactoryZipFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/io/InputStream;", "Ljava/lang/String;"),
    returnType = "L",
    filters = listOf(
        // The task and not the composition the synchronous method of the same signature returns.
        // Older Lottie versions cache the task without the Runnable.
        anyInstruction(
            methodCall(
                definingClass = "this",
                parameters = listOf(
                    "Ljava/lang/String;",
                    "Ljava/util/concurrent/Callable;"
                )
            ),
            methodCall(
                definingClass = "this",
                parameters = listOf(
                    "Ljava/lang/String;",
                    "Ljava/util/concurrent/Callable;",
                    "Ljava/lang/Runnable;"
                )
            )
        )
    )
)
