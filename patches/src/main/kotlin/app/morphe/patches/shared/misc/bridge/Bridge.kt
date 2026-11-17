/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.bridge

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Adds a method to a class, such as an override of a framework method or the replacement of
 * an extension placeholder method. A method that implements an interface, so extension code
 * can call it, is added with addPatchInterface instead.
 *
 * @param accessFlags The flags of the method. A default method of an interface cannot be final.
 */
internal fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registerCount: Int,
    smali: String,
    accessFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            MutableMethodImplementation(registerCount),
        ).toMutable().apply {
            addInstructionsWithLabels(0, smali)
        }
    )
}
