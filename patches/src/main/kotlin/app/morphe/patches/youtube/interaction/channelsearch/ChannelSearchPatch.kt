/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ChannelSearchPatch;"

@Suppress("unused")
val channelSearchPatch = bytecodePatch(
    name = "Channel search",
    description = "Adds an option to search inside the channel that is currently open " +
            "instead of searching all of YouTube.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_channel_search", summary = true)
        )

        // Activity is used as the context of the result dialog.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        // A channel page browses by its channel id, which is what the search is scoped to.
        BrowseFragmentOnCreateViewFingerprint.let {
            it.method.apply {
                val browseDataIndex = it.instructionMatches.first().index

                // The endpoint of the page is read just before it is checked for browse data.
                val endpointIndex = indexOfFirstInstructionReversedOrThrow(
                    browseDataIndex,
                    fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this")
                )
                val endpointField = getInstruction<ReferenceInstruction>(endpointIndex)
                    .getReference<FieldReference>()!!

                // Browse data is an extension of the endpoint, and reading it starts with that field.
                val browseDataExtensionIndex = indexOfFirstInstructionOrThrow(
                    endpointIndex, Opcode.SGET_OBJECT
                )
                val browseDataExtensionField =
                    getInstruction<ReferenceInstruction>(browseDataExtensionIndex)
                        .getReference<FieldReference>()!!

                val browseIdMethod = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
                    returnType = "Ljava/lang/String;",
                    parameters = listOf(endpointField.type),
                    filters = listOf(
                        fieldAccess(opcode = Opcode.SGET_OBJECT, reference = browseDataExtensionField)
                    )
                ).method

                // The fragment is handed the endpoint of every page it shows, including a page that
                // is returned to and served from cache, which makes no browse request to read.
                Fingerprint(
                    definingClass = definingClass,
                    returnType = "V",
                    parameters = listOf(endpointField.type),
                    filters = listOf(
                        fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = endpointField)
                    )
                ).method.apply {
                    val insertIndex = indexOfFirstInstructionOrThrow(
                        fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = endpointField)
                    ) + 1
                    val freeRegister = findFreeRegister(insertIndex)

                    addInstructions(
                        insertIndex,
                        """
                            invoke-static { p1 }, $browseIdMethod
                            move-result-object v$freeRegister
                            invoke-static { v$freeRegister }, $EXTENSION_CLASS->setBrowseId(Ljava/lang/String;)V
                        """
                    )
                }
            }
        }

        // Leaving a channel for the search feed shows no browse page, so nothing would
        // otherwise replace the browse id of the channel.
        SearchResultsFragmentOnCreateViewFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->clearBrowseId()V"
        )

        // The search box otherwise still reads as a search of all of YouTube.
        // Only the default hint is replaced, not the hint of Shorts or playlist search.
        SearchBoxHintFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getSearchHint(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        SearchSubmitFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->searchInChannel(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :search_all_of_youtube
                return-void
                :search_all_of_youtube
                nop
            """
        )
    }
}
