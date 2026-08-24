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
private const val EXTENSION_BROWSE_SERVICE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$BrowseService;"
private const val EXTENSION_BROWSE_RESPONSE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$BrowseResponse;"
private const val EXTENSION_BROWSE_TAB_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$BrowseTab;"
private const val EXTENSION_SECTION_LIST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$SectionList;"
private const val EXTENSION_PLAYLIST_GRID_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistGrid;"
private const val EXTENSION_PLAYLIST_SHELF_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistShelf;"
private const val EXTENSION_LOAD_CHILDREN_RESULT_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$LoadChildrenResult;"
private const val EXTENSION_MUSIC_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$MusicItem;"

// move-result can only write to registers 0-255.
private const val EIGHT_BIT_REGISTER_LIMIT = 256
// iget-object can only address registers 0-15.
private const val FOUR_BIT_REGISTER_LIMIT = 16
// A MediaDescriptionCompat constructor range starts with its receiver. Media ID and title are the
// next two registers.
private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

private const val MUSIC_ITEM_ARTWORK_FIELD_NAME = "c"
private const val MUSIC_ITEM_TITLE_FIELD_NAME = "g"
private const val MUSIC_ITEM_SUBTITLE_FIELD_NAME = "h"
private const val PLAYLIST_BROWSE_ID_PREFIX = "VL"
private const val BROWSE_RESPONSE_BUTTON_CONTENT_FIELD_NAME = "q"
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

        val endpointMediaIdMethod = EndpointMediaIdFingerprint.originalMethod
        val responseTabsMethod = BrowseTabsFingerprint.originalMethod
        val gridItemsMethod = GridItemsFingerprint.originalMethod
        val gridType = gridItemsMethod.parameterTypes.single().toString()
        val gridContinuationsMethod = classDefBy(gridItemsMethod.definingClass).methods
            .single { method ->
                method != gridItemsMethod &&
                    AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                    AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "Ljava/util/List;" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(gridType)
            }
        val musicItemType = MusicItemExtensionFingerprint
            .instructionMatches
            .first()
            .instruction
            .getReference<TypeReference>()!!
            .type
        val browseEndpointIdField = BrowseEndpointRequestFingerprint.instructionMatches
            .first()
            .instruction
            .getReference<FieldReference>()
            ?: throw PatchException("Could not resolve the Browse endpoint ID field")
        val browseServiceClass = patchBrowseService(gridContinuationsMethod)

        patchBrowseResponses(
            responseTabsMethod,
            gridItemsMethod,
            gridContinuationsMethod,
            musicItemType,
            endpointMediaIdMethod,
        )
        patchMusicItem(
            musicItemType,
            browseEndpointIdField,
            endpointMediaIdMethod,
        )
        val invalidParentMediaIdMethod = InvalidParentMediaIdFingerprint.originalMethod
        patchLoadChildrenResult(invalidParentMediaIdMethod)
        val contentSupplierType = invalidParentMediaIdMethod.definingClass
        val loadChildrenResultType = invalidParentMediaIdMethod.parameterTypes.first().toString()

        hookMusicBrowserService(browseServiceClass.type, loadChildrenResultType)
        hookLoadChildren(contentSupplierType, loadChildrenResultType)
    }
}

private fun BytecodePatchContext.hookPlaylistCategoryId() {
    val method = MediaItemFactoryFingerprint.method

    // On YTM 9.15.51, Playlists comes from the MediaDescriptionCompat call with FLAG_BROWSABLE.
    method
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { index ->
            val instruction =
                method.getInstruction<RegisterRangeInstruction>(
                    index,
                )
            val mediaIdRegister =
                instruction.startRegister + MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET
            val titleRegister =
                instruction.startRegister + MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET

            method.addInstructions(
                index,
                """
                    invoke-static/range { v$mediaIdRegister .. v$titleRegister }, $EXTENSION_CLASS->rememberPlaylistCategoryId(Ljava/lang/String;Ljava/lang/CharSequence;)V
                """,
            )
        }
}

