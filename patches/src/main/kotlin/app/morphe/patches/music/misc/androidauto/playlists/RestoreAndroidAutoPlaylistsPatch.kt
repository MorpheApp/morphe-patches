/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.cloneMutable
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.p0Register
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch;"
private const val EXTENSION_PLAYLIST_LOADER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistLoader;"
private const val EXTENSION_PLAYLIST_RESPONSE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistResponse;"
private const val EXTENSION_PLAYLIST_PAGE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistPage;"
private const val EXTENSION_PLAYLIST_BODY_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistBody;"
private const val EXTENSION_PLAYLIST_LISTING_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistListing;"
private const val EXTENSION_PLAYLIST_TRACKS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistTracks;"
private const val EXTENSION_ANDROID_AUTO_RESULT_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$AndroidAutoResult;"
private const val EXTENSION_PLAYLIST_OR_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistOrTrack;"

// move-result can only write to registers 0-255.
private const val MOVE_RESULT_REGISTER_LIMIT = 256
// iget-object can only address registers 0-15.
private const val IGET_REGISTER_LIMIT = 16
// A MediaDescriptionCompat constructor range starts with its receiver. Media ID and title are the
// next two registers.
private const val MEDIA_ID_REGISTER_OFFSET = 1
private const val TITLE_REGISTER_OFFSET = 2

private const val ARTWORK_FIELD_NAME = "c"
private const val TITLE_FIELD_NAME = "g"
private const val SUBTITLE_FIELD_NAME = "h"
private const val PLAYLIST_ID_PREFIX = "VL"
private const val PLAY_BUTTON_FIELD_NAME = "q"
private const val LISTENABLE_FUTURE_CLASS =
    "Lcom/google/common/util/concurrent/ListenableFuture;"

@Suppress("unused")
val restoreAndroidAutoPlaylistsPatch = bytecodePatch(
    name = "Restore playlists in Android Auto",
    description = "Restores YouTube Music playlists in Android Auto.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookPlaylistCategoryId()

        val createPlaybackIdMethod = CreatePlaybackIdFingerprint.originalMethod
        val responsePagesMethod = PlaylistResponseFingerprint.originalMethod
        val extractPlaylistsMethod = ExtractPlaylistsFingerprint.originalMethod
        val playlistListingType = extractPlaylistsMethod.parameterTypes.single().toString()
        val loadMoreActionsMethod = classDefBy(extractPlaylistsMethod.definingClass).methods
            .single { method ->
                method != extractPlaylistsMethod &&
                    AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                    AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "Ljava/util/List;" &&
                    method.parameterTypes.map(CharSequence::toString) ==
                    listOf(playlistListingType)
            }
        val playlistOrTrackType = PlaylistOrTrackFingerprint
            .instructionMatches
            .first()
            .instruction
            .getReference<TypeReference>()!!
            .type
        val playlistIdField = CreatePlaylistRequestFingerprint.instructionMatches
            .first()
            .instruction
            .getReference<FieldReference>()
            ?: throw PatchException("Could not resolve the playlist request ID field")
        val playlistLoaderClass = patchPlaylistLoader(loadMoreActionsMethod)

        patchPlaylistResponses(
            responsePagesMethod,
            extractPlaylistsMethod,
            loadMoreActionsMethod,
            playlistOrTrackType,
            createPlaybackIdMethod,
        )
        patchPlaylistOrTrack(
            playlistOrTrackType,
            playlistIdField,
            createPlaybackIdMethod,
        )
        val playlistLoadFallbackMethod = PlaylistLoadFallbackFingerprint.originalMethod
        patchAndroidAutoResult(playlistLoadFallbackMethod)
        val playlistLoadHandlerType = playlistLoadFallbackMethod.definingClass
        val androidAutoResultType = playlistLoadFallbackMethod.parameterTypes.first().toString()

        hookPlaylistLoader(playlistLoaderClass.type, androidAutoResultType)
        hookPlaylistLoad(playlistLoadHandlerType, androidAutoResultType)
    }
}

