/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.interaction.jam.protoDefaultInstanceFingerprint
import app.morphe.patches.music.interaction.jam.protoParserFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.cloneMutable
import app.morphe.util.findFreeRegister
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.matchSingle
import app.morphe.util.p0Register
import app.morphe.util.returnEarly
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AndroidAutoPatch;"
private const val PHONE_BROWSE_CLIENT =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$PhoneBrowseClient;"
private const val PHONE_BROWSE_RESPONSE =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$PhoneBrowseResponse;"
private const val PHONE_BROWSE_TAB =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$PhoneBrowseTab;"
private const val GRID_RENDERER =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$GridRenderer;"
private const val BROWSE_REQUEST =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$BrowseRequest;"
private const val FOLDER_RELOAD =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$FolderReload;"
private const val PHONE_BROWSE_ITEM =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$PhoneBrowseItem;"
private const val MUSIC_BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"

// Constructor calls pass the MediaDescriptionCompat instance before the media ID and title.
private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

// YTM uses the same data type for playlists and songs.
private const val TITLE_FIELD_NAME = "g"
private const val SUBTITLE_FIELD_NAME = "h"

/**
 * Reuses YTM's phone requests and playback commands to supply Android Auto content.
 *
 * [hookBrowseResults] keeps native podcast folders in a Podcasts tab.
 * [patchFolderRefresh] refreshes Home, Library collections, and Podcasts after Library changes.
 * [patchPlaylistMediaIdBuilder] supplies the media IDs Android Auto uses to play playlists.
 */
@Suppress("unused")
val androidAutoPatch = bytecodePatch(
    name = "Android Auto",
    description = "Bypasses certificate checks and restores YouTube Music Library collections and podcasts in Android Auto.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // region Bypass certificate checks
        CheckCertificateFingerprint.method.returnEarly(true)

        // Devices with real Google Play services installed alongside microG crash inside
        // the Dynamite-based Google signature verifier (IllegalStateException:
        // "Missing DynamiteApplicationContext") before the fingerprint check above is
        // even evaluated.  Report "not Google-signed" immediately, without touching
        // Dynamite; authorization still succeeds via the fingerprint patch above.
        IsGoogleSignedFingerprint.method.returnEarly(false)
        // endregion

        hookPlaylistsTitleMediaIds()
        patchPhoneBrowseClient()
        patchPhoneBrowseResponses()
        val encodeCommandMediaIdMethod = patchPhoneBrowseItem()
        patchBrowseRequests()
        patchFolderRefresh()
        patchPlaylistMediaIdBuilder(encodeCommandMediaIdMethod)
        patchCommandEncoder(encodeCommandMediaIdMethod)
    }
}

// region Identify the Playlists folder in Android Auto's Library

/**
 * The Playlists folder has no fixed media ID, so recognize it by its translated title.
 */
private fun BytecodePatchContext.hookPlaylistsTitleMediaIds() {
    BuildMediaItemFingerprint.let {
        it.method.apply {
            // Capture the Playlists folder ID and title regardless of which branch creates it.
            it.instructionMatches.reversed().forEach { match ->
                val instructionStartRegister = match.getInstruction<RegisterRangeInstruction>().startRegister

                val mediaIdRegister = instructionStartRegister + MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET
                val titleRegister = instructionStartRegister + MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET

                addInstruction(
                    match.index,
                    "invoke-static/range { v$mediaIdRegister .. v$titleRegister }, " +
                            "$EXTENSION_CLASS->rememberPlaylistsTitleMatch(Ljava/lang/String;Ljava/lang/CharSequence;)V"
                )
            }
        }
    }
}

// endregion

// region Phone browse requests

/**
 * Reuses the YTM object that sends phone browse requests.
 * [addPhoneBrowseRequestMethod] requests a page; [addLibraryPaginationRequestMethod] requests more Library items.
 * [hookPhoneClientOnServiceCreate] saves the client when YTM's Auto service starts.
 */
