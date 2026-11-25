/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5022
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.postbuttons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_37_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_39_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HidePostButtonsPatch;"

private const val EXTENSION_HIDEABLE_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HidePostButtonsPatch$HideableState;"

private const val EXTENSION_LABELED_ACTION_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HidePostButtonsPatch$LabeledAction;"

@Suppress("unused")
val hidePostButtonsPatch = patch(
    name = "Hide post buttons",
    description = "Adds options to hide the buttons of posts in the feeds, the post detail screen " +
            "and the media viewer, such as the Join, vote, comment, award, repost and share buttons. " +
            "Hiding the buttons below posts works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        val hideJoinButtonCondition = "invoke-static { }, $EXTENSION_CLASS->hideJoinButton()Z"

        if (is_2026_37_0_or_greater) {
            PostJoinButtonFingerprint.method.returnEarlyIf(hideJoinButtonCondition)
        } else {
            PostJoinButtonLegacyFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val hideTarget = getInstruction<BuilderInstruction21t>(index).target.location.instruction!!
                    val register = findFreeRegister(index)

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            $hideJoinButtonCondition
                            move-result v$register
                            if-nez v$register, :hide
                        """,
                        ExternalLabel("hide", hideTarget)
                    )
                }
            }
        }

        if (is_2026_39_0_or_greater) {
            PostDetailJoinButtonFingerprint.method.returnEarlyIf(hideJoinButtonCondition)
        } else if (is_2026_24_0_or_greater) {
            PostDetailJoinButtonLegacyFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = findFreeRegister(index)

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            $hideJoinButtonCondition
                            move-result v$register
                            if-nez v$register, :hide
                        """,
                        ExternalLabel("hide", getInstruction(index + 1))
                    )
                }
            }
        }

        CommentAwardButtonFingerprint.method.returnEarlyIf(
            "invoke-static { }, $EXTENSION_CLASS->hideCommentAwardButton()Z"
        )

        if (!is_2026_24_0_or_greater) {
            Logger.getLogger(this::class.java.name).warning(
                "Hiding the buttons below posts requires Reddit 2026.24.0+"
            )
        } else {
            hideActionBarButtons()
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

/**
 * Returns at the start of a composable that returns void, if the extension method called by
 * [condition] returns true.
 */
private fun MutableMethod.returnEarlyIf(condition: String) {
    val register = findFreeRegister(0)
    addInstructionsWithLabels(
        0,
        """
            $condition
            move-result v$register
            if-eqz v$register, :show
            return-void
            :show
            nop
        """
    )
}

private fun hideActionBarButtons() {
    // Hidden vote and comment buttons are marked on their states, as the action bar
    // and its buttons are also used by comments.
    fun MutableClass.addHiddenState() {
        val field = ImmutableField(
            type,
            "patch_hidden",
            "Z",
            AccessFlags.PUBLIC.value,
            null,
            null,
            null
        ).toMutable()
        fields.add(field)

        addPatchInterface(EXTENSION_HIDEABLE_STATE_INTERFACE, "patch_isHidden", field)
        addPatchInterface(EXTENSION_HIDEABLE_STATE_INTERFACE, "patch_setHidden", field)
    }

    ActionBarActionToStringFingerprint.classDef.addHiddenState()
    ActionBarVoteStateToStringFingerprint.classDef.addHiddenState()

    hideCounts()

    val buttonFingerprint: Fingerprint
    val voteButtonsFingerprint: Fingerprint
    if (is_2026_37_0_or_greater) {
        buttonFingerprint = ActionBarButtonFingerprint
        voteButtonsFingerprint = ActionBarVoteButtonsFingerprint
    } else {
        buttonFingerprint = ActionBarButtonLegacyFingerprint
        voteButtonsFingerprint = ActionBarVoteButtonsLegacyFingerprint
    }

    // Don't show the action bar buttons whose states are marked as hidden.
    listOf(buttonFingerprint, voteButtonsFingerprint).forEach { fingerprint ->
        fingerprint.method.returnEarlyIf(
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->isHidden(Ljava/lang/Object;)Z"
        )
    }

    // Buttons on the side of posts in the media viewer, which are not an action bar.
    // Button parameters: icon, on click, content description, count test tag.
    MediaViewerRailFingerprint.instructionMatches[2].getMethodCalled().returnEarlyIf(
        "invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->hideMediaViewerButton(Ljava/lang/String;)Z"
    )
    MediaViewerRailVoteButtonsFingerprint.method.returnEarlyIf(
        "invoke-static { }, $EXTENSION_CLASS->hideMediaViewerVoteButtons()Z"
    )

    // The action bars of posts.
    listOf(
        PostFooterFingerprint,
        LinkFooterActionBarFingerprint,
        PostDetailActionBarFingerprint,
        MediaViewerActionBarFingerprint
    ).forEach {
        it.method.apply {
            val actionBarIndex = it.instructionMatches.last().index
            val actionBarInstruction = getInstruction(actionBarIndex)
            val registers = actionBarInstruction.registersUsed
            val actionType = ActionBarActionToStringFingerprint.originalClassDef.type

            // Parameters: vote state, comment button, modifier, appearance, then the buttons that
            // are null when not shown: the award button before 2026.39.0, and the repost, share
            // and mod buttons. The registers are consecutive, as the call uses a register range.
            val voteRegister = registers[0]
            val commentRegister = registers[1]
            val firstButtonIndex = 4
            val repostIndex = if (is_2026_39_0_or_greater) firstButtonIndex else firstButtonIndex + 1
            val modIndex = repostIndex + 2

            fun hideButton(index: Int, extensionMethod: String, type: String): String {
                val register = registers[index]
                return """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->$extensionMethod(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, $type
                """
            }

            val legacyAwardButton = if (is_2026_39_0_or_greater) {
                ""
            } else {
                hideButton(firstButtonIndex, "getAwardButton", "Lkotlin/jvm/functions/Function2;")
            }

            // Award button of the refreshed action bar, after the mod button.
            val legacyRefreshAwardButton = if (is_2026_37_0_or_greater && !is_2026_39_0_or_greater) {
                val awardIndex = modIndex + 1
                val awardType = it.instructionMatches.last().methodReference.parameterTypes[awardIndex]
                hideButton(awardIndex, "getAwardButton", awardType.toString())
            } else {
                ""
            }

            // Takes the buttons that can be null, from the first to the last.
            val hideActionBarParameters = "Ljava/lang/Object;".repeat(modIndex - firstButtonIndex + 1)

            val freeRegister = findFreeRegister(actionBarIndex, registers)

            // Mark the hidden vote and comment buttons, remove the other hidden buttons,
            // and don't show the action bar if all of its buttons are hidden.
            addInstructionsAtControlFlowLabel(
                actionBarIndex,
                """
                    invoke-static/range { v$voteRegister .. v$voteRegister }, $EXTENSION_CLASS->setVoteButtonsHidden(Ljava/lang/Object;)V
                    invoke-static/range { v$commentRegister .. v$commentRegister }, $EXTENSION_CLASS->setCommentButtonHidden(Ljava/lang/Object;)V
                    $legacyAwardButton
                    $legacyRefreshAwardButton
                    ${hideButton(repostIndex, "getRepostButton", actionType)}
                    ${hideButton(repostIndex + 1, "getShareButton", actionType)}
                    ${hideButton(modIndex, "getModButton", actionType)}
                    invoke-static/range { v${registers[firstButtonIndex]} .. v${registers[modIndex]} }, $EXTENSION_CLASS->hideActionBar($hideActionBarParameters)Z
                    move-result v$freeRegister
                    if-nez v$freeRegister, :hide_action_bar
                """,
                ExternalLabel("hide_action_bar", getInstruction(actionBarIndex + 1))
            )
        }
    }

    Fingerprint(
        definingClass = EXTENSION_CLASS,
        name = "isActionBarIncluded"
    ).method.returnEarly(true)
}

private fun hideCounts() {
    val labelField = ActionBarActionLabelFingerprint.instructionMatches.last().fieldReference
    ActionBarActionToStringFingerprint.classDef.addPatchInterface(
        EXTENSION_LABELED_ACTION_INTERFACE,
        "patch_clearLabel",
        """
            const/4 v0, 0x0
            iput-object v0, p0, $labelField
            return-void
        """
    )

    ActionBarVoteStateConstructorFingerprint.let {
        it.method.apply {
            val index = it.instructionMatches.first().index
            val register = getInstruction(index).registersUsed[0]
            addInstructions(
                index,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->hideScore(Z)Z
                    move-result v$register
                """
            )
        }
    }

    CommentScoreHiddenFingerprint.method.apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
            val register = getInstruction<OneRegisterInstruction>(index).registerA

            addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->hideScore(Z)Z
                    move-result v$register
                """
            )
        }
    }
}
