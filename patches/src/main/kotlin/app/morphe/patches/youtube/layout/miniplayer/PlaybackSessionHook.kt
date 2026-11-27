/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5436
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.p0Register
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.hookUserMiniplayerDismissal
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.installPlaybackSessionPlaylistReader
import app.morphe.patches.youtube.layout.captions.CaptionTrackIsDisableOptionFingerprint
import app.morphe.patches.youtube.layout.captions.DefaultCaptionTrackFingerprint
import app.morphe.patches.youtube.layout.captions.SubtitleManagerDefaultTrackGateFingerprint
import app.morphe.patches.youtube.layout.captions.SubtitleManagerManifestTrackGateFingerprint
import app.morphe.patches.youtube.layout.captions.SubtitleManagerSelectedTrackFingerprint
import app.morphe.patches.youtube.layout.captions.SubtitleManagerSelectionVideoIdFingerprint
import app.morphe.patches.youtube.shared.PlayerTypeFingerprint
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.shared.hookPlaybackStartDescriptor
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.patches.youtube.video.information.playerStatusHook
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.series.MediaSessionFingerprint
import app.morphe.patches.youtube.video.series.hookCurrentAccountProvider
import app.morphe.patches.youtube.video.series.resolveNativeAccountContract
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_MINIPLAYER_CLASS = "Lapp/morphe/extension/youtube/patches/MiniplayerPatch;"
private const val EXTENSION_SESSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch;"
private const val EXTENSION_DESCRIPTOR_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$Descriptor;"
private const val EXTENSION_ACCOUNT_PROVIDER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$AccountProvider;"
private const val EXTENSION_ACCOUNT_CLASS =
    $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$Account;"
private const val EXTENSION_CAPTION_SOURCE_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$CaptionSource;"

/**
 * Saves the playback session, and restores it paused in the miniplayer when the app starts.
 * Requires YouTube 21.30+.
 */
internal fun installPlaybackSessionHooks() {
    PlaybackStartDescriptorToStringFingerprint.classDef.apply {
        val builder = PlaybackStartDescriptorBuilderFingerprint.method
        val videoIdList = PlaybackStartDescriptorBuilderFingerprint.instructionMatches[1].fieldReference

        addPatchInterface(
            EXTENSION_DESCRIPTOR_INTERFACE,
            "patch_sessionVideoId",
            PlaybackStartDescriptorToStringFingerprint.instructionMatches[1].getMethodCalled()
        )
        addPatchInterface(
            EXTENSION_DESCRIPTOR_INTERFACE,
            "patch_startSessionPaused",
            PlaybackStartDescriptorStartPausedFingerprint.method
        )
        addPatchInterface(
            EXTENSION_DESCRIPTOR_INTERFACE,
            "patch_setSessionPosition",
            PlaybackStartDescriptorStartPositionFingerprint.method
        )
        addPatchInterface(
            EXTENSION_DESCRIPTOR_INTERFACE,
            "patch_newSessionQueue",
            """
                new-instance v0, ${builder.definingClass}
                invoke-direct {v0}, ${builder.definingClass}-><init>()V
                iput-object p1, v0, $videoIdList
                invoke-virtual {v0}, $builder
                move-result-object v0
                return-object v0
            """
        )
    }

    hookCurrentAccountProvider(
        resolveNativeAccountContract(),
        EXTENSION_ACCOUNT_PROVIDER_INTERFACE,
        "patch_sessionAccount",
        EXTENSION_ACCOUNT_CLASS,
        "$EXTENSION_SESSION_CLASS->attachAccount"
    )

    MediaSessionFingerprint.let {
        it.method.apply {
            val index = it.instructionMatches.first().index
            val register = getInstruction<FiveRegisterInstruction>(index).registerC
            addInstruction(
                index + 1,
                "invoke-static/range { v$register .. v$register }, " +
                        "$EXTENSION_SESSION_CLASS->attachMediaSession(Landroid/media/session/MediaSession;)V"
            )
        }
    }

    YouTubeActivityOnCreateFingerprint.method.addInstruction(
        0,
        "invoke-static/range { p0 .. p1 }, " +
                "$EXTENSION_SESSION_CLASS->initialize(Landroid/app/Activity;Landroid/os/Bundle;)V"
    )

    PlayerTypeFingerprint.method.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_SESSION_CLASS->observeWatchPlayer(Landroid/view/View;)V"
    )

    // The action button getter can first run on touch, even when the overlay is already inflated.
    MiniplayerModernOverlayInflationFingerprint.let {
        it.method.apply {
            val index = it.instructionMatches.last().index
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            addInstruction(
                index + 1,
                "invoke-static/range { v$register .. v$register }, " +
                        "$EXTENSION_MINIPLAYER_CLASS->bindRestoredMiniplayerOverlay(Landroid/view/View;)V"
            )
        }
    }

    hookPlaybackStartDescriptor(EXTENSION_SESSION_CLASS, "prepareWatchDescriptor", "prepareShortsDescriptor")
    hookUserMiniplayerDismissal(
        "$EXTENSION_SESSION_CLASS->onMiniplayerDismissed()V",
        "$EXTENSION_SESSION_CLASS->observeMiniplayerTouch(Landroid/view/MotionEvent;)V",
        "$EXTENSION_SESSION_CLASS->onMiniplayerTouchDismissed()V",
    )
    installPlaybackSessionPlaylistReader()
    installPlaybackSessionCaptionHooks()

    videoTimeHook(EXTENSION_SESSION_CLASS, "videoTimeChanged")
    playerStatusHook(EXTENSION_SESSION_CLASS, "onPlayerStatus")
    setExtensionIsPatchIncluded(EXTENSION_SESSION_CLASS)
}

