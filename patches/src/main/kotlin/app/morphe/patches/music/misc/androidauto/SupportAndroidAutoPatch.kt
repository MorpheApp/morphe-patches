/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto

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
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.cloneMutable
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversed
import app.morphe.util.p0Register
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch;"
private const val EXTENSION_PHONE_BROWSE_CLIENT_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseClient;"
private const val EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseResponse;"
private const val EXTENSION_PHONE_BROWSE_TAB_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseTab;"
private const val EXTENSION_SECTION_LIST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$SectionList;"
private const val EXTENSION_GRID_RENDERER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$GridRenderer;"
private const val EXTENSION_PLAYLIST_CONTENTS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaylistContents;"
private const val EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$AndroidAutoBrowseRequest;"
private const val EXTENSION_PLAYBACK_CALLBACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaybackCallback;"
private const val EXTENSION_PHONE_BROWSE_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseItem;"
private const val MUSIC_BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"

// Constructor calls pass the MediaDescriptionCompat instance before the media ID and title.
private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

// YTM uses the same data type for playlists and songs.
private const val TITLE_FIELD_NAME = "g"
private const val SUBTITLE_FIELD_NAME = "h"

private const val PLAY_BUTTON_CONTAINER_FIELD_NAME = "q"

/**
 * Supplies Android Auto with the playlists available in YTM's phone Library
 * for display and playback.
 *
 * Installation order during patching, before the app runs:
 * 1. [hookPlaylistsTitleMediaIds] identifies Playlists in Android Auto's Library by its translated title.
 * 2. [installPhoneBrowseClientBridges] lets Java fetch the phone Library and selected playlists
 *    through YTM's existing request methods, including Library pagination.
 * 3. [patchPhoneBrowseResponses] lets Java extract Library items, playlist songs, and the playlist's
 *    Play button from the data those requests return.
 * 4. [patchPhoneBrowseItem] provides playlist IDs, titles, and artwork, and distinguishes songs
 *    from the Add a song button.
 * 5. [patchAndroidAutoPlaylists] lets Java answer requests for Playlists instead of returning YTM's empty list.
 * 6. [installPlaybackCallbackBridges] lets Java load a selected playlist before asking YTM to play it,
 *    using YTM's playback thread.
 */
@Suppress("unused")
val supportAndroidAutoPatch = bytecodePatch(
    name = "Restore playlists in Android Auto",
    description = "Restores YouTube Music playlists in Android Auto.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookPlaylistsTitleMediaIds()
        installPhoneBrowseClientBridges()
        patchPhoneBrowseResponses()
        patchPhoneBrowseItem()
        patchAndroidAutoPlaylists()
        installPlaybackCallbackBridges()
    }
}

// region Identify the Playlists folder in Android Auto's Library

/**
 * Playlists has no fixed Android Auto media ID. Pass item IDs and titles to Java's
 * `rememberPlaylistsTitleMatch`, which recognizes it by its translated title.
 */
private fun BytecodePatchContext.hookPlaylistsTitleMediaIds() {
    val buildAndroidAutoMediaItemMethod = BuildAndroidAutoMediaItemFingerprint.method

    buildAndroidAutoMediaItemMethod
        // Capture the Playlists folder ID and title regardless of which branch creates it.
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { index ->
            val instruction =
                buildAndroidAutoMediaItemMethod.getInstruction<RegisterRangeInstruction>(
                    index,
                )
            val mediaDescriptionMediaIdRegister =
                instruction.startRegister + MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET
            val titleRegister =
                instruction.startRegister + MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET

            buildAndroidAutoMediaItemMethod.addInstructions(
                index,
                """
                    invoke-static/range { v$mediaDescriptionMediaIdRegister .. v$titleRegister }, $EXTENSION_CLASS->rememberPlaylistsTitleMatch(Ljava/lang/String;Ljava/lang/CharSequence;)V
                """,
            )
        }
}

// endregion

// region Request the Library and selected playlists

/**
 * Reuses the YTM object that sends requests for the phone's Library and playlist contents.
 * [addPhoneBrowseRequestMethod] requests either by page ID; [addLibraryPaginationRequestMethod]
 * requests more Library items. [capturePhoneBrowseClientOnServiceCreate] saves the object for Java to call.
 */