private fun BytecodePatchContext.hookMusicBrowserService(
    browseServiceType: String,
    loadChildrenResultType: String,
) {
    val musicBrowserServiceType = musicBrowserServiceLoadChildrenFingerprint(
        loadChildrenResultType,
    ).originalMethod.definingClass
    val browseServiceProviders = browseServiceProviderFingerprint(browseServiceType)
        .matchAll()
        .map { match ->
            val (providerFieldMatch, providerGetterMatch) = match.instructionMatches
            val providerField = providerFieldMatch.instruction.getReference<FieldReference>()!!
            val providerGetter = providerGetterMatch.instruction.getReference<MethodReference>()!!
            providerField to providerGetter
        }
        .distinctBy { (field, _) -> field }
    // During onCreate, MusicBrowserService's generated superclass reads the Dagger provider for
    // the class containing BS_GET_BROWSE_DATA.
    val (onCreateMethod, browseServiceProvider) = browseServiceProviders.mapNotNull { provider ->
        musicBrowserServiceOnCreateFingerprint(
            musicBrowserServiceType,
            provider.first.definingClass,
        ).matchOrNull()?.originalMethod?.let { method -> method to provider }
    }.singleOrNull()
        ?: throw PatchException("Could not resolve the Browse service used by MusicBrowserService")
    val (browseProviderField, browseProviderGetter) = browseServiceProvider
    val mutableOnCreateMethod = mutableClassDefBy(
        onCreateMethod.definingClass,
    ).findMutableMethodOf(onCreateMethod)
    val componentIndex = mutableOnCreateMethod.indexOfFirstInstructionOrThrow {
        opcode == Opcode.IGET_OBJECT &&
            getReference<FieldReference>()?.type == browseProviderField.definingClass
    }
    val componentRegister = mutableOnCreateMethod
        .getInstruction<TwoRegisterInstruction>(componentIndex)
        .registerA
    val providerRegister = mutableOnCreateMethod.findFreeRegister(
        componentIndex + 1,
        mutableOnCreateMethod.p0Register,
    )
    if (providerRegister >= FOUR_BIT_REGISTER_LIMIT) {
        throw PatchException("MusicBrowserService.onCreate has no free 4-bit register")
    }

    mutableOnCreateMethod.addInstructions(
        componentIndex + 1,
        """
            iget-object v$providerRegister, v$componentRegister, $browseProviderField
            invoke-interface/range { v$providerRegister .. v$providerRegister }, $browseProviderGetter
            move-result-object v$providerRegister
            check-cast v$providerRegister, $EXTENSION_BROWSE_SERVICE_INTERFACE
            invoke-static/range { v$providerRegister .. v$providerRegister }, $EXTENSION_CLASS->setBrowseService($EXTENSION_BROWSE_SERVICE_INTERFACE)V
        """,
    )
}

private fun BytecodePatchContext.patchBrowseResponses(
    responseTabsMethod: Method,
    gridItemsMethod: Method,
    gridContinuationsMethod: Method,
    musicItemType: String,
    endpointMediaIdMethod: Method,
) {
    val tabMapperNewInstance = BrowseTabsFingerprint.instructionMatches.last()
    val tabMapperType = tabMapperNewInstance
        .instruction
        .getReference<TypeReference>()!!
        .type
    val tabMapper = browseTabMapperFingerprint(tabMapperType)
    val tabNewInstance = tabMapper.instructionMatches.first()
    val tabType = tabNewInstance
        .instruction
        .getReference<TypeReference>()!!
        .type
    val sectionListMethod = sectionListFingerprint(tabType).originalMethod
    val sectionItemMethods = sectionItemsFingerprint(
        sectionListMethod.returnType,
        responseTabsMethod.returnType,
    ).matchAll(2..2)
        .map { match -> match.originalMethod }
    val playlistItemsMethod = playlistItemsFingerprint(
        musicItemType,
    ).originalMethod
    val gridResponseMethod = GridDecoderFingerprint.originalMethod
    val responsePayloadType = gridResponseMethod
        .parameterTypes.single().toString()
    val responsePayloadMethod = classDefBy(
        responseTabsMethod.definingClass,
    ).methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
            method.returnType == responsePayloadType
    } ?: throw PatchException("Could not resolve the Browse response payload getter")
    // The injected PlaylistGrid and PlaylistShelf methods call these private YTM methods.
    listOf(
        gridItemsMethod,
        playlistItemsMethod,
        gridContinuationsMethod,
    ).forEach { method ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).apply {
            accessFlags = accessFlags.toPublicAccessFlags()
        }
    }

    val gridDecoder = addGridDecoder(gridResponseMethod)
    addBrowseResponseInterface(
        responseTabsMethod,
        responsePayloadMethod,
        gridDecoder,
        endpointMediaIdMethod,
    )
    addBrowseTabInterface(sectionListMethod)
    addSectionListInterface(sectionItemMethods)
    addPlaylistGridInterface(gridItemsMethod, gridContinuationsMethod)
    addPlaylistShelfInterface(playlistItemsMethod)
}

