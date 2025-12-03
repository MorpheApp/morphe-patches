package app.morphe.util

import app.morphe.util.FreeRegisterProvider.Companion.branchOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.conditionalBranchOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.returnOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.writeOpcodes
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcode.*
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.EnumSet
import java.util.LinkedList

/**
 * Finds free registers at a specific index in a method.
 * Allows consuming free registers one at a time.
 *
 * If you only need a single free register, instead use [findFreeRegister].
 */
fun Method.getFreeRegisterProvider(startIndex: Int, registersToExclude: List<Int>) =
    FreeRegisterProvider(this, startIndex, registersToExclude)

/**
 * Finds free registers at a specific index in a method.
 * Allows consuming free registers one at a time.
 *
 * If you only need a single free register, instead use [findFreeRegister].
 */
fun Method.getFreeRegisterProvider(startIndex: Int, vararg registersToExclude: Int) =
    FreeRegisterProvider(this, startIndex, *registersToExclude)

/**
 * Simple wrapper around [findFreeRegister] that allows finding then consuming multiple registers.
 * If you only need a single free register, instead use [findFreeRegister].
 */
class FreeRegisterProvider internal constructor(
    method: Method,
    startIndex: Int,
    registersToExclude: List<Int>
) {

    internal constructor(
        method: Method,
        startIndex: Int,
        vararg registersToExclude: Int
    ) : this(method, startIndex, registersToExclude.toList())

    internal companion object {
        val conditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
            IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE,
            IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ,
            PACKED_SWITCH
        )

        val branchOpcodes: EnumSet<Opcode> = conditionalBranchOpcodes.clone().also {
            it.addAll(listOf(GOTO, GOTO_16, GOTO_32))
        }

        val returnOpcodes: EnumSet<Opcode> = EnumSet.of(
            RETURN_VOID, RETURN, RETURN_WIDE, RETURN_OBJECT, RETURN_VOID_NO_BARRIER,
            THROW
        )

        val writeOpcodes: EnumSet<Opcode> = EnumSet.of(
            ARRAY_LENGTH,
            INSTANCE_OF,
            NEW_INSTANCE, NEW_ARRAY,
            MOVE, MOVE_FROM16, MOVE_16, MOVE_WIDE, MOVE_WIDE_FROM16, MOVE_WIDE_16, MOVE_OBJECT,
            MOVE_OBJECT_FROM16, MOVE_OBJECT_16, MOVE_RESULT, MOVE_RESULT_WIDE, MOVE_RESULT_OBJECT, MOVE_EXCEPTION,
            CONST, CONST_4, CONST_16, CONST_HIGH16, CONST_WIDE_16, CONST_WIDE_32,
            CONST_WIDE, CONST_WIDE_HIGH16, CONST_STRING, CONST_STRING_JUMBO,
            IGET, IGET_WIDE, IGET_OBJECT, IGET_BOOLEAN, IGET_BYTE, IGET_CHAR, IGET_SHORT,
            IGET_VOLATILE, IGET_WIDE_VOLATILE, IGET_OBJECT_VOLATILE,
            SGET, SGET_WIDE, SGET_OBJECT, SGET_BOOLEAN, SGET_BYTE, SGET_CHAR, SGET_SHORT,
            SGET_VOLATILE, SGET_WIDE_VOLATILE, SGET_OBJECT_VOLATILE,
            AGET, AGET_WIDE, AGET_OBJECT, AGET_BOOLEAN, AGET_BYTE, AGET_CHAR, AGET_SHORT,
            // Arithmetic and logical operations.
            ADD_DOUBLE_2ADDR, ADD_DOUBLE, ADD_FLOAT_2ADDR, ADD_FLOAT, ADD_INT_2ADDR,
            ADD_INT_LIT8, ADD_INT, ADD_LONG_2ADDR, ADD_LONG, ADD_INT_LIT16,
            AND_INT_2ADDR, AND_INT_LIT8, AND_INT_LIT16, AND_INT, AND_LONG_2ADDR, AND_LONG,
            DIV_DOUBLE_2ADDR, DIV_DOUBLE, DIV_FLOAT_2ADDR, DIV_FLOAT, DIV_INT_2ADDR,
            DIV_INT_LIT16, DIV_INT_LIT8, DIV_INT, DIV_LONG_2ADDR, DIV_LONG,
            DOUBLE_TO_FLOAT, DOUBLE_TO_INT, DOUBLE_TO_LONG,
            FLOAT_TO_DOUBLE, FLOAT_TO_INT, FLOAT_TO_LONG,
            INT_TO_BYTE, INT_TO_CHAR, INT_TO_DOUBLE, INT_TO_FLOAT, INT_TO_LONG, INT_TO_SHORT,
            LONG_TO_DOUBLE, LONG_TO_FLOAT, LONG_TO_INT,
            MUL_DOUBLE_2ADDR, MUL_DOUBLE, MUL_FLOAT_2ADDR, MUL_FLOAT, MUL_INT_2ADDR,
            MUL_INT_LIT16, MUL_INT_LIT8, MUL_INT, MUL_LONG_2ADDR, MUL_LONG,
            NEG_DOUBLE, NEG_FLOAT, NEG_INT, NEG_LONG,
            NOT_INT, NOT_LONG,
            OR_INT_2ADDR, OR_INT_LIT16, OR_INT_LIT8, OR_INT, OR_LONG_2ADDR, OR_LONG,
            REM_DOUBLE_2ADDR, REM_DOUBLE, REM_FLOAT_2ADDR, REM_FLOAT, REM_INT_2ADDR,
            REM_INT_LIT16, REM_INT_LIT8, REM_INT, REM_LONG_2ADDR, REM_LONG,
            RSUB_INT_LIT8, RSUB_INT,
            SHL_INT_2ADDR, SHL_INT_LIT8, SHL_INT, SHL_LONG_2ADDR, SHL_LONG,
            SHR_INT_2ADDR, SHR_INT_LIT8, SHR_INT, SHR_LONG_2ADDR, SHR_LONG,
            SUB_DOUBLE_2ADDR, SUB_DOUBLE, SUB_FLOAT_2ADDR, SUB_FLOAT, SUB_INT_2ADDR,
            SUB_INT, SUB_LONG_2ADDR, SUB_LONG,
            USHR_INT_2ADDR, USHR_INT_LIT8, USHR_INT, USHR_LONG_2ADDR, USHR_LONG,
            XOR_INT_2ADDR, XOR_INT_LIT16, XOR_INT_LIT8, XOR_INT, XOR_LONG_2ADDR, XOR_LONG,
        )
    }

    private var freeRegisters: MutableList<Int> = LinkedList(
        method.findFreeRegisters(
            startIndex, true, registersToExclude
        ).also {
            // FIXME:
            println("Free register: " + method.name + " startIndex: " + startIndex + " free: " + it)
        }
    )

    private val originallyExcludedRegisters = registersToExclude
    private val allocatedFreeRegisters = mutableListOf<Int>()

    /**
     * Returns a free register and removes it from the available list.
     *
     * @return A free register number
     * @throws IllegalStateException if no free registers are available
     */
    fun getFreeRegister(): Int {
        if (freeRegisters.isEmpty()) {
            throw IllegalStateException("No free registers available")
        }
        val register = freeRegisters.removeFirst()
        allocatedFreeRegisters.add(register)
        return register
    }

    /**
     * Returns all registers that have been allocated via [getFreeRegister].
     * This does not include the originally excluded registers.
     *
     * @return List of registers that have been allocated, in allocation order
     */
    fun getAllocatedFreeRegisters(): List<Int> = allocatedFreeRegisters.toList()

    /**
     * Returns all registers that are considered "in use" - both originally
     * excluded registers and newly allocated registers.
     *
     * @return List of all registers that should not be used,
     *         with originally excluded registers first, followed by
     *         newly allocated registers in allocation order
     */
    fun getUsedAndExcludedRegisters(): List<Int> =
        originallyExcludedRegisters + allocatedFreeRegisters

    /**
     * @return The number of free registers still available.
     */
    fun availableCount(): Int = freeRegisters.size

    /**
     * Checks if there are any free registers available.
     */
    fun hasFreeRegisters(): Boolean = freeRegisters.isNotEmpty()
}