private fun BytecodePatchContext.patchPhoneBrowseClient() {
    val (createPhoneBrowseRequestMethod, setRequestBrowseIdMethod, initializeTrackingMethod, sendPhoneBrowseRequestMethod) =
        PhoneBrowseRequestCallsFingerprint.instructionMatches.mapNotNull { match ->
            match.instruction.getReference<MethodReference>()
        }
    val phoneBrowseRequestType = createPhoneBrowseRequestMethod.returnType
    val phoneBrowseClientType = createPhoneBrowseRequestMethod.definingClass

    val phoneBrowseClientClass = mutableClassDefBy(phoneBrowseClientType)
    phoneBrowseClientClass.interfaces.add(PHONE_BROWSE_CLIENT)
    addPhoneAccountScopeBridge(phoneBrowseClientClass, createPhoneBrowseRequestMethod,
        PHONE_BROWSE_CLIENT)
    addPhoneBrowseRequestMethod(
        phoneBrowseClientClass,
        createPhoneBrowseRequestMethod,
        setRequestBrowseIdMethod,
        initializeTrackingMethod,
        sendPhoneBrowseRequestMethod,
    )
    addLibraryPaginationRequestMethod(
        phoneBrowseClientClass,
        phoneBrowseRequestType,
        sendPhoneBrowseRequestMethod,
    )
    hookPhoneClientOnServiceCreate(
        phoneBrowseClientType,
        PHONE_BROWSE_CLIENT,
        "$EXTENSION_CLASS->setPhoneBrowseClient($PHONE_BROWSE_CLIENT)V",
    )
}

/**
 * Fetches a phone page by its browse ID (YTM's page ID).
 * YTM creates and sends the request; the returned future contains the response.
 */
private fun BytecodePatchContext.addPhoneBrowseRequestMethod(
    phoneBrowseClientClass: MutableClass,
    createPhoneBrowseRequestMethod: MethodReference,
    setRequestBrowseIdMethod: MethodReference,
    initializeTrackingMethod: MethodReference,
    sendPhoneBrowseRequestMethod: MethodReference,
) {
    phoneBrowseClientClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            PHONE_BROWSE_CLIENT,
            "patch_requestBrowse",
        ),
        registerCount = 4,
        instructions = """
            invoke-virtual { p0 }, $createPhoneBrowseRequestMethod
            move-result-object v0
            invoke-virtual { v0, p1 }, $setRequestBrowseIdMethod
            invoke-virtual { v0 }, $initializeTrackingMethod
            invoke-virtual { p0, v0, p2 }, $sendPhoneBrowseRequestMethod
            move-result-object v0
            return-object v0
        """
    )
}

// Library pagination requests

/** Requests the next Library page using the pagination command from the previous response. */
private fun BytecodePatchContext.addLibraryPaginationRequestMethod(
    phoneBrowseClientClass: MutableClass,
    phoneBrowseRequestType: String,
    sendPhoneBrowseRequestMethod: MethodReference,
) {
    val getGridPaginationCommandsMethod = gridPaginationCommandsFingerprint(
        GridRendererItemsFingerprint.originalMethod,
    ).originalMethod

    // The pagination request accepts the command type created by getGridPaginationCommandsMethod.
    val paginationReaderReturnTypes =
        getGridPaginationCommandsMethod.instructions.asSequence()
            .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
            .map { reference -> reference.returnType }
            .toSet()
    val createPaginationRequestMethod = classDefBy(phoneBrowseClientClass.type)
        .methods.singleOrNull { method ->
            method.returnType == phoneBrowseRequestType &&
                    method.parameterTypes.singleOrNull()?.toString() in paginationReaderReturnTypes
        } ?: throw PatchException("Could not resolve the Library pagination request method")
    val paginationCommandType = createPaginationRequestMethod
        .parameterTypes.single().toString()

    phoneBrowseClientClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            PHONE_BROWSE_CLIENT,
            "patch_requestLibraryPagination",
        ),
        registerCount = 3,
        instructions = """
            check-cast p1, $paginationCommandType
            invoke-virtual { p0, p1 }, $createPaginationRequestMethod
            move-result-object p1
            invoke-virtual { p0, p1, p2 }, $sendPhoneBrowseRequestMethod
            move-result-object p1
            return-object p1
        """
    )
}

// Obtain YTM's phone request clients

