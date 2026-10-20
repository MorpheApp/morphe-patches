/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
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
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
    filters = listOf(
        string("Failed to get Google certificates from remote")
    )
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

// Android Auto browse requests, media items, and folder refresh

private const val SINGLE_COLUMN_BROWSE = 58_173_949L
private const val GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG = 0x40000L
private const val NEXT_COMMAND_PRESENT_FLAG = 0x1L
private const val RELOAD_COMMAND_PRESENT_FLAG = 0x2L

// Identify the link to Playlists and how YTM returns its contents to Android Auto

/**
 * Reads the action stored in an Android Auto media ID, such as opening a page or playing music.
 * YTM returns null when it cannot decode the ID, including those for pages added by this patch.
 */
internal fun decodedMediaIdFingerprint(mediaIdType: String) = Fingerprint(
    definingClass = mediaIdType,
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;"
        ),
        opcode(Opcode.INSTANCE_OF),
        literal(0),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * Matches the constructor that stores an Android Auto item's ID, title, and artwork.
 * Used to locate creation of the Playlists link and YTM's artwork conversion.
 */
internal val MEDIA_DESCRIPTION_CONSTRUCTOR_CALL = methodCall(
    definingClass = "Landroid/support/v4/media/MediaDescriptionCompat;",
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Landroid/graphics/Bitmap;",
        "Landroid/net/Uri;",
        "Landroid/os/Bundle;",
        "Landroid/net/Uri;"
    )
)

/** Creates Android Auto media items, including the link to Playlists. */
internal object BuildMediaItemFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(
        // Three constructor calls cover media items that can be opened, played, or both.
        MEDIA_DESCRIPTION_CONSTRUCTOR_CALL,
        MEDIA_DESCRIPTION_CONSTRUCTOR_CALL,
        MEDIA_DESCRIPTION_CONSTRUCTOR_CALL
    )
)

/** Returns an empty list when YTM does not recognize the page Android Auto requested. */
internal object SendEmptyMediaItemsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    filters = listOf(
        string("Invalid media id: ")
    )
)

// Locate YTM's phone request clients and browse methods

/**
 * Initializes MusicBrowserService, which serves YTM's pages and search results to Android Auto.
 * The patch obtains the phone request clients here so they are available when Auto connects.
 */
internal fun browserServiceOnCreateFingerprint(
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

/** Obtains YTM's object for sending phone browse or search requests, depending on the supplied type. */
internal fun phoneClientProviderFingerprint(phoneClientType: String) = Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(phoneClientType, location = MatchAfterImmediately())
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
        // FEmusic_home identifies the phone's Home page; YTM compares it with the browse ID read above.
        string("FEmusic_home", location = MatchAfterImmediately())
    ),
    // apply(Object) also compares FEmusic_home, but returns a Boolean with declared type Object.
    // Exclude it to select the method that creates the browse request.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" }
)

/**
 * Playlist search creates a browse request, sets its browse ID and empty tracking data, then sends it.
 * The phone browse bridge reuses these four methods with the requested browse ID.
 */
internal object PhoneBrowseRequestCallsFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "L"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("FEplaylist_filter_search", location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/lang/String;"),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L", "Ljava/util/concurrent/Executor;"),
            returnType = "Lcom/google/common/util/concurrent/ListenableFuture;"
        )
    )
)

/** Identifies the continuation token field in the phone browse request's diagnostic string. */
internal fun phoneBrowseContinuationFingerprint(requestType: String) = Fingerprint(
    definingClass = requestType,
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("browseId"),
    filters = listOf(
        string("continuation"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(2)
        )
    )
)

// Read the initial Library response; pagination has a separate parser.

/** Returns TabRenderer wrappers for phone page content, such as Library collections or playlist songs. */
internal object PhoneBrowseResponseTabsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(literal(SINGLE_COLUMN_BROWSE))
)

/** Finds readers for a phone tab's section container and its decoded renderers. */
internal object PhoneBrowseTabContentsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        methodCall(definingClass = "Ljava/util/Iterator;", name = "next"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "L",
            location = MatchAfterImmediately()
        )
    ),
    strings = listOf("swipe-to-refresh", "FEmusic_trending")
)

// Library items and pagination

/** The phone Library's refresh method; its class also parses the results of Library pagination. */
internal object HandleMusicReloadShelfEventFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L")
)

/** Reads playlist, artist, and podcast renderers from the phone's GridRenderer container. */
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

/** Extracts the Library grid returned by pagination, directly from the response or from its first section. */
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

// Read the playlist browse ID from an item's command

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
    ),
    strings = listOf("thumbnailOverlayColor")
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
internal fun mediaDescriptionFingerprint(thumbnailDetailsType: String) = Fingerprint(
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
    custom = { _, classDef ->
        classDef.instanceFields.any { field ->
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
            definingClass = $$"Landroid/net/Uri$Builder;",
            name = "appendEncodedPath",
            parameters = listOf("Ljava/lang/String;"),
            returnType = $$"Landroid/net/Uri$Builder;"
        )
    )
)

/** Loads an Android Auto folder again to refresh its contents without disconnecting. */
internal fun mediaBrowserReloadFingerprint(baseServiceType: String) = Fingerprint(
    definingClass = baseServiceType,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    filters = listOf(
        string("onLoadChildren must call detach() or sendResult() before returning for package=")
    )
)

// Android Auto search

/** Identifies the query and parameter fields in a phone search request. */
internal object PhoneSearchRequestFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("query", "params", "musicSearchRequestType"),
    filters = listOf(
        string("query"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;", location = MatchAfterWithin(2)),
        string("params"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;", location = MatchAfterWithin(2)),
    ),
)

/** Finds the calls YTM uses to create and send a phone search. */
internal object PhoneSearchSubmissionFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("executeInnerTubeSearch", "Could not parse searchbox stats"),
)

/** Reads the catalog and uploaded search types assigned to a phone search request. */
internal object SearchRequestTypeFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("MUSIC_SEARCH_REQUEST_TYPE_CATALOG"),
        fieldAccess(opcode = Opcode.SPUT_OBJECT, definingClass = "this"),
        string("MUSIC_SEARCH_REQUEST_TYPE_UPLOADED"),
        fieldAccess(opcode = Opcode.SPUT_OBJECT, definingClass = "this")
    )
)

/** Returns a completed list of search results to Android Auto. */
internal object SearchResultDeliveryFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    strings = listOf("MBS: SearchResult.sendResult() called multiple times.")
)

/** YTM's ContentSupplier handles requests for folder contents and search results in Android Auto. */
internal object ContentSupplierFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("com/google/android/apps/youtube/music/mediabrowser/content/ContentSupplier"),
)