private fun BytecodePatchContext.installPhoneBrowseClientBridges() {
    val createRequestFromBrowseEndpointMethod = CreatePhoneBrowseRequestFingerprint.originalMethod
    val phoneBrowseRequestType = createRequestFromBrowseEndpointMethod.returnType
    val createPhoneBrowseRequestMethod = createRequestFromBrowseEndpointMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { reference ->
            reference.parameterTypes.isEmpty() &&
                reference.returnType == phoneBrowseRequestType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the method that creates a phone Browse request")
    // YTM creates and sends Library requests from the same class.
    val phoneBrowseClientType = createPhoneBrowseRequestMethod.definingClass
    val phoneBrowseRequestSenderFingerprint = sendPhoneBrowseRequestFingerprint(
        phoneBrowseClientType,
        phoneBrowseRequestType,
    )
    val sendPhoneBrowseRequestMethod = phoneBrowseRequestSenderFingerprint.originalMethod
    val requestBrowseIdField = phoneBrowseRequestSenderFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val phoneBrowseClientClass = mutableClassDefBy(phoneBrowseClientType)
    phoneBrowseClientClass.interfaces.add(EXTENSION_PHONE_BROWSE_CLIENT_INTERFACE)
    addPhoneBrowseRequestMethod(
        phoneBrowseClientClass,
        createPhoneBrowseRequestMethod,
        sendPhoneBrowseRequestMethod,
        requestBrowseIdField,
    )
    addLibraryPaginationRequestMethod(
        phoneBrowseClientClass,
        phoneBrowseRequestType,
        sendPhoneBrowseRequestMethod,
    )
    capturePhoneBrowseClientOnServiceCreate(phoneBrowseClientType)
}

/**
 * Lets Java fetch the phone Library or a selected playlist by its Browse ID (YTM's page ID).
 * YTM creates and sends the request; the returned future contains the Library items or playlist contents.
 */
private fun BytecodePatchContext.addPhoneBrowseRequestMethod(
    phoneBrowseClientClass: MutableClass,
    createPhoneBrowseRequestMethod: MethodReference,
    sendPhoneBrowseRequestMethod: Method,
    requestBrowseIdField: FieldReference,
) {
    val phoneBrowseRequestType = createPhoneBrowseRequestMethod.returnType
    val requestHierarchyMethods = generateSequence(
        classDefBy(phoneBrowseRequestType),
    ) { classDef ->
        classDef.superclass?.let { superclass -> classDefByOrNull(superclass) }
    }.flatMap { classDef -> classDef.methods.asSequence() }
    val clickTrackingParamsSetterMethod = requestHierarchyMethods
        // A public byte[] setter can write the same clickTrackingParams field.
        // Select the protected setter to avoid matching both.
        .singleOrNull { method ->
            AccessFlags.PROTECTED.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("[B")
        }
        ?: throw PatchException("Could not uniquely resolve the click tracking parameter setter")
    val setRequestBrowseIdMethod = setRequestBrowseIdFingerprint(
        requestBrowseIdField,
    ).originalMethod
    phoneBrowseClientClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_CLIENT_INTERFACE,
            "patch_requestBrowse",
        ),
        registerCount = 5,
        instructions = """
            invoke-virtual { p0 }, $createPhoneBrowseRequestMethod
            move-result-object v0
            invoke-virtual { v0, p1 }, $setRequestBrowseIdMethod
            # YTM rejects null clickTrackingParams, so pass an empty byte array.
            const/4 v1, 0x0
            new-array v1, v1, [B
            invoke-virtual { v0, v1 }, $clickTrackingParamsSetterMethod
            invoke-virtual { p0, v0, p2 }, $sendPhoneBrowseRequestMethod
            move-result-object v0
            return-object v0
        """,
    )
}

// Library pagination requests

