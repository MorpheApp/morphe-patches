/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.videoeffects

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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
            name = "glUniformMatrix4fv"
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

/**
 * Picks the type of view a video is drawn on.
 */
internal object VideoViewTypeFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "L", "Z", "Z"),
    filters = listOf(
        string(";vdgsv."),
        // A surface view becomes a secure surface view if the cold config or the player config says so.
        opcode(Opcode.IF_NE),
        methodCall(
            parameters = listOf(),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, MatchAfterImmediately()),
        methodCall(
            parameters = listOf(),
            returnType = "Z",
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Types of views a video can be drawn on.
 */
internal object VideoViewTypeEnumFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("GL_SURFACE"),
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            location = MatchAfterWithin(10)
        ),
        string("DUAL_SURFACE"),
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * Chooses the config of the EGL context and surfaces of the OpenGL renderer.
 */
internal object EglChooseConfigFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/opengl/EGL14;",
            name = "eglChooseConfig"
        ),
        string("createEglConfig: eglChooseConfig#2")
    )
)

/**
 * Creates the EGL surface that the OpenGL renderer draws on.
 */
internal object EglCreateSurfaceFingerprint : Fingerprint(
    classFingerprint = EglChooseConfigFingerprint,
    returnType = "V",
    parameters = listOf("Landroid/view/Surface;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/opengl/EGLConfig;"
        ),
        methodCall(
            definingClass = "Landroid/opengl/EGL14;",
            name = "eglCreateWindowSurface"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/opengl/EGLSurface;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/view/Surface;"
        )
    )
)

/**
 * Draws the latest frame that the decoder put into an image reader.
 */
internal fun imageReaderDrawFrameFingerprint(eglHelperType: String) = Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/ImageReaderMediaGlRenderer;",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = eglHelperType
        ),
        methodCall(
            definingClass = "Landroid/media/Image;",
            name = "getTimestamp"
        )
    )
)

/**
 * Draws the latest frame that the decoder put into a surface texture.
 */
internal fun surfaceTextureDrawFrameFingerprint(eglHelperType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = eglHelperType
        ),
        methodCall(
            definingClass = "Landroid/graphics/SurfaceTexture;",
            name = "updateTexImage"
        ),
        methodCall(
            definingClass = "Landroid/graphics/SurfaceTexture;",
            name = "getTimestamp",
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * Listeners that are called with the output format of the decoder for each video frame.
 */
internal object VideoFrameListenerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    // Presentation time, release time, format and decoder output format.
    parameters = listOf("J", "J", "L", "Landroid/media/MediaFormat;")
)