private fun BytecodePatchContext.hookPlaylistCategoryId() {
    val method = PlaylistCategoryFingerprint.method

    // On YTM 9.15.51, Playlists comes from the MediaDescriptionCompat call with FLAG_BROWSABLE.
    method
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { index ->
            val instruction =
                method.getInstruction<RegisterRangeInstruction>(
                    index,
                )
            val mediaIdRegister =
                instruction.startRegister + MEDIA_ID_REGISTER_OFFSET
            val titleRegister =
                instruction.startRegister + TITLE_REGISTER_OFFSET

            method.addInstructions(
                index,
                """
                    invoke-static/range { v$mediaIdRegister .. v$titleRegister }, $EXTENSION_CLASS->rememberPlaylistCategoryId(Ljava/lang/String;Ljava/lang/CharSequence;)V
                """,
            )
        }
}

private fun BytecodePatchContext.hookPlaylistLoader(
    playlistLoaderType: String,
    androidAutoResultType: String,
) {
    val musicBrowserServiceType = musicBrowserServiceFingerprint(
        androidAutoResultType,
    ).originalMethod.definingClass
    val providerMatches = playlistLoaderProviderFingerprint(playlistLoaderType)
        .matchAll()
        .map { match ->
            val (providerFieldMatch, providerGetMatch) = match.instructionMatches
            val providerField = providerFieldMatch.instruction.getReference<FieldReference>()!!
            val providerGetMethod = providerGetMatch.instruction.getReference<MethodReference>()!!
            providerField to providerGetMethod
        }
        .distinctBy { (field, _) -> field }
    // During onCreate, MusicBrowserService's generated superclass reads the Dagger provider for
    // the class containing BS_GET_BROWSE_DATA.
    val (onCreateMethod, providerMatch) = providerMatches.mapNotNull { provider ->
        playlistLoaderOnCreateFingerprint(
            musicBrowserServiceType,
            provider.first.definingClass,
        ).matchOrNull()?.originalMethod?.let { method -> method to provider }
    }.singleOrNull()
        ?: throw PatchException("Could not resolve the playlist loader used by MusicBrowserService")
    val (providerField, providerGetMethod) = providerMatch
    val mutableOnCreateMethod = mutableClassDefBy(
        onCreateMethod.definingClass,
    ).findMutableMethodOf(onCreateMethod)
    val componentIndex = mutableOnCreateMethod.indexOfFirstInstructionOrThrow {
        opcode == Opcode.IGET_OBJECT &&
            getReference<FieldReference>()?.type == providerField.definingClass
    }
    val componentRegister = mutableOnCreateMethod
        .getInstruction<TwoRegisterInstruction>(componentIndex)
        .registerA
    val providerRegister = mutableOnCreateMethod.findFreeRegister(
        componentIndex + 1,
        mutableOnCreateMethod.p0Register,
    )
    if (providerRegister >= IGET_REGISTER_LIMIT) {
        throw PatchException("MusicBrowserService.onCreate has no free 4-bit register")
    }

    mutableOnCreateMethod.addInstructions(
        componentIndex + 1,
        """
            iget-object v$providerRegister, v$componentRegister, $providerField
            invoke-interface/range { v$providerRegister .. v$providerRegister }, $providerGetMethod
            move-result-object v$providerRegister
            check-cast v$providerRegister, $EXTENSION_PLAYLIST_LOADER_INTERFACE
            invoke-static/range { v$providerRegister .. v$providerRegister }, $EXTENSION_CLASS->setPlaylistLoader($EXTENSION_PLAYLIST_LOADER_INTERFACE)V
        """,
    )
}