/** Requests the next Library page using the pagination command from the previous response. */
private fun BytecodePatchContext.addLibraryPaginationRequestMethod(
    phoneBrowseClientClass: MutableClass,
    phoneBrowseRequestType: String,
    sendPhoneBrowseRequestMethod: Method,
) {
    val getGridItemsMethod = GridRendererItemsFingerprint.originalMethod
    val getGridPaginationCommandsMethod = gridPaginationCommandsFingerprint(
        getGridItemsMethod,
    ).originalMethod
    // The pagination request accepts the command type created by getGridPaginationCommandsMethod.
    val paginationReaderReturnTypes =
        getGridPaginationCommandsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .map { reference -> reference.returnType }
        .toSet()
    val createPaginationRequestMethod = classDefBy(phoneBrowseClientClass.type).methods.singleOrNull { method ->
        method.returnType == phoneBrowseRequestType &&
            method.parameterTypes.singleOrNull()?.toString() in paginationReaderReturnTypes
    } ?: throw PatchException("Could not resolve the Library pagination request method")
    val paginationCommandType = createPaginationRequestMethod
        .parameterTypes.single().toString()
    phoneBrowseClientClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_CLIENT_INTERFACE,
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
        """,
    )
}

// Obtain YTM's object for sending Library and playlist requests

/**
 * Saves the object YTM creates for Library and playlist requests when MusicBrowserService starts.
 * Java's `setPhoneBrowseClient` keeps it for requests made while Android Auto is connected.
 */
private fun BytecodePatchContext.capturePhoneBrowseClientOnServiceCreate(phoneBrowseClientType: String) {
    val phoneBrowseClientProviderCandidates =
        phoneBrowseClientProviderFingerprint(phoneBrowseClientType)
            .matchAll()
            .map { match ->
                val (providerFieldMatch, providerGetMatch) = match.instructionMatches
                val providerField = providerFieldMatch.instruction.getReference<FieldReference>()!!
                val providerGetMethod = providerGetMatch.instruction.getReference<MethodReference>()!!
                providerField to providerGetMethod
            }
            .distinctBy { (field, _) -> field }
    // Several providers create this request sender. Select the one used when MusicBrowserService starts.
    val (onCreateMatch, providerField, providerGetMethod) = phoneBrowseClientProviderCandidates
        .mapNotNull { (providerField, providerGetMethod) ->
            musicBrowserServiceSuperclassOnCreateFingerprint(
                MUSIC_BROWSER_SERVICE_CLASS,
                providerField.definingClass,
            ).matchOrNull()?.let { match ->
                Triple(match, providerField, providerGetMethod)
            }
        }
        .singleOrNull()
        ?: throw PatchException(
            "Could not resolve MusicBrowserService's BS_GET_BROWSE_DATA provider",
        )
    val mutableOnCreateMethod = onCreateMatch.method
    val generatedComponentReadIndex = onCreateMatch.instructionMatches.single { match ->
        val field = match.instruction.getReference<FieldReference>()
        match.instruction.opcode == Opcode.IGET_OBJECT &&
            field?.type == providerField.definingClass
    }.index
    val generatedComponentRegister = mutableOnCreateMethod
        .getInstruction<TwoRegisterInstruction>(generatedComponentReadIndex)
        .registerA
    val phoneBrowseClientRegister = mutableOnCreateMethod.findFreeRegister(
        generatedComponentReadIndex + 1,
        mutableOnCreateMethod.p0Register,
    )
    mutableOnCreateMethod.addInstructions(
        generatedComponentReadIndex + 1,
        """
            iget-object v$phoneBrowseClientRegister, v$generatedComponentRegister, $providerField
            invoke-interface/range { v$phoneBrowseClientRegister .. v$phoneBrowseClientRegister }, $providerGetMethod
            move-result-object v$phoneBrowseClientRegister
            check-cast v$phoneBrowseClientRegister, $EXTENSION_PHONE_BROWSE_CLIENT_INTERFACE
            invoke-static/range { v$phoneBrowseClientRegister .. v$phoneBrowseClientRegister }, $EXTENSION_CLASS->setPhoneBrowseClient($EXTENSION_PHONE_BROWSE_CLIENT_INTERFACE)V
        """,
    )
}

// endregion

// region Read Library and playlist responses

/**
 * Lets Java extract the lists returned by YTM's phone Library and playlist requests.
 * YTM nests the lists inside TabRenderer and section data. [addPhoneBrowsePageInterfaces] installs
 * PhoneBrowseTab and SectionList on the YTM objects that read this data, so Java can reach the lists.
 * [addGridRendererInterface] provides Library items; [addPlaylistContentsInterface] provides playlist songs.
 * Pagination may return Library items directly or in its first section; [addPhoneBrowseResponseInterface] handles both.
 */
private fun BytecodePatchContext.patchPhoneBrowseResponses() {
    addPhoneBrowseResponseInterface()
    addPhoneBrowsePageInterfaces()
    addGridRendererInterface()
    addPlaylistContentsInterface()
}

// Library pagination responses

/**
 * Reuses YTM's Library pagination parser without creating the phone's Library list UI.
 * It reads items directly or from the first section. An unrecognized result returns null.
 */
private fun BytecodePatchContext.addPaginatedLibraryGridDecoder(
    decodePaginatedLibraryGridMethod: Method,
): Method {
    // YTM's pagination parser only needs the response data.
    // Copy it as a static method so Android Auto can use it without creating
    // the object that manages the phone's Library list.
    val clonedDecoderMethod = decodePaginatedLibraryGridMethod.cloneMutable(
        name = "patch_decodePaginatedLibraryGrid",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        // The original method uses p0 for "this" and p1 for the response.
        // Keep an unused first argument so the copied code still finds the response in p1.
        parameters = listOf(
            ImmutableMethodParameter(decodePaginatedLibraryGridMethod.definingClass, null, null),
        ) + decodePaginatedLibraryGridMethod.parameters,
    )
    mutableClassDefBy(decodePaginatedLibraryGridMethod.definingClass).methods.add(
        clonedDecoderMethod,
    )
    return clonedDecoderMethod
}

// Read returned Library and playlist data

/**
 * Gives Java access to the lists and playlist Play button in YTM's returned phone data.
 * The pagination getter uses YTM's parser copied by [addPaginatedLibraryGridDecoder].
 * [addPlaylistPlayButtonMediaIdGetter] adds access to a media ID that starts the selected playlist.
 */
private fun BytecodePatchContext.addPhoneBrowseResponseInterface() {
    val getTabsMethod = PhoneBrowseResponseTabsFingerprint.originalMethod
    val decodePaginatedLibraryGridMethod = LibraryPaginationDecoderFingerprint.originalMethod
    val getLibraryPaginationResponseProtoMethod = classDefBy(
        getTabsMethod.definingClass,
    ).methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
        method.returnType == decodePaginatedLibraryGridMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the Library pagination response method")
    val paginatedLibraryGridDecoderMethod = addPaginatedLibraryGridDecoder(
        decodePaginatedLibraryGridMethod,
    )
    val encodeCommandMediaIdMethod = EncodeCommandMediaIdFingerprint.originalMethod
    val phoneBrowseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    phoneBrowseResponseClass.interfaces.add(EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE)
    addPlaylistPlayButtonMediaIdGetter(
        phoneBrowseResponseClass,
        getTabsMethod,
        encodeCommandMediaIdMethod,
    )
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE,
            "patch_getTabs",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getTabsMethod
            move-result-object p0
            return-object p0
        """,
    )
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE,
            "patch_getPaginatedLibraryGrid",
        ),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $getLibraryPaginationResponseProtoMethod
            move-result-object p0
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $paginatedLibraryGridDecoderMethod
            move-result-object p0
            check-cast p0, $EXTENSION_GRID_RENDERER_INTERFACE
            return-object p0
        """,
    )
}

// Play a selected playlist: the Play button above the song list

/** YTM types and methods used to read a playlist's Play button. */
private data class PlaylistPlayButton(
    val containingMessageType: String,
    val decodeMethod: Method,
    val commandField: FieldReference,
)

/**
 * Locates YTM's method that extracts the Play button from returned playlist data,
 * then identifies the field containing what YTM runs when Play is pressed.
 * [addPlaylistPlayButtonMediaIdGetter] uses it to reproduce a press of the phone's Play button.
 */
private fun BytecodePatchContext.findPlaylistPlayButton(commandType: String): PlaylistPlayButton {
    val playButtonRegistrationMatch = playButtonRendererFingerprint(commandType)
    val initializer = playButtonRegistrationMatch.originalMethod
    val playButtonProtoFieldIndex = playButtonRegistrationMatch.instructionMatches.single().index
    // This initializer registers ButtonRenderer under field 65153809 and a different message under 79971800.
    // Read the type and extension field from ButtonRenderer's registration only.
    val registrationStart = initializer.indexOfFirstInstructionReversed(
        playButtonProtoFieldIndex - 1,
        Opcode.SPUT_OBJECT,
    ) + 1
    val registrationEnd = initializer.indexOfFirstInstructionOrThrow(playButtonProtoFieldIndex, Opcode.SPUT_OBJECT)
    val registrationInstructions = initializer.instructions
        .drop(registrationStart).take(registrationEnd - registrationStart + 1)

    val buttonRendererType = registrationInstructions
        .singleOrNull { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        ?.getReference<TypeReference>()?.type
        ?: throw PatchException("Could not uniquely resolve the Play button's message type")
    val registerProtobufExtensionInstruction = registrationInstructions.singleOrNull { instruction ->
        instruction.getReference<MethodReference>()?.name == "newSingularGeneratedExtension"
    } as? RegisterRangeInstruction
        ?: throw PatchException("Could not resolve the Play button's extension registration call")
    // newSingularGeneratedExtension takes the data type containing the Play button as its first argument.
    val playButtonContainerRegister = registerProtobufExtensionInstruction.startRegister
    val playButtonContainerType = registrationInstructions.singleOrNull { instruction ->
        instruction.opcode == Opcode.SGET_OBJECT &&
            (instruction as OneRegisterInstruction).registerA == playButtonContainerRegister
    }?.getReference<FieldReference>()?.type
        ?: throw PatchException("Could not uniquely resolve the playlist data containing the Play button")
    val playButtonProtobufExtensionField = initializer.getInstruction<Instruction>(registrationEnd)
        .getReference<FieldReference>()!!

    val decodePlayButtonMethod = decodeButtonRendererFingerprint(
        playButtonContainerType,
        buttonRendererType,
        playButtonProtobufExtensionField,
    ).originalMethod

    // The playlist Play button and live chat button store their commands in the same field of YTM's button data.
    // Use the live chat button's code to identify that field.
    val copiedPlayCommandFields = buttonRendererCommandCopyFingerprint(
        buttonRendererType,
        commandType,
    ).matchAll()
        .map { match ->
            // IGET_OBJECT reads the button's command; IPUT_OBJECT copies it to the object handling the press.
            val playButtonCommandReadMatch = match.instructionMatches.single { instructionMatch ->
                instructionMatch.instruction.opcode == Opcode.IGET_OBJECT
            }
            playButtonCommandReadMatch.instruction.getReference<FieldReference>()!!
        }
    val playCommandField = copiedPlayCommandFields
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the command stored in the playlist Play button")

    return PlaylistPlayButton(
        containingMessageType = playButtonContainerType,
        decodeMethod = decodePlayButtonMethod,
        commandField = playCommandField,
    )
}

/**
 * Gives Java a media ID that starts the playlist as if its phone Play button were pressed.
 * [findPlaylistPlayButton] identifies the button data. YTM's encoder converts its playback
 * command into the string accepted by onPlayFromMediaId; a missing button or command returns null.
 */
private fun BytecodePatchContext.addPlaylistPlayButtonMediaIdGetter(
    phoneBrowseResponseClass: MutableClass,
    getTabsMethod: Method,
    encodeCommandMediaIdMethod: Method,
) {
    val commandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    val playButton = findPlaylistPlayButton(commandType)
    // Exclude the cached tab list to select the original response containing the Play button.
    val phoneBrowseResponseProtoField = getTabsMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .filter { field ->
            field.definingClass == getTabsMethod.definingClass &&
                field.type != getTabsMethod.returnType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the phone Browse response message field")
    // Use the same response.q that YTM decodes to display the playlist's Play button.
    val playButtonContainerField =
        classDefBy(phoneBrowseResponseProtoField.type).fields.singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) &&
                field.name == PLAY_BUTTON_CONTAINER_FIELD_NAME &&
                field.type == playButton.containingMessageType
        } ?: throw PatchException("Could not resolve the playlist field containing the Play button")

    val decodePlayButton = 0x1
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE,
            "patch_getPlaylistPlayButtonMediaId",
        ),
        registerCount = 7,
        instructions = """
            iget-object v0, p0, $phoneBrowseResponseProtoField
            iget-object v1, v0, $playButtonContainerField
            # Enable decoding of the playlist Play button; false returns null.
            const/4 v2, $decodePlayButton
            invoke-static { v2, v1 }, ${playButton.decodeMethod}
            move-result-object v3
            if-eqz v3, :no_playlist_play_button_media_id
            iget-object v4, v3, ${playButton.commandField}
            if-eqz v4, :no_playlist_play_button_media_id
            invoke-static { v4 }, $encodeCommandMediaIdMethod
            move-result-object v5
            return-object v5
            :no_playlist_play_button_media_id
            const/4 v5, 0x0
            return-object v5
        """,
    )
}

// Read Library items and playlist songs

/** Connects Java's PhoneBrowseTab and SectionList methods to the nested lists in YTM's phone response. */
private fun BytecodePatchContext.addPhoneBrowsePageInterfaces() {
    val getTabsMethod = PhoneBrowseResponseTabsFingerprint.originalMethod
    val tabMapperMatch = PhoneBrowseResponseTabsFingerprint.instructionMatches.single { match ->
        match.instruction.opcode == Opcode.NEW_INSTANCE
    }
    val tabMapperType = tabMapperMatch
        .instruction
        .getReference<TypeReference>()!!
        .type
    val tabWrapperMatch = createPhoneBrowseTabFingerprint(
        tabMapperType,
    ).instructionMatches.single { match ->
        match.instruction.opcode == Opcode.NEW_INSTANCE
    }
    val tabWrapperType = tabWrapperMatch
        .instruction
        .getReference<TypeReference>()!!
        .type
    val getSectionListMethod = getSectionListFingerprint(tabWrapperType).originalMethod
    val getSectionContentsMethod = sectionListContentsFingerprint(
        getSectionListMethod.returnType,
        getTabsMethod.returnType,
    ).originalMethod

    addPhoneBrowseTabInterface(getSectionListMethod)
    addSectionListInterface(getSectionContentsMethod)
}

/** Provides the section list inside a TabRenderer; [addSectionListInterface] reads the Library items or songs inside it. */
private fun BytecodePatchContext.addPhoneBrowseTabInterface(
    getSectionListMethod: Method,
) {
    val phoneBrowseTabClass = mutableClassDefBy(getSectionListMethod.definingClass)
    phoneBrowseTabClass.interfaces.add(EXTENSION_PHONE_BROWSE_TAB_INTERFACE)
    phoneBrowseTabClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_TAB_INTERFACE,
            "patch_getSectionList",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getSectionListMethod
            move-result-object p0
            check-cast p0, $EXTENSION_SECTION_LIST_INTERFACE
            return-object p0
        """,
    )
}

