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
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The methods that start navigation to another video, as last hooked by [navigationIntentHook], or null before
 * the first hook. Several patches hook them, and finding them checks the methods of every class, so they are
 * found once. A hook can replace a method with a clone, which then takes its place here.
 */
private var navigationMethods: MutableList<MutableMethod>? = null

/**
 * Forgets the methods [navigationIntentHook] found before and after patching. Patches that call it must depend on
 * this patch, so the methods of another app or of an earlier run are never hooked.
 */
internal val navigationIntentHookPatch = patch {
    execute {
        navigationMethods = null
    }

    finalize {
        navigationMethods = null
    }
}

/**
 * Hooks the method that starts navigation to another video.
 * The extension method receives the navigation intent enum and returns true to cancel the navigation.
 *
 * @param includePlayerButtons Also hook the player Next and Previous button handler, which can load a video
 *                             from a server supplied command without reaching the navigation method.
 */
internal fun navigationIntentHook(
    extensionClass: String,
    extensionMethod: String,
    includePlayerButtons: Boolean = false
) {
    val enumType = NavigationIntentEnumFingerprint.originalClassDef.type

    val wrapperClassDef = NavigationRequestFingerprint.originalClassDef
    val wrapperType = wrapperClassDef.type
    val enumField = wrapperClassDef.fields.first { it.type == enumType }

    fun hook(original: MutableMethod): MutableMethod {
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
        return method
    }

    val methods = navigationMethods
        ?: navigationMethodFingerprint(wrapperType).matchAll().mapTo(ArrayList()) { it.method }
    methods.replaceAll(::hook)
    navigationMethods = methods

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
