/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.findInstructionIndicesReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// region BypassCertificateChecksPatch.kt: Certificate checks that block Android Auto.

internal object CheckCertificateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L"),
    strings = listOf(
        "X509",
        "isPartnerSHAFingerprint"
    )
)

/**
 * Anchors [IsGoogleSignedFingerprint] to the class that contains the remote
 * Google-certificates fetch logic.
 */
internal object GoogleCertificatesRemoteFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("Failed to get Google certificates from remote")
)

/**
 * Checks whether the caller is Google-signed. [GoogleCertificatesRemoteFingerprint] limits the match
 * to the certificate verifier class; the method signature alone also matches unrelated methods.
 */
internal object IsGoogleSignedFingerprint : Fingerprint(
    classFingerprint = GoogleCertificatesRemoteFingerprint,
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;")
)

// endregion

// region SupportAndroidAutoPatch.kt: Playlists, podcasts, playback, and Library refresh in Android Auto.

private const val PHONE_BROWSE_TABS_PROTO_FIELD = 58_173_949L
private const val TAB_RENDERER_PROTO_FIELD = 58_174_010L
private const val TAB_CONTENT_PRESENT_FLAG = 1L
private const val SECTION_LIST_CONTENTS_FIELD_NAME = "f"
private const val GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG = 0x40000L
private const val NEXT_COMMAND_PRESENT_FLAG = 0x1L
private const val RELOAD_COMMAND_PRESENT_FLAG = 0x2L

// Identify the Playlists folder and how YTM returns its contents to Android Auto

/**
 * Matches the constructor that stores an Android Auto item's ID, title, and artwork.
 * [BuildAndroidAutoMediaItemFingerprint] uses it to find where YTM creates the Playlists folder;
 * [androidAutoMediaDescriptionFingerprint] uses it to find YTM's artwork conversion.
 */
internal val MEDIA_DESCRIPTION_CONSTRUCTOR_CALL = methodCall(
    definingClass = "Landroid/support/v4/media/MediaDescriptionCompat;",
    name = "<init>",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Landroid/graphics/Bitmap;",
        "Landroid/net/Uri;",
        "Landroid/os/Bundle;",
        "Landroid/net/Uri;"
    ),
    returnType = "V"
)

/** Creates Android Auto media items, including the Playlists folder. */
internal object BuildAndroidAutoMediaItemFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        // Three constructor calls cover media items that can be opened, played, or both.
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    }
)

/** Returns an empty Android Auto list for an unrecognized media ID. */
internal object SendEmptyAndroidAutoMediaItemsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: ")
)

// Locate YTM's phone browse client and request methods

/** MusicBrowserService initialization, where the patch obtains YTM's phone browse client. */
internal fun musicBrowserServiceSuperclassOnCreateFingerprint(
    musicBrowserServiceType: String,
    generatedComponentType: String
) = Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            name = "generatedComponent",
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = generatedComponentType
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = musicBrowserServiceType
        )
    )
)

/** Obtains YTM's object for sending Library and playlist requests. */
internal fun phoneBrowseClientProviderFingerprint(phoneBrowseClientType: String) = Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(phoneBrowseClientType, location = MatchAfterImmediately())
    )
)

/** Creates a request for the phone's Library, Home, or a playlist. */
internal object CreatePhoneBrowseRequestFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;"
        ),
        // FEmusic_home identifies the phone's Home page; YTM compares it with the ID read above.
        string("FEmusic_home", location = MatchAfterImmediately())
    ),
    // apply(Object) also compares FEmusic_home, but returns a Boolean with declared type Object.
    // Exclude it to select the method that creates the Browse request.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" }
)

/** Sends a Library or playlist request and returns a future for the response. */
internal fun sendPhoneBrowseRequestFingerprint(
    phoneBrowseClientType: String,
    phoneBrowseRequestType: String
) = Fingerprint(
    definingClass = phoneBrowseClientType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(phoneBrowseRequestType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseRequestType,
            type = "Ljava/lang/String;"
        )
    )
)

/** Sets the Library or playlist page ID on a YTM request. */
internal fun setRequestBrowseIdFingerprint(requestBrowseIdField: FieldReference) = Fingerprint(
    definingClass = requestBrowseIdField.definingClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            reference = requestBrowseIdField,
            opcode = Opcode.IPUT_OBJECT
        )
    )
)

// Read the initial Library response; pagination has a separate parser.

/** Returns YTM's wrappers for TabRenderer data containing Library items or playlist songs. */
internal object PhoneBrowseResponseTabsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(PHONE_BROWSE_TABS_PROTO_FIELD),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;"
        ),
        newInstance("L", location = MatchAfterWithin(2))
    )
)

/** Creates YTM's object for reading the sections in TabRenderer data. */
internal fun createPhoneBrowseTabFingerprint(tabMapperType: String) = Fingerprint(
    definingClass = tabMapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("L"),
        literal(
            TAB_RENDERER_PROTO_FIELD,
            location = MatchAfterWithin(3)
        )
    )
)

/** Extracts the sections containing Library items or playlist songs from YTM's TabRenderer data. */
internal fun getSectionListFingerprint(tabWrapperType: String) = Fingerprint(
    definingClass = tabWrapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        // YTM checks this flag before reading content from TabRenderer.
        literal(TAB_CONTENT_PRESENT_FLAG)
    )
)

/** Returns the lists in a phone Library section. */
internal fun sectionListContentsFingerprint(
    sectionListType: String,
    sectionContentsType: String
) = Fingerprint(
    definingClass = sectionListType,
    returnType = sectionContentsType,
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            // Field f contains the Library grid or playlist song list.
            name = SECTION_LIST_CONTENTS_FIELD_NAME
        )
    )
)

