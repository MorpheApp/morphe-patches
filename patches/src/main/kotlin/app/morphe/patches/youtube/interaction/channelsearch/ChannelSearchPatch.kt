/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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
        val browseIdField = BrowseIdTraceFingerprint.instructionMatches.last().getFieldAccessed()

        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf("Ljava/lang/String;"),
            filters = listOf(
                fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = browseIdField)
            )
        ).method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->setBrowseId(Ljava/lang/String;)V"
        )

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