/**
 * Restores the captions state of the saved session before the native default track selection,
 * unless the caption settings hide the captions.
 */
private fun installPlaybackSessionCaptionHooks() {
    val trackType = DefaultCaptionTrackFingerprint.originalMethod.returnType
    val selectedTrack = SubtitleManagerSelectedTrackFingerprint(trackType).instructionMatches.last().fieldReference

    SubtitleManagerSelectionVideoIdFingerprint.let {
        it.classDef.apply {
            addPatchInterface(
                EXTENSION_CAPTION_SOURCE_INTERFACE,
                "patch_sessionCaptionVideoId",
                it.instructionMatches[1].getMethodCalled()
            )
            addPatchInterface(
                EXTENSION_CAPTION_SOURCE_INTERFACE,
                "patch_sessionCaptionsEnabled",
                """
                    iget-object v0, p0, $selectedTrack
                    if-eqz v0, :disabled
                    invoke-virtual { v0 }, ${CaptionTrackIsDisableOptionFingerprint.originalMethod}
                    move-result v0
                    xor-int/lit8 v0, v0, 0x1
                    return v0
                    :disabled
                    const/4 v0, 0x0
                    return v0
                """
            )
        }

        it.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_SESSION_CLASS->attachCaptions($EXTENSION_CAPTION_SOURCE_INTERFACE)V"
        )
    }

    // Both gates are in the same method, so hook the later one first.
    listOf(SubtitleManagerDefaultTrackGateFingerprint, SubtitleManagerManifestTrackGateFingerprint)
        .sortedByDescending { it.instructionMatches[2].index }
        .forEach {
            it.method.apply {
                val index = it.instructionMatches[2].index
                val enabledRegister = getInstruction<OneRegisterInstruction>(index).registerA
                // p0 is the subtitle manager. A non-range invoke only takes registers up to v15.
                check(enabledRegister < 16 && p0Register < 16) {
                    "Caption gate registers do not fit a non-range invoke"
                }

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$enabledRegister, p0 }, $EXTENSION_SESSION_CLASS->restoreCaptionPreference(Z$EXTENSION_CAPTION_SOURCE_INTERFACE)Z
                        move-result v$enabledRegister
                    """
                )
            }
        }
}
