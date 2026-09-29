/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
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
import app.morphe.util.p0Register
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
private const val EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$AndroidAutoBrowseRequest;"
private const val EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$AndroidAutoFolderReload;"
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

/**
 * Connects the Java extension to YTM's Library requests, browse results, and playlist playback.
 * Injected interfaces let Java request Library pages and read their playlist IDs, text, and artwork.
 *
 * Java handles Android Auto's Library and Playlists requests using those interfaces.
 * [hookAndroidAutoBrowseResults] lets Java add a Podcasts tab containing Home's podcast lists.
 * [installAndroidAutoFolderRefresh] lets Java reload playlists and podcasts after Library changes.
 * [installPlaylistMediaIdBuilder] supplies the media IDs used to play playlist cards.
 */
@Suppress("unused")
val supportAndroidAutoPatch = bytecodePatch(
    name = "Support Android Auto",
    description = "Restores YouTube Music playlists and podcasts in Android Auto.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookPlaylistsTitleMediaIds()
        installPhoneBrowseClientBridges()
        patchPhoneBrowseResponses()
        val encodeCommandMediaIdMethod = patchPhoneBrowseItem()
        patchAndroidAutoPlaylists()
        installAndroidAutoFolderRefresh()
        installPlaylistMediaIdBuilder(encodeCommandMediaIdMethod)
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

// region Request the Library

/**
 * Reuses the YTM object that sends phone Library requests.
 * [addPhoneBrowseRequestMethod] requests the Library; [addLibraryPaginationRequestMethod]
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
 * Lets Java fetch the phone Library by its Browse ID (YTM's page ID).
 * YTM creates and sends the request; the returned future contains the Library response.
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

// Obtain YTM's phone browse client

/**
 * Passes YTM's phone browse client to Java's `setPhoneBrowseClient` when MusicBrowserService starts.
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

// region Read Library responses

/**
 * Lets Java extract the lists returned by YTM's phone Library requests.
 * YTM nests the lists inside TabRenderer and section data. [addPhoneBrowsePageInterfaces] installs
 * PhoneBrowseTab and SectionList on the YTM objects that read this data, so Java can reach the lists.
 * [addGridRendererInterface] provides Library items.
 * Pagination may return Library items directly or in its first section; [addPhoneBrowseResponseInterface] handles both.
 */
private fun BytecodePatchContext.patchPhoneBrowseResponses() {
    addPhoneBrowseResponseInterface()
    addPhoneBrowsePageInterfaces()
    addGridRendererInterface()
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

// Read returned Library data

/**
 * Gives Java access to the lists in YTM's phone Library response.
 * The pagination getter uses YTM's parser copied by [addPaginatedLibraryGridDecoder].
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
    val phoneBrowseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    phoneBrowseResponseClass.interfaces.add(EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE)
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

/** Adds a getter for the section list inside YTM's TabRenderer wrapper. */
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

/** Adds a getter for the section contents so Java can find the Library grids. */
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

// endregion

// region Library items

/**
 * Adds getters for a Library item's playlist ID, title, subtitle, and artwork.
 * Returns YTM's media ID encoder for [installPlaylistMediaIdBuilder].
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

    val browseEndpointBrowseIdField = CreatePhoneBrowseRequestFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the BrowseEndpoint browse ID field")
    // The native item builder encodes its command before converting its artwork.
    val (encodeCommandMatch, _, artworkUriMatch) =
        androidAutoMediaDescriptionFingerprint(thumbnailDetailsField.type).instructionMatches
    val encodeCommandMediaIdMethod = encodeCommandMatch.instruction.getReference<MethodReference>()!!
    val createArtworkUriMethod = artworkUriMatch.instruction.getReference<MethodReference>()!!
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
    addArtworkUriGetter(phoneBrowseItemClass, artworkContainerField, thumbnailDetailsField, createArtworkUriMethod)
    return encodeCommandMediaIdMethod
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
        """,
    )
}

/**
 * Adds an artwork getter that decodes the Library item's thumbnail data and calls YTM's Android Auto
 * URI converter. Android Auto loads the image from the returned URI.
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

/** Lets Java answer Library and Playlists requests with playlists fetched from the phone Library. */
private fun BytecodePatchContext.patchAndroidAutoPlaylists() {
    val sendEmptyAndroidAutoMediaItemsMethod = SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod
    addAndroidAutoBrowseRequestInterface(sendEmptyAndroidAutoMediaItemsMethod)
    hookAndroidAutoPlaylistsRequest(
        sendEmptyAndroidAutoMediaItemsMethod.definingClass,
        sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString(),
    )
}

/**
 * Adds methods to read the requested media ID and send results through YTM.
 * Hooks the same result method for the main tabs and Podcasts.
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

    // YTM 9.15's one-argument overload forwards the list and null to this two-argument method.
    // Newer supported versions call it directly, so the patch can use the same method for all versions.
    val deliverAndroidAutoMediaItemsMethod = androidAutoRequestClass.methods.single { method ->
        method.returnType == "V" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes.first().toString() == "Ljava/util/List;" &&
            method.parameterTypes.last().toString().startsWith("L")
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
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
            "patch_deliverAndroidAutoItems",
        ),
        registerCount = 3,
        instructions = """
            const/4 v0, 0x0
            invoke-virtual { p0, p1, v0 }, $deliverAndroidAutoMediaItemsMethod
            return-void
        """,
    )
    hookAndroidAutoBrowseResults(deliverAndroidAutoMediaItemsMethod)
}

/**
 * Calls Java's `handleAndroidAutoPlaylists` before YTM dispatches the request to its content providers.
 * When Java accepts the request, skip YTM's handlers so only Java returns a result.
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

// region Android Auto connections and folder refresh

/**
 * Hooks successful Library changes and lets Java reload folders through YTM's browser service.
 */
private fun BytecodePatchContext.installAndroidAutoFolderRefresh() {
    // Android Auto requests list updates through MediaBrowserServiceCompat.
    // MediaBrowserService.notifyChildrenChanged does not reach that connection, so refresh through the compat service.
    val serviceSuperclass = classDefBy(MUSIC_BROWSER_SERVICE_CLASS).superclass
        ?: throw PatchException("Could not resolve MusicBrowserService's superclass")
    val baseServiceType = classDefBy(serviceSuperclass).superclass
        ?: throw PatchException("Could not resolve the browser service base class above $serviceSuperclass")
    val reloadMethod = mediaBrowserReloadFingerprint(baseServiceType).originalMethod

    addAndroidAutoRequestConnectionGetter(reloadMethod)
    addAndroidAutoFolderReload(baseServiceType, reloadMethod)
    hookLibraryChangeCompletion()
}

/** Exposes the connection so Java can identify requests that update the same Android Auto folder. */
private fun BytecodePatchContext.addAndroidAutoRequestConnectionGetter(reloadMethod: Method) {
    val connectionType = reloadMethod.parameterTypes[1].toString()
    // YTM passes the Android Auto connection to the object that returns the list.
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

    val androidAutoRequestType =
        SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod.parameterTypes.first().toString()
    val androidAutoRequestClass = mutableClassDefBy(androidAutoRequestType)
    // The request can hold other result types; check for the result object that stores the Android Auto connection.
    val resultBaseType = folderResultClass.superclass
        ?: throw PatchException("Could not resolve the Android Auto folder result base class")
    val requestResultField = androidAutoRequestClass.fields.single { field ->
        field.type == resultBaseType
    }

    folderResultClass.accessFlags = folderResultClass.accessFlags.toPublicAccessFlags()
    resultConnectionField.accessFlags = resultConnectionField.accessFlags.toPublicAccessFlags()
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
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
        """,
    )
}

/**
 * Passes folder requests and their connections to Java's `rememberAndroidAutoSubscription`.
 * Adds `patch_reloadFolder` to repeat those requests through YTM's existing reload method.
 */
private fun BytecodePatchContext.addAndroidAutoFolderReload(
    baseServiceType: String,
    reloadMethod: Method,
) {
    val connectionType = reloadMethod.parameterTypes[1].toString()
    val baseServiceClass = mutableClassDefBy(baseServiceType)
    baseServiceClass.interfaces.add(EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE)
    baseServiceClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE,
            "patch_reloadFolder",
        ),
        registerCount = 4,
        instructions = """
            check-cast p2, $connectionType
            invoke-virtual { p0, p1, p2, p3 }, $reloadMethod
            return-void
        """,
    )
    val rememberSubscriptionMethod = "$EXTENSION_CLASS->rememberAndroidAutoSubscription(" +
        EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE +
        "Ljava/lang/String;Ljava/lang/Object;)V"
    baseServiceClass.findMutableMethodOf(reloadMethod).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p2 }, $rememberSubscriptionMethod
        """,
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
            """,
        )
    }
}

// endregion

// region Android Auto browse results

/**
 * Passes browse results through Java's `handleAndroidAutoBrowseResult` before YTM sends them to Android Auto.
 */
private fun hookAndroidAutoBrowseResults(deliverAndroidAutoMediaItemsMethod: MutableMethod) {
    val handleAndroidAutoBrowseResultMethod = "$EXTENSION_CLASS->handleAndroidAutoBrowseResult(" +
        EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE +
        "Ljava/util/List;)Ljava/util/List;"
    deliverAndroidAutoMediaItemsMethod.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p1 }, $handleAndroidAutoBrowseResultMethod
            move-result-object p1
        """,
    )
}

// endregion

// region Playlist playback

/**
 * Replaces Java's `createPlaylistMediaId` stub with YTM's playlist command builder and media ID encoder.
 * YTM loads the queue when Android Auto plays the resulting media ID.
 */
private fun BytecodePatchContext.installPlaylistMediaIdBuilder(encodeCommandMediaIdMethod: MethodReference) {
    val commandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    val createPlaybackCommandMethod = playlistPlaybackCommandFingerprint(commandType).originalMethod
    val buildCommandMethod = createPlaybackCommandMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .distinct()
        .single { method -> method.name == "build" && method.parameterTypes.isEmpty() }
    val extensionClass = mutableClassDefBy(EXTENSION_CLASS)
    val stub = extensionClass.methods.single { it.name == "createPlaylistMediaId" }
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
        """,
    )
    extensionClass.methods.remove(stub)
    extensionClass.methods.add(method)
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
