/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_25_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_37_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideNavigationButtonsPatch;"

private const val EXTENSION_HEADER_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideNavigationButtonsPatch$NavigationButtonInterface;"

private const val EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS =
    "Lapp/morphe/extension/reddit/patches/SubredditCreateButtonPatch;"

private const val EXTENSION_HEADER_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SubredditCreateButtonPatch$HeaderState;"

@Suppress("unused")
val hideNavigationButtonsPatch = patch(
    name = "Hide navigation buttons",
    description = "Adds options to hide buttons in the navigation bar, " +
            "and to show a Create post button in communities when the Create button is hidden."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        // region legacy method
        if (!is_2026_25_0_or_greater) {
            val navigationButtonInnerMethod =
                BottomNavScreenResourceBuilderLegacyFingerprint.instructionMatches[0]
                    .methodReference

            mutableClassDefBy(navigationButtonInnerMethod.definingClass).apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                // Add methods to access obfuscated navigation button fields.
                val labelField = fields.single { field ->
                    field.type == "Ljava/lang/String;"
                }
                addPatchInterface(EXTENSION_HEADER_ITEM_INTERFACE, "patch_getLabel", labelField)
            }

            BottomNavScreenResourceBuilderLegacyFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(ADD_METHOD_CALL).forEach { index ->
                    val instruction = getInstruction<FiveRegisterInstruction>(index)

                    val listRegister = instruction.registerC
                    val objectRegister = instruction.registerD

                    replaceInstruction(
                        index,
                        "invoke-static { v$listRegister, v$objectRegister }, " +
                                "$EXTENSION_CLASS->hideNavigationButtonsLegacy(Ljava/util/List;Ljava/lang/Object;)V"
                    )
                }
            }
        }

        // endregion

        // region modern method

        BottomNavScreenListBuilderFingerprint.let {
            it.method.apply {
                val enumIndex = it.instructionMatches.last().index
                val enumRegister = getInstruction<TwoRegisterInstruction>(enumIndex).registerA
                val freeRegister = findFreeRegister(enumIndex, enumRegister)
                val jumpInstructionIndex = it.instructionMatches[1].index
                val jumpInstruction = getInstruction(jumpInstructionIndex)

                addInstructionsWithLabels(
                    enumIndex + 1,
                    """
                        invoke-static { v$enumRegister }, $EXTENSION_CLASS->hideNavigationTab(Ljava/lang/Enum;)Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :jump
                    """,
                    ExternalLabel("jump", jumpInstruction)
                )
            }
        }

        // endregion

        // region Create button in communities

        if (is_2026_37_0_or_greater) {
            SubredditHeaderStateToStringFingerprint.let {
                it.classDef.addPatchInterface(
                    EXTENSION_HEADER_STATE_INTERFACE,
                    "patch_getDisplayName",
                    it.instructionMatches.last().fieldReference
                )
            }

            SubredditToolbarFingerprint.let {
                val testTagMatch = it.instructionMatches[1]
                val iconButtonMatch = it.instructionMatches[2]
                val iconButtonMethod = iconButtonMatch.methodReference
                val iconType = iconButtonMethod.parameterTypes[0].toString()

                // Draws the Create button with the same composable as the Search button.
                // 0x1400000014 is the packed 20x20 dp size the app uses for top bar icons.
                // 0x180000 are the changed flags the app passes for the Search button.
                val drawCreateButtonMethod = ImmutableMethod(
                    it.classDef.type,
                    "patch_drawCreateButton",
                    listOf(
                        ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                        ImmutableMethodParameter(testTagMatch.methodReference.returnType, null, null),
                        ImmutableMethodParameter(iconButtonMethod.parameterTypes[8].toString(), null, null),
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
                            invoke-static { }, $EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS->isEnabled()Z
                            move-result v0
                            if-eqz v0, :disabled
                            new-instance v0, $iconType
                            const-wide v1, 0x1400000014L
                            invoke-static { }, $EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS->getIcon()I
                            move-result v3
                            const/4 v4, 0x0
                            invoke-direct/range { v0 .. v4 }, $iconType-><init>(JIZ)V
                            invoke-static { }, $EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS->getLabel()Ljava/lang/String;
                            move-result-object v1
                            invoke-static/range { p0 .. p0 }, $EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS->getDescription(Ljava/lang/Object;)Ljava/lang/String;
                            move-result-object v2
                            move-object v3, v2
                            move/from16 v4, p3
                            invoke-static/range { p0 .. p0 }, $EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS->getClick(Ljava/lang/Object;)Lkotlin/jvm/functions/Function0;
                            move-result-object v5
                            move-object/from16 v6, p1
                            const/4 v7, 0x0
                            move-object/from16 v8, p2
                            const/high16 v9, 0x180000
                            invoke-static/range { v0 .. v9 }, $iconButtonMethod
                            :disabled
                            return-void
                        """
                    )
                }.also(it.classDef.methods::add)

                it.method.apply {
                    val insertIndex = iconButtonMatch.index + 1
                    // Modifier before the test tag of the Search button is applied.
                    val modifierRegister = testTagMatch.instruction.registersUsed[0]
                    val expandedRegister = iconButtonMatch.instruction.registersUsed[4]
                    val composerRegister = iconButtonMatch.instruction.registersUsed[8]

                    val registerProvider = getFreeRegisterProvider(
                        insertIndex,
                        4,
                        p0Register,
                        modifierRegister,
                        expandedRegister,
                        composerRegister
                    )
                    val freeStateRegister = registerProvider.getFreeRegister4Bit()
                    val freeModifierRegister = registerProvider.getFreeRegister4Bit()
                    val freeComposerRegister = registerProvider.getFreeRegister4Bit()
                    val freeExpandedRegister = registerProvider.getFreeRegister4Bit()

                    addInstructions(
                        insertIndex,
                        """
                            move-object/from16 v$freeStateRegister, p0
                            move-object/from16 v$freeModifierRegister, v$modifierRegister
                            move-object/from16 v$freeComposerRegister, v$composerRegister
                            move/from16 v$freeExpandedRegister, v$expandedRegister
                            invoke-static { v$freeStateRegister, v$freeModifierRegister, v$freeComposerRegister, v$freeExpandedRegister }, $drawCreateButtonMethod
                        """
                    )
                }
            }

            setExtensionIsPatchIncluded(EXTENSION_SUBREDDIT_CREATE_BUTTON_CLASS)
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