internal fun BytecodePatchContext.hookPhoneClientOnServiceCreate(
    clientType: String,
    clientInterface: String,
    setter: String,
) {
    val providerCandidates =
        phoneClientProviderFingerprint(clientType)
            .matchAll()
            .map { match ->
                val (providerFieldMatch, providerGetMatch) = match.instructionMatches
                val providerField = providerFieldMatch.instruction.getReference<FieldReference>()!!
                val providerGetMethod =
                    providerGetMatch.instruction.getReference<MethodReference>()!!
                providerField to providerGetMethod
            }
            .distinctBy { (field, _) -> field }
    // Several providers create this request sender. Select the one used when MusicBrowserService starts.
    val (onCreateMatch, providerField, providerGetMethod) = providerCandidates
        .mapNotNull { (providerField, providerGetMethod) ->
            browserServiceOnCreateFingerprint(
                MUSIC_BROWSER_SERVICE_CLASS,
                providerField.definingClass,
            ).matchOrNull()?.let { match ->
                Triple(match, providerField, providerGetMethod)
            }
        }
        .singleOrNull()
        ?: throw PatchException(
            "Could not resolve MusicBrowserService's phone client provider: $clientType",
        )
    val mutableOnCreateMethod = onCreateMatch.method
    val generatedComponentReadIndex = onCreateMatch.instructionMatches.single { match ->
        val field = match.instruction.getReference<FieldReference>()
        match.instruction.opcode == Opcode.IGET_OBJECT && field?.type == providerField.definingClass
    }.index
    val generatedComponentRegister = mutableOnCreateMethod
        .getInstruction<TwoRegisterInstruction>(generatedComponentReadIndex)
        .registerA
    val clientRegister = mutableOnCreateMethod.findFreeRegister(
        generatedComponentReadIndex + 1,
        mutableOnCreateMethod.p0Register,
    )

    mutableOnCreateMethod.addInstructions(
        generatedComponentReadIndex + 1,
        """
            iget-object v$clientRegister, v$generatedComponentRegister, $providerField
            invoke-interface/range { v$clientRegister .. v$clientRegister }, $providerGetMethod
            move-result-object v$clientRegister
            check-cast v$clientRegister, $clientInterface
            invoke-static/range { v$clientRegister .. v$clientRegister }, $setter
        """
    )
}

// endregion

// region Read Library responses

/**
 * The first phone Library response wraps its grid in tabs and sections.
 * Later responses can contain the grid directly or inside their first section.
 */
private fun BytecodePatchContext.patchPhoneBrowseResponses() {
    addPhoneBrowseResponseInterface()
    addPhoneBrowseTabInterface()
    addGridRendererInterface()
}

// Library pagination responses

/**
 * Reuses YTM's Library pagination parser without creating the phone's Library screen.
 * It reads the grid directly or from the first section. An unrecognized result returns null.
 */
private fun BytecodePatchContext.addPaginatedLibraryGridDecoder(
    decodePaginatedLibraryGridMethod: Method,
): Method {
    // YTM's pagination parser only needs the response data.
    // Copy it as a static method so Android Auto can use it without creating
    // the object that manages the phone's Library screen.
    val clonedDecoderMethod = decodePaginatedLibraryGridMethod.cloneMutable(
        name = "patch_decodePaginatedLibraryGrid",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        // The original method uses p0 for "this" and p1 for the response.
        // Keep an unused first argument so the copied code still finds the response in p1.
        parameters = listOf(
            ImmutableMethodParameter(decodePaginatedLibraryGridMethod.definingClass,
                null, null),
        ) + decodePaginatedLibraryGridMethod.parameters,
    )
    mutableClassDefBy(decodePaginatedLibraryGridMethod.definingClass).methods.add(
        clonedDecoderMethod,
    )
    return clonedDecoderMethod
}

// Read returned Library data

/**
 * Exposes YTM's Library readers and the response bytes needed to parse collections.
 * The pagination getter uses YTM's parser copied by [addPaginatedLibraryGridDecoder].
 */
private fun BytecodePatchContext.addPhoneBrowseResponseInterface() {
    val getTabsMethod = PhoneBrowseResponseTabsFingerprint.originalMethod
    val decodePaginatedLibraryGridMethod = LibraryPaginationDecoderFingerprint.originalMethod

    val getLibraryPaginationResponseProtoMethod = Fingerprint(
        definingClass = getTabsMethod.definingClass,
        returnType = decodePaginatedLibraryGridMethod
            .parameterTypes.single().toString(),
        parameters = listOf(),
        custom = { method, _ ->
            !AccessFlags.STATIC.isSet(method.accessFlags)
        }
    ).method

    val paginatedLibraryGridDecoderMethod = addPaginatedLibraryGridDecoder(
        decodePaginatedLibraryGridMethod,
    )
    val phoneBrowseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    phoneBrowseResponseClass.interfaces.add(PHONE_BROWSE_RESPONSE)
    val responseProto = getTabsMethod.instructions.mapNotNull { it.getReference<FieldReference>() }.distinct()
        .single { it.definingClass == phoneBrowseResponseClass.type && classDefByOrNull(it.type)?.let { type ->
            generateSequence(type) { current -> current.superclass?.let { parent -> classDefByOrNull(parent) } }
                .any { current -> current.interfaces.contains("Lcom/google/protobuf/MessageLite;") }
        } == true }
    phoneBrowseResponseClass.addInterfaceMethod(
        findInterfaceMethod(PHONE_BROWSE_RESPONSE, "patch_responseBytes"), 1, """
            iget-object p0, p0, $responseProto
            invoke-virtual { p0 }, ${responseProto.type}->toByteArray()[B
            move-result-object p0
            return-object p0
        """,
    )
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            PHONE_BROWSE_RESPONSE,
            "patch_getTabs",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getTabsMethod
            move-result-object p0
            return-object p0
        """
    )

    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            PHONE_BROWSE_RESPONSE,
            "patch_getPaginatedLibraryGrid",
        ),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $getLibraryPaginationResponseProtoMethod
            move-result-object p0
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $paginatedLibraryGridDecoderMethod
            move-result-object p0
            check-cast p0, $GRID_RENDERER
            return-object p0
        """
    )
}

