/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.videoeffects

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Draws videos with the OpenGL renderer and its passthrough shader, even if the server
 * sends no video effects for the video.
 */
internal object GlVideoRendererFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45727453L)
    )
)

/**
 * Draw method of the passthrough shader, which the passthrough and recomposition video effects use.
 */
internal object PassthroughShaderDrawFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Landroid/opengl/GLES20;",
            name = "glUseProgram"
        ),
        methodCall(
            definingClass = "Landroid/opengl/GLES20;",
            name = "glDrawArrays"
        ),
        string("null shader input for recomposition")
    )
)

internal object PassthroughShaderConstructorFingerprint : Fingerprint(
    classFingerprint = PassthroughShaderDrawFingerprint,
    name = "<init>",
    filters = listOf(
        string("texCoordVar = (texTransMat * vTexCoord).xy;", StringComparisonType.CONTAINS),
        string("gl_FragColor = texture2D(texture, texCoordVar);", StringComparisonType.CONTAINS)
    )
)