private fun BytecodePatchContext.patchPlaylistResponses(
    responsePagesMethod: Method,
    extractPlaylistsMethod: Method,
    loadMoreActionsMethod: Method,
    playlistOrTrackType: String,
    createPlaybackIdMethod: Method,
) {
    val pageFactoryNewInstance = PlaylistResponseFingerprint.instructionMatches.last()
    val pageFactoryType = pageFactoryNewInstance
        .instruction
        .getReference<TypeReference>()!!
        .type
    val pageFactory = createPlaylistPageFingerprint(pageFactoryType)
    val pageNewInstance = pageFactory.instructionMatches.first()
    val pageType = pageNewInstance
        .instruction
        .getReference<TypeReference>()!!
        .type
    val getBodyMethod = getPlaylistBodyFingerprint(pageType).originalMethod
    val bodyGroupMethods = playlistBodyGroupsFingerprint(
        getBodyMethod.returnType,
        responsePagesMethod.returnType,
    ).matchAll(2..2)
        .map { match -> match.originalMethod }
    val extractPlaylistTracksMethod = extractPlaylistTracksFingerprint(
        playlistOrTrackType,
    ).originalMethod
    val morePlaylistsMethod = DecodeMorePlaylistsFingerprint.originalMethod
    val responsePayloadType = morePlaylistsMethod
        .parameterTypes.single().toString()
    val responsePayloadMethod = classDefBy(
        responsePagesMethod.definingClass,
    ).methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
            method.returnType == responsePayloadType
    } ?: throw PatchException("Could not resolve the playlist response payload")
    // PlaylistListing and PlaylistTracks call these methods from different classes.
    listOf(
        extractPlaylistsMethod,
        extractPlaylistTracksMethod,
        loadMoreActionsMethod,
    ).forEach { method ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).apply {
            accessFlags = accessFlags.toPublicAccessFlags()
        }
    }

    val morePlaylistsDecoder = addMorePlaylistsDecoder(morePlaylistsMethod)
    addPlaylistResponseInterface(
        responsePagesMethod,
        responsePayloadMethod,
        morePlaylistsDecoder,
        createPlaybackIdMethod,
    )
    addPlaylistPageInterface(getBodyMethod)
    addPlaylistBodyInterface(bodyGroupMethods)
    addPlaylistListingInterface(extractPlaylistsMethod, loadMoreActionsMethod)
    addPlaylistTracksInterface(extractPlaylistTracksMethod)
}