private fun BytecodePatchContext.addBrowseResponseInterface(
    responseTabsMethod: Method,
    responsePayloadMethod: Method,
    gridDecoder: Method,
    endpointMediaIdMethod: Method,
) {
    val responseClass = mutableClassDefBy(responseTabsMethod.definingClass)
    responseClass.interfaces.add(EXTENSION_BROWSE_RESPONSE_INTERFACE)
    addPlaylistMediaIdGetter(responseClass, responseTabsMethod, endpointMediaIdMethod)
    responseClass.addInterfaceMethod(
        name = "patch_getTabs",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $responseTabsMethod
            move-result-object p0
            return-object p0
        """,
    )
    responseClass.addInterfaceMethod(
        name = "patch_getContinuationGrid",
        parameters = emptyList(),
        returnType = "Ljava/lang/Object;",
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $responsePayloadMethod
            move-result-object p0
            # The cloned method does not read its first argument.
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $gridDecoder
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addBrowseTabInterface(
    sectionListMethod: Method,
) {
    val tabClass = mutableClassDefBy(sectionListMethod.definingClass)
    tabClass.interfaces.add(EXTENSION_BROWSE_TAB_INTERFACE)
    tabClass.addInterfaceMethod(
        name = "patch_getSectionList",
        parameters = emptyList(),
        returnType = "Ljava/lang/Object;",
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $sectionListMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addSectionListInterface(
    sectionItemMethods: List<Method>,
) {
    val (firstItemMethod, secondItemMethod) = sectionItemMethods
    val sectionListClass = mutableClassDefBy(firstItemMethod.definingClass)
    sectionListClass.interfaces.add(EXTENSION_SECTION_LIST_INTERFACE)
    sectionListClass.addInterfaceMethod(
        name = "patch_getItemLists",
        parameters = emptyList(),
        returnType = "[Ljava/lang/Iterable;",
        registerCount = 4,
        instructions = """
            const/4 v0, 0x2
            new-array v0, v0, [Ljava/lang/Iterable;
            invoke-virtual { p0 }, $firstItemMethod
            move-result-object v1
            const/4 v2, 0x0
            aput-object v1, v0, v2
            invoke-virtual { p0 }, $secondItemMethod
            move-result-object v1
            const/4 v2, 0x1
            aput-object v1, v0, v2
            return-object v0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistGridInterface(
    gridItemsMethod: Method,
    gridContinuationsMethod: Method,
) {
    val gridType = gridItemsMethod.parameterTypes.single().toString()
    val gridClass = mutableClassDefBy(gridType)
    gridClass.interfaces.add(EXTENSION_PLAYLIST_GRID_INTERFACE)
    gridClass.addInterfaceMethod(
        name = "patch_getItems",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $gridItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
    gridClass.addInterfaceMethod(
        name = "patch_getContinuations",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $gridContinuationsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistShelfInterface(
    playlistItemsMethod: Method,
) {
    val playlistShelfType = playlistItemsMethod.parameterTypes.first().toString()
    val playlistShelfClass = mutableClassDefBy(playlistShelfType)
    playlistShelfClass.interfaces.add(EXTENSION_PLAYLIST_SHELF_INTERFACE)
    playlistShelfClass.addInterfaceMethod(
        name = "patch_getItems",
        parameters = emptyList(),
        returnType = "Ljava/lang/Iterable;",
        registerCount = 2,
        instructions = """
            const/4 v0, 0x0
            # false returns extension-161429595 track rows.
            invoke-static { p0, v0 }, $playlistItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPlaylistMediaIdGetter(
    responseClass: MutableClass,
    responseTabsMethod: Method,
    endpointMediaIdMethod: Method,
) {
    val endpointType = endpointMediaIdMethod.parameterTypes.single().toString()
    val initializerMethod = buttonRendererExtensionFingerprint(endpointType).originalMethod
    val buttonRendererType = initializerMethod.instructions
        .first { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .getReference<TypeReference>()!!
        .type
    val containingType = initializerMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .first { field -> field.definingClass == field.type }
        .type
    val descriptorField = initializerMethod.instructions
        .first { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .getReference<FieldReference>()!!
    val buttonRendererDecoderMethod = buttonRendererDecoderFingerprint(
        containingType,
        buttonRendererType,
        descriptorField,
    ).originalMethod
    val commandEndpointField = buttonRendererEndpointCopyFingerprint(
        buttonRendererType,
        endpointType,
    ).matchAll()
        .map { match ->
            val (playEndpointReadMatch, _) = match.instructionMatches
            playEndpointReadMatch.instruction.getReference<FieldReference>()!!
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the ButtonRenderer command endpoint")

    val responsePayloadField = responseTabsMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .filter { field ->
            field.definingClass == responseTabsMethod.definingClass &&
                field.type != responseTabsMethod.returnType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the Browse response payload field")
    val buttonContentField = classDefBy(responsePayloadField.type).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.name == BROWSE_RESPONSE_BUTTON_CONTENT_FIELD_NAME &&
            field.type == containingType
    } ?: throw PatchException("Could not resolve the playlist ButtonRenderer content field")

    // response.q stores the playlist Play command in ButtonRenderer extension 65153809.
    responseClass.addInterfaceMethod(
        name = "patch_getPlaylistMediaId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 2,
        instructions = """
            iget-object p0, p0, $responsePayloadField
            iget-object p0, p0, $buttonContentField
            # true returns the Play ButtonRenderer or null.
            const/4 v0, 0x1
            invoke-static { v0, p0 }, $buttonRendererDecoderMethod
            move-result-object p0
            if-eqz p0, :no_play_endpoint
            iget-object p0, p0, $commandEndpointField
            if-eqz p0, :no_play_endpoint
            invoke-static { p0 }, $endpointMediaIdMethod
            move-result-object p0
            return-object p0
            :no_play_endpoint
            const/4 p0, 0x0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addGridDecoder(method: Method): Method {
    val decoder = method.cloneMutable(
        name = "patch_decodeGrid",
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

private fun BytecodePatchContext.patchMusicItem(
    musicItemType: String,
    browseEndpointIdField: FieldReference,
    endpointMediaIdMethod: Method,
) {
    val musicItemFields = classDefBy(musicItemType).fields.toList()

    fun musicItemField(name: String) = musicItemFields
        .first { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkField = musicItemField(MUSIC_ITEM_ARTWORK_FIELD_NAME)
    val titleField = musicItemField(MUSIC_ITEM_TITLE_FIELD_NAME)
    val subtitleField = musicItemField(MUSIC_ITEM_SUBTITLE_FIELD_NAME)
    if (artworkField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected music item artwork, title, or subtitle fields")
    }
    val artworkPayloadType = MusicThumbnailExtensionFingerprint.originalMethod.instructions
        .first { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .getReference<TypeReference>()!!
        .type
    val artworkPayloadFields = classDefBy(artworkPayloadType).fields
        .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
        .toList()
    val artworkDecoderMethod = musicThumbnailDecoderFingerprint(
        artworkField.type,
    ).originalMethod
    val artworkMethod = androidAutoArtworkFingerprint(
        artworkPayloadFields.map(FieldReference::getType).toSet(),
    ).originalMethod
    val artworkReadTypes = artworkMethod.instructions
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
        .toSet()
    val artworkUriMethod = artworkMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { method ->
            method.returnType == "Landroid/net/Uri;" && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().toString() in artworkReadTypes
        }
        .singleOrNull { method ->
            artworkPayloadFields.any { field ->
                field.type == method.parameterTypes.single().toString()
            }
        }
        ?: throw PatchException("Could not resolve the Android Auto artwork Uri helper")
    val artworkPayloadField = artworkPayloadFields.singleOrNull { field ->
        field.type == artworkUriMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the artwork payload field")
    val renderTextMethod = renderTextFingerprint(titleField.type).originalMethod

    val endpointType = endpointMediaIdMethod.parameterTypes.single().toString()
    val endpointFields = musicItemFields
        .filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == endpointType
    }
    if (endpointFields.size != 2) {
        throw PatchException("Could not resolve the two music row endpoints")
    }
    val (firstEndpointField, secondEndpointField) = endpointFields

    val browseIdDecoderMethod = browseEndpointDecoderFingerprint(
        endpointType,
        browseEndpointIdField.definingClass,
    ).originalMethod

    val musicItemClass = mutableClassDefBy(musicItemType)
    musicItemClass.interfaces.add(EXTENSION_MUSIC_ITEM_INTERFACE)
    musicItemClass.addBrowseIdGetter(
        firstEndpointField,
        secondEndpointField,
        browseIdDecoderMethod,
        browseEndpointIdField,
    )
    musicItemClass.addMediaIdGetter(
        firstEndpointField,
        secondEndpointField,
        endpointMediaIdMethod,
    )
    musicItemClass.addTextGetter("patch_getTitle", titleField, renderTextMethod)
    musicItemClass.addTextGetter("patch_getSubtitle", subtitleField, renderTextMethod)
    musicItemClass.addArtworkUriGetter(
        artworkField,
        artworkDecoderMethod,
        artworkPayloadField,
        artworkUriMethod,
    )
}

// Fields i and k can both contain Browse endpoints; conflicting VL IDs are ignored.
private fun MutableClass.addBrowseIdGetter(
    firstEndpointField: FieldReference,
    secondEndpointField: FieldReference,
    browseIdDecoderMethod: Method,
    browseEndpointIdField: FieldReference,
) {
    addInterfaceMethod(
        name = "patch_getBrowseId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 4,
        instructions = """
            const/4 v0, 0x0
            iget-object v1, p0, $firstEndpointField
            if-eqz v1, :second_endpoint
            invoke-static { v1 }, $browseIdDecoderMethod
            move-result-object v1
            if-eqz v1, :second_endpoint
            iget-object v1, v1, $browseEndpointIdField
            if-eqz v1, :second_endpoint
            const-string v2, "$PLAYLIST_BROWSE_ID_PREFIX"
            invoke-virtual { v1, v2 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :second_endpoint
            move-object v0, v1

            :second_endpoint
            iget-object v1, p0, $secondEndpointField
            if-eqz v1, :return_id
            invoke-static { v1 }, $browseIdDecoderMethod
            move-result-object v1
            if-eqz v1, :return_id
            iget-object v1, v1, $browseEndpointIdField
            if-eqz v1, :return_id
            const-string v2, "$PLAYLIST_BROWSE_ID_PREFIX"
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

private fun MutableClass.addMediaIdGetter(
    firstEndpointField: FieldReference,
    secondEndpointField: FieldReference,
    endpointMediaIdMethod: Method,
) {
    addInterfaceMethod(
        name = "patch_getMediaId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $firstEndpointField
            if-eqz v0, :second_endpoint
            invoke-static { v0 }, $endpointMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            if-eqz v0, :second_endpoint
            invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
            move-result v1
            if-eqz v1, :return_id

            :second_endpoint
            iget-object v0, p0, $secondEndpointField
            if-nez v0, :decode_second_endpoint
            # ART on 9.15.51 otherwise merges this missing-action path with decoded Strings as
            # Object.
            const/4 v0, 0x0
            goto :return_id

            :decode_second_endpoint
            invoke-static { v0 }, $endpointMediaIdMethod
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
    renderTextMethod: Method,
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
            invoke-static { v0, v1 }, $renderTextMethod
            move-result-object v0
            return-object v0
        """,
    )
}

private fun MutableClass.addArtworkUriGetter(
    artworkField: FieldReference,
    artworkDecoderMethod: Method,
    artworkPayloadField: FieldReference,
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
            invoke-static { v0 }, $artworkDecoderMethod
            move-result-object v0
            if-eqz v0, :no_artwork
            check-cast v0, ${artworkPayloadField.definingClass}
            iget-object v0, v0, $artworkPayloadField
            invoke-static { v0 }, $artworkUriMethod
            move-result-object v0
            return-object v0
            :no_artwork
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

private fun BytecodePatchContext.patchBrowseService(
    gridContinuationsMethod: Method,
): MutableClass {
    val endpointRequestMethod = BrowseEndpointRequestFingerprint.originalMethod
    val requestBuilderType = endpointRequestMethod.returnType
    val requestBuilderFactoryMethod = endpointRequestMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .firstOrNull { reference ->
            reference.parameterTypes.isEmpty() && reference.returnType == requestBuilderType
        }
        ?: throw PatchException("Could not resolve the Browse request factory")
    val browseServiceType = requestBuilderFactoryMethod.definingClass
    val continuationTypes = gridContinuationsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .map { reference -> reference.returnType }
        .toSet()
    val continuationBuilderMethod = classDefBy(browseServiceType).methods.singleOrNull { method ->
        method.returnType == requestBuilderType &&
            method.parameterTypes.singleOrNull()?.toString() in continuationTypes
    }
        ?: throw PatchException("Could not resolve the continuation request factory")
    val requestFingerprint = browseRequestFingerprint(
        browseServiceType,
        requestBuilderType,
    )
    val browseRequestMethod = requestFingerprint.originalMethod
    val requestBrowseIdField = requestFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val requestBuilderMethods = generateSequence(classDefBy(requestBuilderType)) { classDef ->
        classDef.superclass?.let { superclass -> classDefByOrNull(superclass) }
    }.flatMap { classDef -> classDef.methods.asSequence() }
    val clickTrackingParamsSetterMethod = requestBuilderMethods
        .firstOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("[B")
        }
        ?: throw PatchException("Could not resolve the click tracking parameter setter")
    val browseIdSetterMethod = browseIdSetterFingerprint(requestBrowseIdField).originalMethod
    val browseServiceClass = mutableClassDefBy(browseServiceType)
    browseServiceClass.interfaces.add(EXTENSION_BROWSE_SERVICE_INTERFACE)
    browseServiceClass.addInterfaceMethod(
        name = "patch_requestBrowse",
        parameters = listOf("Ljava/lang/String;", "Ljava/util/concurrent/Executor;"),
        returnType = LISTENABLE_FUTURE_CLASS,
        registerCount = 5,
        instructions = """
            invoke-virtual { p0 }, $requestBuilderFactoryMethod
            move-result-object v0
            invoke-virtual { v0, p1 }, $browseIdSetterMethod
            # Browse requests require clickTrackingParams, even when it is empty.
            const/4 v1, 0x0
            new-array v1, v1, [B
            invoke-virtual { v0, v1 }, $clickTrackingParamsSetterMethod
            invoke-virtual { p0, v0, p2 }, $browseRequestMethod
            move-result-object v0
            return-object v0
        """,
    )
    val continuationType = continuationBuilderMethod.parameterTypes.single().toString()
    browseServiceClass.addInterfaceMethod(
        name = "patch_requestContinuation",
        parameters = listOf("Ljava/lang/Object;", "Ljava/util/concurrent/Executor;"),
        returnType = LISTENABLE_FUTURE_CLASS,
        registerCount = 3,
        instructions = """
            check-cast p1, $continuationType
            invoke-virtual { p0, p1 }, $continuationBuilderMethod
            move-result-object p1
            invoke-virtual { p0, p1, p2 }, $browseRequestMethod
            move-result-object p1
            return-object p1
        """,
    )

    return browseServiceClass
}

private fun BytecodePatchContext.patchLoadChildrenResult(
    invalidParentMediaIdMethod: Method,
) {
    val loadChildrenResultType = invalidParentMediaIdMethod.parameterTypes.first().toString()
    val loadChildrenResultClass = mutableClassDefBy(loadChildrenResultType)
    loadChildrenResultClass.interfaces.add(EXTENSION_LOAD_CHILDREN_RESULT_INTERFACE)
    // LoadChildrenResult.toString() labels the String read by the invalid-ID branch as parentMediaId.
    val parentMediaIdFieldPath = invalidParentMediaIdMethod.instructions.asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .windowed(2)
        .first { (resultField, parentMediaIdField) ->
            resultField.definingClass == loadChildrenResultType &&
                parentMediaIdField.definingClass == resultField.type &&
                parentMediaIdField.type == "Ljava/lang/String;"
        }

    // YTM 9.15.51's invalid-ID path calls b(List), which forwards to c(List, null).
    val resultDeliveryMethod = invalidParentMediaIdMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .first { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            reference.definingClass == loadChildrenResultType && reference.returnType == "V" &&
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
    loadChildrenResultClass.addInterfaceMethod(
        name = "patch_getParentMediaId",
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        registerCount = 1,
        instructions = parentMediaIdInstructions,
    )
    val hasExtraDeliveryParameter = resultDeliveryMethod.parameterTypes.size == 2
    loadChildrenResultClass.addInterfaceMethod(
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

private fun BytecodePatchContext.hookLoadChildren(
    contentSupplierType: String,
    loadChildrenResultType: String,
) {
    val loadChildrenMethod = contentSupplierLoadChildrenFingerprint(
        contentSupplierType,
        loadChildrenResultType,
    ).method
    val handledRegister = loadChildrenMethod.findFreeRegister(0)
    if (handledRegister >= EIGHT_BIT_REGISTER_LIMIT) {
        throw PatchException("ContentSupplier load-children method has no free 8-bit register")
    }

    loadChildrenMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->replacePlaylists(Ljava/lang/Object;)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", loadChildrenMethod.getInstruction<Instruction>(0)),
    )
}
