/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4316
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.request

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_MEDIA_HTTP_REQUEST_INTERFACE =
    "Lapp/morphe/extension/shared/patches/MediaHttpRequest;"

private const val EXTENSION_MEDIA_HTTP_HEADER_INTERFACE =
    "Lapp/morphe/extension/shared/patches/MediaHttpHeader;"

/**
 * Adds a method that returns the value of a field.
 */
private fun MutableClass.addGetter(methodName: String, field: FieldReference) {
    methods.add(
        ImmutableMethod(
            type,
            methodName,
            listOf(),
            field.type,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    iget-object v0, p0, $field
                    return-object v0
                """
            )
        }
    )
}

/**
 * Lets the extension read the media requests that native code starts, and replace them.
 */
internal val mediaRequestPatch = patch(
    description = "mediaRequestPatch",
) {
    execute {
        mutableClassDefBy(HTTP_REQUEST_CLASS_TYPE).apply {
            interfaces.add(EXTENSION_MEDIA_HTTP_REQUEST_INTERFACE)

            // Each field of the request has its own type.
            val urlField = instanceFields.single { field -> field.type == "Ljava/lang/String;" }
            val bodyField = instanceFields.single { field -> field.type == "[B" }
            val headersField = instanceFields.single { field -> field.type == "Ljava/util/ArrayList;" }

            addGetter("patch_getUrl", urlField)
            addGetter("patch_getBody", bodyField)
            addGetter("patch_getHeaders", headersField)

            // The constructor takes each field, and each field has its own type.
            val constructor = HttpRequestConstructorFingerprint.originalMethod
            val constructorParameters = constructor.parameterTypes.map(CharSequence::toString)
            val loadConstructorParameters = constructorParameters.mapIndexed { index, parameterType ->
                // Register v0 is the new request.
                val register = "v${index + 1}"
                val field = instanceFields.single { field -> field.type == parameterType }
                when (parameterType) {
                    bodyField.type -> "move-object $register, p1"
                    headersField.type -> "move-object $register, p2"
                    "Z" -> "iget-boolean $register, p0, $field"
                    else -> "iget-object $register, p0, $field"
                }
            }.joinToString("\n")

            methods.add(
                ImmutableMethod(
                    type,
                    "patch_withBodyAndHeaders",
                    listOf(
                        ImmutableMethodParameter(bodyField.type, null, null),
                        ImmutableMethodParameter(headersField.type, null, null),
                    ),
                    EXTENSION_MEDIA_HTTP_REQUEST_INTERFACE,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    // The new request, its parameters, and the 3 parameters of this method.
                    MutableMethodImplementation(1 + constructorParameters.size + 3),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            new-instance v0, $type
                            $loadConstructorParameters
                            invoke-direct/range { v0 .. v${constructorParameters.size} }, $constructor
                            return-object v0
                        """
                    )
                }
            )
        }

        mutableClassDefBy(HTTP_HEADER_CLASS_TYPE).apply {
            interfaces.add(EXTENSION_MEDIA_HTTP_HEADER_INTERFACE)

            val (nameField, valueField) = HttpHeaderConstructorFingerprint.instructionMatches
                .map { match -> match.fieldReference }
            addGetter("patch_getName", nameField)
            addGetter("patch_getValue", valueField)
        }
    }
}

/**
 * Calls the extension when native code starts a media request.
 *
 * @param descriptor Static method that takes the request and the callbacks of the request.
 * @param replacesRequest If the method returns the request to start instead,
 *                        which can be a copy made by the extension.
 */
internal fun hookMediaRequest(
    descriptor: String,
    replacesRequest: Boolean = false
) {
    NetFetchStartFetchTaskFingerprint.method.apply {
        if (replacesRequest) {
            addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p2 }, $descriptor(${EXTENSION_MEDIA_HTTP_REQUEST_INTERFACE}Ljava/lang/Object;)$EXTENSION_MEDIA_HTTP_REQUEST_INTERFACE
                    move-result-object p1
                    check-cast p1, $HTTP_REQUEST_CLASS_TYPE
                """
            )
        } else {
            addInstruction(
                0,
                "invoke-static/range { p1 .. p2 }, $descriptor(${EXTENSION_MEDIA_HTTP_REQUEST_INTERFACE}Ljava/lang/Object;)V"
            )
        }
    }
}

/**
 * Calls the extension with each chunk of a media response, before native code reads it.
 *
 * @param descriptor Static method that takes the callbacks of the request and the chunk.
 */
internal fun hookMediaResponseChunk(descriptor: String) {
    NetFetchResponseChunkFingerprint.method.addInstruction(
        0,
        "invoke-static/range { p0 .. p1 }, $descriptor(Ljava/lang/Object;Ljava/nio/ByteBuffer;)V"
    )
}
