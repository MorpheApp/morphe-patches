/*
 * Copyright 2025 Morphe.
 * https://github.com/morpheapp/morphe-patches
 *
 * File-Specific License Notice (GPLv3 Section 7 Additional Permission).
 *
 * This file is part of the Morphe patches project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the Morphe patches LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * File-Specific Exception to Section 7b:
 * -------------------------------------
 * Section 7b (Attribution Requirement) of the Morphe patches LICENSE
 * does not apply to THIS FILE. Use of this file does NOT require any
 * user-facing, in-application, or UI-visible attribution.
 *
 * For this file only, attribution under Section 7b is satisfied by
 * retaining this comment block in the source code of this file.
 *
 * Distribution and Derivative Works:
 * ----------------------------------
 * This comment block MUST be preserved in all copies, distributions,
 * and derivative works of this file, whether in source or modified
 * form.
 *
 * All other terms of the Morphe Patches LICENSE, including Section 7c
 * (Project Name Restriction) and the GPLv3 itself, remain fully
  * applicable to this file.
 */

package app.morphe.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT
import com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT_OBJECT
import com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT_WIDE
import com.android.tools.smali.dexlib2.Opcode.RETURN
import com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT
import com.android.tools.smali.dexlib2.Opcode.RETURN_WIDE
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * Find the instruction index used for a toString() StringBuilder write of a given String name.
 *
 * @param fieldName The name of the field to find.  Partial matches are allowed.
 */
private fun Method.findInstructionIndexFromToString(fieldName: String) : Int {
    val stringIndex = indexOfFirstInstruction {
        val reference = getReference<StringReference>()
        reference?.string?.contains(fieldName) == true
    }
    if (stringIndex < 0) {
        throw IllegalArgumentException("Could not find usage of string: '$fieldName'")
    }
    val stringRegister = getInstruction<OneRegisterInstruction>(stringIndex).registerA

    // Find use of the string with a StringBuilder.
    val stringUsageIndex = indexOfFirstInstruction(stringIndex) {
        val reference = getReference<MethodReference>()
        reference?.definingClass == "Ljava/lang/StringBuilder;" &&
                (this as? FiveRegisterInstruction)?.registerD == stringRegister
    }
    if (stringUsageIndex < 0) {
        throw IllegalArgumentException("Could not find StringBuilder usage in: $this")
    }

    // Find the next usage of StringBuilder, which should be the desired field.
    val fieldUsageIndex = indexOfFirstInstruction(stringUsageIndex + 1) {
        val reference = getReference<MethodReference>()
        reference?.definingClass == "Ljava/lang/StringBuilder;" && reference.name == "append"
    }
    if (fieldUsageIndex < 0) {
        // Should never happen.
        throw IllegalArgumentException("Could not find StringBuilder append usage in: $this")
    }
    val fieldUsageRegister = getInstruction<FiveRegisterInstruction>(fieldUsageIndex).registerD

    // Look backwards up the method to find the instruction that sets the register.
    var fieldSetIndex = indexOfFirstInstructionReversedOrThrow(fieldUsageIndex - 1) {
        fieldUsageRegister == writeRegister
    }

    // If the field is a method call, then adjust from MOVE_RESULT to the method call.
    val fieldSetOpcode = getInstruction(fieldSetIndex).opcode
    if (fieldSetOpcode == MOVE_RESULT ||
        fieldSetOpcode == MOVE_RESULT_WIDE ||
        fieldSetOpcode == MOVE_RESULT_OBJECT) {
        fieldSetIndex--
    }

    return fieldSetIndex
}

/**
 * Find the method used for a toString() StringBuilder write of a given String name.
 *
 * @param fieldName The name of the field to find.  Partial matches are allowed.
 */
context(BytecodePatchContext)
internal fun Method.findMethodFromToString(fieldName: String) : MutableMethod {
    val methodUsageIndex = findInstructionIndexFromToString(fieldName)
    return navigate(this).to(methodUsageIndex).stop()
}

/**
 * Find the field used for a toString() StringBuilder write of a given String name.
 *
 * @param fieldName The name of the field to find.  Partial matches are allowed.
 */
internal fun Method.findFieldFromToString(fieldName: String) : FieldReference {
    val methodUsageIndex = findInstructionIndexFromToString(fieldName)
    return getInstruction<ReferenceInstruction>(methodUsageIndex).getReference<FieldReference>()!!
}

