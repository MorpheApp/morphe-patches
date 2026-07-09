/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.request

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object BuildRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lorg/chromium/net/UrlRequest",
    custom = { methodDef, _ ->
        val parameterTypes = methodDef.parameterTypes
        val parameterTypesSize = parameterTypes.size
        (parameterTypesSize == 6 || parameterTypesSize == 7 || parameterTypesSize == 8) &&
                parameterTypes[1] == "Ljava/util/Map;" &&
                methodDef.implementation != null &&
                !methodDef.definingClass.startsWith("Lorg/") &&
                indexOfNewUrlRequestBuilderInstruction(methodDef) >= 0
    }
)

internal fun indexOfNewUrlRequestBuilderInstruction(method: Method) =
    method.indexOfFirstInstruction {
        opcode == Opcode.INVOKE_VIRTUAL &&
                getReference<MethodReference>().toString() == $$"Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest$Builder;"
    }
