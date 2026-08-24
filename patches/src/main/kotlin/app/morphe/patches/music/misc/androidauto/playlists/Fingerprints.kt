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

private const val SINGLE_COLUMN_BROWSE_RESULTS_FIELD_NUMBER = 58_173_949L
private const val TAB_RENDERER_FIELD_NUMBER = 58_174_010L
private const val TAB_CONTENT_PRESENT_FLAG = 1L
private const val MUSIC_ITEM_FIELD_NUMBER = 161_429_595L
private const val BUTTON_RENDERER_EXTENSION_FIELD_NUMBER = 65_153_809L
private const val MUSIC_THUMBNAIL_FIELD_NUMBER = 164_480_666L

// Android Auto media-item creation and loadChildren

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

internal object MediaItemFactoryFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    },
)

internal object InvalidParentMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: ")
)

internal fun contentSupplierLoadChildrenFingerprint(
    contentSupplierType: String,
    loadChildrenResultType: String,
) = Fingerprint(
    definingClass = contentSupplierType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(loadChildrenResultType),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal fun musicBrowserServiceLoadChildrenFingerprint(loadChildrenResultType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<TypeReference>()?.type == loadChildrenResultType ||
                instruction.getReference<MethodReference>()?.returnType == loadChildrenResultType
        } == true
    },
)

internal fun musicBrowserServiceOnCreateFingerprint(
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

internal fun browseServiceProviderFingerprint(browseServiceType: String) = Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(browseServiceType, location = MatchAfterImmediately()),
    ),
)

// Browse requests

internal object BrowseEndpointRequestFingerprint : Fingerprint(
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

internal fun browseRequestFingerprint(
    browseServiceType: String,
    requestBuilderType: String,
) = Fingerprint(
    definingClass = browseServiceType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(requestBuilderType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = requestBuilderType,
            type = "Ljava/lang/String;",
        ),
    ),
)

internal fun browseIdSetterFingerprint(field: FieldReference) = Fingerprint(
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

// Browse responses

// Library tabs come from extension 58173949 in the FEmusic_library_landing response.
internal object BrowseTabsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(SINGLE_COLUMN_BROWSE_RESULTS_FIELD_NUMBER),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;",
        ),
        newInstance("L", location = MatchAfterWithin(2)),
    ),
)

internal fun browseTabMapperFingerprint(mapperType: String) = Fingerprint(
    definingClass = mapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("L"),
        literal(
            TAB_RENDERER_FIELD_NUMBER,
            location = MatchAfterWithin(3),
        ),
    ),
)

internal fun sectionListFingerprint(tabType: String) = Fingerprint(
    definingClass = tabType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(literal(TAB_CONTENT_PRESENT_FLAG)),
)

internal fun sectionItemsFingerprint(
    sectionListType: String,
    returnType: String,
) = Fingerprint(
    definingClass = sectionListType,
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = returnType,
    parameters = emptyList(),
)

// Library and playlist rows

// FEmusic_library_landing playlists and loaded playlist tracks both use extension 161429595.
internal object MusicItemExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            MUSIC_ITEM_FIELD_NUMBER,
            location = MatchAfterWithin(2),
        ),
    ),
)

internal object MusicReloadShelfEventFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
)

// GridRenderer uses presence bits 0x1000 and 0x40000 for extension-161429595 rows.
internal object GridItemsFingerprint : Fingerprint(
    classFingerprint = MusicReloadShelfEventFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(
        literal(0x1000L),
        literal(0x40000L),
    ),
)

internal object GridDecoderFingerprint : Fingerprint(
    classFingerprint = MusicReloadShelfEventFingerprint,
    accessFlags = listOf(
        AccessFlags.PROTECTED,
        AccessFlags.FINAL,
        AccessFlags.BRIDGE,
        AccessFlags.SYNTHETIC,
    ),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
)

// Field c on playlist and track rows contains thumbnail extension 164480666.
internal object MusicThumbnailExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            MUSIC_THUMBNAIL_FIELD_NUMBER,
            location = MatchAfterWithin(2),
        ),
    ),
)

internal fun musicThumbnailDecoderFingerprint(artworkType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/MessageLite;",
    parameters = listOf(artworkType),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
            name = "getGeneratedRegistry",
            parameters = emptyList(),
            returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
        ),
    ),
)

internal fun androidAutoArtworkFingerprint(
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

internal fun renderTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;"),
)

internal fun browseEndpointDecoderFingerprint(
    endpointType: String,
    decodedEndpointType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = decodedEndpointType,
    parameters = listOf(endpointType),
)

internal object EndpointMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    // MediaItemData.createMediaId serializes the row command as Android Auto's media ID.
    custom = endpointMediaId@{ method, _ ->
        val endpointType = method.parameterTypes.single().toString()
        if (endpointType == "Ljava/lang/String;") return@endpointMediaId false
        val endpointStores = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .filter { field -> field.type == endpointType }
            .distinct()
        val wrapperType = endpointStores.singleOrNull()?.definingClass
            ?: return@endpointMediaId false
        method.instructions.any { instruction ->
            val reference = instruction.getReference<MethodReference>()
                ?: return@any false
            reference.parameterTypes.map(CharSequence::toString) == listOf(wrapperType) &&
                reference.returnType == "Ljava/lang/String;"
        }
    },
)

// Playlist playback

internal fun buttonRendererExtensionFingerprint(endpointType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(BUTTON_RENDERER_EXTENSION_FIELD_NUMBER)),
    // Extension 65153809 is ButtonRenderer in response.q and FeedbackEndpoint in a command.
    custom = { method, _ ->
        val containingType = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .firstOrNull { field -> field.definingClass == field.type }
            ?.type
        containingType != null && containingType != endpointType
    },
)

internal fun buttonRendererDecoderFingerprint(
    containingType: String,
    buttonRendererType: String,
    descriptorField: FieldReference,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = buttonRendererType,
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

internal fun buttonRendererEndpointCopyFingerprint(
    buttonRendererType: String,
    endpointType: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = buttonRendererType,
            type = endpointType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = endpointType,
            location = MatchAfterWithin(4),
        ),
    ),
    // The live-chat ImageButton copies its click command from ButtonRenderer.
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList()
            ?: return@Fingerprint false
        val copiedEndpointFields = instructions.mapIndexedNotNull { index, instruction ->
            val field = instruction.getReference<FieldReference>()
                ?: return@mapIndexedNotNull null
            if (instruction.opcode != Opcode.IGET_OBJECT ||
                field.definingClass != buttonRendererType || field.type != endpointType
            ) {
                return@mapIndexedNotNull null
            }
            val copiedToCommand = instructions.drop(index + 1).take(4).any { nearby ->
                val target = nearby.getReference<FieldReference>()
                    ?: return@any false
                nearby.opcode == Opcode.IPUT_OBJECT &&
                    target.definingClass != buttonRendererType && target.type == endpointType
            }
            field.takeIf { copiedToCommand }
        }.distinct()
        copiedEndpointFields.size == 1
    },
)