/** Exposes a phone tab's decoded section contents so the Library grid can be read. */
private fun BytecodePatchContext.addPhoneBrowseTabInterface() {
    val (getSectionListMethod, getSectionRenderersMethod) = PhoneBrowseTabContentsFingerprint
        .instructionMatches.filter { it.instruction.opcode == Opcode.INVOKE_VIRTUAL }
        .map { it.instruction.getReference<MethodReference>()!! }
    val phoneBrowseTabClass = mutableClassDefBy(getSectionListMethod.definingClass)
    phoneBrowseTabClass.interfaces.add(PHONE_BROWSE_TAB)
    phoneBrowseTabClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            PHONE_BROWSE_TAB,
            "patch_getSectionContents",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getSectionListMethod
            move-result-object p0
            if-eqz p0, :no_sections
            invoke-virtual { p0 }, $getSectionRenderersMethod
            move-result-object p0
            return-object p0
            :no_sections
            const/4 p0, 0x0
            return-object p0
        """
    )
}

/** Exposes Library item renderers and pagination commands from YTM's GridRenderer container. */
private fun BytecodePatchContext.addGridRendererInterface() {
    val getItemsMethod = GridRendererItemsFingerprint.originalMethod
    val getPaginationCommandsMethod = gridPaginationCommandsFingerprint(getItemsMethod).originalMethod
    // The getters below call these private methods from the GridRenderer class.
    listOf(getItemsMethod, getPaginationCommandsMethod).forEach { method ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).apply {
            accessFlags = accessFlags.toPublicAccessFlags()
        }
    }

    val gridRendererType = getItemsMethod.parameterTypes.single().toString()
    val gridRendererClass = mutableClassDefBy(gridRendererType)
    gridRendererClass.interfaces.add(GRID_RENDERER)
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            GRID_RENDERER,
            "patch_getItems",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getItemsMethod
            move-result-object p0
            return-object p0
        """
    )
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            GRID_RENDERER,
            "patch_getPaginationCommands",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getPaginationCommandsMethod
            move-result-object p0
            return-object p0
        """
    )
}

// endregion

// region Library items

/**
 * Exposes Library playlist metadata and returns YTM's media ID encoder for playback.
 */
private fun BytecodePatchContext.patchPhoneBrowseItem(): MethodReference {
    // The field read after YTM's presence check identifies the class used for Library items.
    val phoneBrowseItemType = GridRendererItemsFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()!!
        .type
    val phoneBrowseItemFields = classDefBy(phoneBrowseItemType).fields.toList()

    fun phoneBrowseItemField(name: String) = phoneBrowseItemFields
        .single { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    // Both phone thumbnail renderers must agree on the container and decoded thumbnail fields.
    val (artworkContainerField, thumbnailDetailsField) = phoneBrowseItemArtworkFingerprint(phoneBrowseItemType)
        .matchAll()
        .map { match ->
            match.instructionMatches.mapNotNull { it.instruction.getReference<FieldReference>() }
        }
        .distinct()
        .single()
    val titleField = phoneBrowseItemField(TITLE_FIELD_NAME)
    val subtitleField = phoneBrowseItemField(SUBTITLE_FIELD_NAME)
    if (artworkContainerField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected phone Browse item metadata fields")
    }

    val browseIdField = CreatePhoneBrowseRequestFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the BrowseEndpoint browse ID field")
    // The native item builder encodes its command before converting its artwork.
    val (encodeCommandMatch, _, artworkUriMatch) =
        mediaDescriptionFingerprint(thumbnailDetailsField.type).instructionMatches
    val encodeCommandMediaIdMethod = encodeCommandMatch.instruction.getReference<MethodReference>()!!
    val createArtworkUriMethod = artworkUriMatch.instruction.getReference<MethodReference>()!!
    val itemCommandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    // Both tap commands are read; resolvePlaylistBrowseId does not depend on their order.
    val commandFields = phoneBrowseItemFields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == itemCommandType
    }
    if (commandFields.size != 2) throw PatchException("Expected two phone item commands")

    val commandToBrowseEndpointMethod = browseEndpointFromCommandFingerprint(
        itemCommandType,
        browseIdField.definingClass,
    ).originalMethod

    val formatTextMethod = formatTextFingerprint(titleField.type).originalMethod

    val phoneBrowseItemClass = mutableClassDefBy(phoneBrowseItemType)
    phoneBrowseItemClass.interfaces.add(PHONE_BROWSE_ITEM)
    phoneBrowseItemClass.addPlaylistBrowseIdGetter(
        findInterfaceMethod(PHONE_BROWSE_ITEM, "patch_getPlaylistBrowseId"),
        commandFields[0],
        commandFields[1],
        commandToBrowseEndpointMethod,
        browseIdField,
    )
    phoneBrowseItemClass.addTextGetter(
        findInterfaceMethod(PHONE_BROWSE_ITEM, "patch_getTitle"),
        titleField,
        formatTextMethod,
    )
    phoneBrowseItemClass.addTextGetter(
        findInterfaceMethod(PHONE_BROWSE_ITEM, "patch_getSubtitle"),
        subtitleField,
        formatTextMethod,
    )
    addArtworkUriGetter(phoneBrowseItemClass, artworkContainerField, thumbnailDetailsField, createArtworkUriMethod)
    return encodeCommandMediaIdMethod
}

/**
 * Reads both tap actions so conflicting playlist browse IDs can be rejected.
 */
private fun MutableClass.addPlaylistBrowseIdGetter(
    interfaceMethod: Method,
    firstCommandField: FieldReference,
    secondCommandField: FieldReference,
    commandToBrowseEndpointMethod: Method,
    browseIdField: FieldReference,
) {
    // YTM throws if the command does not open a page. collectPlaylistsFromGrid catches this and skips the item.
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            const/4 v0, 0x0
            iget-object v2, p0, $firstCommandField
            if-eqz v2, :check_second_command
            invoke-static { v2 }, $commandToBrowseEndpointMethod
            move-result-object v2
            iget-object v0, v2, $browseIdField

            :check_second_command
            const/4 v1, 0x0
            iget-object v2, p0, $secondCommandField
            if-eqz v2, :resolve_browse_id
            invoke-static { v2 }, $commandToBrowseEndpointMethod
            move-result-object v2
            iget-object v1, v2, $browseIdField

            :resolve_browse_id
            invoke-static { v0, v1 }, $EXTENSION_CLASS->resolvePlaylistBrowseId(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """
    )
}