/**
 * Starting from and including the instruction at index [startIndex],
 * finds the next register that is written to and not read from. If a return instruction
 * is encountered, then the lowest unused register is returned.
 *
 * This method can return a non 4-bit register, and the calling code may need to temporarily
 * swap register contents if a 4-bit register is required.
 *
 * @param startIndex Inclusive starting index.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 * @return The lowest register number (usually a 4-bit register) that is free at the given index.
 * @throws IllegalArgumentException If no free registers exist at the given index.
 * @see [FreeRegisterProvider]
 */
fun Method.findFreeRegister(
    startIndex: Int,
    vararg registersToExclude: Int
) = findFreeRegisters(startIndex, true, registersToExclude.toList()).first()


private fun Method.findFreeRegisters(
    startIndex: Int,
    checkBranch: Boolean,
    registersToExclude: List<Int>
): List<Int> {
    val freeRegisters = findFreeRegistersInternal(
        startIndex = startIndex,
        checkBranch = checkBranch,
        followBranches = checkBranch,
        visitedBranches = mutableSetOf(),
        registersToExclude = registersToExclude.toSet()
    )

    if (freeRegisters.isEmpty()) {
        throw IllegalArgumentException("Could not find a free register from startIndex: " +
                "$startIndex excluding: $registersToExclude")
    }

    return freeRegisters.sorted()
}

