/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5457
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.subredditcreate

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.layout.navigation.hideNavigationButtonsPatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SubredditCreateButtonPatch;"
private const val HEADER_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SubredditCreateButtonPatch$HeaderState;"

@Suppress("unused")
val subredditCreateButtonPatch = patch(
    name = "Create button in communities",
    description = "Adds an option to show a Create post button in the top bar of each community while the Create button of the navigation bar is hidden. This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        versionCheckPatch,
        hideNavigationButtonsPatch
    )

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Create button in communities' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        HeaderStateToStringFingerprint.let {
            mutableClassDefBy(it.classDef.type).apply {
                addPatchInterface(
                    HEADER_STATE_INTERFACE,
                    "patch_getDisplayName",
                    it.instructionMatches.last().fieldReference
                )
            }
        }

        val testTagMatch = SubredditToolbarSearchButtonFingerprint.instructionMatches[1]
        val drawButtonMatch = SubredditToolbarSearchButtonFingerprint.instructionMatches[2]
        val drawButtonRef = drawButtonMatch.methodReference

        val iconType = drawButtonRef.parameterTypes[0].toString()
        val modifierType = testTagMatch.methodReference.returnType.toString()
        val composerType = drawButtonRef.parameterTypes[8].toString()

        val toolbarClass = SubredditToolbarSearchButtonFingerprint.classDef
        val helperMethod = ImmutableMethod(
            toolbarClass.type,
            "patch_drawCreatePostButton",
            listOf(
                ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                ImmutableMethodParameter(modifierType, null, null),
                ImmutableMethodParameter(composerType, null, null),
                ImmutableMethodParameter("Z", null, null)
            ),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(14)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isEnabled()Z
                    move-result v0
                    if-nez v0, :enabled
                    return-void
                    :enabled
                    new-instance v0, $iconType
                    const-wide v1, 0x1400000014L
                    invoke-static { }, $EXTENSION_CLASS->getIcon()I
                    move-result v3
                    const/4 v4, 0x0
                    invoke-direct/range { v0 .. v4 }, $iconType-><init>(JIZ)V
                    invoke-static { }, $EXTENSION_CLASS->getLabel()Ljava/lang/String;
                    move-result-object v1
                    invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->getDescription(Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v2
                    move-object v3, v2
                    move/from16 v4, p3
                    invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->getClick(Ljava/lang/Object;)Lkotlin/jvm/functions/Function0;
                    move-result-object v5
                    move-object/from16 v6, p1
                    const/4 v7, 0x0
                    move-object/from16 v8, p2
                    const/high16 v9, 0x180000
                    invoke-static/range { v0 .. v9 }, $drawButtonRef
                    return-void
                """
            )
        }.also(toolbarClass.methods::add)

        val baseModifierReg = testTagMatch.instruction.registersUsed.first()
        val composerReg = drawButtonMatch.instruction.registersUsed[8]
        val isExpandedReg = drawButtonMatch.instruction.registersUsed[4]
        val insertIndex = drawButtonMatch.index + 1

        SubredditToolbarSearchButtonFingerprint.method.apply {
            val p0Reg = p0Register
            val needModReg = baseModifierReg >= 16
            val needComposerReg = composerReg >= 16
            val needExpandedReg = isExpandedReg >= 16
            val needed =
                1 +
                    (if (needModReg) 1 else 0) +
                    (if (needComposerReg) 1 else 0) +
                    (if (needExpandedReg) 1 else 0)
            val registerProvider = getFreeRegisterProvider(
                insertIndex,
                needed,
                p0Reg,
                baseModifierReg,
                composerReg,
                isExpandedReg
            )
            val stateReg = registerProvider.getFreeRegister4Bit()
            val modReg = if (needModReg) registerProvider.getFreeRegister4Bit() else baseModifierReg
            val compReg = if (needComposerReg) registerProvider.getFreeRegister4Bit() else composerReg
            val expandedReg = if (needExpandedReg) registerProvider.getFreeRegister4Bit() else isExpandedReg

            val smali = buildString {
                appendLine("move-object/from16 v$stateReg, p0")
                if (needModReg) {
                    appendLine("move-object/from16 v$modReg, v$baseModifierReg")
                }
                if (needComposerReg) {
                    appendLine("move-object/from16 v$compReg, v$composerReg")
                }
                if (needExpandedReg) {
                    appendLine("move/from16 v$expandedReg, v$isExpandedReg")
                }
                append("invoke-static { v$stateReg, v$modReg, v$compReg, v$expandedReg }, $helperMethod")
            }
            addInstructions(insertIndex, smali)
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