// Read playlist titles and artwork

/** Titles and subtitles are stored as YTM text objects; use its formatter to read them. */
private fun MutableClass.addTextGetter(
    interfaceMethod: Method,
    textField: FieldReference,
    formatTextMethod: Method,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $textField
            # No separate pronunciation text is needed for the title or subtitle.
            const/4 v1, 0x0
            invoke-static { v0, v1 }, $formatTextMethod
            move-result-object v0
            return-object v0
        """
    )
}

/**
 * Uses YTM's artwork URI converter so Android Auto can load the phone item's thumbnail.
 */
private fun BytecodePatchContext.addArtworkUriGetter(
    itemClass: MutableClass,
    artworkContainerField: FieldReference,
    thumbnailDetailsField: FieldReference,
    createArtworkUriMethod: MethodReference,
) {
    val decodeArtworkPayloadMethod = decodeThumbnailFingerprint(
        artworkContainerField.type,
    ).originalMethod
    itemClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            PHONE_BROWSE_ITEM,
            "patch_getArtworkUri",
        ),
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $artworkContainerField
            invoke-static { v0 }, $decodeArtworkPayloadMethod
            move-result-object v0
            if-eqz v0, :no_artwork
            check-cast v0, ${thumbnailDetailsField.definingClass}
            iget-object v0, v0, $thumbnailDetailsField
            invoke-static { v0 }, $createArtworkUriMethod
            move-result-object v0
            return-object v0
            :no_artwork
            const/4 v0, 0x0
            return-object v0
        """
    )
}

// endregion

// region Android Auto requests

private fun BytecodePatchContext.patchBrowseRequests() {
    val sendEmptyItemsMethod = SendEmptyMediaItemsFingerprint.originalMethod
    addBrowseRequestInterface(sendEmptyItemsMethod)
    hookBrowseRequest(
        sendEmptyItemsMethod.definingClass,
        sendEmptyItemsMethod.parameterTypes.first().toString(),
    )
}

