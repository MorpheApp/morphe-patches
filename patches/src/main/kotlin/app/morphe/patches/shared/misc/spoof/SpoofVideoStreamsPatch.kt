/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.media.mediaFetchPlayerConfigPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch;"

private val spoofVideoStreamsResourcePatch = resourcePatch {
    execute {
        // region copy the ejs wrapper.

        copyResources(
            "spoof",
            ResourceGroup(
                "raw",
                "astring-1.9.0.min.js",
                "meriyah-6.1.4.min.js",
                "polyfill.js",
                "yt.solver.core.js", // yt-dlp-ejs 0.8.0: https://github.com/yt-dlp/ejs/releases/tag/0.8.0
                "yt.solver.wrapper.js",
            )
        )

        // endregion
    }
}

internal fun spoofVideoStreamsPatch(
    extensionClass: String,
    mainActivityOnCreateFingerprint: Fingerprint,
    fixMediaFetchHotConfigAlternative: BytecodePatchBuilder.() -> Boolean,
    fixParsePlaybackResponseFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    fixMediaSessionFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    fixReelItemWatchResponseFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    useNewRequestBuilderFingerprint: BytecodePatchBuilder.() -> Boolean,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {},
) = bytecodePatch(
    name = "Spoof video streams",
    description = "Adds options to spoof the client video streams to fix playback."
) {
    block()

    dependsOn(
        fixProtoLibraryPatch,
        spoofVideoStreamsResourcePatch,
        mediaFetchPlayerConfigPatch(
            extensionClass = EXTENSION_CLASS,
            hasMediaSessionFeatureFlag = fixMediaSessionFeatureFlag,
            highPriority = true
        )
    )

    execute {
        mainActivityOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setMainActivity(Landroid/app/Activity;)V       
                invoke-static { }, $extensionClass->setClientOrderToUse()V   
            """
        )

        // region Enable extension helper method used by other patches

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // endregion

        // region Block /initplayback requests to fall back to /get_watch requests.


        BuildInitPlaybackRequestFingerprint.let {
            it.method.apply {
                val moveUriStringIndex = it.instructionMatches.first().index
                val targetRegister = getInstruction<OneRegisterInstruction>(moveUriStringIndex).registerA

                addInstructions(
                    moveUriStringIndex + 1,
                    """
                        invoke-static { v$targetRegister }, $EXTENSION_CLASS->blockInitPlaybackRequest(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$targetRegister
                    """
                )
            }
        }

        // endregion

        // region Block /get_watch requests to fall back to /player requests.

        if (useNewRequestBuilderFingerprint()) {
            BuildPlayerRequestURIBuilderFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        $$"""
                            invoke-static { v$$register }, $$EXTENSION_CLASS->blockGetWatchRequest(Landroid/net/Uri$Builder;)Landroid/net/Uri$Builder;
                            move-result-object v$$register
                        """
                    )
                }
            }
        } else {
            BuildPlayerRequestURIFingerprint.let {
                it.method.apply {
                    val invokeToStringIndex = it.instructionMatches.first().index
                    val uriRegister = getInstruction<FiveRegisterInstruction>(invokeToStringIndex).registerC

                    addInstructions(
                        invokeToStringIndex,
                        """
                            invoke-static { v$uriRegister }, $EXTENSION_CLASS->blockGetWatchRequest(Landroid/net/Uri;)Landroid/net/Uri;
                            move-result-object v$uriRegister
                        """
                    )
                }
            }
        }

        // endregion

        // region Get replacement streams at player requests.

        BuildRequestFingerprint.method.apply {
            val newRequestBuilderIndex = BuildRequestFingerprint.instructionMatches.first().index
            val buildRequestMethodURLRegister = getInstruction<FiveRegisterInstruction>(newRequestBuilderIndex).registerD
            val freeRegister = findFreeRegister(newRequestBuilderIndex, buildRequestMethodURLRegister)

            addInstructions(
                newRequestBuilderIndex,
                """
                    move-object v$freeRegister, p1
                    invoke-static { v$buildRequestMethodURLRegister, v$freeRegister }, $EXTENSION_CLASS->fetchStreams(Ljava/lang/String;Ljava/util/Map;)V
                """
            )
        }

        // endregion

        // region Replace the streaming data with the replacement streams.

        CreateStreamingDataFingerprint.let {
            it.method.apply {
                val videoDetailsMatch = it.instructionMatches[5]
                val videoDetailsIndex = videoDetailsMatch.index
                val videoDetailsRegister = getInstruction<TwoRegisterInstruction>(videoDetailsIndex).registerA
                val videoDetailsClass = videoDetailsMatch.instruction.getReference<FieldReference>()!!.type

                val playerProtoClass = parameterTypes.first().toString()
                val getStreamingDataField = it.instructionMatches.first().instruction.getReference<FieldReference>()!!
                val setStreamingDataField = it.instructionMatches[1].instruction.getReference<FieldReference>()!!
                val setPlayerConfigField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!
                val playerConfigClass = setPlayerConfigField.type
                val mediaCommonConfigField = abrStateDataFingerprint(playerConfigClass)
                    .instructionMatches[1].instruction.getReference<FieldReference>()!!

                val (createBuilderMethod, mergeFromMethod) =
                    with(PlayerConfigBuilderFingerprint) {
                        Pair(
                            instructionMatches[2].instruction.getReference<MethodReference>()!!,
                            instructionMatches[4].instruction.getReference<MethodReference>()!!,
                        )
                    }

                val (castReference, buildMethod) =
                    with(PlayerConfigBuilderFingerprint) {
                        Pair(
                            instructionMatches[5].instruction.getReference<TypeReference>()!!,
                            instructionMatches[6].instruction.getReference<MethodReference>()!!
                        )
                    }

                val castInstruction = if (castReference.type != buildMethod.definingClass) """
                    check-cast v4, $castReference
                """ else """
                    nop
                """

                val helperMethod = ImmutableMethod(
                    it.classDef.type,
                    "patch_setStreamingData",
                    listOf(
                        ImmutableMethodParameter(videoDetailsClass, null, null),
                        ImmutableMethodParameter(playerProtoClass, null, null)
                    ),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(11),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $EXTENSION_CLASS->isSpoofingEnabled()Z
                            move-result v0
                            if-eqz v0, :disabled
    
                            # Get video ID.
                            iget-object v2, p1, $videoDetailsClass->c:Ljava/lang/String;
                            if-eqz v2, :disabled
    
                            # Get streaming data.
                            invoke-static { v2 }, $EXTENSION_CLASS->getStreamingData(Ljava/lang/String;)[B
                            move-result-object v3
                            if-eqz v3, :disabled
    
                            # Parse streaming data.
                            sget-object v4, $playerProtoClass->a:$playerProtoClass
                            invoke-static { v4, v3 }, ${parseByteArrayMethodRef.get()!!}
                            move-result-object v5
                            check-cast v5, $playerProtoClass
    
                            # Set streaming data.
                            iget-object v6, v5, $getStreamingDataField
                            if-eqz v6, :disabled
                            iput-object v6, p0, $setStreamingDataField

                            # Get player config.
                            invoke-static { v2 }, $EXTENSION_CLASS->getPlayerConfig(Ljava/lang/String;)[B
                            move-result-object v3
                            if-eqz v3, :disabled
                            sget-object v4, $playerConfigClass->a:$playerConfigClass
                            invoke-virtual { v4 }, $createBuilderMethod
                            move-result-object v4
                            $castInstruction
                            invoke-static { }, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
                            move-result-object v5
                            invoke-virtual { v4, v3, v5 }, $mergeFromMethod
                            move-result-object v5
                            check-cast v5, $castReference
                            invoke-virtual { v5 }, $buildMethod
                            move-result-object v5
                            check-cast v5, $playerConfigClass
                            iget-object v6, v5, $mediaCommonConfigField
                            if-eqz v6, :disabled

                            # Set media common config.
                            iget-object v5, p2, $setPlayerConfigField
                            iput-object v6, v5, $mediaCommonConfigField
                            iput-object v5, p2, $setPlayerConfigField

                            :disabled
                            return-void
                        """
                    )
                }

                it.classDef.methods.add(helperMethod)

                addInstruction(
                    videoDetailsIndex + 1,
                    "invoke-direct { p0, v$videoDetailsRegister, p1 }, $helperMethod"
                )
            }
        }

        // endregion

        // region block getAtt request

        BuildRequestFingerprint.let {
            it.method.apply {
                val insertIndex = indexOfNewUrlRequestBuilderInstruction(this)
                val register = it.instructionMatches.first()
                    .getInstruction<FiveRegisterInstruction>().registerD

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->blockGetAttRequest(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        // endregion

        // region Remove video playback request body to fix playback.
        // It is assumed, YouTube makes a request with a body tuned for Android.
        // Requesting streams intended for other platforms with a body tuned for Android could be the cause of 400 errors.
        // A proper fix may include modifying the request body to match the platforms expected body.

        BuildMediaDataSourceFingerprint.method.apply {
            val targetIndex =
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

            // Instructions are added just before the method returns,
            // so there's no concern of clobbering in-use registers.
            addInstructions(
                targetIndex,
                """
                    # Field a: Stream uri.
                    # Field c: Http method.
                    # Field d: Post data.
                    move-object v0, p0  # method has over 15 registers and must copy p0 to a lower register.
                    iget-object v1, v0, $definingClass->a:Landroid/net/Uri;
                    iget v2, v0, $definingClass->c:I
                    iget-object v3, v0, $definingClass->d:[B
                    invoke-static { v1, v2, v3 }, $EXTENSION_CLASS->removeVideoPlaybackPostBody(Landroid/net/Uri;I[B)[B
                    move-result-object v1
                    iput-object v1, v0, $definingClass->d:[B
                """
            )
        }

        // endregion

        // region Append spoof info.

        NerdsStatsVideoFormatBuilderFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->appendSpoofedClient(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        // endregion

        // region Fix iOS livestream current time.

        HlsCurrentTimeFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->fixHLSCurrentTime(Z)Z"
            )
        }

        // endregion

        // region Disable SABR playback.
        // If SABR is disabled, it seems 'MediaFetchHotConfig' may no longer need an override (not confirmed).

        with(MediaFetchEnumConstructorFingerprint.method) {
            val sabrFieldIndex = indexOfFirstInstructionOrThrow(
                MediaFetchEnumConstructorFingerprint.stringMatches.last().index,
                fieldAccess(opcode = Opcode.SPUT_OBJECT, type = definingClass)
            )
            val sabrFieldReference = getInstruction<ReferenceInstruction>(sabrFieldIndex)
                .reference as FieldReference

            Fingerprint(
                returnType = definingClass,
                filters = opcodesToFilters(
                    Opcode.SGET_OBJECT,
                    Opcode.RETURN_OBJECT,
                ),
                custom = { method, _ ->
                    !method.parameterTypes.isEmpty()
                }
            ).method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->disableSABR()Z
                    move-result v0
                    if-eqz v0, :ignore
                    sget-object v0, $sabrFieldReference
                    return-object v0
                    :ignore
                    nop
                """
            )
        }

        //endregion

        // region turn off stream config replacement feature flag.

        if (fixMediaFetchHotConfigAlternative()) {
            MediaFetchHotConfigAlternativeFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->useMediaFetchHotConfigReplacement(Z)Z"
                )
            }
        }

        if (fixParsePlaybackResponseFeatureFlag()) {
            PlaybackStartDescriptorFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->usePlaybackStartFeatureFlag(Z)Z"
                )
            }
        }

        if (fixReelItemWatchResponseFeatureFlag()) {
            ReelItemWatchResponseFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->useReelItemWatchResponseFeatureFlag(Z)Z"
                )
            }
        }

        // endregion

        executeBlock()
    }
}
