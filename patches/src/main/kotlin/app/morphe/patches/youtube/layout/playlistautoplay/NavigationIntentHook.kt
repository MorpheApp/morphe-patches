/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Hooks the method that starts navigation to another video.
 * The extension method receives the navigation intent enum and returns true to cancel the navigation.
 */
context(patchContext: BytecodePatchContext)
internal fun navigationIntentHook(extensionClass: String, extensionMethod: String) {
    val enumType = NavigationIntentEnumFingerprint.originalClassDef.type

    val wrapperClassDef = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
        parameters = listOf(enumType, "L", "L")
    ).originalClassDef
    val wrapperType = wrapperClassDef.type
    val enumField = wrapperClassDef.fields.first { it.type == enumType }

    Fingerprint(
        returnType = "V",
        parameters = listOf(wrapperType),
        custom = { method, classDef ->
            method.implementation != null && classDef.methods.any { sibling ->
                sibling.implementation != null &&
                        sibling.returnType == "I" &&
                        sibling.parameterTypes.singleOrNull() == wrapperType
            }
        }
    ).matchAll().forEach { match ->
        var method = match.method
        if (method.implementation!!.registerCount <= 2) {
            method = method.cloneParameters()
        }

        val freeRegister = method.findFreeRegister(0)
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v$freeRegister, p1
                iget-object v$freeRegister, v$freeRegister, $enumField
                invoke-static { v$freeRegister }, $extensionClass->$extensionMethod(Ljava/lang/Enum;)Z
                move-result v$freeRegister
                if-eqz v$freeRegister, :continue
                return-void
                :continue
                nop
            """
        )
    }
}
