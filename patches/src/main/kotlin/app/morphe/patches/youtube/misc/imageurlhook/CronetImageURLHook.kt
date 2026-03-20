/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.imageurlhook

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

private lateinit var loadImageURLMethodRef : WeakReference<MutableMethod>
private var loadImageURLIndex = 0

private lateinit var loadImageSuccessCallbackMethodRef : WeakReference<MutableMethod>
private var loadImageSuccessCallbackIndex = 0

private lateinit var loadImageErrorCallbackMethodRef : WeakReference<MutableMethod>
private var loadImageErrorCallbackIndex = 0

val cronetImageURLHookPatch = bytecodePatch(
    description = "Hooks Cronet image URLs.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        loadImageURLMethodRef = WeakReference(
            MessageDigestImageURLFingerprint
                .match(MessageDigestImageURLParentFingerprint.originalClassDef).method
        )

        loadImageSuccessCallbackMethodRef = WeakReference(
            OnSucceededFingerprint
                .match(OnResponseStartedFingerprint.originalClassDef).method
        )

        loadImageErrorCallbackMethodRef = WeakReference(
            OnFailureFingerprint
                .match(OnResponseStartedFingerprint.originalClassDef).method
        )

        // The URL is required for the failure callback hook, but the URL field is obfuscated.
        // Add a helper get method that returns the URL field.
        val urlFieldInstruction = RequestFingerprint.method.instructions.first {
            val reference = it.getReference<FieldReference>()
            it.opcode == Opcode.IPUT_OBJECT && reference?.type == "Ljava/lang/String;"
        } as ReferenceInstruction

        val urlFieldName = (urlFieldInstruction.reference as FieldReference).name
        val definingClass = CRONET_URL_REQUEST_CLASS_DESCRIPTOR
        val addedMethodName = "getHookedUrl"
        RequestFingerprint.classDef.methods.add(
            ImmutableMethod(
                definingClass,
                addedMethodName,
                emptyList(),
                "Ljava/lang/String;",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $definingClass->$urlFieldName:Ljava/lang/String;
                        return-object v0
                    """
                )
            },
        )
    }
}

/**
 * @param highPriority If the hook should be called before all other hooks.
 */
fun addImageURLHook(targetMethodClass: String, highPriority: Boolean = false) {
    loadImageURLMethodRef.get()!!.addInstructions(
        if (highPriority) 0 else loadImageURLIndex,
        """
        invoke-static { p1 }, $targetMethodClass->overrideImageURL(Ljava/lang/String;)Ljava/lang/String;
        move-result-object p1
        """,
    )
    loadImageURLIndex += 2
}

/**
 * If a connection completed, which includes normal 200 responses but also includes
 * status 404 and other error like http responses.
 */
fun addImageURLSuccessCallbackHook(targetMethodClass: String) {
    loadImageSuccessCallbackMethodRef.get()!!.addInstruction(
        loadImageSuccessCallbackIndex++,
        "invoke-static { p1, p2 }, $targetMethodClass->handleCronetSuccess(" +
            "Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;)V",
    )
}

/**
 * If a connection outright failed to complete any connection.
 */
fun addImageURLErrorCallbackHook(targetMethodClass: String) {
    loadImageErrorCallbackMethodRef.get()!!.addInstruction(
        loadImageErrorCallbackIndex++,
        "invoke-static { p1, p2, p3 }, $targetMethodClass->handleCronetFailure(" +
            "Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;Ljava/io/IOException;)V",
    )
}