/** Exposes the lists inside a Library or playlist response so their contents can be collected. */
private fun BytecodePatchContext.addSectionListInterface(
    getSectionContentsMethod: Method,
) {
    val sectionListClass = mutableClassDefBy(getSectionContentsMethod.definingClass)
    sectionListClass.interfaces.add(EXTENSION_SECTION_LIST_INTERFACE)
    sectionListClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_SECTION_LIST_INTERFACE,
            "patch_getContents",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getSectionContentsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

/** Provides the Library's playlists, artists, and podcasts, plus the commands used to load more of them. */
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
    gridRendererClass.interfaces.add(EXTENSION_GRID_RENDERER_INTERFACE)
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_GRID_RENDERER_INTERFACE,
            "patch_getItems",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_GRID_RENDERER_INTERFACE,
            "patch_getPaginationCommands",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getPaginationCommandsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

/** Reads playlist contents for the empty-playlist check and Liked Music playback. */
private fun BytecodePatchContext.addPlaylistContentsInterface() {
    val phoneBrowseItemType = PhoneBrowseItemFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val getItemsMethod = playlistItemsFingerprint(phoneBrowseItemType).originalMethod
    // The getter below calls this private method from the playlist contents class.
    mutableClassDefBy(getItemsMethod.definingClass).findMutableMethodOf(getItemsMethod).apply {
        accessFlags = accessFlags.toPublicAccessFlags()
    }

    val playlistContentsType = getItemsMethod.parameterTypes.first().toString()
    val playlistContentsClass = mutableClassDefBy(playlistContentsType)
    playlistContentsClass.interfaces.add(EXTENSION_PLAYLIST_CONTENTS_INTERFACE)
    // YTM can return the song/button data directly or wrap it for the phone's list.
    // PhoneBrowseItem's getters are installed on the original data type.
    val createUiObjects = 0x0
    playlistContentsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PLAYLIST_CONTENTS_INTERFACE,
            "patch_getItems",
        ),
        registerCount = 2,
        instructions = """
            const/4 v0, $createUiObjects
            invoke-static { p0, v0 }, $getItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

// endregion

// region Library and playlist items

/**
 * YTM uses one item type for Library content, playlist songs, and the Add a song button.
 * [addPlaylistBrowseIdGetter] identifies playlists among Library items; [addVideoIdCheck] identifies
 * songs among playlist contents. [addTextGetter] provides titles; [findPhoneBrowseItemArtworkField]
 * and [addArtworkUriGetter] use YTM's thumbnail code to provide artwork URIs.
 * YTM's phone list has separate commands for single tap and double tap.
 * Playback through [addCommandMediaIdGetter] uses the default single tap command,
 * or double tap if the single tap command is absent. The song ID check must inspect that same command.
 */
private fun BytecodePatchContext.patchPhoneBrowseItem() {
    val phoneBrowseItemType = PhoneBrowseItemFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val phoneBrowseItemFields = classDefBy(phoneBrowseItemType).fields.toList()

    fun phoneBrowseItemField(name: String) = phoneBrowseItemFields
        .single { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkContainerField = findPhoneBrowseItemArtworkField(phoneBrowseItemType)
    val titleField = phoneBrowseItemField(TITLE_FIELD_NAME)
    val subtitleField = phoneBrowseItemField(SUBTITLE_FIELD_NAME)
    if (artworkContainerField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected phone Browse item metadata fields")
    }

    val browseEndpointBrowseIdField = CreatePhoneBrowseRequestFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the BrowseEndpoint browse ID field")
    val encodeCommandMediaIdMethod = EncodeCommandMediaIdFingerprint.originalMethod
    val itemCommandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    val singleTapCommandField = phoneBrowseItemSingleTapCommandFingerprint(phoneBrowseItemType, itemCommandType)
        .instructionMatches.single().instruction.getReference<FieldReference>()!!
    // YTM's phone list uses the other command field for double tap.
    val doubleTapCommandField = phoneBrowseItemFields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.type == itemCommandType && field != singleTapCommandField
    } ?: throw PatchException("Could not resolve the phone item's double tap command")

    val commandToBrowseEndpointMethod = browseEndpointFromCommandFingerprint(
        itemCommandType,
        browseEndpointBrowseIdField.definingClass,
    ).originalMethod

    val formatTextMethod = formatTextFingerprint(titleField.type).originalMethod

    val phoneBrowseItemClass = mutableClassDefBy(phoneBrowseItemType)
    phoneBrowseItemClass.interfaces.add(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE)
    phoneBrowseItemClass.addPlaylistBrowseIdGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getPlaylistBrowseId"),
        singleTapCommandField,
        doubleTapCommandField,
        commandToBrowseEndpointMethod,
        browseEndpointBrowseIdField,
    )
    val readItemCommand = """
        iget-object v0, p0, $singleTapCommandField
        if-nez v0, :have_command
        iget-object v0, p0, $doubleTapCommandField
        :have_command
    """
    phoneBrowseItemClass.addCommandMediaIdGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getCommandMediaId"),
        readItemCommand,
        encodeCommandMediaIdMethod,
    )
    phoneBrowseItemClass.addVideoIdCheck(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_hasPlayableVideoId"),
        readItemCommand,
        findWatchEndpointAccess(itemCommandType),
    )
    phoneBrowseItemClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getTitle"),
        titleField,
        formatTextMethod,
    )
    phoneBrowseItemClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getSubtitle"),
        subtitleField,
        formatTextMethod,
    )
    addArtworkUriGetter(phoneBrowseItemClass, artworkContainerField)
}

/**
 * Reads page IDs from both tap commands for Java's resolvePlaylistBrowseId to select a playlist.
 */
private fun MutableClass.addPlaylistBrowseIdGetter(
    interfaceMethod: Method,
    singleTapCommandField: FieldReference,
    doubleTapCommandField: FieldReference,
    commandToBrowseEndpointMethod: Method,
    browseEndpointBrowseIdField: FieldReference,
) {
    // YTM throws if the command does not open a page. collectPlaylistsFromGrid catches this and skips the item.
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            const/4 v0, 0x0
            iget-object v2, p0, $singleTapCommandField
            if-eqz v2, :check_double_tap_command
            invoke-static { v2 }, $commandToBrowseEndpointMethod
            move-result-object v2
            iget-object v0, v2, $browseEndpointBrowseIdField

            :check_double_tap_command
            const/4 v1, 0x0
            iget-object v2, p0, $doubleTapCommandField
            if-eqz v2, :resolve_playlist_id
            invoke-static { v2 }, $commandToBrowseEndpointMethod
            move-result-object v2
            iget-object v1, v2, $browseEndpointBrowseIdField

            :resolve_playlist_id
            invoke-static { v0, v1 }, $EXTENSION_CLASS->resolvePlaylistBrowseId(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """,
    )
}

