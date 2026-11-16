/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4923
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.tabletgrid

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/TabletGridFeedPatch;"

@Suppress("unused")
val tabletGridFeedPatch = patch(
    name = "Tablet grid feed",
    description = "Adds an option to show feeds as a grid with 2 or 3 columns on tablets and other wide screens."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        val listFeedCallMatch = LatestFeedContentFingerprint.instructionMatches.first()
        val listFeedMethod = listFeedCallMatch.getMethodCalled()
        val listFeedRef = listFeedCallMatch.methodReference
        val listFeedParams = listFeedRef.parameterTypes.map { it.toString() }

        val feedTypeIndex = listFeedParams.indexOf("Lcom/reddit/feeds/data/FeedType;")
        if (feedTypeIndex != 5) {
            throw PatchException("Expected FeedType parameter at index 5, but was at $feedTypeIndex")
        }

        val composerIndex = listFeedParams.indexOfLast { it != "I" }
        if (composerIndex == -1) {
            throw PatchException("Could not find Composer parameter in list feed method")
        }
        val composerType = listFeedParams[composerIndex]

        val masonryCallMatch = SearchMasonryCallFingerprint.instructionMatches[1]
        val masonryRef = masonryCallMatch.methodReference
        val masonryParams = masonryRef.parameterTypes.map { it.toString() }
        val isLegacy = masonryParams.size == 15
        val legacyOffset = if (isLegacy) 1 else 0

        val masonryComposerType = masonryParams[11 + legacyOffset]
        if (composerType != masonryComposerType) {
            throw PatchException("Composer parameter type mismatch: expected $masonryComposerType, got $composerType")
        }

        val masonryStateSlotType = masonryParams[9 + legacyOffset]
        val stateSlotIndex = (8 until listFeedParams.size).firstOrNull { listFeedParams[it] == masonryStateSlotType }
            ?: throw PatchException("Could not find stateSlot parameter matching $masonryStateSlotType in list feed method")

        val footerField = SearchFooterFieldFingerprint.instructionMatches.last().fieldReference

        val igetMatch = CalculateCellsFingerprint.instructionMatches.first()
        val igetIndex = igetMatch.index
        val registerA = (igetMatch.instruction as TwoRegisterInstruction).registerA
        val p1 = CalculateCellsFingerprint.method.p0Register + 1

        if (p1 > 15 || registerA > 15) {
            throw PatchException("Registers exceed 15 in CalculateCells: p1=$p1, registerA=$registerA")
        }

        CalculateCellsFingerprint.method.addInstructions(
            igetIndex + 1,
            """
                invoke-static { v$p1, v$registerA }, $EXTENSION_CLASS->columnsForWidth(II)I
                move-result v$registerA
            """
        )

        val originalFirstInstruction = listFeedMethod.getInstruction(0)
        listFeedMethod.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                move-object/from16 v1, p1
                move-object/from16 v2, p2
                move-object/from16 v3, p4
                move-object/from16 v4, p5
                move-object/from16 v5, p7
                move-object/from16 v6, p$stateSlotIndex
                move-object/from16 v7, p$composerIndex
                move/from16 v8, p6
                invoke-static/range { v0 .. v8 }, $EXTENSION_CLASS->masonryFeed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;F)Z
                move-result v0
                if-eqz v0, :continue
                return-void
            """,
            ExternalLabel("continue", originalFirstInstruction)
        )

        val paddingType = masonryParams[4]
        Fingerprint(definingClass = paddingType, name = "<init>", parameters = listOf("F", "F", "F", "F")).method

        fun extensionMethod(name: String) = Fingerprint(definingClass = EXTENSION_CLASS, name = name).method

        extensionMethod("useLegacyMasonry").returnEarly(isLegacy)

        val checkCasts = masonryParams.mapIndexedNotNull { index, type ->
            if (type.startsWith("L") || type.startsWith("[")) {
                "check-cast p$index, $type"
            } else {
                null
            }
        }.joinToString("\n")

        val bridgeName = if (isLegacy) "callMasonryLegacy" else "callMasonry"
        extensionMethod(bridgeName).addInstructions(
            0,
            """
                $checkCasts
                invoke-static/range { p0 .. p${masonryParams.size - 1} }, ${masonryRef.definingClass}->${masonryRef.name}(${masonryRef.parameterTypes.joinToString("")})${masonryRef.returnType}
                return-void
            """
        )

        extensionMethod("newPadding").addInstructions(
            0,
            """
                new-instance p4, $paddingType
                invoke-direct { p4, p0, p1, p2, p3 }, $paddingType-><init>(FFFF)V
                return-object p4
            """
        )

        extensionMethod("footer").addInstructions(
            0,
            """
                sget-object p0, ${footerField.definingClass}->${footerField.name}:${footerField.type}
                return-object p0
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