/**
 * Adds public [AccessFlags] and removes private and protected flags (if present).
 */
internal fun Int.toPublicAccessFlags(): Int {
    return this.or(AccessFlags.PUBLIC.value)
        .and(AccessFlags.PROTECTED.value.inv())
        .and(AccessFlags.PRIVATE.value.inv())
}

/**
 * Find the [MutableMethod] from a given [Method] in a [MutableClass].
 *
 * @param method The [Method] to find.
 * @return The [MutableMethod].
 */
fun MutableClass.findMutableMethodOf(method: Method) = this.methods.first {
    MethodUtil.methodSignaturesMatch(it, method)
}

/**
 * Apply a transform to all methods of the class.
 *
 * @param transform The transformation function. Accepts a [MutableMethod] and returns a transformed [MutableMethod].
 */
fun MutableClass.transformMethods(transform: MutableMethod.() -> MutableMethod) {
    val transformedMethods = methods.map { it.transform() }
    methods.clear()
    methods.addAll(transformedMethods)
}

/**
 * Inject a call to a method that hides a view.
 *
 * @param insertIndex The index to insert the call at.
 * @param viewRegister The register of the view to hide.
 * @param classDescriptor The descriptor of the class that contains the method.
 * @param targetMethod The name of the method to call.
 */
fun MutableMethod.injectHideViewCall(
    insertIndex: Int,
    viewRegister: Int,
    classDescriptor: String,
    targetMethod: String,
) = addInstruction(
    insertIndex,
    "invoke-static { v$viewRegister }, $classDescriptor->$targetMethod(Landroid/view/View;)V",
)

/**
 * Inserts instructions at a given index, using the existing control flow label at that index.
 * Inserted instructions can have it's own control flow labels as well.
 *
 * Effectively this changes the code from:
 * :label
 * (original code)
 *
 * Into:
 * :label
 * (patch code)
 * (original code)
 */
fun MutableMethod.addInstructionsAtControlFlowLabel(
    insertIndex: Int,
    instructions: String,
    vararg externalLabels: ExternalLabel
) {
    // Duplicate original instruction and add to +1 index.
    addInstruction(insertIndex + 1, getInstruction(insertIndex))

    // Add patch code at same index as duplicated instruction,
    // so it uses the original instruction control flow label.
    addInstructionsWithLabels(insertIndex + 1, instructions, *externalLabels)

    // Remove original non duplicated instruction.
    removeInstruction(insertIndex)

    // Original instruction is now after the inserted patch instructions,
    // and the original control flow label is on the first instruction of the patch code.
}

/**
 * Get the index of the first instruction with the id of the given resource id name.
 *
 * Requires [resourceMappingPatch] as a dependency.
 *
 * @param resourceName the name of the resource to find the id for.
 * @return the index of the first instruction with the id of the given resource name, or -1 if not found.
 * @throws PatchException if the resource cannot be found.
 * @see [indexOfFirstResourceIdOrThrow], [indexOfFirstLiteralInstructionReversed]
 */
fun Method.indexOfFirstResourceId(resourceName: String): Int {
    return indexOfFirstLiteralInstruction(getResourceId(ResourceType.ID, resourceName))
}

/**
 * Get the index of the first instruction with the id of the given resource name or throw a [PatchException].
 *
 * Requires [resourceMappingPatch] as a dependency.
 *
 * @throws [PatchException] if the resource is not found, or the method does not contain the resource id literal value.
 * @see [indexOfFirstResourceId], [indexOfFirstLiteralInstructionReversedOrThrow]
 */
fun Method.indexOfFirstResourceIdOrThrow(resourceName: String): Int {
    val index = indexOfFirstResourceId(resourceName)
    if (index < 0) {
        throw PatchException("Found resource id for: '$resourceName' but method does not contain the id: $this")
    }

    return index
}