private fun Method.findFreeRegistersInternal(
    startIndex: Int,
    checkBranch: Boolean,
    followBranches: Boolean,
    visitedBranches: MutableSet<Int>,
    registersToExclude: Set<Int>
): List<Int> {
    if (implementation == null) {
        throw IllegalArgumentException("Method has no implementation: $this")
    }
    if (startIndex < 0 || startIndex >= instructions.count()) {
        throw IllegalArgumentException("startIndex out of bounds: $startIndex")
    }

    val addresses = buildInstructionAddressMap()

    val usedRegisters = registersToExclude.toMutableSet()
    val freeRegisters = mutableSetOf<Int>()
    val branchTargets = mutableListOf<Int>()
    var encounteredBranch = false

    for (i in startIndex until instructions.count()) {
        val instruction = getInstruction(i)
        val instructionRegisters = instruction.registersUsed

        val writeRegister = instruction.writeRegister
        if (writeRegister != null) {
            if (writeRegister !in usedRegisters) {
                if (instructionRegisters.count { it == writeRegister } == 1) {
                    freeRegisters.add(writeRegister)
                }
            }
        }

        usedRegisters.addAll(instructionRegisters)

        if (instruction.isReturnInstruction) {
            val allRegisters = (0 until implementation!!.registerCount).toSet()
            val unusedRegisters = allRegisters - usedRegisters
            freeRegisters.addAll(unusedRegisters)
            return freeRegisters.toList()
        }

        if (instruction.isBranchInstruction) {
            encounteredBranch = true

            if (checkBranch && !followBranches) {
                break
            }

            if (followBranches) {
                val targetIndex = getBranchTargetIndex(instruction, i, addresses)
                if (targetIndex != null && targetIndex !in visitedBranches) {
                    branchTargets.add(targetIndex)
                }

                if (instruction.isConditionalBranch) {
                    if (i + 1 < instructions.count() && (i + 1) !in visitedBranches) {
                        branchTargets.add(i + 1)
                    }
                }
            }
        }
    }

    if (encounteredBranch && followBranches && branchTargets.isNotEmpty()) {
        val allFreeRegisters = mutableSetOf<Int>()
        allFreeRegisters.addAll(freeRegisters)

        visitedBranches.addAll(branchTargets)

        for (targetIndex in branchTargets) {
            val targetFreeRegisters = findFreeRegistersInternal(
                startIndex = targetIndex,
                checkBranch = checkBranch,
                followBranches = false,
                visitedBranches = visitedBranches,
                registersToExclude = usedRegisters
            )
            allFreeRegisters.addAll(targetFreeRegisters)
        }

        return allFreeRegisters.toList()
    }

    return freeRegisters.toList()
}

