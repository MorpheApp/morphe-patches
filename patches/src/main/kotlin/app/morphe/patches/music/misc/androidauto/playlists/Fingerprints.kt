/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PAGE_LIST_FIELD_NUMBER = 58_173_949L
private const val PAGE_FIELD_NUMBER = 58_174_010L
private const val SECTIONS_PRESENT_FLAG = 1L
private const val PLAYLIST_OR_TRACK_FIELD_NUMBER = 161_429_595L
private const val PLAY_BUTTON_FIELD_NUMBER = 65_153_809L
private const val THUMBNAIL_FIELD_NUMBER = 164_480_666L

// Android Auto playlist loading

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

internal object PlaylistCategoryFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    },
)

internal object PlaylistLoadFallbackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: ")
)

internal fun playlistLoadHandlerFingerprint(
    playlistLoadHandlerType: String,
    androidAutoResultType: String,
) = Fingerprint(
    definingClass = playlistLoadHandlerType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(androidAutoResultType),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal fun musicBrowserServiceFingerprint(androidAutoResultType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<TypeReference>()?.type == androidAutoResultType ||
                instruction.getReference<MethodReference>()?.returnType == androidAutoResultType
        } == true
    },
)

internal fun playlistLoaderOnCreateFingerprint(
    musicBrowserServiceType: String,
    componentType: String,
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
            type = componentType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = musicBrowserServiceType,
        ),
    ),
)

internal fun playlistLoaderProviderFingerprint(playlistLoaderType: String) = Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(playlistLoaderType, location = MatchAfterImmediately()),
    ),
)

// Playlist requests

internal object CreatePlaylistRequestFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
        ),
        string("FEmusic_home", location = MatchAfterImmediately()),
    ),
    // An Object-returning lambda also references FEmusic_home, but does not build the request.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" }
)

internal fun sendPlaylistRequestFingerprint(
    playlistLoaderType: String,
    playlistRequestBuilderType: String,
) = Fingerprint(
    definingClass = playlistLoaderType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(playlistRequestBuilderType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = playlistRequestBuilderType,
            type = "Ljava/lang/String;",
        ),
    ),
)

internal fun setLibraryOrPlaylistIdFingerprint(field: FieldReference) = Fingerprint(
    definingClass = field.definingClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = field.definingClass,
            name = field.name,
            type = field.type,
        ),
    ),
)

// Playlist responses

// Protobuf field 58173949 contains the page list returned by FEmusic_library_landing.
internal object PlaylistResponseFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(PAGE_LIST_FIELD_NUMBER),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;",
        ),
        newInstance("L", location = MatchAfterWithin(2)),
    ),
)

internal fun createPlaylistPageFingerprint(factoryType: String) = Fingerprint(
    definingClass = factoryType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("L"),
        literal(
            PAGE_FIELD_NUMBER,
            location = MatchAfterWithin(3),
        ),
    ),
)

internal fun getPlaylistBodyFingerprint(pageType: String) = Fingerprint(
    definingClass = pageType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(literal(SECTIONS_PRESENT_FLAG)),
)

internal fun playlistBodyGroupsFingerprint(
    bodyType: String,
    returnType: String,
) = Fingerprint(
    definingClass = bodyType,
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = returnType,
    parameters = emptyList(),
)

// Playlist entries and tracks

// Protobuf field 161429595 is shared by Library playlist entries and loaded tracks.
internal object PlaylistOrTrackFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            PLAYLIST_OR_TRACK_FIELD_NUMBER,
            location = MatchAfterWithin(2),
        ),
    ),
)

internal object PlaylistReloadHandlerFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
)

// The playlist reader checks presence bits 0x1000 and 0x40000 before adding rows from protobuf
// field 161429595.
internal object ExtractPlaylistsFingerprint : Fingerprint(
    classFingerprint = PlaylistReloadHandlerFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(
        literal(0x1000L),
        literal(0x40000L),
    ),
)

internal fun extractPlaylistTracksFingerprint(playlistOrTrackType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L", "Z"),
    filters = listOf(opcode(Opcode.CHECK_CAST)),
    custom = { method, _ ->
        method.instructions.any { instruction ->
            instruction.opcode == Opcode.CHECK_CAST &&
                instruction.getReference<TypeReference>()?.type == playlistOrTrackType
        }
    },
)

