/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Hooks the method that starts navigation to another video.
 * The extension method receives the navigation intent enum and returns true to cancel the navigation.
 *
 * @param includePlayerButtons Also hook the player Next and Previous button handler, which can load a video
 *                             from a server supplied command without reaching the navigation method.
 */
context(patchContext: BytecodePatchContext)
internal fun navigationIntentHook(
    extensionClass: String,
    extensionMethod: String,
    includePlayerButtons: Boolean = false
) {
    val enumType = NavigationIntentEnumFingerprint.originalClassDef.type

    val wrapperClassDef = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
        parameters = listOf(enumType, "L", "L")
    ).originalClassDef
    val wrapperType = wrapperClassDef.type
    val enumField = wrapperClassDef.fields.first { it.type == enumType }

    fun hook(original: MutableMethod) {
        var method = original
        if (method.implementation!!.registerCount <= method.numberOfParameterRegisters) {
            method = method.cloneParameters()
        }

        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                iget-object v0, v0, $enumField
                invoke-static { v0 }, $extensionClass->$extensionMethod(Ljava/lang/Enum;)Z
                move-result v0
                if-eqz v0, :continue
                return-void
                :continue
                nop
            """
        )
    }

    // Matches each implementation of the navigation method. These share no common instructions
    // (some are empty or only delegate), so the wrapper parameter is what identifies them.
    Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf(wrapperType)
    ).matchAll().forEach { hook(it.method) }

    if (includePlayerButtons) {
        hook(
            Fingerprint(
                accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
                returnType = "V",
                parameters = listOf(wrapperType, "Ljava/lang/Runnable;"),
                filters = listOf(
                    methodCall(parameters = listOf(wrapperType), returnType = "Z"),
                    methodCall(smali = "Ljava/lang/Runnable;->run()V", location = MatchAfterWithin(3)),
                    methodCall(parameters = listOf(wrapperType), returnType = "V", location = MatchAfterWithin(3))
                )
            ).method
        )
    }
}