private fun BytecodePatchContext.addBrowseRequestInterface(
    sendEmptyItemsMethod: Method,
) {
    val requestType = sendEmptyItemsMethod.parameterTypes.first().toString()
    val requestClass = mutableClassDefBy(requestType)
    val requestFields = sendEmptyItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .toList()
    // YTM's "Invalid media id" log reads the requested Android Auto folder ID through these two fields.
    val requestedMediaIdHolderField = requestFields.single { field ->
        field.definingClass == requestType
    }
    val requestedMediaIdField = requestFields.single { field ->
        field.definingClass == requestedMediaIdHolderField.type && field.type == "Ljava/lang/String;"
    }
    skipCustomMediaIdDecoding(requestedMediaIdField)

    // YTM 9.15's one-argument overload forwards the list and null to this two-argument method.
    // Newer supported versions call it directly, so the patch can use the same method for all versions.
    val deliverItemsMethod = requestClass.methods.single { method ->
        method.returnType == "V" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.first().toString() == "Ljava/util/List;" &&
                method.parameterTypes.last().toString().startsWith("L")
    }

    requestClass.interfaces.add(BROWSE_REQUEST)
    requestClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            BROWSE_REQUEST,
            "patch_getRequestedMediaId",
        ),
        registerCount = 1,
        instructions = """
            iget-object p0, p0, $requestedMediaIdHolderField
            iget-object p0, p0, $requestedMediaIdField
            return-object p0
        """
    )

    requestClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            BROWSE_REQUEST,
            "patch_deliverBrowseItems",
        ),
        registerCount = 3,
        instructions = """
            const/4 v0, 0x0
            invoke-virtual { p0, p1, v0 }, $deliverItemsMethod
            return-void
        """
    )
    hookBrowseResults(deliverItemsMethod)
}

/**
 * YTM cannot parse our custom media IDs; it logs a warning and returns null.
 * Skip that failed parse and return the same result.
 */
private fun BytecodePatchContext.skipCustomMediaIdDecoding(requestedMediaIdField: FieldReference) {
    val decodedMediaIdMethod = decodedMediaIdFingerprint(requestedMediaIdField.definingClass).method
    val mediaIdRegister = decodedMediaIdMethod.findFreeRegister(0)
    decodedMediaIdMethod.addInstructionsWithLabels(
        0,
        """
            iget-object v$mediaIdRegister, p0, $requestedMediaIdField
            invoke-static/range { v$mediaIdRegister .. v$mediaIdRegister }, $EXTENSION_CLASS->isCustomMediaId(Ljava/lang/String;)Z
            move-result v$mediaIdRegister
            if-eqz v$mediaIdRegister, :resume
            const/4 v$mediaIdRegister, 0x0
            return-object v$mediaIdRegister
        """,
        ExternalLabel("resume", decodedMediaIdMethod.getInstruction<Instruction>(0)),
    )
}

/**
 * When the patch handles a page request, skip YTM's handler to avoid returning two results.
 */
private fun BytecodePatchContext.hookBrowseRequest(
    requestHandlerType: String,
    requestType: String,
) {
    Fingerprint(
        definingClass = requestHandlerType,
        returnType = "V",
        parameters = listOf(requestType)
    ).matchSingle().method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handleBrowseRequest($BROWSE_REQUEST)Z
            move-result v0
            if-eqz v0, :resume
            return-void
            :resume
            nop
        """
    )
}

// endregion

// region Android Auto connections and folder refresh

/**
 * Reloads Android Auto folders after successful Library changes.
 */
private fun BytecodePatchContext.patchFolderRefresh() {
    // Android Auto requests folder updates through MediaBrowserServiceCompat.
    // MediaBrowserService.notifyChildrenChanged does not reach that connection, so refresh through the compat service.
    val serviceSuperclass = classDefBy(MUSIC_BROWSER_SERVICE_CLASS).superclass
        ?: throw PatchException("Could not resolve MusicBrowserService's superclass")
    val baseServiceType = classDefBy(serviceSuperclass).superclass
        ?: throw PatchException("Could not resolve the browser service base class above $serviceSuperclass")
    val reloadMethod = mediaBrowserReloadFingerprint(baseServiceType).originalMethod

    addRequestConnectionGetter(reloadMethod)
    addFolderReload(baseServiceType, reloadMethod)
    hookLibraryChangeCompletion()
}

/** Identifies the Android Auto connection so refreshes target the right folder subscription. */
private fun BytecodePatchContext.addRequestConnectionGetter(reloadMethod: Method) {
    val connectionType = reloadMethod.parameterTypes[1].toString()
    // YTM passes the Android Auto connection to the object that delivers the folder's contents.
    val folderResultConstructor = reloadMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .distinct()
        .single { method ->
            method.name == "<init>" && connectionType in method.parameterTypes
        }
    val folderResultClass = mutableClassDefBy(folderResultConstructor.definingClass)
    val resultConnectionField = folderResultClass.fields.single { field ->
        field.type == connectionType
    }

    val requestType =
        SendEmptyMediaItemsFingerprint.originalMethod.parameterTypes.first().toString()
    val requestClass = mutableClassDefBy(requestType)
    // The request can hold other result types; check for the result object that stores the Android Auto connection.
    val resultBaseType = folderResultClass.superclass
        ?: throw PatchException("Could not resolve the Android Auto folder result base class")
    val requestResultField = requestClass.fields.single { field ->
        field.type == resultBaseType
    }

    folderResultClass.accessFlags = folderResultClass.accessFlags.toPublicAccessFlags()
    resultConnectionField.accessFlags = resultConnectionField.accessFlags.toPublicAccessFlags()
    requestClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            BROWSE_REQUEST,
            "patch_getBrowserConnection",
        ),
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $requestResultField
            instance-of v1, v0, ${folderResultClass.type}
            if-eqz v1, :unknown_connection
            check-cast v0, ${folderResultClass.type}
            iget-object v0, v0, $resultConnectionField
            return-object v0
            :unknown_connection
            # Without the connection, the patch cannot tell whether two requests update the same Android Auto folder.
            const/4 v0, 0x0
            return-object v0
        """
    )
}