/**
 * Find the index of the first literal instruction with the given long value.
 *
 * @return the first literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstruction(literal: Long) = implementation?.let {
    it.instructions.indexOfFirst { instruction ->
        (instruction as? WideLiteralInstruction)?.wideLiteral == literal
    }
} ?: -1

/**
 * Find the index of the first literal instruction with the given long value,
 * or throw an exception if not found.
 *
 * @return the first literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionOrThrow(literal: Long): Int {
    val index = indexOfFirstLiteralInstruction(literal)
    if (index < 0) throw PatchException("Could not find long literal: $literal")
    return index
}

/**
 * Find the index of the first literal instruction with the given float value.
 *
 * @return the first literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstruction(literal: Float) =
    indexOfFirstLiteralInstruction(literal.toRawBits().toLong())

/**
 * Find the index of the first literal instruction with the given float value,
 * or throw an exception if not found.
 *
 * @return the first literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionOrThrow(literal: Float): Int {
    val index = indexOfFirstLiteralInstruction(literal)
    if (index < 0) throw PatchException("Could not find float literal: $literal")
    return index
}

/**
 * Find the index of the first literal instruction with the given double value.
 *
 * @return the first literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstruction(literal: Double) =
    indexOfFirstLiteralInstruction(literal.toRawBits())

/**
 * Find the index of the first literal instruction with the given double value,
 * or throw an exception if not found.
 *
 * @return the first literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionOrThrow(literal: Double): Int {
    val index = indexOfFirstLiteralInstruction(literal)
    if (index < 0) throw PatchException("Could not find double literal: $literal")
    return index
}

/**
 * Find the index of the last literal instruction with the given value.
 *
 * @return the last literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstructionReversed(literal: Long) = implementation?.let {
    it.instructions.indexOfLast { instruction ->
        (instruction as? WideLiteralInstruction)?.wideLiteral == literal
    }
} ?: -1

/**
 * Find the index of the last wide literal instruction with the given long value,
 * or throw an exception if not found.
 *
 * @return the last literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionReversedOrThrow(literal: Long): Int {
    val index = indexOfFirstLiteralInstructionReversed(literal)
    if (index < 0) throw PatchException("Could not find long literal: $literal")
    return index
}

/**
 * Find the index of the last literal instruction with the given float value.
 *
 * @return the last literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstructionReversed(literal: Float) =
    indexOfFirstLiteralInstructionReversed(literal.toRawBits().toLong())

/**
 * Find the index of the last wide literal instruction with the given float value,
 * or throw an exception if not found.
 *
 * @return the last literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionReversedOrThrow(literal: Float): Int {
    val index = indexOfFirstLiteralInstructionReversed(literal)
    if (index < 0) throw PatchException("Could not find float literal: $literal")
    return index
}

/**
 * Find the index of the last literal instruction with the given double value.
 *
 * @return the last literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstructionReversed(literal: Double) =
    indexOfFirstLiteralInstructionReversed(literal.toRawBits())

/**
 * Find the index of the last wide literal instruction with the given double value,
 * or throw an exception if not found.
 *
 * @return the last literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionReversedOrThrow(literal: Double): Int {
    val index = indexOfFirstLiteralInstructionReversed(literal)
    if (index < 0) throw PatchException("Could not find double literal: $literal")
    return index
}

/**
 * Check if the method contains a literal with the given long value.
 *
 * @return if the method contains a literal with the given value.
 */
fun Method.containsLiteralInstruction(literal: Long) = indexOfFirstLiteralInstruction(literal) >= 0

/**
 * Check if the method contains a literal with the given float value.
 *
 * @return if the method contains a literal with the given value.
 */
fun Method.containsLiteralInstruction(literal: Float) = indexOfFirstLiteralInstruction(literal) >= 0

/**
 * Check if the method contains a literal with the given double value.
 *
 * @return if the method contains a literal with the given value.
 */
fun Method.containsLiteralInstruction(literal: Double) = indexOfFirstLiteralInstruction(literal) >= 0

/**
 * Traverse the class hierarchy starting from the given root class.
 *
 * @param targetClass the class to start traversing the class hierarchy from.
 * @param callback function that is called for every class in the hierarchy.
 */
fun BytecodePatchContext.traverseClassHierarchy(targetClass: MutableClass, callback: MutableClass.() -> Unit) {
    callback(targetClass)

    targetClass.superclass ?: return

    mutableClassDefByOrNull(targetClass.superclass!!)?.let {
        traverseClassHierarchy(it, callback)
    }
}

/**
 * Get the [Reference] of an [Instruction] as [T].
 *
 * @param T The type of [Reference] to cast to.
 * @return The [Reference] as [T] or null
 * if the [Instruction] is not a [ReferenceInstruction] or the [Reference] is not of type [T].
 * @see ReferenceInstruction
 */
inline fun <reified T : Reference> Instruction.getReference() = (this as? ReferenceInstruction)?.reference as? T

