/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.returnyoutubedislike

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.layout.returnyoutubedislike.DislikeFingerprint
import app.morphe.patches.shared.layout.returnyoutubedislike.EndpointServiceNameFingerprint
import app.morphe.patches.shared.layout.returnyoutubedislike.likeEndpointParserFingerprint
import app.morphe.patches.shared.layout.returnyoutubedislike.requestParameterCheckFingerprint
import app.morphe.patches.shared.misc.litho.context.EXTENSION_CONTEXT_INTERFACE
import app.morphe.patches.shared.misc.litho.context.conversionContextClassDef
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.videoactionbar.restoreOldVideoActionBarPatch
import app.morphe.patches.youtube.misc.litho.context.conversionContextPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.videoid.hookPlayerResponseVideoId
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.numberOfParameterRegistersLogical
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ReturnYouTubeDislikePatch;"

val returnYouTubeDislikePatch = bytecodePatch(
    name = "Return YouTube Dislike",
    description = "Adds an option to show the dislike count of videos with Return YouTube Dislike.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        conversionContextPatch,
        videoIdPatch,
        playerTypeHookPatch,
        restoreOldVideoActionBarPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

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

        // region Inject newVideoLoaded event handler to update dislikes when a new video is loaded.

        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")

        // Hook the player response video ID, to start loading RYD sooner in the background.
        hookPlayerResponseVideoId("$EXTENSION_CLASS->preloadVideoId(Ljava/lang/String;Z)V")

        // endregion

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

        // Verify stubbed classes are not obfuscated.
        classDefBy("Lcom/facebook/litho/ComponentHost;")
        classDefBy("Lcom/facebook/litho/TextContent;")
        classDefBy("Lcom/facebook/yoga/YogaNative;")

        // region Hook code for creation and cached lookup of text Spans.

        // Alternatively the hook can be made in the creation of Spans in TextComponentSpec.
        // And it works in all situations except if the likes do not such as disliking.
        // This hook handles all situations, as it's where the created Spans are stored and later reused.

        // Find the field name of the conversion context.
        val textComponentConversionContextField = TextComponentConstructorFingerprint
            .originalClassDef.fields.find {
                it.type == conversionContextClassDef.type
            } ?: throw PatchException("Could not find conversion context field")

        // Old pre 20.40 and lower hook.
        TextComponentLookupFingerprint.let {
            // 21.05 clobbers p0 (this) register.
            // Add additional registers so all parameters including p0 are free to use anywhere in the method.
            it.method.cloneParameters().apply {
                // Find the instruction for creating the text data object.
                val insertIndex = indexOfFirstInstructionOrThrow(
                    newInstance(TextComponentDataFingerprint.originalClassDef.type)
                )
                val charSequenceIndex = indexOfFirstInstructionOrThrow(
                    insertIndex,
                    fieldAccess(
                        opcode = Opcode.IPUT_OBJECT,
                        type = "Ljava/lang/CharSequence;"
                    )
                )
                val charSequenceRegister = getInstruction<TwoRegisterInstruction>(charSequenceIndex).registerA
                val conversionContext = findFreeRegister(insertIndex, charSequenceRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        # Copy conversion context.
                        move-object/from16 v$conversionContext, p0
                        iget-object v$conversionContext, v$conversionContext, $textComponentConversionContextField
                        invoke-static { v$conversionContext, v$charSequenceRegister }, $EXTENSION_CLASS->onLithoTextLoaded(${EXTENSION_CONTEXT_INTERFACE}Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                        move-result-object v$charSequenceRegister
                        
                        :ignore
                        nop
                    """
                )
            }
        }

        // Hook new litho text creation code.
        TextComponentFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useNewLithoTextCreation(Z)Z"
            )
        }

        LithoSpannableStringCreationFingerprint.let {
            val conversionContextField = it.classDef.type +
                    "->" + textComponentConversionContextField.name +
                    ":" + textComponentConversionContextField.type

            // 21.05+ clobbers p0 and must clone to preserve it.
            it.method.cloneParameters().apply {
                // Must offset match indexes since cloning adds additional move instructions.
                val insertIndex = it.instructionMatches[1].index + numberOfParameterRegistersLogical
                val charSequenceRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerD
                val conversionContextRegister = findFreeRegister(insertIndex, charSequenceRegister)

                addInstructions(
                    insertIndex,
                    """
                        move-object/from16 v$conversionContextRegister, p0
                        iget-object v$conversionContextRegister, v$conversionContextRegister, $conversionContextField
                        invoke-static { v$conversionContextRegister, v$charSequenceRegister }, $EXTENSION_CLASS->onLithoTextLoaded(${EXTENSION_CONTEXT_INTERFACE}Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                        move-result-object v$charSequenceRegister
                    """
                )
            }
        }

        // endregion

        // The compact action bar has icon only like and dislike buttons with no text to hook,
        // so the counts are drawn over the button host views, which are found by their view tag.
        ComponentHostSetContentDescriptionFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->onComponentHostContentDescription(Lcom/facebook/litho/ComponentHost;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object p1
            """
        )

        // The dislike icon is given a margin so the button wrapping it grows and leaves room for
        // the count. A width is no use, since the layout stretches the icon over whatever it gets.
        //
        // The only long of the node is the pointer to its native counterpart.

        // The method reads the same pointer itself, so it always has the two locals this needs.
        YogaSetWidthFingerprint.let {
            val register = it.instructionMatches.last().getInstruction<FiveRegisterInstruction>().registerC
            it.method.addInstruction(
                it.instructionMatches.last().index,
                "invoke-static { v$register, v${register + 1}, p1 }, $EXTENSION_CLASS->onYogaSetWidth(JF)V"
            )
        }
    }
}
