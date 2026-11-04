/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CommandResolverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("L", "L", "Ljava/util/Map;"),
    filters = listOf(
        anyInstruction(
            string("CommandResolver threw exception during resolution")
        )
    )
)

/**
 * Request of the 'ypc/get_offline_upsell' endpoint.
 */
internal object OfflineUpsellRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        string("ypc/get_offline_upsell")
    )
)

/**
 * Handles the Download command of accounts that are offered Premium instead,
 * by showing a progress dialog and then the Premium upsell of the endpoint.
 */
internal object OfflineUpsellCommandFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        string("com.google.android.libraries.youtube.logging.interaction_logger"),
        newInstance(type = { OfflineUpsellRequestFingerprint.classDef })
    )
)

/**
 * Resolves the offline playlist endpoint, which the Download button of Liked music and of
 * some other playlists sends instead of the offline upsell.
 */
internal object MusicOfflinePlaylistEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        string("Object is not an offlineable playlist: %s")
    )
)

/**
 * The constructors of the two presenters of the native song rows of the playlists, of Liked
 * music and of the song results, which inflate the layout music_two_column_item. The text view
 * stored right after the id of the subtitle is looked up shows the details of the song.
 */
internal object TwoColumnItemPresenterConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "music_two_column_item"),
        resourceLiteral(ResourceType.ID, "subtitle"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/widget/TextView;",
            location = MatchAfterWithin(4)
        )
    )
)

/**
 * The method of a presenter that binds a song to its row. The corner radius of the thumbnail
 * is read once the details text is set, whichever path the accessibility text took.
 */
internal fun twoColumnItemBindFingerprint(presenterType: String) = Fingerprint(
    definingClass = presenterType,
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;"),
    filters = listOf(
        string("isDraggable"),
        resourceLiteral(ResourceType.DIMEN, "music_thumbnail_default_corner_radius")
    )
)

/**
 * The bind method of the presenter that binds a wrapper of the renderer of the row, instead of
 * the renderer itself like the other presenter. The renderer is read out of the wrapper before
 * the draggable flag of the row.
 */
internal object TwoColumnItemWrapperBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;"),
    filters = listOf(
        checkCast("L"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        string("isDraggable", location = MatchAfterWithin(3)),
        resourceLiteral(ResourceType.DIMEN, "music_thumbnail_default_corner_radius")
    )
)

internal object MusicOfflineVideoEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf("Object is not an offlineable video: %s"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "L"
        ),
        methodCall(
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL),
            parameters = listOf("Ljava/lang/String;", "L", "L", "L"),
            returnType = "V"
        )
    )
)
