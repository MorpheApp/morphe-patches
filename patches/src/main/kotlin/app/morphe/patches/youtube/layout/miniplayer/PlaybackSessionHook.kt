/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.string
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.hookUserMiniplayerDismissal
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.installPlaybackSessionPlaylistReader
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.parseProtoInstructions
import app.morphe.patches.youtube.shared.VideoPlaybackIntentFingerprint
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.shared.hookPlaybackStartDescriptor
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.patches.youtube.video.information.playerStatusHook
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.series.MediaSessionFingerprint
import app.morphe.patches.youtube.video.series.hookCurrentAccountProvider
import app.morphe.patches.youtube.video.series.resolveNativeAccountContract
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import java.util.logging.Logger

private const val SESSION = "Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch;"
private const val START_PAUSED_PRESENCE_BIT = 16384L
private const val DESCRIPTOR = $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$Descriptor;"
private const val WATCH_STARTER = $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$WatchStarter;"
private const val ACCOUNT_PROVIDER = $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$AccountProvider;"
private const val ACCOUNT = $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$Account;"

internal fun installPlaybackSessionHooks(): Boolean {
    fun unavailable(hook: String): Boolean {
        Logger.getLogger("app.morphe.patches.youtube.layout.miniplayer")
            .warning("Continue watching on startup unavailable: $hook")
        return false
    }
    val descriptor = PlaybackStartDescriptorToStringFingerprint
    val descriptorType = descriptor.originalClassDef.type
    val videoId = descriptor.instructionMatches[1].getMethodCalled()
    val watch = VideoPlaybackIntentFingerprint
    val dispatch = watch.originalMethod
    val builder = watch.instructionMatches.first().getMethodCalled()
    if (builder.returnType != descriptorType) return unavailable("watch descriptor builder")

    // The native startup miniplayer's descriptor mutator sets this boolean in the watch proto.
    val pause = Fingerprint(
        definingClass = descriptorType,
        parameters = emptyList(),
        returnType = "V",
        filters = listOf(
            literal(START_PAUSED_PRESENCE_BIT),
            fieldAccess(opcode = Opcode.IPUT_BOOLEAN, type = "Z")
        )
    ).matchOrNull()?.originalMethod
        ?: return unavailable("native pause-on-start setter")

    val positionGetter = Fingerprint(
        definingClass = descriptorType,
        parameters = emptyList(),
        returnType = "Lj$/time/Duration;",
        filters = listOf(
            methodCall(definingClass = descriptorType, parameters = emptyList(), returnType = "J"),
            methodCall(name = "ofMillis", parameters = listOf("J"), returnType = "Lj$/time/Duration;")
        )
    ).matchOrNull()?.instructionMatches?.first()?.getMethodCalled()
        ?: return unavailable("descriptor start position getter")
    val positionField = Fingerprint(
        definingClass = descriptorType,
        name = positionGetter.name,
        parameters = emptyList(),
        returnType = "J",
        filters = listOf(fieldAccess(opcode = Opcode.IGET_WIDE, type = "J"))
    ).matchOrNull()?.instructionMatches?.single()?.fieldReference
        ?: return unavailable("descriptor start position field")
    val setPosition = Fingerprint(
        definingClass = descriptorType,
        parameters = listOf("J"),
        returnType = "V",
        filters = listOf(fieldAccess(opcode = Opcode.IPUT_WIDE, reference = { positionField }))
    ).matchOrNull()?.originalMethod ?: return unavailable("descriptor start position setter")

    val queueList = Fingerprint(
        definingClass = builder.definingClass,
        name = builder.name,
        returnType = descriptorType,
        parameters = emptyList(),
        filters = listOf(
            string("Only one of localProto, command, videoIdList can be set"),
            fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT, type = "Ljava/util/List;"),
            methodCall(definingClass = "Ljava/util/List;", name = "get")
        )
    ).matchOrNull()?.instructionMatches?.get(1)?.fieldReference
        ?: return unavailable("descriptor video ID list")

    descriptor.classDef.apply {
        addPatchInterface(DESCRIPTOR, "patch_sessionVideoId", videoId)
        addPatchInterface(DESCRIPTOR, "patch_startSessionPaused", pause)
        addPatchInterface(DESCRIPTOR, "patch_setSessionPosition", setPosition)
        addPatchInterface(
            DESCRIPTOR,
            "patch_newSessionQueue",
            """
                new-instance v0, ${builder.definingClass}
                invoke-direct {v0}, ${builder.definingClass}-><init>()V
                iput-object p1, v0, $queueList
                invoke-virtual {v0}, $builder
                move-result-object v0
                return-object v0
            """
        )
    }

    val commandType = dispatch.parameterTypes.first().toString()
    val watchType = dispatch.definingClass
    mutableClassDefBy(watchType).apply {
        addPatchInterface(
            WATCH_STARTER,
            "patch_startSessionWatch",
            """
                ${parseProtoInstructions(commandType, bytes = "p1", result = "v0", registry = "v1")}
                check-cast v0, $commandType
                invoke-virtual {p0, v0, p2}, $dispatch
                return-void
            """
        )
        methods.filter { it.name == "<init>" }.forEach { constructor ->
            constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                constructor.addInstruction(index, "invoke-static/range {p0 .. p0}, $SESSION->attachWatchStarter($WATCH_STARTER)V")
            }
        }
    }

    hookCurrentAccountProvider(resolveNativeAccountContract(), ACCOUNT_PROVIDER, "patch_sessionAccount",
        ACCOUNT, "$SESSION->attachAccount")
    val mediaSession = MediaSessionFingerprint.instructionMatches.first()
    val sessionRegister = mediaSession.getInstruction<FiveRegisterInstruction>().registerC
    MediaSessionFingerprint.method.addInstruction(
        mediaSession.index + 1,
        "invoke-static/range {v$sessionRegister .. v$sessionRegister}, $SESSION->attachMediaSession(Landroid/media/session/MediaSession;)V"
    )

    YouTubeActivityOnCreateFingerprint.method.addInstruction(
        0,
        "invoke-static/range {p0 .. p1}, $SESSION->initialize(Landroid/app/Activity;Landroid/os/Bundle;)V"
    )
    hookPlaybackStartDescriptor(SESSION, "prepareWatchDescriptor", "prepareShortsDescriptor")
    hookUserMiniplayerDismissal("$SESSION->onMiniplayerDismissed()V")
    installPlaybackSessionPlaylistReader()
    videoTimeHook(SESSION, "videoTimeChanged")
    playerStatusHook(SESSION, "onPlayerStatus")
    setExtensionIsPatchIncluded(SESSION)
    return true
}