/**
 * @return The index of the first opcode specified, or -1 if not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(targetOpcode: Opcode): Int = indexOfFirstInstruction(0, targetOpcode)

/**
 * @param startIndex Optional starting index to start searching from.
 * @return The index of the first opcode specified, or -1 if not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(startIndex: Int = 0, targetOpcode: Opcode): Int =
    indexOfFirstInstruction(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of the first [Instruction] that matches the predicate, starting from [startIndex].
 *
 * @param startIndex Optional starting index to start searching from.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(startIndex: Int = 0, filter: Instruction.() -> Boolean): Int {
    var instructions = this.implementation?.instructions ?: return -1
    if (startIndex != 0) {
        instructions = instructions.drop(startIndex)
    }
    val index = instructions.indexOfFirst(filter)

    return if (index >= 0) {
        startIndex + index
    } else {
        -1
    }
}

/**
 * @return The index of the first opcode specified
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(targetOpcode: Opcode): Int = indexOfFirstInstructionOrThrow(0, targetOpcode)

/**
 * @return The index of the first opcode specified, starting from the index specified.
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(startIndex: Int = 0, targetOpcode: Opcode): Int =
    indexOfFirstInstructionOrThrow(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of the first [Instruction] that matches the predicate, starting from [startIndex].
 *
 * @return The index of the instruction.
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(startIndex: Int = 0, filter: Instruction.() -> Boolean): Int {
    val index = indexOfFirstInstruction(startIndex, filter)
    if (index < 0) {
        throw PatchException("Could not find instruction index")
    }

    return index
}

/**
 * Get the index of matching instruction,
 * starting from and [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionReversedOrThrow
 */