// Read the playback command attached to a song

/**
 * Converts a song's playback command into the media ID accepted by YTM's onPlayFromMediaId callback.
 * [addVideoIdCheck] excludes the Add a song button, whose command can also be converted to a media ID.
 */
private fun MutableClass.addCommandMediaIdGetter(
    interfaceMethod: Method,
    readItemCommand: String,
    encodeCommandMediaIdMethod: Method,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 2,
        instructions = """
            $readItemCommand
            if-nez v0, :encode_command_media_id
            # Reset v0 to an explicit null so Android's bytecode verifier accepts the String return type.
            const/4 v0, 0x0
            goto :return_command_media_id

            :encode_command_media_id
            invoke-static { v0 }, $encodeCommandMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            :return_command_media_id
            return-object v0
        """,
    )
}

// Check playlist contents

/**
 * Fields and methods for reading a song ID from WatchEndpoint, YTM's playback command data.
 */
private data class WatchEndpointAccess(
    val protobufExtensionField: FieldReference,
    val messageType: String,
    val videoIdField: FieldReference,
    val protobufExtensionSetField: FieldReference,
    val protobufExtensionKeyField: FieldReference,
    val hasProtobufExtensionMethod: Method,
    val getProtobufExtensionMethod: Method,
)

/** Identifies where YTM stores a song's ID for the check installed by [addVideoIdCheck]. */
private fun BytecodePatchContext.findWatchEndpointAccess(commandType: String): WatchEndpointAccess {
    val watchEndpointInitializer = WatchEndpointExtensionFingerprint.originalMethod
    val watchEndpointProtobufExtensionField = watchEndpointInitializer.instructions
        .filter { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .singleOrNull { field -> field.definingClass == watchEndpointInitializer.definingClass }
        ?: throw PatchException("Could not resolve the WatchEndpoint extension field")
    val watchEndpointType = watchEndpointInitializer.instructions
        .filter { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .mapNotNull { instruction -> instruction.getReference<TypeReference>()?.type }
        .singleOrNull()
        ?: throw PatchException("Could not resolve the WatchEndpoint message type")
    // YTM's WatchEndpoint resolver checks d for an empty video ID.
    val watchEndpointVideoIdField = classDefBy(watchEndpointType).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.name == "d" && field.type == "Ljava/lang/String;"
    } ?: throw PatchException("Could not resolve WatchEndpoint.videoId")

    val commandSuperclass = classDefBy(commandType).superclass
        ?: throw PatchException("Could not resolve the command superclass")
    // YTM reads the inherited j field to check or retrieve a command's protobuf extensions.
    val protobufExtensionSetField = classDefBy(commandSuperclass).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == "j"
    } ?: throw PatchException("Could not resolve the command extension set")
    val protobufExtensionSetClass = classDefBy(protobufExtensionSetField.type)
    val getWatchEndpointMethod = protobufExtensionSetClass.methods.singleOrNull { method ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "Ljava/lang/Object;" &&
            method.parameterTypes.singleOrNull()?.startsWith("L") == true
    } ?: throw PatchException("Could not resolve the method that reads a protobuf extension")
    // The getter's parameter type identifies the lookup key stored in the extension registration.
    val protobufExtensionKeyType = getWatchEndpointMethod.parameterTypes.single().toString()
    val protobufExtensionKeyField = classDefBy(watchEndpointProtobufExtensionField.type).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == protobufExtensionKeyType
    } ?: throw PatchException("Could not resolve the WatchEndpoint extension key")
    val hasWatchEndpointMethod = protobufExtensionSetClass.methods.singleOrNull { method ->
        method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(protobufExtensionKeyType)
    } ?: throw PatchException("Could not resolve the method that checks for WatchEndpoint")

    return WatchEndpointAccess(
        protobufExtensionField = watchEndpointProtobufExtensionField,
        messageType = watchEndpointType,
        videoIdField = watchEndpointVideoIdField,
        protobufExtensionSetField = protobufExtensionSetField,
        protobufExtensionKeyField = protobufExtensionKeyField,
        hasProtobufExtensionMethod = hasWatchEndpointMethod,
        getProtobufExtensionMethod = getWatchEndpointMethod,
    )
}

