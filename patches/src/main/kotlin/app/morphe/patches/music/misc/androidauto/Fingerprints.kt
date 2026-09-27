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
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Matches YTM's existing request, playlist, and playback code for the Android Auto patches.
 *
 * [bypassCertificateChecksPatch] uses [CheckCertificateFingerprint] and [IsGoogleSignedFingerprint].
 * [supportAndroidAutoPatch] reuses the matched code to:
 * - Identify Playlists and return Android Auto items: [BuildAndroidAutoMediaItemFingerprint],
 *   [SendEmptyAndroidAutoMediaItemsFingerprint].
 * - Fetch the phone Library and selected playlists: [CreatePhoneBrowseRequestFingerprint],
 *   [PhoneBrowseResponseTabsFingerprint], [gridPaginationCommandsFingerprint], [LibraryPaginationDecoderFingerprint].
 * - Read titles and artwork: [formatTextFingerprint], [phoneBrowseItemArtworkFingerprint],
 *   [androidAutoMediaDescriptionFingerprint].
 * - Read item commands: [PhoneBrowseItemFingerprint], [phoneBrowseItemSingleTapCommandFingerprint].
 * - Read the playlist's Play button: [decodeButtonRendererFingerprint].
 */

private const val PHONE_BROWSE_TABS_PROTO_FIELD = 58_173_949L
private const val TAB_RENDERER_PROTO_FIELD = 58_174_010L
private const val TAB_CONTENT_PRESENT_FLAG = 1L
private const val SECTION_LIST_CONTENTS_FIELD_NAME = "f"
private const val PHONE_BROWSE_ITEM_PROTO_FIELD = 161_429_595L
private const val GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG = 0x40000L
private const val NEXT_COMMAND_PRESENT_FLAG = 0x1L
private const val RELOAD_COMMAND_PRESENT_FLAG = 0x2L
private const val PLAY_BUTTON_PROTO_FIELD = 65_153_809L
private const val THUMBNAIL_PROTO_FIELD = 164_480_666L
private const val WATCH_ENDPOINT_PROTO_FIELD = 48_687_757L

// Bypass certificate checks

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
 * [GoogleSignatureVerifier.c(String)][defpackage.tcn.c] — the boolean entry-point
 * that [AllowlistManager.g][defpackage.kxo.g] calls to decide whether the caller
 * is Google-signed.  Scoped to [GoogleCertificatesRemoteFingerprint] so the
 * patcher never picks up an unrelated `(String)→boolean` method.
 */
internal object IsGoogleSignedFingerprint : Fingerprint(
    classFingerprint = GoogleCertificatesRemoteFingerprint,
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;")
)

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
        "Landroid/net/Uri;",
    ),
    returnType = "V",
)

/** Creates Android Auto media items, including the Playlists folder. */
internal object BuildAndroidAutoMediaItemFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        // Three constructor calls cover media items that can be opened, played, or both.
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    },
)

/** Returns an empty Android Auto list for an unrecognized media ID. */
internal object SendEmptyAndroidAutoMediaItemsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: "),
)

// Request Library and playlist pages through YTM

/** MusicBrowserService initialization, where the patch obtains YTM's object for Library and playlist requests. */
internal fun musicBrowserServiceSuperclassOnCreateFingerprint(
    musicBrowserServiceType: String,
    generatedComponentType: String,
) = Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            name = "generatedComponent",
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = generatedComponentType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = musicBrowserServiceType,
        ),
    ),
)

/** Obtains YTM's object for sending Library and playlist requests. */
internal fun phoneBrowseClientProviderFingerprint(
    phoneBrowseClientType: String,
) = Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(phoneBrowseClientType, location = MatchAfterImmediately()),
    ),
)

/** Creates a request for the phone's Library, Home, or a playlist. */
internal object CreatePhoneBrowseRequestFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
        ),
        // FEmusic_home identifies the phone's Home page; YTM compares it with the ID read above.
        string("FEmusic_home", location = MatchAfterImmediately()),
    ),
    // apply(Object) also compares FEmusic_home, but returns a Boolean with declared type Object.
    // Exclude it to select the method that creates the Browse request.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" },
)

