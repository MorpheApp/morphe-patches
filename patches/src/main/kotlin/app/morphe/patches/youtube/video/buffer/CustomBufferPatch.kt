package app.morphe.patches.youtube.video.buffer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.writeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/CustomBufferPatch;"

private const val PLAYBACK_BUFFER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/PlaybackBufferPatch;"

private const val LOAD_CONTROL_ACCESS_DESCRIPTOR =
    $$"Lapp/morphe/extension/youtube/patches/CustomBufferPatch$LoadControlAccess;"

/**
 * Register that holds `this` at [index], usable by a non-range invoke (v0-v15).
 */
private fun MutableMethod.findThisRegister(index: Int): Int {
    val parameterRegisters = parameterTypes.fold(1) { count, type ->
        count + if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    val thisRegister = implementation!!.registerCount - parameterRegisters

    fun isWrittenBetween(register: Int, start: Int, end: Int) = (start until end).any { i ->
        val instruction = getInstruction(i)
        val written = instruction.writeRegister ?: return@any false
        written == register || (instruction.opcode.setsWideRegister() && written + 1 == register)
    }

    if (thisRegister <= 15 && !isWrittenBetween(thisRegister, 0, index)) return thisRegister

    for (i in 0 until index) {
        val instruction = getInstruction(i)
        if (instruction.opcode != Opcode.MOVE_OBJECT &&
            instruction.opcode != Opcode.MOVE_OBJECT_FROM16 &&
            instruction.opcode != Opcode.MOVE_OBJECT_16
        ) continue

        val move = instruction as TwoRegisterInstruction
        if (move.registerB == thisRegister && move.registerA <= 15 &&
            !isWrittenBetween(move.registerA, i + 1, index)
        ) {
            return move.registerA
        }
    }

    throw PatchException("Could not find a register that holds this in $this")
}

@Suppress("unused")
val customBufferPatch = patch(
    name = "Custom buffer",
    description = "Adds options to set the forward buffer, back buffer and RAM limit of the video player " +
            "in seconds and MB, to keep played video until new video is loaded, " +
            "and to keep the video buffer in RAM only instead of the disk cache."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playbackBufferPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_custom_buffer_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_custom_buffer", summary = true),
                    TextPreference("morphe_custom_buffer_forward_max", inputType = InputType.NUMBER),
                    TextPreference("morphe_custom_buffer_resume_below", inputType = InputType.NUMBER),
                    TextPreference("morphe_custom_buffer_back", inputType = InputType.NUMBER),
                    SwitchPreference("morphe_custom_buffer_donate", summary = true),
                    TextPreference("morphe_custom_buffer_sabr_forward_max", inputType = InputType.NUMBER),
                    TextPreference("morphe_custom_buffer_sabr_back", inputType = InputType.NUMBER),
                    TextPreference("morphe_custom_buffer_ram_mb", inputType = InputType.NUMBER),
                    SwitchPreference("morphe_custom_buffer_disable_disk_cache", summary = true)
                )
            )
        )

        val loadControlClass = ShouldContinueLoadingFingerprint.classDef
        val loadControlType = loadControlClass.type

        // region Let the extension read the allocator bytes and the byte target.

        ShouldContinueLoadingFingerprint.method.apply {
            val allocatorGetterIndex = indexOfFirstInstructionOrThrow(0) {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_VIRTUAL &&
                    reference?.definingClass == loadControlType &&
                    reference.parameterTypes.isEmpty() &&
                    reference.returnType.startsWith("L")
            }
            val allocatorGetter =
                getInstruction(allocatorGetterIndex).getReference<MethodReference>()!!

            val allocatedBytesIndex = indexOfFirstInstructionOrThrow(allocatorGetterIndex) {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_VIRTUAL &&
                    reference?.definingClass == allocatorGetter.returnType &&
                    reference.parameterTypes.isEmpty() &&
                    reference.returnType == "I"
            }
            val allocatedBytesMethod =
                getInstruction(allocatedBytesIndex).getReference<MethodReference>()!!

            val targetBytesIndex = indexOfFirstInstructionOrThrow(allocatedBytesIndex) {
                val reference = getReference<FieldReference>()
                opcode == Opcode.IGET &&
                    reference?.definingClass == loadControlType &&
                    reference.type == "I"
            }
            val targetBytesField =
                getInstruction(targetBytesIndex).getReference<FieldReference>()!!

            loadControlClass.addPatchInterface(
                LOAD_CONTROL_ACCESS_DESCRIPTOR,
                "patch_getAllocatedBytes",
                """
                    invoke-virtual { p0 }, $allocatorGetter
                    move-result-object v0
                    invoke-virtual { v0 }, $allocatedBytesMethod
                    move-result v0
                    return v0
                """
            )
            loadControlClass.addPatchInterface(
                LOAD_CONTROL_ACCESS_DESCRIPTOR,
                "patch_getTargetBytes",
                targetBytesField
            )
        }

        // endregion

        // region Forward buffer limit and hysteresis.

        ShouldContinueLoadingFingerprint.let {
            it.method.apply {
                val bufferedField = it.instructionMatches.first().fieldReference!!

                // First read of the buffered duration, before the Playback buffer patch scales it.
                val readIndex = indexOfFirstInstructionOrThrow(0) {
                    opcode == Opcode.IGET_WIDE && getReference<FieldReference>() == bufferedField
                }
                val read = getInstruction<TwoRegisterInstruction>(readIndex)
                val bufferedRegister = read.registerA
                val objectRegister = read.registerB
                if (bufferedRegister + 1 > 15) {
                    throw PatchException("Buffered duration register is out of range: v$bufferedRegister")
                }
                val thisRegister = findThisRegister(readIndex)

                addInstructionsWithLabels(
                    readIndex + 1,
                    """
                        invoke-static { v$thisRegister, v$bufferedRegister, v${bufferedRegister + 1} }, $EXTENSION_CLASS_DESCRIPTOR->shouldContinueLoading(Ljava/lang/Object;J)I
                        move-result v$bufferedRegister
                        if-ltz v$bufferedRegister, :original
                        return v$bufferedRegister
                        :original
                        iget-wide v$bufferedRegister, v$objectRegister, $bufferedField
                    """
                )
            }
        }

        // endregion

        // region Back buffer.

        BackBufferDurationFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionOrThrow(0) { opcode == Opcode.RETURN_WIDE }
            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS_DESCRIPTOR->getBackBufferUs(J)J
                    move-result-wide v$register
                """
            )
        }

        // endregion

        // region Donate the unused forward buffer to the back buffer.

        val backBufferMethodName = BackBufferDurationFingerprint.originalMethod.name
        val loadControlTypes = loadControlClass.interfaces.toSet() + loadControlType

        DoSomeWorkFingerprint.let {
            val playerClass = it.classDef

            // The player field that stores getBackBufferDurationUs().
            val backBufferField = playerClass.methods.filter { method -> method.name == "<init>" }
                .firstNotNullOfOrNull { constructor ->
                    val instructions = constructor.implementation?.instructions?.toList() ?: return@firstNotNullOfOrNull null
                    instructions.indices.firstNotNullOfOrNull { i ->
                        val call = instructions[i].getReference<MethodReference>()
                        if (call == null || call.name != backBufferMethodName || call.returnType != "J" ||
                            call.parameterTypes.isNotEmpty() || call.definingClass !in loadControlTypes
                        ) return@firstNotNullOfOrNull null

                        instructions.drop(i + 1).take(3)
                            .firstOrNull { instruction -> instruction.opcode == Opcode.IPUT_WIDE }
                            ?.getReference<FieldReference>()
                    }
                } ?: throw PatchException("Could not find the back buffer field in ${playerClass.type}")

            // discardBuffer(position - backBuffer) is called in doSomeWork and also right after
            // every seek. Both must use the donated value, or a seek inside the buffer
            // throws away everything older than (new position - back buffer).
            fun MutableMethod.discardIndices() = instructions.indices.filter { i ->
                val instruction = getInstruction(i)
                if (instruction.opcode != Opcode.IGET_WIDE ||
                    instruction.getReference<FieldReference>() != backBufferField
                ) return@filter false

                val next = instructions.drop(i + 1).take(3)
                next.any { n -> n.opcode == Opcode.SUB_LONG_2ADDR || n.opcode == Opcode.SUB_LONG } &&
                    next.any { n ->
                        val call = n.getReference<MethodReference>()
                        n.opcode == Opcode.INVOKE_INTERFACE && call?.returnType == "V" && call.parameterTypes.size == 1 &&
                            call.parameterTypes.first() == "J"
                    }
            }

            if (it.method.discardIndices().isEmpty()) {
                throw PatchException("Could not find the back buffer discard in doSomeWork")
            }

            playerClass.methods.forEach { method -> method.apply {
                discardIndices().asReversed().forEach { index ->
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA
                    addInstructions(
                        index + 1,
                        """
                            invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS_DESCRIPTOR->getDiscardBackBufferUs(J)J
                            move-result-wide v$register
                        """
                    )
                }
            } }
        }

        // endregion

        // region RAM limit.

        TracksSelectedFingerprint.method.apply {
            // After the Playback buffer patch scaled the limit.
            val scaleIndex = indexOfFirstInstructionOrThrow(0) {
                val reference = getReference<MethodReference>()
                reference?.definingClass == PLAYBACK_BUFFER_CLASS_DESCRIPTOR && reference.name == "scaleByteLimit"
            }
            val register = getInstruction<OneRegisterInstruction>(scaleIndex + 1).registerA

            addInstructions(
                scaleIndex + 2,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->getByteLimit(I)I
                    move-result v$register
                """
            )
        }

        // endregion

        // region Media cache in RAM only.

        MediaCacheFingerprint.method.apply {
            val cacheInterface = returnType
            val emptyCaches = mutableListOf<ClassDef>()
            classDefForEach { classDef ->
                if (classDef.interfaces.size == 1 &&
                    classDef.interfaces.first() == cacheInterface &&
                    classDef.fields.none() &&
                    classDef.directMethods.any { method -> method.name == "<init>" && method.parameterTypes.isEmpty() }
                ) {
                    emptyCaches.add(classDef)
                }
            }
            val emptyCache = emptyCaches.singleOrNull()
                ?: throw PatchException("Expected one empty media cache class, found: ${emptyCaches.map { it.type }}")

            if (implementation!!.registerCount - (1 + parameterTypes.size) < 1) { // Both parameters are objects.
                throw PatchException("No free register in the media cache method")
            }

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->disableDiskCache()Z
                    move-result v0
                    if-eqz v0, :original
                    new-instance v0, ${emptyCache.type}
                    invoke-direct { v0 }, ${emptyCache.type}-><init>()V
                    return-object v0
                """,
                ExternalLabel("original", getInstruction(0))
            )
        }

        // endregion
    }
}