/**
 * Very simple instruction-address table.
 *
 * We assume each instruction is exactly 1 code unit. This is sufficient for
 * resolving branch targets because Dalvik branch offsets always target valid
 * instruction boundaries, and the relative instruction indices are preserved.
 *
 * This avoids reflection and avoids needing to know the real instruction size.
 */
private fun Method.buildInstructionAddressMap(): IntArray {
    val count = instructions.count()
    val addresses = IntArray(count)
    for (i in 0 until count) {
        addresses[i] = i // 1 code unit per instruction
    }
    return addresses
}

/**
 * Convert an offset (in code units) into an instruction index.
 */
private fun getBranchTargetIndex(
    instruction: Instruction,
    currentIndex: Int,
    addresses: IntArray
): Int? {
    if (instruction !is OffsetInstruction) return null

    // Bytecode code offset, and not the same as Instruction object index.
    val offset = instruction.codeOffset
    val targetAddress = addresses[currentIndex] + offset

    // Since address == index, targetAddress is the instruction index.
    if (targetAddress < 0 || targetAddress >= addresses.size) {
        return null
    }

    return targetAddress
}

/**
 * Starting from and including the instruction at index [startIndex],
 * finds the next register that is written to and not read from. If a return instruction
 * is encountered, then the lowest unused register is returned.
 *
 * This method can return a non 4-bit register, and the calling code may need to temporarily
 * swap register contents if a 4-bit register is required.
 *
 * @param startIndex Inclusive starting index.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 * @return The lowest register number (usually a 4-bit register) that is free at the given index.
 * @throws IllegalArgumentException If no free registers exist at the given index.
 * @see [FreeRegisterProvider]
 */
fun Method.findFreeRegister(
    startIndex: Int,
    registersToExclude: List<Int>
) = findFreeRegisters(startIndex, true, registersToExclude).first()


/**
 * @return The registers used by this instruction.
 */
internal val Instruction.registersUsed: List<Int>
    get() = when (this) {
        is FiveRegisterInstruction -> {
            when (registerCount) {
                0 -> listOf()
                1 -> listOf(registerC)
                2 -> listOf(registerC, registerD)
                3 -> listOf(registerC, registerD, registerE)
                4 -> listOf(registerC, registerD, registerE, registerF)
                else -> listOf(registerC, registerD, registerE, registerF, registerG)
            }
        }

        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        is RegisterRangeInstruction -> (startRegister until (startRegister + registerCount)).toList()
        else -> emptyList()
    }

/**
 * @return The register that is written to by this instruction,
 *         or NULL if this is not a write opcode.
 */
internal val Instruction.writeRegister: Int?
    get() {
        if (this.opcode !in writeOpcodes) {
            return null
        }
        if (this !is OneRegisterInstruction) {
            throw IllegalStateException("Not a write instruction: $this")
        }
        return registerA
    }

/**
 * This differs from [isBranchInstruction] in that it does not include unconditional goto.
 *
 * @return If this instruction is a conditional branch (multiple branch paths).
 *
 */
internal val Instruction.isConditionalBranch: Boolean
    get() = this.opcode in conditionalBranchOpcodes

/**
 * @return If this instruction is an unconditional or conditional branch opcode.
 */
internal val Instruction.isBranchInstruction: Boolean
    get() = this.opcode in branchOpcodes

/**
 * @return If this instruction returns or throws.
 */
internal val Instruction.isReturnInstruction: Boolean
    get() = this.opcode in returnOpcodes