/** Sends a Library or playlist request and returns a future for the response. */
internal fun sendPhoneBrowseRequestFingerprint(
    phoneBrowseClientType: String,
    phoneBrowseRequestType: String,
) = Fingerprint(
    definingClass = phoneBrowseClientType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(phoneBrowseRequestType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseRequestType,
            type = "Ljava/lang/String;",
        ),
    ),
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
            opcode = Opcode.IPUT_OBJECT,
        ),
    ),
)

// Read the contents returned by Library and playlist requests
// The first Library response and playlist contents use TabRenderer and section data; pagination has a separate parser.

/** Returns YTM's wrappers for TabRenderer data containing Library items or playlist songs. */
internal object PhoneBrowseResponseTabsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(PHONE_BROWSE_TABS_PROTO_FIELD),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;",
        ),
        newInstance("L", location = MatchAfterWithin(2)),
    ),
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
            location = MatchAfterWithin(3),
        ),
    ),
)

/** Extracts the sections containing Library items or playlist songs from YTM's TabRenderer data. */
internal fun getSectionListFingerprint(tabWrapperType: String) = Fingerprint(
    definingClass = tabWrapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        // YTM checks this flag before reading content from TabRenderer.
        literal(TAB_CONTENT_PRESENT_FLAG),
    ),
)

/** Returns each section's Library items (GridRenderer) or playlist songs (PlaylistContents). */
internal fun sectionListContentsFingerprint(
    sectionListType: String,
    sectionContentsType: String,
) = Fingerprint(
    definingClass = sectionListType,
    returnType = sectionContentsType,
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            // Field f contains the Library grid or playlist song list.
            name = SECTION_LIST_CONTENTS_FIELD_NAME,
        ),
    ),
)

// Read individual Library and playlist items

/** YTM's shared item type for playlists, songs, and the Add a song button. */
internal object PhoneBrowseItemFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            PHONE_BROWSE_ITEM_PROTO_FIELD,
            location = MatchAfterWithin(2),
        ),
    ),
)

/** Returns the command for a single tap on a playlist or song in YTM's phone list. */
internal fun phoneBrowseItemSingleTapCommandFingerprint(
    phoneBrowseItemType: String,
    commandType: String,
) = Fingerprint(
    returnType = commandType,
    parameters = listOf(phoneBrowseItemType),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseItemType,
            type = commandType,
        ),
    ),
)

/** The phone Library's refresh method; its class also parses the results of Library pagination. */
internal object HandleMusicReloadShelfEventFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
)

/** Reads Library items, including playlists, artists, and podcasts. */
internal object GridRendererItemsFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(literal(GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG)),
)

// Library pagination commands

/** Returns the commands YTM uses to request more Library items or refresh the Library. */
internal fun gridPaginationCommandsFingerprint(getGridItemsMethod: Method) = Fingerprint(
    definingClass = getGridItemsMethod.definingClass,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = getGridItemsMethod.parameterTypes.map(CharSequence::toString),
    filters = listOf(
        // These flags indicate whether pagination (NEXT) or refresh (RELOAD) commands are present.
        literal(NEXT_COMMAND_PRESENT_FLAG),
        literal(RELOAD_COMMAND_PRESENT_FLAG),
    ),
    custom = { method, _ -> method != getGridItemsMethod },
)

// Read a selected playlist's songs

/** Reads a playlist's songs and Add a song button. */
internal fun playlistItemsFingerprint(phoneBrowseItemType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L", "Z"),
    filters = listOf(checkCast(phoneBrowseItemType)),
)

// Library pagination responses

/** Extracts Library items returned by pagination, either directly from the response or from its first section. */
internal object LibraryPaginationDecoderFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(
        AccessFlags.PROTECTED,
        AccessFlags.FINAL,
        AccessFlags.BRIDGE,
        AccessFlags.SYNTHETIC,
    ),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
)

// Playlist titles and artwork

/** Registers the YTM data type containing thumbnail details for playlists and songs. */
internal object PhoneBrowseItemThumbnailFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            THUMBNAIL_PROTO_FIELD,
            location = MatchAfterWithin(2),
        ),
    ),
)

/** Reads playlist or song artwork before decoding the thumbnail used by the phone's Library. */
internal fun phoneBrowseItemArtworkFingerprint(
    phoneBrowseItemType: String,
    thumbnailProtobufExtensionField: FieldReference,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L", phoneBrowseItemType, "I"),
    strings = listOf("thumbnailOverlayColor"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseItemType,
        ),
        fieldAccess(
            reference = thumbnailProtobufExtensionField,
            opcode = Opcode.SGET_OBJECT,
            location = MatchAfterWithin(3),
        ),
    ),
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
            returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
        ),
    ),
)