/**
 * Retains folder subscriptions so Library changes can reload them on the current Auto connection.
 */
private fun BytecodePatchContext.addFolderReload(
    baseServiceType: String,
    reloadMethod: Method,
) {
    val connectionType = reloadMethod.parameterTypes[1].toString()
    val baseServiceClass = mutableClassDefBy(baseServiceType)
    baseServiceClass.interfaces.add(FOLDER_RELOAD)
    baseServiceClass.addInterfaceMethod(
        interfaceMethod = findInterfaceMethod(
            FOLDER_RELOAD,
            "patch_reloadFolder",
        ),
        registerCount = 4,
        instructions = """
            check-cast p2, $connectionType
            invoke-virtual { p0, p1, p2, p3 }, $reloadMethod
            return-void
        """
    )
    val rememberSubscriptionMethod = "$EXTENSION_CLASS->rememberSubscription(" +
            FOLDER_RELOAD +
            "Ljava/lang/String;Ljava/lang/Object;)V"
    baseServiceClass.findMutableMethodOf(reloadMethod).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p2 }, $rememberSubscriptionMethod
        """
    )
}

/** Refreshes Android Auto when a successful request changes the Library. */
private fun BytecodePatchContext.hookLibraryChangeCompletion() {
    val createRequestType = libraryChangeRequestFingerprint("playlist/create").originalMethod.definingClass
    val requestBaseType = classDefBy(createRequestType).superclass
        ?: throw PatchException("Could not resolve the Library request base class")
    val endpointOwnerType = classDefBy(requestBaseType).superclass
        ?: throw PatchException("Could not resolve the request endpoint's declaring class")

    // Callback and future requests both clear their serialized body after a successful response.
    val completionMethods = requestSuccessCallbackFingerprint(requestBaseType).matchAll()
        .map { match -> match.instructionMatches.first().instruction.getReference<MethodReference>()!! }
        .distinct()
    completionMethods.forEach { completionReference ->
        val completionMethod = requestCompletionFingerprint(completionReference).method
        val requestDataClass = classDefBy(completionMethod.definingClass)
        val requestField = requestDataClass.instanceFields.single { field -> field.type == requestBaseType }
        // Read the endpoint field used to build the request URL, rather than its obfuscated name.
        val endpointField = requestUrlFingerprint(requestDataClass.type, endpointOwnerType)
            .instructionMatches.first().instruction.getReference<FieldReference>()!!
        val requestRegister = completionMethod.findFreeRegister(0)
        completionMethod.addInstructions(
            0,
            """
                iget-object v$requestRegister, p0, $requestField
                iget-object v$requestRegister, v$requestRegister, $endpointField
                invoke-static/range { v$requestRegister .. v$requestRegister }, $EXTENSION_CLASS->onRequestSucceeded(Ljava/lang/String;)V
            """
        )
    }
}

// endregion

// region Android Auto browse results

/**
 * Adds Podcasts and applies Library layout changes before results reach Android Auto.
 */
private fun hookBrowseResults(deliverItemsMethod: MutableMethod) {
    // YTM 9.15 lacks the size guard used by later versions; oversized results otherwise leave Auto loading.
    val hasNativeSizeGuard = deliverItemsMethod.instructions.any {
        it.getReference<StringReference>()?.string == " from first 10 items."
    }
    if (!hasNativeSizeGuard) {
        deliverItemsMethod.addInstructions(0, """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->limitBrowseItems(Ljava/util/List;)Ljava/util/List;
            move-result-object p1
        """)
    }

    val handleBrowseResultMethod = "$EXTENSION_CLASS->handleBrowseResult(" +
            BROWSE_REQUEST +
            "Ljava/util/List;)Ljava/util/List;"

    deliverItemsMethod.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p1 }, $handleBrowseResultMethod
            move-result-object p1
        """
    )
}

