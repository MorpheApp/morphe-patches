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
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.VideoStreamingDataConstructorFingerprint
import app.morphe.patches.shared.VideoStreamingDataToStringFingerprint
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.request.buildRequestPatch
import app.morphe.patches.shared.misc.request.hookBuildRequest
import app.morphe.patches.shared.misc.request.hookMediaRequest
import app.morphe.patches.shared.misc.request.hookMediaResponseChunk
import app.morphe.patches.shared.misc.request.mediaRequestPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch;"

internal fun addMediaSessionOverride(extension: String) {
    MediaSessionFeatureFlagFingerprint.matchAll().forEach {
        it.method.insertLiteralOverride(
            it.instructionMatches.first().index,
            extension
        )
    }
}

private val spoofVideoStreamsResourcePatch = patch {
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
                "po_token.html",
            )
        )

        // endregion
    }
}

internal fun spoofVideoStreamsPatch(
    extensionClass: String,
    mainActivityOnCreateFingerprints: List<Fingerprint>,
    fixParsePlaybackResponseFeatureFlag: PatchDefinition.() -> Boolean,
    fixMediaSessionFeatureFlag: PatchDefinition.() -> Boolean,
    fixReelItemWatchResponseFeatureFlag: PatchDefinition.() -> Boolean,
    restoreMissingCuepointMethod: PatchDefinition.() -> Boolean,
    block: PatchDefinition.() -> Unit,
    executeBlock: () -> Unit = {},
) = patch(
    name = "Spoof video streams",
    description = "Adds options to spoof the client video streams to fix playback."
) {
    block()

    dependsOn(
        fixProtoLibraryPatch,
        spoofVideoStreamsResourcePatch,
        buildRequestPatch,
        mediaRequestPatch
    )

    execute {
        mainActivityOnCreateFingerprints.forEach { fingerprint ->
            fingerprint.method.addInstruction(
                0,
                "invoke-static { }, $extensionClass->setClientOrderToUse()V"
            )
        }

        // region Enable extension helper method used by other patches

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // endregion

        // region Fetch streaming data.
        // The requests of the app are not changed. The streams are fetched when the app requests a video,
        // and also when the app builds the streams of a video it loaded with a request that was not seen.

        hookBuildRequest("$EXTENSION_CLASS->fetchStreams", includeBody = true)

        // When a media response asks for the player response again, the app loads the video again,
        // which can be with requests that are not seen. The streams fetched before are not given again.
        ReloadPlayerResponseFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->onReloadPlayerResponse()V"
        )

        // endregion

        // region Make the media requests of the streams of TV clients name the TV client.

        hookMediaRequest("$EXTENSION_CLASS->onMediaRequest", replacesRequest = true)
        hookMediaResponseChunk("$EXTENSION_CLASS->onMediaResponseChunk")

        // endregion

        // region Replace the streaming data with the replacement streams.

        CreateStreamingDataFingerprint.let {
            it.method.apply {
                val videoDetailsMatch = it.instructionMatches[5]
                val videoDetailsIndex = videoDetailsMatch.index
                val videoDetailsRegister = getInstruction<TwoRegisterInstruction>(videoDetailsIndex).registerA
                val videoDetailsClass = videoDetailsMatch.fieldReference.type

                val playerProtoClass = parameterTypes.first().toString()
                val getStreamingDataField = it.instructionMatches.first().fieldReference
                val setStreamingDataField = it.instructionMatches[1].fieldReference
                val setPlayerConfigField = it.instructionMatches.last().fieldReference
                val playerConfigClass = setPlayerConfigField.type
                val (mediaCommonConfigField, mediaUstreamerRequestConfig) =
                    with(AbrStateDataFingerprint) {
                        Pair(
                            instructionMatches[1].fieldReference,
                            instructionMatches[2].fieldReference,
                        )
                    }

                val (createBuilderMethod, mergeFromMethod) =
                    with(PlayerConfigBuilderFingerprint) {
                        Pair(
                            instructionMatches[2].methodReference,
                            instructionMatches[4].methodReference,
                        )
                    }

                val (castReference, buildMethod) =
                    with(PlayerConfigBuilderFingerprint) {
                        Pair(
                            instructionMatches[5].instruction.getReference<TypeReference>()!!,
                            instructionMatches[6].methodReference
                        )
                    }

                // The replacement streams can be of another video, such as an album track played
                // as its song version, and then the app keeps the timeline of the music video.
                val videoLengthField = classDefBy(videoDetailsClass).fields
                    .singleOrNull { field -> field.type == "J" }
                val overrideVideoLength = if (videoLengthField == null) "nop" else """
                    invoke-static { v2 }, $EXTENSION_CLASS->getVideoLengthSeconds(Ljava/lang/String;)J
                    move-result-wide v3
                    const-wide/16 v6, 0x0
                    cmp-long v0, v3, v6
                    if-lez v0, :length_overridden
                    iput-wide v3, p1, $videoDetailsClass->${videoLengthField.name}:J
                    :length_overridden
                    nop
                """

                val castInstruction = if (castReference.type != buildMethod.definingClass) """
                    check-cast v4, $castReference
                """ else """
                    nop
                """

                // Parses the player config bytes in v3 to a new player config in v5.
                val parsePlayerConfig = """
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
                            invoke-static { v2, p2 }, $EXTENSION_CLASS->getStreamingData(Ljava/lang/String;Ljava/lang/Object;)[B
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

                            # Set video length.
                            $overrideVideoLength

                            # Get player config. The app player config tells how the video is played inline.
                            iget-object v4, p2, $setPlayerConfigField
                            const/4 v1, 0x0
                            if-eqz v4, :get_player_config
                            invoke-virtual { v4 }, $playerConfigClass->toByteArray()[B
                            move-result-object v1
                            :get_player_config
                            invoke-static { v2, v1 }, $EXTENSION_CLASS->getPlayerConfig(Ljava/lang/String;[B)[B
                            move-result-object v3
                            if-eqz v3, :disabled
                            $parsePlayerConfig

                            # Check if player config contains android media lib config.
                            invoke-static { v2 }, $EXTENSION_CLASS->hasAndroidMedia(Ljava/lang/String;)Z
                            move-result v3
                            if-nez v3, :override_all_player_config

                            iget-object v6, v5, $mediaCommonConfigField
                            if-eqz v6, :disabled
                            iget-object v7, v6, $mediaUstreamerRequestConfig
                            if-eqz v7, :disabled

                            # Set media uStreamer request config in a copy of the app player config.
                            # The app config may already be serialized, which caches its size,
                            # and changing it then breaks serializing it again.
                            if-eqz v1, :disabled
                            move-object v3, v1
                            $parsePlayerConfig
                            iget-object v6, v5, $mediaCommonConfigField
                            iput-object v7, v6, $mediaUstreamerRequestConfig

                            :override_all_player_config
                            # Set player config.
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

        // region Remove video playback request body to fix playback.
        // It is assumed, YouTube makes a request with a body tuned for Android.
        // Requesting streams intended for other platforms with a body tuned for Android could be the cause of 400 errors.
        // A proper fix may include modifying the request body to match the platforms expected body.

        BuildMediaDataSourceFingerprint.let {
            val urlField = it.instructionMatches[0].fieldReference
            val httpMethodField = it.instructionMatches[1].fieldReference
            val httpBodyField = it.instructionMatches[2].fieldReference
            val index = it.instructionMatches.last().index

            it.method.addInstructions(
                index,
                """
                    # Field a: Stream uri.
                    # Field $httpMethodField: Http method.
                    # Field $httpBodyField: Post data.
                    move-object v0, p0  # method has over 15 registers and must copy p0 to a lower register.
                    iget-object v1, v0, $urlField
                    iget v2, v0, $httpMethodField
                    iget-object v3, v0, $httpBodyField
                    invoke-static { v1, v2, v3 }, $EXTENSION_CLASS->removeVideoPlaybackPostBody(Landroid/net/Uri;I[B)[B
                    move-result-object v1
                    iput-object v1, v0, $httpBodyField
                """
            )
        }

        // endregion

        // region Request plain streams of other clients as GET requests with a short range.
        // Streams of other clients are plain urls, which the server rejects or sends slowly if they are
        // requested like the streams of the app, with a POST body. Saving a video also requests a large
        // range, which the server sends at about playback speed.

        CronetUrlRequestBuildFingerprint.let {
            val fields = it.classDef.fields
            val urlField = fields.first { field ->
                field.type == "Ljava/lang/String;" && AccessFlags.FINAL.isSet(field.accessFlags)
            }
            val uploadField = fields.first { field -> field.type == "Lorg/chromium/net/UploadDataProvider;" }
            val httpMethodField = CronetUrlRequestSetHttpMethodFingerprint.instructionMatches.first().fieldReference

            // Insert before the hook at the start of the method, which moves the index of the call.
            val createRequest = it.instructionMatches.last()
            val urlRegister = createRequest.instruction.registersUsed[1]
            it.method.addInstructions(
                createRequest.index,
                """
                    invoke-static/range { v$urlRegister .. v$urlRegister }, $EXTENSION_CLASS->limitPlainStreamRange(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$urlRegister
                """
            )

            it.method.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v1, v0, $urlField
                    invoke-static { v1 }, $EXTENSION_CLASS->isPlainStreamRequest(Ljava/lang/String;)Z
                    move-result v1
                    if-eqz v1, :keep_request
                    const-string v1, "GET"
                    iput-object v1, v0, $httpMethodField
                    const/4 v1, 0x0
                    iput-object v1, v0, $uploadField
                    :keep_request
                    nop
                """
            )
        }

        // A response with a shortened range ends before the requested range does. The app takes that
        // as the end of the stream, and a saved video would end there. Instead, the transfer is paused
        // like for a task of a higher priority, and the saved video continues from the last byte it got.
        // The response ends when its remaining byte count is zero, or when it is finished.
        CronetDataSourceReadFingerprint.let {
            val remainingBytesField = it.instructionMatches.last().fieldReference
            val exceptionType = PriorityTooLowExceptionFingerprint.method.definingClass

            val helperMethod = ImmutableMethod(
                it.classDef.type,
                "patch_pauseIfStreamRangeLimited",
                listOf(ImmutableMethodParameter(it.classDef.type, null, null)),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        iget-wide v0, p0, $remainingBytesField
                        invoke-static { v0, v1 }, $EXTENSION_CLASS->isLimitedStreamRangeFinished(J)Z
                        move-result v0
                        if-eqz v0, :keep_reading
                        new-instance v0, $exceptionType
                        const/4 v1, 0x0
                        invoke-direct { v0, v1, v1 }, $exceptionType-><init>(II)V
                        throw v0
                        :keep_reading
                        return-void
                    """
                )
            }
            it.classDef.methods.add(helperMethod)

            // The response is read into a byte array or a buffer.
            listOf(it, CronetDataSourceReadBufferFingerprint).forEach { read ->
                // Insert after the remaining byte count is cleared, before the hook at the start of the method.
                read.method.addInstruction(
                    read.instructionMatches.last().index + 1,
                    "invoke-static/range { p0 .. p0 }, $helperMethod"
                )
                read.method.addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $helperMethod"
                )
            }
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

        // region Fix iOS live stream current time.

        HlsCurrentTimeFingerprint.method.apply {
            // Flag can exist in multiple places in the method.
            findInstructionIndicesReversedOrThrow(
                HlsCurrentTimeFingerprint.filters!!.first()
            ).forEach { match ->
                insertLiteralOverride(
                    match,
                    "$EXTENSION_CLASS->fixHLSCurrentTime(Z)Z"
                )
            }
        }

        // endregion

        // region Disable SABR playback.

        with(MediaFetchEnumConstructorFingerprint.method) {
            val sabrFieldIndex = indexOfFirstInstructionOrThrow(
                MediaFetchEnumConstructorFingerprint.stringMatches.last().index,
                fieldAccess(opcode = Opcode.SPUT_OBJECT, type = definingClass)
            )
            val sabrFieldReference = getInstruction<ReferenceInstruction>(sabrFieldIndex)
                .reference as FieldReference

            // Several videos are set up at once, such as the next track of the queue,
            // so the choice is made from the streams of the video the decision is for.
            val videoStreamingDataType = VideoStreamingDataToStringFingerprint.classDef.type
            val videoIdField = VideoStreamingDataConstructorFingerprint.instructionMatches[1].fieldReference

            Fingerprint(
                returnType = definingClass,
                filters = opcodesToFilters(
                    Opcode.SGET_OBJECT,
                    Opcode.RETURN_OBJECT,
                ),
                custom = { method, _ ->
                    // Player config and the streaming data of the video.
                    method.parameterTypes.size == 2
                            && method.parameterTypes[1].toString() == videoStreamingDataType
                }
            ).method.addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p2, $videoIdField
                    invoke-static { v0 }, $EXTENSION_CLASS->disableSABR(Ljava/lang/String;)Z
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

        // region Turn off feature flags that interfere with spoofing.

        if (fixParsePlaybackResponseFeatureFlag()) {
            PlaybackStartDescriptorFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->usePlaybackStartFeatureFlag(Z)Z"
                )
            }
        }

        if (fixMediaSessionFeatureFlag()) {
            addMediaSessionOverride("$EXTENSION_CLASS->useMediaSessionFeatureFlag(Z)Z")
        }

        if (fixReelItemWatchResponseFeatureFlag()) {
            ReelItemWatchResponseFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->useReelItemWatchResponseFeatureFlag(Z)Z"
                )
            }
        }

        // Restore missing method sometimes called by
        // com.google.android.libraries.youtube.media.interfaces.NetFetchCallbacks$CppProxy
        //
        // The 'native_onCuepointList(long memoryAddress, CuepointListOuterClass cuepointListOuterClass)' method exists,
        // but the 'parseFrom' method is missing from 'CuepointListOuterClass'.
        //
        // This seems to be because the 'GeneratedMessage' class was generated instead of the 'GeneratedMessageLite' class
        // due to incorrect option settings in the proto file (e.g. 'option optimize_for = SPEED') when Google bulit the app.
        //
        // This issue was fixed in YT 21.13 and YTM 9.12.
        // See: https://github.com/MorpheApp/morphe-patches/pull/2284#issuecomment-5204046377
        if (restoreMissingCuepointMethod()) {
            CuepointListFingerprint.classDef.apply {
                if (methods.none {
                        it.name == "parseFrom"
                                && it.parameterTypes.isNotEmpty()
                                && it.parameterTypes.first() == "Ljava/nio/ByteBuffer;"
                    }
                ) {
                    val cueField = fields.single {
                        it.type == CUEPOINT_LIST_CLASS
                    }
                    val superClass = superclass!!

                    // Verify the superclass method exists.
                    Fingerprint(
                        definingClass = superClass,
                        name = "parseFrom",
                        returnType = superClass,
                        parameters = listOf(
                            superClass,
                            "Ljava/nio/ByteBuffer;",
                            "Lcom/google/protobuf/ExtensionRegistryLite;"
                        )
                    ).method

                    methods.add(
                        ImmutableMethod(
                            type,
                            "parseFrom",
                            listOf(
                                ImmutableMethodParameter("Ljava/nio/ByteBuffer;", null, null),
                                ImmutableMethodParameter(
                                    "Lcom/google/protobuf/ExtensionRegistryLite;",
                                    null,
                                    null
                                )
                            ),
                            CUEPOINT_LIST_CLASS,
                            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                            null,
                            null,
                            MutableMethodImplementation(3),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """    
                                    sget-object v0, $cueField
                                    invoke-static { v0, p0, p1 }, $superClass->parseFrom(${superClass}Ljava/nio/ByteBuffer;Lcom/google/protobuf/ExtensionRegistryLite;)$superClass
                                    move-result-object p0
                                    check-cast p0, $CUEPOINT_LIST_CLASS
                                    return-object p0
                                """
                            )
                        }
                    )
                }
            }
        }

        // endregion

        executeBlock()
    }
}