// Library items and pagination

/** The phone Library's refresh method; its class also parses the results of Library pagination. */
internal object HandleMusicReloadShelfEventFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L")
)

/** Reads Library items, including playlists, artists, and podcasts. */
internal object GridRendererItemsFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(
        literal(GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(3))
    )
)

/** Returns the commands YTM uses to request more Library items or refresh the Library. */
internal fun gridPaginationCommandsFingerprint(getGridItemsMethod: Method) = Fingerprint(
    definingClass = getGridItemsMethod.definingClass,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = getGridItemsMethod.parameterTypes.map(CharSequence::toString),
    filters = listOf(
        // These flags indicate whether pagination (NEXT) or refresh (RELOAD) commands are present.
        literal(NEXT_COMMAND_PRESENT_FLAG),
        literal(RELOAD_COMMAND_PRESENT_FLAG)
    ),
    custom = { method, _ -> method != getGridItemsMethod }
)

/** Extracts Library items returned by pagination, either directly from the response or from its first section. */
internal object LibraryPaginationDecoderFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(
        AccessFlags.PROTECTED,
        AccessFlags.FINAL,
        AccessFlags.BRIDGE,
        AccessFlags.SYNTHETIC
    ),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L")
)

// Read individual Library and playlist items

/** Returns the command for a single tap on a playlist or song in YTM's phone list. */
internal fun phoneBrowseItemSingleTapCommandFingerprint(
    phoneBrowseItemType: String,
    commandType: String
) = Fingerprint(
    returnType = commandType,
    parameters = listOf(phoneBrowseItemType),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseItemType,
            type = commandType
        )
    )
)

// Read the playlist ID from an item's command

/** Reads the BrowseEndpoint, which identifies the YTM page a command opens. */
internal fun browseEndpointFromCommandFingerprint(
    commandType: String,
    browseEndpointType: String
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = browseEndpointType,
    parameters = listOf(commandType)
)

// Playlist titles and artwork

/** Reads an item's artwork container, decodes it, then reads the thumbnail details. */
internal fun phoneBrowseItemArtworkFingerprint(phoneBrowseItemType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("L", phoneBrowseItemType, "I"),
    strings = listOf("thumbnailOverlayColor"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseItemType
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "L"),
            returnType = "Lj$/util/Optional;",
            location = MatchAfterWithin(4)
        ),
        methodCall(definingClass = "Lj$/util/Optional;", name = "get"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately())
    )
)

/** Decodes the artwork message containing a playlist or song's thumbnail details. */
internal fun decodeThumbnailFingerprint(thumbnailFieldType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/MessageLite;",
    parameters = listOf(thumbnailFieldType),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
            name = "getGeneratedRegistry",
            parameters = emptyList(),
            returnType = "Lcom/google/protobuf/ExtensionRegistryLite;"
        )
    )
)

/** Creates an Android Auto item using YTM's command encoder and artwork URI converter. */
internal fun androidAutoMediaDescriptionFingerprint(thumbnailDetailsType: String) = Fingerprint(
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "Ljava/lang/String;"
        ),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = thumbnailDetailsType),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf(thumbnailDetailsType),
            returnType = "Landroid/net/Uri;"
        ),
        MEDIA_DESCRIPTION_CONSTRUCTOR_CALL
    )
)

/** Converts playlist and song titles or subtitles into display text. */
internal fun formatTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;")
)

// Play a selected playlist

/** YTM's playback command builder accepts a playlist ID without a song ID. */
internal fun playlistPlaybackCommandFingerprint(commandType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;", "Ljava/lang/String;", "I", "F",
        "Ljava/lang/String;", "Ljava/lang/String;", "Z"
    ),
    filters = listOf(
        fieldAccess(
            definingClass = commandType,
            type = commandType,
            opcode = Opcode.SGET_OBJECT
        )
    )
)

// Refresh after Library changes

/** YTM's requests that change the Library. */
internal fun libraryChangeRequestFingerprint(endpoint: String) = Fingerprint(
    name = "<init>",
    returnType = "V",
    strings = listOf(endpoint)
)

/** Calls the request's success callback with its response. */
internal fun requestSuccessCallbackFingerprint(requestBaseType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lcom/google/protobuf/MessageLite;"),
    filters = listOf(
        methodCall(parameters = emptyList(), returnType = "V"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "V"
        )
    ),
    custom = { method, classDef ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && classDef.instanceFields.any { field ->
            field.type == requestBaseType
        }
    }
)

/** The successful response clears the cached request body before notifying its caller. */
internal fun requestCompletionFingerprint(method: MethodReference) = Fingerprint(
    definingClass = method.definingClass,
    name = method.name,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        literal(0),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "[B")
    )
)

/** Identifies the endpoint that YTM appends to its request URL. */
internal fun requestUrlFingerprint(requestDataType: String, endpointOwnerType: String) = Fingerprint(
    definingClass = requestDataType,
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = endpointOwnerType,
            type = "Ljava/lang/String;"
        ),
        methodCall(
            definingClass = "Landroid/net/Uri\$Builder;",
            name = "appendEncodedPath",
            parameters = listOf("Ljava/lang/String;"),
            returnType = "Landroid/net/Uri\$Builder;"
        )
    )
)

/** Loads an Android Auto list again to refresh its contents without disconnecting. */
internal fun mediaBrowserReloadFingerprint(baseServiceType: String) = Fingerprint(
    definingClass = baseServiceType,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    strings = listOf("onLoadChildren must call detach() or sendResult() before returning for package=")
)

// endregion