// endregion

// region Playlist playback

/**
 * Uses YTM's playlist playback command so selecting a playlist loads its queue.
 */
private fun BytecodePatchContext.patchPlaylistMediaIdBuilder(encodeCommandMediaIdMethod: MethodReference) {
    val commandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    val createPlaybackCommandMethod = playlistPlaybackCommandFingerprint(commandType).originalMethod
    val buildCommandMethod = createPlaybackCommandMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .distinct()
        .single { method -> method.name == "build" && method.parameterTypes.isEmpty() }
    val patchClass = mutableClassDefBy(EXTENSION_CLASS)
    val stub = patchClass.methods.single { it.name == "createPlaylistMediaId" }
    val method = stub.cloneMutable(additionalRegisters = 7)
    method.addInstructions(
        0,
        """
            # No song ID; use the playlist ID and default playback options.
            const/4 v0, 0x0
            move-object v1, p0
            const/4 v2, 0x0
            const/4 v3, 0x0
            const/4 v4, 0x0
            const/4 v5, 0x0
            const/4 v6, 0x0
            invoke-static/range { v0 .. v6 }, $createPlaybackCommandMethod
            move-result-object v0
            invoke-virtual { v0 }, $buildCommandMethod
            move-result-object v0
            check-cast v0, $commandType
            invoke-static { v0 }, $encodeCommandMediaIdMethod
            move-result-object v0
            return-object v0
        """
    )
    patchClass.methods.remove(stub)
    patchClass.methods.add(method)
}

// endregion

// region Methods added to YTM classes

/** Reads the account used for requests so pending loads are not shared or delivered across accounts. */
internal fun BytecodePatchContext.addPhoneAccountScopeBridge(
    client: MutableClass, factory: MethodReference, api: String,
) {
    val method = classDefBy(factory.definingClass).methods.single {
        it.name == factory.name && it.parameterTypes == factory.parameterTypes
    }
    val constructors = method.instructions.mapNotNull { it.getReference<MethodReference>() }
        .filter { it.name == "<init>" && it.definingClass == factory.returnType }
    val getter = method.instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }
        .mapNotNull { it.getReference<MethodReference>() }.distinct().single { call ->
            call.parameterTypes.isEmpty() && constructors.any { call.returnType in it.parameterTypes } &&
                    client.instanceFields.any { it.type == call.definingClass }
        }
    val field = client.instanceFields.single { it.type == getter.definingClass }
    client.addInterfaceMethod(findInterfaceMethod(api, "patch_accountScope"), 1, """
        iget-object p0, p0, $field
        invoke-interface { p0 }, $getter
        move-result-object p0
        return-object p0
    """)
}

/** Decodes command bytes with YTM's protobuf registry before using its Android Auto media ID encoder. */
private fun BytecodePatchContext.patchCommandEncoder(encoder: MethodReference) {
    val type = encoder.parameterTypes.single().toString()
    val defaultInstance = protoDefaultInstanceFingerprint(type).matchSingle().instructionMatches.single()
        .instruction.getReference<FieldReference>()!!
    val hierarchy = buildSet {
        var current: String? = type
        while (current != null && add(current)) current = classDefByOrNull(current)?.superclass
    }
    val parser = protoParserFingerprint(hierarchy).matchSingle().originalMethod
    val patchClass = mutableClassDefBy(EXTENSION_CLASS)
    val stub = patchClass.methods.single { it.name == "encodeCommand" }
    val method = stub.cloneMutable(additionalRegisters = 2)
    method.addInstructions(0, """
        sget-object v0, $defaultInstance
        invoke-static {}, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
        move-result-object v1
        invoke-static { v0, p0, v1 }, $parser
        move-result-object v0
        check-cast v0, $type
        invoke-static { v0 }, $encoder
        move-result-object v0
        return-object v0
    """)
    patchClass.methods.remove(stub)
    patchClass.methods.add(method)
}

internal fun BytecodePatchContext.findInterfaceMethod(
    interfaceType: String,
    name: String,
) = classDefBy(interfaceType).methods.singleOrNull { method -> method.name == name }
    ?: throw PatchException("Could not resolve $name in $interfaceType")

/** Copies the interface signature so calls reach the method added to the YTM class. */
internal fun MutableClass.addInterfaceMethod(
    interfaceMethod: Method,
    registerCount: Int,
    instructions: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            interfaceMethod.name,
            interfaceMethod.parameters.map { parameter ->
                ImmutableMethodParameter(parameter.type, null, null)
            },
            interfaceMethod.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registerCount),
        ).toMutable().apply {
            addInstructions(0, instructions)
        }
    )
}

// endregion