fun Method.indexOfFirstInstructionReversed(startIndex: Int? = null, targetOpcode: Opcode): Int =
    indexOfFirstInstructionReversed(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of matching instruction,
 * starting from and [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionReversedOrThrow
 */
fun Method.indexOfFirstInstructionReversed(startIndex: Int? = null, filter: Instruction.() -> Boolean): Int {
    var instructions = this.implementation?.instructions ?: return -1
    if (startIndex != null) {
        instructions = instructions.take(startIndex + 1)
    }

    return instructions.indexOfLast(filter)
}

/**
 * Get the index of matching instruction,
 * starting from the end of the method and searching down.
 *
 * @return -1 if the instruction is not found.
 */
fun Method.indexOfFirstInstructionReversed(targetOpcode: Opcode): Int = indexOfFirstInstructionReversed {
    opcode == targetOpcode
}

/**
 * Get the index of matching instruction,
 * starting from [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return The index of the instruction.
 * @see indexOfFirstInstructionReversed
 */
fun Method.indexOfFirstInstructionReversedOrThrow(startIndex: Int? = null, targetOpcode: Opcode): Int =
    indexOfFirstInstructionReversedOrThrow(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of matching instruction,
 * starting from the end of the method and searching down.
 *
 * @return -1 if the instruction is not found.
 */
fun Method.indexOfFirstInstructionReversedOrThrow(targetOpcode: Opcode): Int = indexOfFirstInstructionReversedOrThrow {
    opcode == targetOpcode
}

/**
 * Get the index of matching instruction,
 * starting from [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return The index of the instruction.
 * @see indexOfFirstInstructionReversed
 */
fun Method.indexOfFirstInstructionReversedOrThrow(startIndex: Int? = null, filter: Instruction.() -> Boolean): Int {
    val index = indexOfFirstInstructionReversed(startIndex, filter)

    if (index < 0) {
        throw PatchException("Could not find instruction index")
    }

    return index
}

/**
 * @return An immutable list of indices of the instructions in reverse order.
 *  _Returns an empty list if no indices are found_
 *  @see findInstructionIndicesReversedOrThrow
 */
fun Method.findInstructionIndicesReversed(filter: Instruction.() -> Boolean): List<Int> = instructions
    .withIndex()
    .filter { (_, instruction) -> filter(instruction) }
    .map { (index, _) -> index }
    .asReversed()

/**
 * @return An immutable list of indices of the instructions in reverse order.
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversedOrThrow(filter: Instruction.() -> Boolean): List<Int> {
    val indexes = findInstructionIndicesReversed(filter)
    if (indexes.isEmpty()) throw PatchException("No matching instructions found in: $this")

    return indexes
}

/**
 * @return An immutable list of indices of the opcode in reverse order.
 *  _Returns an empty list if no indices are found_
 * @see findInstructionIndicesReversedOrThrow
 */
fun Method.findInstructionIndicesReversed(opcode: Opcode): List<Int> =
    findInstructionIndicesReversed { this.opcode == opcode }

/**
 * @return An immutable list of indices of the opcode in reverse order.
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversedOrThrow(opcode: Opcode): List<Int> {
    val instructions = findInstructionIndicesReversed(opcode)
    if (instructions.isEmpty()) throw PatchException("Could not find opcode: $opcode in: $this")

    return instructions
}

/**
 * Overrides the first move result with an extension call.
 * Suitable for calls to extension code to override boolean and integer values.
 */
internal fun MutableMethod.insertLiteralOverride(literal: Long, extensionMethodDescriptor: String) {
    val literalIndex = indexOfFirstLiteralInstructionOrThrow(literal)
    insertLiteralOverride(literalIndex, extensionMethodDescriptor)
}

internal fun MutableMethod.insertLiteralOverride(literalIndex: Int, extensionMethodDescriptor: String) {
    // TODO: make this work with objects and wide primitive values.
    val index = indexOfFirstInstructionOrThrow(literalIndex, MOVE_RESULT)
    val register = getInstruction<OneRegisterInstruction>(index).registerA

    val operation = if (register < 16) {
        "invoke-static { v$register }"
    } else {
        "invoke-static/range { v$register .. v$register }"
    }

    addInstructions(
        index + 1,
        """
            $operation, $extensionMethodDescriptor
            move-result v$register
        """
    )
}

/**
 * Overrides a literal value result with a constant value.
 */
internal fun MutableMethod.insertLiteralOverride(literal: Long, override: Boolean) {
    val literalIndex = indexOfFirstLiteralInstructionOrThrow(literal)
    return insertLiteralOverride(literalIndex, override)
}

/**
 * Constant value override of the first MOVE_RESULT after the index parameter.
 */
internal fun MutableMethod.insertLiteralOverride(literalIndex: Int, override: Boolean) {
    val index = indexOfFirstInstructionOrThrow(literalIndex, MOVE_RESULT)
    val register = getInstruction<OneRegisterInstruction>(index).registerA
    val overrideValue = if (override) "0x1" else "0x0"

    addInstruction(
        index + 1,
        "const v$register, $overrideValue"
    )
}

/**
 * Called for _all_ methods with the given literal value.
 * Method indices are iterated from last to first.
 */
fun BytecodePatchContext.forEachLiteralValueInstruction(
    literal: Long,
    block: MutableMethod.(matchingIndex: Int) -> Unit,
) {
    val matchingIndexes = ArrayList<Int>()

    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            method.implementation?.instructions?.let { instructions ->
                matchingIndexes.clear()

                instructions.forEachIndexed { index, instruction ->
                    if ((instruction as? WideLiteralInstruction)?.wideLiteral == literal) {
                        matchingIndexes.add(index)
                    }
                }

                if (matchingIndexes.isNotEmpty()) {
                    val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(method)
                    matchingIndexes.asReversed().forEach { index ->
                        block.invoke(mutableMethod, index)
                    }
                }
            }
        }
    }

}

private const val RETURN_TYPE_MISMATCH = "Mismatch between override type and Method return type"

/**
 * Overrides the first instruction of a method with a return-void instruction.
 * None of the method code will ever execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly() {
    check(returnType.first() == 'V') {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(false.toHexString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Boolean` return value.
 * None of the original method code will execute.
 *
 * For methods that return an object or any array type, calling this method with `false`
 * will force the method to return a `null` value.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Boolean) {
    check(returnType.first() == 'Z') {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(value.toHexString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Byte` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Byte) {
    check(returnType.first() == 'B') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Short` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Short) {
    check(returnType.first() == 'S') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Char` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Char) {
    check(returnType.first() == 'C') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.code.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Int` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Int) {
    check(returnType.first() == 'I') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Long` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Long) {
    check(returnType.first() == 'J') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Float` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Float) {
    check(returnType.first() == 'F') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Double` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Double) {
    check(returnType.first() == 'J') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant String return value.
 * None of the original method code will execute.
 *
 * Target method must have return type
 * Ljava/lang/String; or Ljava/lang/CharSequence;
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: String) {
    check(returnType == "Ljava/lang/String;" || returnType == "Ljava/lang/CharSequence;") {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(value, false)
}

/**
 * Overrides the first instruction of a method with a constant `NULL` return value.
 * None of the original method code will execute.
 *
 * @param value Value must be `Null`.
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Void?) {
    val returnType = returnType.first()
    check(returnType == 'L' || returnType != '[') {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(false.toHexString(), false)
}

/**
 * Overrides all return statements with a constant `Boolean` value.
 * All method code is executed the same as unpatched.
 *
 * For methods that return an object or any array type, calling this method with `false`
 * will force the method to return a `null` value.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Boolean) {
    check(this.returnType.first() == 'Z') {
        RETURN_TYPE_MISMATCH
    }

    overrideReturnValue(value.toHexString(), true)
}

/**
 * Overrides all return statements with a constant `Byte` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Byte) {
    check(returnType.first() == 'B') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Short` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Short) {
    check(returnType.first() == 'S') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Char` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Char) {
    check(returnType.first() == 'C') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.code.toString(), true)
}

/**
 * Overrides all return statements with a constant `Int` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Int) {
    check(returnType.first() == 'I') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Long` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Long) {
    check(returnType.first() == 'J') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Float` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Float) {
    check(returnType.first() == 'F') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Double` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Double) {
    check(returnType.first() == 'D') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant String value.
 * All method code is executed the same as unpatched.
 *
 * Target method must have return type
 * Ljava/lang/String; or Ljava/lang/CharSequence;
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: String) {
    check(returnType == "Ljava/lang/String;" || returnType == "Ljava/lang/CharSequence;") {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(value, true)
}

/**
 * Overrides all return statements with a constant `Null` value.
 * All method code is executed the same as unpatched.
 *
 * @param value Value must be `Null`.
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Void?) {
    val returnType = returnType.first()
    check(returnType == 'L' || returnType == '[') {
        RETURN_TYPE_MISMATCH
    }

    overrideReturnValue(false.toHexString(), true)
}

private fun MutableMethod.overrideReturnValue(value: String, returnLate: Boolean) {
    val instructions = if (returnType == "Ljava/lang/String;" || returnType == "Ljava/lang/CharSequence;" ) {
        """
            const-string v0, "$value"
            return-object v0
        """
    } else when (returnType.first()) {
        // If return type is an object, always return null.
        'L', '[' -> {
            """
                const/4 v0, 0x0
                return-object v0
            """
        }

        'V' -> {
            "return-void"
        }

        'B', 'Z' -> {
            """
                const/4 v0, $value
                return v0
            """
        }

        'S', 'C' -> {
            """
                const/16 v0, $value
                return v0
            """
        }

        'I', 'F' -> {
            """
                const v0, $value
                return v0
            """
        }

        'J', 'D' -> {
            """
                const-wide v0, $value
                return-wide v0
            """
        }

        else -> throw Exception("Return type is not supported: $this")
    }

    if (returnLate) {
        findInstructionIndicesReversedOrThrow {
            opcode == RETURN || opcode == RETURN_WIDE || opcode == RETURN_OBJECT
        }.forEach { index ->
            addInstructionsAtControlFlowLabel(index, instructions)
        }
    } else {
        addInstructions(0, instructions)
    }
}

/**
 * Remove the given AccessFlags from the field.
 */
internal fun MutableField.removeFlags(vararg flags: AccessFlags) {
    val bitField = flags.map { it.value }.reduce { acc, flag -> acc and flag }
    this.accessFlags = this.accessFlags and bitField.inv()
}

internal fun BytecodePatchContext.addStaticFieldToExtension(
    className: String,
    methodName: String,
    fieldName: String,
    objectClass: String,
    smaliInstructions: String
) {
    val mutableClass = mutableClassDefBy(className)
    val objectCall = "$mutableClass->$fieldName:$objectClass"

    mutableClass.apply {
        methods.first { method -> method.name == methodName }.apply {
            staticFields.add(
                ImmutableField(
                    definingClass,
                    fieldName,
                    objectClass,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    annotations,
                    null
                ).toMutable()
            )

            addInstructionsWithLabels(
                0,
                """
                    sget-object v0, $objectCall
                """ + smaliInstructions
            )
        }
    }
}

/**
 * Set the custom condition for this fingerprint to check for a literal value.
 *
 * @param customLiteral The literal value.
 */
@Deprecated("Instead use InstructionFilter and `literal()`")
fun customLiteral(literalSupplier: () -> Long): ((method: Method, classDef: ClassDef) -> Boolean) =
    { method, _ ->
        method.containsLiteralInstruction(literalSupplier())
    }
