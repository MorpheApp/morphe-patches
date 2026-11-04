/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

@file:Suppress("DEPRECATION")

package app.morphe.patches.shared.misc.library

import app.morphe.patcher.patch.bytecodeContext
import app.morphe.patcher.patch.resourceContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ResourceGroup
import app.morphe.util.addStaticFieldToExtension
import app.morphe.util.copyResources
import app.morphe.util.findMethodFromToString
import app.morphe.util.setExtensionIsPatchIncluded
import app.morphe.util.traverseClassHierarchy
import com.android.tools.smali.dexlib2.iface.Method

// Functions of morphe-patches-library that still need a context of the patcher, which patches no
// longer have. They are in a package of their own, as otherwise calling the functions they wrap
// would be ambiguous. Once the library no longer needs a context, remove this file and import the
// functions from the library.

fun copyResources(sourceResourceDirectory: String, vararg resources: ResourceGroup) =
    resourceContext.copyResources(sourceResourceDirectory, *resources)

fun traverseClassHierarchy(targetClass: MutableClass, callback: MutableClass.() -> Unit) =
    bytecodeContext.traverseClassHierarchy(targetClass, callback)

fun addStaticFieldToExtension(
    className: String,
    methodName: String,
    fieldName: String,
    objectClass: String,
    smaliInstructions: String,
) = bytecodeContext.addStaticFieldToExtension(className, methodName, fieldName, objectClass, smaliInstructions)

fun setExtensionIsPatchIncluded(patchExtensionClassType: String) =
    with(bytecodeContext) { setExtensionIsPatchIncluded(patchExtensionClassType) }

fun Method.findMethodFromToString(fieldName: String): MutableMethod =
    with(bytecodeContext) { findMethodFromToString(fieldName) }