internal object DecodeMorePlaylistsFingerprint : Fingerprint(
    classFingerprint = PlaylistReloadHandlerFingerprint,
    accessFlags = listOf(
        AccessFlags.PROTECTED,
        AccessFlags.FINAL,
        AccessFlags.BRIDGE,
        AccessFlags.SYNTHETIC,
    ),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
)

// Protobuf field 164480666 stores the thumbnail payload in field c of playlist entries and tracks.
internal object PlaylistThumbnailFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            THUMBNAIL_FIELD_NUMBER,
            location = MatchAfterWithin(2),
        ),
    ),
)

internal fun decodePlaylistThumbnailFingerprint(thumbnailType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/MessageLite;",
    parameters = listOf(thumbnailType),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
            name = "getGeneratedRegistry",
            parameters = emptyList(),
            returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
        ),
    ),
)

internal fun playlistArtworkFingerprint(
    payloadFieldTypes: Set<String>,
) = Fingerprint(
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    // Before building MediaDescriptionCompat, YTM converts the decoded thumbnail to its artwork Uri.
    custom = { method, _ ->
        if (method.parameterTypes.size != 1) return@Fingerprint false
        val instructions = method.implementation?.instructions ?: return@Fingerprint false
        val readFieldTypes = instructions
            .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
            .toSet()
        instructions.any { instruction ->
            val reference = instruction.getReference<MethodReference>()
                ?: return@any false
            val parameterType = reference.parameterTypes.singleOrNull()?.toString()
                ?: return@any false
            reference.returnType == "Landroid/net/Uri;" &&
                parameterType in payloadFieldTypes && parameterType in readFieldTypes
        }
    },
)

internal fun formatPlaylistTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;"),
)

internal fun decodePlaylistActionFingerprint(
    actionType: String,
    decodedActionType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = decodedActionType,
    parameters = listOf(actionType),
)

internal object CreatePlaybackIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    // MediaItemData.createMediaId serializes the row's click action as Android Auto's media ID.
    custom = createPlaybackId@{ method, _ ->
        val actionType = method.parameterTypes.single().toString()
        if (actionType == "Ljava/lang/String;") return@createPlaybackId false
        val actionStores = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .filter { field -> field.type == actionType }
            .distinct()
        val wrapperType = actionStores.singleOrNull()?.definingClass
            ?: return@createPlaybackId false
        method.instructions.any { instruction ->
            val reference = instruction.getReference<MethodReference>()
                ?: return@any false
            reference.parameterTypes.map(CharSequence::toString) == listOf(wrapperType) &&
                reference.returnType == "Ljava/lang/String;"
        }
    },
)

// Playlist playback

internal fun playlistPlayButtonFingerprint(actionType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(PLAY_BUTTON_FIELD_NUMBER)),
    // Protobuf field 65153809 is ButtonRenderer in response.q and FeedbackEndpoint on click actions.
    custom = { method, _ ->
        val containingType = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .firstOrNull { field -> field.definingClass == field.type }
            ?.type
        containingType != null && containingType != actionType
    },
)

internal fun decodePlaylistPlayButtonFingerprint(
    containingType: String,
    playlistPlayButtonType: String,
    descriptorField: FieldReference,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = playlistPlayButtonType,
    parameters = listOf("Z", containingType),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = descriptorField.definingClass,
            name = descriptorField.name,
            type = descriptorField.type,
        ),
    ),
)

internal fun playlistPlayActionFingerprint(
    playlistPlayButtonType: String,
    actionType: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = playlistPlayButtonType,
            type = actionType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = actionType,
            location = MatchAfterWithin(4),
        ),
    ),
    // The live-chat ImageButton copies its click action from ButtonRenderer.
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList()
            ?: return@Fingerprint false
        val copiedActionFields = instructions.mapIndexedNotNull { index, instruction ->
            val field = instruction.getReference<FieldReference>()
                ?: return@mapIndexedNotNull null
            if (instruction.opcode != Opcode.IGET_OBJECT ||
                field.definingClass != playlistPlayButtonType || field.type != actionType
            ) {
                return@mapIndexedNotNull null
            }
            val copiedToAction = instructions.drop(index + 1).take(4).any { nearby ->
                val target = nearby.getReference<FieldReference>()
                    ?: return@any false
                nearby.opcode == Opcode.IPUT_OBJECT &&
                    target.definingClass != playlistPlayButtonType && target.type == actionType
            }
            field.takeIf { copiedToAction }
        }.distinct()
        copiedActionFields.size == 1
    },
)