/**
 * The Add a song button can have a media ID, so an ID alone does not prove a playlist contains songs.
 * [findWatchEndpointAccess] locates the song ID inside a playback command.
 * Use the same command as [addCommandMediaIdGetter] when checking for that ID.
 */
private fun MutableClass.addVideoIdCheck(
    interfaceMethod: Method,
    readItemCommand: String,
    watchEndpoint: WatchEndpointAccess,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            $readItemCommand
            if-eqz v0, :no_video_id
            iget-object v0, v0, ${watchEndpoint.protobufExtensionSetField}
            sget-object v1, ${watchEndpoint.protobufExtensionField}
            iget-object v1, v1, ${watchEndpoint.protobufExtensionKeyField}
            invoke-virtual { v0, v1 }, ${watchEndpoint.hasProtobufExtensionMethod}
            move-result v2
            if-eqz v2, :no_video_id
            invoke-virtual { v0, v1 }, ${watchEndpoint.getProtobufExtensionMethod}
            move-result-object v0
            check-cast v0, ${watchEndpoint.messageType}
            iget-object v0, v0, ${watchEndpoint.videoIdField}
            invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
            move-result v0
            if-nez v0, :no_video_id
            const/4 v0, 0x1
            return v0
            :no_video_id
            const/4 v0, 0x0
            return v0
        """,
    )
}

// Read playlist titles and artwork

/** Identifies the artwork field read by YTM's playlist and song thumbnail renderer. */
private fun BytecodePatchContext.findPhoneBrowseItemArtworkField(phoneBrowseItemType: String): FieldReference {
    val thumbnailInitializer = PhoneBrowseItemThumbnailFingerprint.originalMethod
    val thumbnailProtobufExtensionField = thumbnailInitializer.instructions
        .filter { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .singleOrNull { field -> field.definingClass == thumbnailInitializer.definingClass }
        ?: throw PatchException("Could not resolve the thumbnail extension registration")
    return phoneBrowseItemArtworkFingerprint(phoneBrowseItemType, thumbnailProtobufExtensionField)
        .matchAll()
        .map { match ->
            val artworkRead = match.instructionMatches.single { instructionMatch ->
                instructionMatch.instruction.opcode == Opcode.IGET_OBJECT
            }
            artworkRead.instruction.getReference<FieldReference>()!!
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the artwork field used by YTM's thumbnail renderers")
}

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
        """,
    )
}