private fun BytecodePatchContext.addPlaylistResponseInterface(
    responsePagesMethod: Method,
    responsePayloadMethod: Method,
    morePlaylistsDecoder: Method,
    createPlaybackIdMethod: Method,
) {
    val responseClass = mutableClassDefBy(responsePagesMethod.definingClass)
    responseClass.interfaces.add(EXTENSION_PLAYLIST_RESPONSE_INTERFACE)
    addPlaylistPlaybackIdGetter(responseClass, responsePagesMethod, createPlaybackIdMethod)
    responseClass.addInterfaceMethod(
        name = "patch_getPages",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $responsePagesMethod
            move-result-object p0
            return-object p0
        """,
    )
    responseClass.addInterfaceMethod(
        name = "patch_getMorePlaylists",
        parameters = emptyList(),
        returnType = "Ljava/lang/Object;",
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $responsePayloadMethod
            move-result-object p0
            # The cloned method does not read its first argument.
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $morePlaylistsDecoder
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistPageInterface(
    getBodyMethod: Method,
) {
    val pageClass = mutableClassDefBy(getBodyMethod.definingClass)
    pageClass.interfaces.add(EXTENSION_PLAYLIST_PAGE_INTERFACE)
    pageClass.addInterfaceMethod(
        name = "patch_getBody",
        parameters = emptyList(),
        returnType = "Ljava/lang/Object;",
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getBodyMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistBodyInterface(
    bodyGroupMethods: List<Method>,
) {
    val (firstGroupMethod, secondGroupMethod) = bodyGroupMethods
    val bodyClass = mutableClassDefBy(firstGroupMethod.definingClass)
    bodyClass.interfaces.add(EXTENSION_PLAYLIST_BODY_INTERFACE)
    bodyClass.addInterfaceMethod(
        name = "patch_getGroups",
        parameters = emptyList(),
        returnType = "[Ljava/lang/Iterable;",
        registerCount = 4,
        instructions = """
            const/4 v0, 0x2
            new-array v0, v0, [Ljava/lang/Iterable;
            invoke-virtual { p0 }, $firstGroupMethod
            move-result-object v1
            const/4 v2, 0x0
            aput-object v1, v0, v2
            invoke-virtual { p0 }, $secondGroupMethod
            move-result-object v1
            const/4 v2, 0x1
            aput-object v1, v0, v2
            return-object v0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistListingInterface(
    extractPlaylistsMethod: Method,
    loadMoreActionsMethod: Method,
) {
    val playlistListingType = extractPlaylistsMethod.parameterTypes.single().toString()
    val playlistListingClass = mutableClassDefBy(playlistListingType)
    playlistListingClass.interfaces.add(EXTENSION_PLAYLIST_LISTING_INTERFACE)
    playlistListingClass.addInterfaceMethod(
        name = "patch_getPlaylists",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $extractPlaylistsMethod
            move-result-object p0
            return-object p0
        """,
    )
    playlistListingClass.addInterfaceMethod(
        name = "patch_getLoadMoreActions",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $loadMoreActionsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistTracksInterface(
    extractPlaylistTracksMethod: Method,
) {
    val playlistTracksType = extractPlaylistTracksMethod.parameterTypes.first().toString()
    val playlistTracksClass = mutableClassDefBy(playlistTracksType)
    playlistTracksClass.interfaces.add(EXTENSION_PLAYLIST_TRACKS_INTERFACE)
    playlistTracksClass.addInterfaceMethod(
        name = "patch_getTracks",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 2,
        instructions = """
            const/4 v0, 0x0
            # false returns tracks from protobuf field 161429595.
            invoke-static { p0, v0 }, $extractPlaylistTracksMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistPlaybackIdGetter(
    responseClass: MutableClass,
    responsePagesMethod: Method,
    createPlaybackIdMethod: Method,
) {
    val playActionType = createPlaybackIdMethod.parameterTypes.single().toString()
    val playlistPlayButtonInitializerMethod = playlistPlayButtonFingerprint(
        playActionType,
    ).originalMethod
    val playlistPlayButtonType = playlistPlayButtonInitializerMethod.instructions
        .first { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .getReference<TypeReference>()!!
        .type
    val containingType = playlistPlayButtonInitializerMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .first { field -> field.definingClass == field.type }
        .type
    val descriptorField = playlistPlayButtonInitializerMethod.instructions
        .first { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .getReference<FieldReference>()!!
    val decodePlaylistPlayButtonMethod = decodePlaylistPlayButtonFingerprint(
        containingType,
        playlistPlayButtonType,
        descriptorField,
    ).originalMethod
    val playlistPlayActionField = playlistPlayActionFingerprint(
        playlistPlayButtonType,
        playActionType,
    ).matchAll()
        .map { match ->
            val (playActionReadMatch, _) = match.instructionMatches
            playActionReadMatch.instruction.getReference<FieldReference>()!!
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the playlist Play action")

    val responsePayloadField = responsePagesMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .filter { field ->
            field.definingClass == responsePagesMethod.definingClass &&
                field.type != responsePagesMethod.returnType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the playlist response payload field")
    val playButtonField = classDefBy(responsePayloadField.type).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.name == PLAY_BUTTON_FIELD_NAME &&
            field.type == containingType
    } ?: throw PatchException("Could not resolve the playlist Play button field")

    // response.q stores the playlist Play button in protobuf field 65153809.
    responseClass.addInterfaceMethod(
        name = "patch_getPlaybackId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 2,
        instructions = """
            iget-object p0, p0, $responsePayloadField
            iget-object p0, p0, $playButtonField
            # true returns the Play ButtonRenderer or null.
            const/4 v0, 0x1
            invoke-static { v0, p0 }, $decodePlaylistPlayButtonMethod
            move-result-object p0
            if-eqz p0, :no_play_button
            iget-object p0, p0, $playlistPlayActionField
            if-eqz p0, :no_play_button
            invoke-static { p0 }, $createPlaybackIdMethod
            move-result-object p0
            return-object p0
            :no_play_button
            const/4 p0, 0x0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addMorePlaylistsDecoder(method: Method): Method {
    val decoder = method.cloneMutable(
        name = "patch_decodeMorePlaylists",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        // The original method clears its receiver register before reading the response from p1.
        // The added first parameter keeps that register layout in the static clone.
        parameters = listOf(
            ImmutableMethodParameter(method.definingClass, null, null),
        ) + method.parameters,
    )
    mutableClassDefBy(method.definingClass).methods.add(decoder)
    return decoder
}

private fun BytecodePatchContext.patchPlaylistOrTrack(
    playlistOrTrackType: String,
    playlistIdField: FieldReference,
    createPlaybackIdMethod: Method,
) {
    val playlistOrTrackFields = classDefBy(playlistOrTrackType).fields.toList()

    fun playlistOrTrackField(name: String) = playlistOrTrackFields
        .first { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkField = playlistOrTrackField(ARTWORK_FIELD_NAME)
    val titleField = playlistOrTrackField(TITLE_FIELD_NAME)
    val subtitleField = playlistOrTrackField(SUBTITLE_FIELD_NAME)
    if (artworkField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected playlist/track artwork, title, or subtitle fields")
    }
    val playlistThumbnailPayloadType = PlaylistThumbnailFingerprint.originalMethod.instructions
        .first { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .getReference<TypeReference>()!!
        .type
    val playlistThumbnailPayloadFields = classDefBy(playlistThumbnailPayloadType).fields
        .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
        .toList()
    val decodePlaylistThumbnailMethod = decodePlaylistThumbnailFingerprint(
        artworkField.type,
    ).originalMethod
    val artworkCallsiteMethod = playlistArtworkFingerprint(
        playlistThumbnailPayloadFields.map(FieldReference::getType).toSet(),
    ).originalMethod
    val artworkReadTypes = artworkCallsiteMethod.instructions
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
        .toSet()
    val artworkUriMethod = artworkCallsiteMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { method ->
            method.returnType == "Landroid/net/Uri;" && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().toString() in artworkReadTypes
        }
        .singleOrNull { method ->
            playlistThumbnailPayloadFields.any { field ->
                field.type == method.parameterTypes.single().toString()
            }
        }
        ?: throw PatchException("Could not resolve the Android Auto artwork Uri helper")
    val playlistThumbnailPayloadField = playlistThumbnailPayloadFields.singleOrNull { field ->
        field.type == artworkUriMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the artwork payload field")
    val formatPlaylistTextMethod = formatPlaylistTextFingerprint(titleField.type).originalMethod

    val actionType = createPlaybackIdMethod.parameterTypes.single().toString()
    val actionFields = playlistOrTrackFields
        .filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == actionType
        }
    if (actionFields.size != 2) {
        throw PatchException("Could not resolve the two playlist/track click actions")
    }
    val (firstActionField, secondActionField) = actionFields

    val decodePlaylistActionMethod = decodePlaylistActionFingerprint(
        actionType,
        playlistIdField.definingClass,
    ).originalMethod

    val playlistOrTrackClass = mutableClassDefBy(playlistOrTrackType)
    playlistOrTrackClass.interfaces.add(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE)
    playlistOrTrackClass.addPlaylistIdGetter(
        firstActionField,
        secondActionField,
        decodePlaylistActionMethod,
        playlistIdField,
    )
    playlistOrTrackClass.addPlaybackIdGetter(
        firstActionField,
        secondActionField,
        createPlaybackIdMethod,
    )
    playlistOrTrackClass.addTextGetter("patch_getTitle", titleField, formatPlaylistTextMethod)
    playlistOrTrackClass.addTextGetter("patch_getSubtitle", subtitleField, formatPlaylistTextMethod)
    playlistOrTrackClass.addArtworkUriGetter(
        artworkField,
        decodePlaylistThumbnailMethod,
        playlistThumbnailPayloadField,
        artworkUriMethod,
    )
}

// Fields i and k can both contain click actions; conflicting VL playlist IDs are ignored.
private fun MutableClass.addPlaylistIdGetter(
    firstActionField: FieldReference,
    secondActionField: FieldReference,
    decodePlaylistActionMethod: Method,
    playlistIdField: FieldReference,
) {
    addInterfaceMethod(
        name = "patch_getPlaylistId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 4,
        instructions = """
            const/4 v0, 0x0
            iget-object v1, p0, $firstActionField
            if-eqz v1, :second_action
            invoke-static { v1 }, $decodePlaylistActionMethod
            move-result-object v1
            if-eqz v1, :second_action
            iget-object v1, v1, $playlistIdField
            if-eqz v1, :second_action
            const-string v2, "$PLAYLIST_ID_PREFIX"
            invoke-virtual { v1, v2 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :second_action
            move-object v0, v1

            :second_action
            iget-object v1, p0, $secondActionField
            if-eqz v1, :return_id
            invoke-static { v1 }, $decodePlaylistActionMethod
            move-result-object v1
            if-eqz v1, :return_id
            iget-object v1, v1, $playlistIdField
            if-eqz v1, :return_id
            const-string v2, "$PLAYLIST_ID_PREFIX"
            invoke-virtual { v1, v2 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :return_id
            if-eqz v0, :use_second_id
            invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :return_id
            const/4 v0, 0x0
            return-object v0

            :use_second_id
            move-object v0, v1
            :return_id
            return-object v0
        """,
    )
}

private fun MutableClass.addPlaybackIdGetter(
    firstActionField: FieldReference,
    secondActionField: FieldReference,
    createPlaybackIdMethod: Method,
) {
    addInterfaceMethod(
        name = "patch_getPlaybackId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $firstActionField
            if-eqz v0, :second_action
            invoke-static { v0 }, $createPlaybackIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            if-eqz v0, :second_action
            invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
            move-result v1
            if-eqz v1, :return_id

            :second_action
            iget-object v0, p0, $secondActionField
            if-nez v0, :decode_second_action
            # ART on 9.15.51 otherwise merges this missing-action path with decoded Strings as
            # Object.
            const/4 v0, 0x0
            goto :return_id

            :decode_second_action
            invoke-static { v0 }, $createPlaybackIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            :return_id
            return-object v0
        """,
    )
}

private fun MutableClass.addTextGetter(
    name: String,
    field: FieldReference,
    formatTextMethod: Method,
) {
    addInterfaceMethod(
        name = name,
        parameters = emptyList(),
        returnType = "Ljava/lang/CharSequence;",
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $field
            # The second argument is optional text-to-speech content.
            const/4 v1, 0x0
            invoke-static { v0, v1 }, $formatTextMethod
            move-result-object v0
            return-object v0
        """,
    )
}

private fun MutableClass.addArtworkUriGetter(
    artworkField: FieldReference,
    decodeThumbnailMethod: Method,
    thumbnailPayloadField: FieldReference,
    artworkUriMethod: MethodReference,
) {
    addInterfaceMethod(
        name = "patch_getArtworkUri",
        parameters = emptyList(),
        returnType = "Landroid/net/Uri;",
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $artworkField
            if-eqz v0, :no_artwork
            invoke-static { v0 }, $decodeThumbnailMethod
            move-result-object v0
            if-eqz v0, :no_artwork
            check-cast v0, ${thumbnailPayloadField.definingClass}
            iget-object v0, v0, $thumbnailPayloadField
            invoke-static { v0 }, $artworkUriMethod
            move-result-object v0
            return-object v0
            :no_artwork
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

private fun BytecodePatchContext.patchPlaylistLoader(
    loadMoreActionsMethod: Method,
): MutableClass {
    val createPlaylistRequestMethod = CreatePlaylistRequestFingerprint.originalMethod
    val playlistRequestBuilderType = createPlaylistRequestMethod.returnType
    val playlistRequestFactoryMethod = createPlaylistRequestMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .firstOrNull { reference ->
            reference.parameterTypes.isEmpty() &&
                reference.returnType == playlistRequestBuilderType
        }
        ?: throw PatchException("Could not resolve the playlist request factory")
    val playlistLoaderType = playlistRequestFactoryMethod.definingClass
    val loadMoreActionTypes = loadMoreActionsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .map { reference -> reference.returnType }
        .toSet()
    val createLoadMoreRequestMethod = classDefBy(
        playlistLoaderType,
    ).methods.singleOrNull { method ->
        method.returnType == playlistRequestBuilderType &&
            method.parameterTypes.singleOrNull()?.toString() in loadMoreActionTypes
    }
        ?: throw PatchException("Could not resolve the load-more request factory")
    val playlistRequestFingerprint = sendPlaylistRequestFingerprint(
        playlistLoaderType,
        playlistRequestBuilderType,
    )
    val sendPlaylistRequestMethod = playlistRequestFingerprint.originalMethod
    val libraryOrPlaylistIdField = playlistRequestFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val playlistRequestBuilderMethods = generateSequence(
        classDefBy(playlistRequestBuilderType),
    ) { classDef ->
        classDef.superclass?.let { superclass -> classDefByOrNull(superclass) }
    }.flatMap { classDef -> classDef.methods.asSequence() }
    val clickTrackingParamsSetterMethod = playlistRequestBuilderMethods
        // YTM 9.32.51 and 9.33.52 also declare a public byte[] clickTrackingParams setter.
        .firstOrNull { method ->
            AccessFlags.PROTECTED.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("[B")
        }
        ?: throw PatchException("Could not resolve the click tracking parameter setter")
    val setLibraryOrPlaylistIdMethod = setLibraryOrPlaylistIdFingerprint(
        libraryOrPlaylistIdField,
    ).originalMethod
    val playlistLoaderClass = mutableClassDefBy(playlistLoaderType)
    playlistLoaderClass.interfaces.add(EXTENSION_PLAYLIST_LOADER_INTERFACE)
    playlistLoaderClass.addInterfaceMethod(
        name = "patch_requestPage",
        parameters = listOf("Ljava/lang/String;", "Ljava/util/concurrent/Executor;"),
        returnType = LISTENABLE_FUTURE_CLASS,
        registerCount = 5,
        instructions = """
            invoke-virtual { p0 }, $playlistRequestFactoryMethod
            move-result-object v0
            invoke-virtual { v0, p1 }, $setLibraryOrPlaylistIdMethod
            # Playlist requests require clickTrackingParams, even when it is empty.
            const/4 v1, 0x0
            new-array v1, v1, [B
            invoke-virtual { v0, v1 }, $clickTrackingParamsSetterMethod
            invoke-virtual { p0, v0, p2 }, $sendPlaylistRequestMethod
            move-result-object v0
            return-object v0
        """,
    )
    val loadMoreActionType = createLoadMoreRequestMethod
        .parameterTypes.single().toString()
    playlistLoaderClass.addInterfaceMethod(
        name = "patch_requestMorePlaylists",
        parameters = listOf("Ljava/lang/Object;", "Ljava/util/concurrent/Executor;"),
        returnType = LISTENABLE_FUTURE_CLASS,
        registerCount = 3,
        instructions = """
            check-cast p1, $loadMoreActionType
            invoke-virtual { p0, p1 }, $createLoadMoreRequestMethod
            move-result-object p1
            invoke-virtual { p0, p1, p2 }, $sendPlaylistRequestMethod
            move-result-object p1
            return-object p1
        """,
    )

    return playlistLoaderClass
}

private fun BytecodePatchContext.patchAndroidAutoResult(
    playlistLoadFallbackMethod: Method,
) {
    val androidAutoResultType = playlistLoadFallbackMethod.parameterTypes.first().toString()
    val androidAutoResultClass = mutableClassDefBy(androidAutoResultType)
    androidAutoResultClass.interfaces.add(EXTENSION_ANDROID_AUTO_RESULT_INTERFACE)
    // LoadChildrenResult.toString() labels the String read by the invalid-ID branch as parentMediaId.
    val parentMediaIdFieldPath = playlistLoadFallbackMethod.instructions.asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .windowed(2)
        .first { (resultField, parentMediaIdField) ->
            resultField.definingClass == androidAutoResultType &&
                parentMediaIdField.definingClass == resultField.type &&
                parentMediaIdField.type == "Ljava/lang/String;"
        }

    // YTM 9.15.51's invalid-ID path calls b(List), which forwards to c(List, null).
    val resultDeliveryMethod = playlistLoadFallbackMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .first { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            reference.definingClass == androidAutoResultType && reference.returnType == "V" &&
                parameters.size in 1..2 &&
                parameters.firstOrNull() == "Ljava/util/List;" &&
                parameters.drop(1).all { it.startsWith("L") || it.startsWith("[") }
        }

    val parentMediaIdInstructions = buildString {
        parentMediaIdFieldPath.forEach { field ->
            appendLine("iget-object p0, p0, $field")
        }
        append("return-object p0")
    }
    androidAutoResultClass.addInterfaceMethod(
        name = "patch_getParentMediaId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 1,
        instructions = parentMediaIdInstructions,
    )
    val hasExtraDeliveryParameter = resultDeliveryMethod.parameterTypes.size == 2
    androidAutoResultClass.addInterfaceMethod(
        name = "patch_sendResult",
        parameters = listOf("Ljava/util/List;"),
        returnType = "V",
        registerCount = if (hasExtraDeliveryParameter) 3 else 2,
        instructions = if (hasExtraDeliveryParameter) {
            """
                const/4 v0, 0x0
                invoke-virtual { p0, p1, v0 }, $resultDeliveryMethod
                return-void
            """
        } else {
            """
                invoke-virtual { p0, p1 }, $resultDeliveryMethod
                return-void
            """
        },
    )
}

private fun MutableClass.addInterfaceMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    registerCount: Int,
    instructions: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { parameter -> ImmutableMethodParameter(parameter, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registerCount),
        ).toMutable().apply {
            addInstructions(0, instructions)
        },
    )
}

private fun BytecodePatchContext.hookPlaylistLoad(
    playlistLoadHandlerType: String,
    androidAutoResultType: String,
) {
    val playlistLoadHandlerMethod = playlistLoadHandlerFingerprint(
        playlistLoadHandlerType,
        androidAutoResultType,
    ).method
    val handledRegister = playlistLoadHandlerMethod.findFreeRegister(0)
    if (handledRegister >= MOVE_RESULT_REGISTER_LIMIT) {
        throw PatchException("Playlist load handler has no free 8-bit register")
    }

    playlistLoadHandlerMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handlePlaylistLoad(Ljava/lang/Object;)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", playlistLoadHandlerMethod.getInstruction<Instruction>(0)),
    )
}
