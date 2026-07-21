/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1994
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.returnyoutubedislike

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.layout.returnyoutubedislike.DislikeFingerprint
import app.morphe.patches.shared.layout.returnyoutubedislike.EndpointServiceNameFingerprint
import app.morphe.patches.shared.layout.returnyoutubedislike.likeEndpointParserFingerprint
import app.morphe.patches.shared.layout.returnyoutubedislike.requestParameterCheckFingerprint
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookSpannableString
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private val returnYouTubeDislikeResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.RETURN_YOUTUBE_DISLIKE.addPreferences(
            SwitchPreference("morphe_ryd_enabled"),
            SwitchPreference("morphe_ryd_dislike_percentage", summary = true),
            SwitchPreference("morphe_ryd_compact_layout", summary = true),
            SwitchPreference("morphe_ryd_estimated_like", summary = true),
            SwitchPreference("morphe_ryd_toast_on_connection_error", summary = true),
            NonInteractivePreference(
                key = "morphe_ryd_attribution",
                tag = "app.morphe.extension.shared.returnyoutubedislike.ui.ReturnYouTubeDislikeAboutPreference",
                selectable = true,
            ),
            PreferenceCategory(
                key = "morphe_ryd_statistics_category",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = emptySet(), // Preferences are added by custom class at runtime.
                tag = "app.morphe.extension.shared.returnyoutubedislike.ui.ReturnYouTubeDislikeDebugStatsPreferenceCategory"
            )
        )
    }
}

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/ReturnYouTubeDislikePatch;"

@Suppress("unused")
val returnYouTubeDislikePatch = bytecodePatch(
    name = "Return YouTube Dislike",
    description = "Adds an option to show the dislike count of tracks with Return YouTube Dislike.",
) {
    dependsOn(
        returnYouTubeDislikeResourcePatch,
        settingsPatch,
        musicVideoInformationPatch,
        textComponentPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // region Hook like/dislike/remove like button clicks to send votes to the API.

        val endPointServiceNameField = EndpointServiceNameFingerprint
            .instructionMatches.last().instruction.getReference<FieldReference>()!!
        val likeEndpointParserClass = DislikeFingerprint.classDef.superclass!!
        val videoIdField = requestParameterCheckFingerprint(likeEndpointParserClass)
            .instructionMatches.last().instruction.getReference<FieldReference>()!!

        likeEndpointParserFingerprint(likeEndpointParserClass).let {
            it.method.apply {
                val insertIndex = it.instructionMatches[1].index + 1
                val likeEndpointTargetClassRegister =
                    getInstruction<TwoRegisterInstruction>(insertIndex - 1).registerA
                val registerProvider = getFreeRegisterProvider(
                    insertIndex, 2, likeEndpointTargetClassRegister
                )
                val endPointServiceNameRegister = registerProvider.getFreeRegister()
                val videoIdRegister = registerProvider.getFreeRegister()

                addInstructions(
                    insertIndex,
                    """
                        iget-object v$endPointServiceNameRegister, p0, $endPointServiceNameField
                        iget-object v$videoIdRegister, v$likeEndpointTargetClassRegister, $videoIdField
                        invoke-static { v$endPointServiceNameRegister, v$videoIdRegister }, $EXTENSION_CLASS->sendVote(Ljava/lang/String;Ljava/lang/String;)V
                    """
                )
            }
        }

        // endregion

        // region Hook code for creation and cached lookup of text Spans.

        hookSpannableString(
            classDescriptor = EXTENSION_CLASS,
            overrideSpan = true
        )

        // endregion

        // region Inject newVideoLoaded event handler to update dislikes when a new track is loaded.

        musicVideoIdHook("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")

        // endregion
    }
}