/**
 * Uses the thumbnail URL already included with a Library item. YTM's Android Auto code
 * selects a thumbnail and creates its URI; Android Auto loads the image from that URI.
 * [decodeThumbnailFingerprint] identifies YTM's artwork parser;
 * [androidAutoMediaDescriptionFingerprint] locates an Android Auto item builder that uses the URI conversion.
 */
private fun BytecodePatchContext.addArtworkUriGetter(
    itemClass: MutableClass,
    artworkContainerField: FieldReference,
) {
    val artworkPayloadType = PhoneBrowseItemThumbnailFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val artworkPayloadFields = classDefBy(artworkPayloadType).fields
        .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
    val artworkPayloadFieldTypes = artworkPayloadFields.map(FieldReference::getType).toSet()
    val decodeArtworkPayloadMethod = decodeThumbnailFingerprint(
        artworkContainerField.type,
    ).originalMethod
    // Use the same artwork URI format as YTM's Android Auto items.
    val androidAutoMediaDescriptionMethod = androidAutoMediaDescriptionFingerprint(
        artworkPayloadFieldTypes,
    ).originalMethod
    val mediaDescriptionReadFieldTypes = androidAutoMediaDescriptionMethod.instructions
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
        .toSet()
    // Phone Library thumbnails and Android Auto artwork use the same thumbnail data type.
    // Match the URI converter that accepts it.
    val createArtworkUriMethod = androidAutoMediaDescriptionMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { method ->
            method.returnType == "Landroid/net/Uri;" && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().toString() in mediaDescriptionReadFieldTypes
        }
        .singleOrNull { method ->
            method.parameterTypes.single().toString() in artworkPayloadFieldTypes
        }
        ?: throw PatchException("Could not resolve YTM's Android Auto artwork Uri method")
    val thumbnailDetailsType = createArtworkUriMethod.parameterTypes.single().toString()
    val thumbnailDetailsField = artworkPayloadFields.singleOrNull { field ->
        field.type == thumbnailDetailsType
    } ?: throw PatchException("Could not resolve the thumbnail details field")

    itemClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_ITEM_INTERFACE,
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
        """,
    )
}

// endregion

// region Intercept Android Auto playlist requests

/** Replaces YTM's empty Playlists result with playlists fetched from the phone Library by Java. */
private fun BytecodePatchContext.patchAndroidAutoPlaylists() {
    val sendEmptyAndroidAutoMediaItemsMethod = SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod
    addAndroidAutoBrowseRequestInterface(sendEmptyAndroidAutoMediaItemsMethod)
    hookAndroidAutoPlaylistsRequest(
        sendEmptyAndroidAutoMediaItemsMethod.definingClass,
        sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString(),
    )
}

/**
 * Lets Java identify what Android Auto requested and return a list through YTM's existing response method.
 */
private fun BytecodePatchContext.addAndroidAutoBrowseRequestInterface(
    sendEmptyAndroidAutoMediaItemsMethod: Method,
) {
    val androidAutoRequestType = sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString()
    val androidAutoRequestClass = mutableClassDefBy(androidAutoRequestType)
    val androidAutoRequestFields = sendEmptyAndroidAutoMediaItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .toList()
    // YTM's "Invalid media id" log reads the requested media ID through these two fields.
    val requestedMediaIdHolderField = androidAutoRequestFields.single { field ->
        field.definingClass == androidAutoRequestType
    }
    val requestedMediaIdField = androidAutoRequestFields.single { field ->
        field.definingClass == requestedMediaIdHolderField.type &&
            field.type == "Ljava/lang/String;"
    }

    val deliverAndroidAutoMediaItemsMethod = sendEmptyAndroidAutoMediaItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .distinct()
        .single { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            reference.definingClass == androidAutoRequestType && reference.returnType == "V" &&
                parameters.size in 1..2 &&
                parameters.firstOrNull() == "Ljava/util/List;" &&
                parameters.drop(1).all { it.startsWith("L") || it.startsWith("[") }
        }

    androidAutoRequestClass.interfaces.add(EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE)
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
            "patch_getRequestedMediaId",
        ),
        registerCount = 1,
        instructions = """
            iget-object p0, p0, $requestedMediaIdHolderField
            iget-object p0, p0, $requestedMediaIdField
            return-object p0
        """,
    )
    // YTM passes null for the optional second argument when returning an empty list.
    val usesTwoArgumentDeliveryMethod = deliverAndroidAutoMediaItemsMethod.parameterTypes.size == 2
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
            "patch_deliverAndroidAutoItems",
        ),
        registerCount = if (usesTwoArgumentDeliveryMethod) 3 else 2,
        instructions = if (usesTwoArgumentDeliveryMethod) {
            """
                const/4 v0, 0x0
                invoke-virtual { p0, p1, v0 }, $deliverAndroidAutoMediaItemsMethod
                return-void
            """
        } else {
            """
                invoke-virtual { p0, p1 }, $deliverAndroidAutoMediaItemsMethod
                return-void
            """
        },
    )
}

/**
 * Gives Java's `handleAndroidAutoPlaylists` the request before YTM can return an empty Playlists list.
 * If Java accepts it, stop here while Java loads the phone Library and returns the playlists.
 * Otherwise continue YTM's code for the requested Android Auto list.
 */
private fun BytecodePatchContext.hookAndroidAutoPlaylistsRequest(
    androidAutoRequestHandlerType: String,
    androidAutoRequestType: String,
) {
    val handleAndroidAutoRequestMethod = mutableClassDefBy(
        androidAutoRequestHandlerType,
    ).methods.single { method ->
        method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(androidAutoRequestType)
    }
    val handledRegister = handleAndroidAutoRequestMethod.findFreeRegister(0)

    handleAndroidAutoRequestMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handleAndroidAutoPlaylists($EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", handleAndroidAutoRequestMethod.getInstruction<Instruction>(0)),
    )
}

// endregion

// region Playback callbacks

/**
 * [hookPlaylistPlayback] lets Java turn a selected playlist into YTM's playback media ID.
 * [addPlaybackCallbackAccess] keeps playback on YTM's own thread.
 */
private fun BytecodePatchContext.installPlaybackCallbackBridges() {
    val playFromMediaIdMethod = AndroidAutoPlayFromMediaIdFingerprint.method
    val callbackClass = mutableClassDefBy(playFromMediaIdMethod.definingClass)
    // YTM forwards onPlayFromMediaId to the object stored in this field.
    val delegateField = playFromMediaIdMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .single { field -> field.definingClass == callbackClass.type }

    addPlaybackCallbackAccess(callbackClass, delegateField)
    hookPlaylistPlayback(playFromMediaIdMethod)
}

/** Exposes the callback's Handler so playlist playback runs on YTM's playback thread. */
private fun BytecodePatchContext.addPlaybackCallbackAccess(
    callbackClass: MutableClass,
    delegateField: FieldReference,
) {
    val delegateClass = classDefBy(delegateField.type)
    val handlerField = delegateClass.fields.singleOrNull { field ->
        classDefByOrNull(field.type)?.superclass == "Landroid/os/Handler;"
    } ?: throw PatchException("Could not find media session callback Handler")
    callbackClass.interfaces.add(EXTENSION_PLAYBACK_CALLBACK_INTERFACE)
    callbackClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PLAYBACK_CALLBACK_INTERFACE,
            "patch_getCallbackHandler",
        ),
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $delegateField
            if-eqz v0, :no_handler
            iget-object v0, v0, $handlerField
            return-object v0
            :no_handler
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

/**
 * The patch's playlist media IDs contain a page ID that YTM cannot play directly.
 * Java's `handlePlayFromMediaId` loads that playlist and obtains a YTM playback media ID first.
 * A true return stops the original call; false lets YTM play an ID it already understands.
 */
private fun hookPlaylistPlayback(playFromMediaIdMethod: MutableMethod) {
    val handlePlaylistSelectionMethod = "$EXTENSION_CLASS->handlePlayFromMediaId(" +
        "Landroid/media/session/MediaSession${'$'}Callback;" +
        "Ljava/lang/String;Landroid/os/Bundle;)Z"
    val handledRegister = playFromMediaIdMethod.findFreeRegister(0)
    playFromMediaIdMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p2 }, $handlePlaylistSelectionMethod
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", playFromMediaIdMethod.getInstruction<Instruction>(0)),
    )
}

// endregion

// region Add the methods declared in the Java interfaces to YTM classes

private fun BytecodePatchContext.extensionInterfaceMethod(
    interfaceType: String,
    name: String,
) = classDefBy(interfaceType).methods.singleOrNull { method -> method.name == name }
    ?: throw PatchException("Could not resolve $name in $interfaceType")

/** Copies the Java declaration's signature so calls through the interface reach the method installed on YTM. */
private fun MutableClass.addInterfaceMethod(
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
        },
    )
}

// endregion