/** Creates an Android Auto item and converts its artwork to an image URI. */
internal fun androidAutoMediaDescriptionFingerprint(
    thumbnailFieldTypes: Set<String>,
) = Fingerprint(
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions
        if (method.parameterTypes.size != 1 || instructions == null) {
            false
        } else {
            val readFieldTypes = instructions
                .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
                .toSet()
            instructions
                .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
                .filter { reference -> reference.returnType == "Landroid/net/Uri;" }
                .mapNotNull { reference ->
                    reference.parameterTypes.singleOrNull()?.toString()
                }
                .any { parameterType ->
                    parameterType in thumbnailFieldTypes && parameterType in readFieldTypes
                }
        }
    },
)

/** Converts playlist and song titles or subtitles into display text. */
internal fun formatTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;"),
)

// Read the playlist ID from an item's command

/** Reads the BrowseEndpoint, which identifies the YTM page a command opens. */
internal fun browseEndpointFromCommandFingerprint(
    commandType: String,
    browseEndpointType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = browseEndpointType,
    parameters = listOf(commandType),
)

// Encode item commands for Android Auto

/** Converts the command for a selected item into the media ID YTM accepts from Android Auto. */
internal object EncodeCommandMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    custom = { method, _ ->
        val commandType = method.parameterTypes.single().toString()
        if (commandType == "Ljava/lang/String;") {
            false
        } else {
            // YTM stores the command in another object, then converts that object to a media ID string.
            val commandWrapperType = method.instructions
                .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
                .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
                .filter { field -> field.type == commandType }
                .distinct()
                .singleOrNull()
                ?.definingClass
            commandWrapperType != null && method.instructions
                .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
                .any { reference ->
                    reference.parameterTypes.map(CharSequence::toString) ==
                        listOf(commandWrapperType) &&
                        reference.returnType == "Ljava/lang/String;"
                }
        }
    },
)

// Check playlist contents

/** WatchEndpoint: YTM's data identifying the song or video to play. */
internal object WatchEndpointExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(WATCH_ENDPOINT_PROTO_FIELD)),
)

// Read the command from the Play button above the playlist's songs

/** Registers ButtonRenderer, the data describing buttons such as Play above a playlist's songs. */
internal fun playButtonRendererFingerprint(commandType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(PLAY_BUTTON_PROTO_FIELD)),
    // FeedbackEndpoint uses the same protobuf field number for a command. The Play button has a different data type.
    custom = { method, _ ->
        val playButtonMessageType = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .firstOrNull { field -> field.definingClass == field.type }
            ?.type
        playButtonMessageType != null && playButtonMessageType != commandType
    },
)

/** Reads the Play button from the playlist data containing it. */
internal fun decodeButtonRendererFingerprint(
    playButtonContainerType: String,
    buttonRendererType: String,
    playButtonProtobufExtensionField: FieldReference,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = buttonRendererType,
    parameters = listOf("Z", playButtonContainerType),
    filters = listOf(
        fieldAccess(
            reference = playButtonProtobufExtensionField,
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

/**
 * The playlist Play button and live chat button store their commands in the same field of YTM's button data.
 * Use the live chat button's code to identify that field.
 */
internal fun buttonRendererCommandCopyFingerprint(
    buttonRendererType: String,
    commandType: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = buttonRendererType,
            type = commandType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = commandType,
            location = MatchAfterWithin(4),
        ),
    ),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val copiedCommandFields = instructions.mapIndexedNotNull { index, instruction ->
            val buttonField = instruction.getReference<FieldReference>()
            if (instruction.opcode == Opcode.IGET_OBJECT &&
                buttonField?.definingClass == buttonRendererType &&
                buttonField.type == commandType
            ) {
                val commandCopied = instructions.drop(index + 1).take(4).any { nearby ->
                    val target = nearby.getReference<FieldReference>()
                    nearby.opcode == Opcode.IPUT_OBJECT &&
                        target?.definingClass != buttonRendererType &&
                        target?.type == commandType
                }
                buttonField.takeIf { commandCopied }
            } else {
                null
            }
        }.distinct()
        copiedCommandFields.size == 1
    },
)
