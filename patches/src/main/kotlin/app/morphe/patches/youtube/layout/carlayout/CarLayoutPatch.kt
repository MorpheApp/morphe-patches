/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.carlayout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.string
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.youtube.layout.formfactor.InnerTubeClientConfigFingerprint
import app.morphe.patches.youtube.layout.formfactor.RepeatedItemSectionRendererFingerprint
import app.morphe.patches.youtube.misc.cardisplay.carLayoutPatchIncluded
import app.morphe.patches.youtube.misc.cardisplay.carDisplayDetectionPatch
import app.morphe.patches.youtube.misc.cardisplay.configuredCarDisplayDetection
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.BrowseEndpointParentFingerprint
import app.morphe.patches.youtube.misc.contexthook.addClientBrowseIdFormFactorHook
import app.morphe.patches.youtube.misc.contexthook.addClientFormFactorHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CAR_LAYOUT_CLASS =
    "Lapp/morphe/extension/youtube/patches/car/CarLayout;"

/** The universal form factor hook of the Change form factor patch, if it is selected. */
private const val MORPHE_CHANGE_FORM_FACTOR_CLASS =
    "Lapp/morphe/extension/youtube/patches/ChangeFormFactorPatch;"

private val detectionValues = mapOf("Car displays" to "car", "Any external display" to "any")
private const val DETECTION_DESCRIPTION =
    "Car displays: Android Auto, DriveDeck and hosts that use the car UI mode, and on Android 15 and later " +
        "phone mirroring by DriveDeck, Gearslip, graphene-auto or MyCar. " +
        "Any external display: also other car hosts, but also a TV or a monitor."

/**
 * Uses the automotive layout on a car display, such as a parked app on Android Auto or DriveDeck,
 * and the original layout on the phone. Works with Change form factor: on a car display this patch decides.
 */
@Suppress("unused")
val carDisplayLayoutPatch = patch(
    name = "Automotive layout on car displays",
    description = "Uses the automotive layout on a car display, such as a parked app on Android Auto or " +
        "DriveDeck, and the original layout on the phone. Works with Change form factor: " +
        "on a car display this patch decides.",
) {
    dependsOn(
        carDisplayDetectionPatch,
        clientContextHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    val detection by stringOption(
        key = "detection", default = "car", values = detectionValues,
        title = "Car display detection", description = DETECTION_DESCRIPTION, required = true,
    )

    execute {
        val chosen = detection ?: throw PatchException("Choose a car display detection")
        if (chosen !in detectionValues.values) throw PatchException("Unknown car display detection: $chosen")
        if (chosen == "any") configuredCarDisplayDetection = "any"
        carLayoutPatchIncluded = true

        // Pages the automotive form factor breaks, ported from the Change form factor patch.
        setOf(
            Endpoint.GET_WATCH,
            Endpoint.NEXT,
            Endpoint.GUIDE,
            Endpoint.REEL,
        ).forEach { endpoint ->
            addClientFormFactorHook(endpoint, "$CAR_LAYOUT_CLASS->replaceBrokenFormFactor(I)I")
        }

        // The browse request names its page, so only the pages that work keep the automotive layout.
        addClientBrowseIdFormFactorHook(
            Endpoint.BROWSE,
            browseIdField(),
            "$CAR_LAYOUT_CLASS->replaceBrowseFormFactor(ILjava/lang/String;)I",
        )
    }

    // After every other patch executed, so Change form factor, if selected, finds the original code
    // and this patch can follow it.
    finalize {
        // The form factor of every request. Change form factor, if selected, sets it right after the
        // field is read; this patch then runs after it, so on a car display the automotive layout is used.
        val changeFormFactorIncluded = hookUniversalFormFactor()

        // Change form factor has its own guard for this, which changes the code this one matches.
        if (changeFormFactorIncluded) return@finalize

        // The tablet form factor can make the app parse an item section a page does not have.
        RepeatedItemSectionRendererFingerprint.clearMatch()
        RepeatedItemSectionRendererFingerprint.let {
            it.method.apply {
                val match = it.instructionMatches[1]
                val listRegister = match.instruction.registersUsed[0]
                val indexRegister = match.instruction.registersUsed[1]
                val free = findFreeRegister(match.index, listRegister, indexRegister)
                addInstructionsWithLabels(
                    match.index,
                    """
                        invoke-static { v$listRegister, v$indexRegister }, $CAR_LAYOUT_CLASS->checkItemSectionRenderer(Ljava/util/List;I)Z
                        move-result v$free
                        if-nez v$free, :valid_index
                        return-void
                        :valid_index
                        nop
                    """,
                )
            }
        }
    }
}

/** The browse ID field of the browse endpoint, found from where the endpoint reads its own field. */
private fun browseIdField(): FieldReference {
    val browse = BrowseEndpointParentFingerprint.classDef
    return browse.methods.firstNotNullOfOrNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@firstNotNullOfOrNull null
        val start = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "browseId"
        }
        if (start < 0) return@firstNotNullOfOrNull null
        instructions.drop(start + 1).take(5).firstNotNullOfOrNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { field ->
                it.opcode == Opcode.IGET_OBJECT && field.definingClass == browse.type && field.type == "Ljava/lang/String;"
            }
        }
    } ?: throw PatchException("Browse page id not found")
}

/**
 * Inserts the universal form factor hook of this patch after the one of the Change form factor patch,
 * or right after the form factor is read when that patch is not selected.
 *
 * @return Whether the Change form factor patch is selected.
 */
private fun hookUniversalFormFactor(): Boolean {
    InnerTubeClientConfigFingerprint.clearMatch()
    InnerTubeClientConfigFingerprint.let {
        val method = it.method
        val index = it.instructionMatches.last().index
        val register = method.getInstruction<TwoRegisterInstruction>(index).registerA
        val instructions = method.instructions.toList()
        val changeFormFactorCall = (index + 1 until minOf(index + 8, instructions.size)).firstOrNull { i ->
            ((instructions[i] as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass ==
                    MORPHE_CHANGE_FORM_FACTOR_CLASS
        }
        val insertIndex = if (changeFormFactorCall != null &&
            instructions.getOrNull(changeFormFactorCall + 1)?.opcode == Opcode.MOVE_RESULT
        ) {
            changeFormFactorCall + 2
        } else {
            index + 1
        }
        method.addInstructions(
            insertIndex,
            """
                invoke-static { v$register }, $CAR_LAYOUT_CLASS->getUniversalFormFactor(I)I
                move-result v$register
            """,
        )
        return changeFormFactorCall != null
    }
}
