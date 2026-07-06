/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Crossfade discovery uses two tiers of fingerprints:
 *
 * 1. **Anchor fingerprints** — unique log/error strings that resolve stable classes and hook sites.
 * 2. **Execute-time fingerprints** — inline [Fingerprint] instances in `crossfadePatch` for types
 *    only known after anchors resolve.
 *
 * Three method discoveries (`getPlaybackState`, `getDuration`, `getCurrentPosition`) use manual
 * hierarchy-walking instead of `Fingerprint(definingClass=...)` because the methods may be
 * defined on a superclass of the ExoPlayer impl, not on the impl itself.
 */

/** Medialib outer player (atad): `stopVideo`. */
internal object StopVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("stopVideo", "MedialibPlayer.stopVideo"),
)

/** Inner coordinator (athu): `playNextInQueue` / gapless. */
internal object PlayNextInQueueFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
    ),
    strings = listOf("gapless.seek.next", "playNextInQueue."),
)

/** Audio/video toggle button class (nba). */
internal object AudioVideoToggleFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to update user last selected audio"),
)

internal object PauseVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("pauseVideo", "MedialibPlayer.pauseVideo()"),
)

internal object PlayVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("playVideo", "MedialibPlayer.playVideo()"),
)

/**
 * ExoPlayer concrete implementation (cpp) — unique "ExoPlayerImpl" log tag.
 * Must also check that the class implements ExoPlayer, because a synthetic Runnable
 * (coz) also references "ExoPlayerImpl" as a log tag.
 */
internal object ExoPlayerImplFingerprint : Fingerprint(
    strings = listOf("ExoPlayerImpl"),
    custom = { _, classDef ->
        classDef.interfaces.any { it == "Landroidx/media3/exoplayer/ExoPlayer;" }
    },
)

/** MedialibPlayer loadVideo method (atzq.o). Scoped to StopVideoFingerprint class. */
internal object LoadVideoFingerprint : Fingerprint(
    classFingerprint = StopVideoFingerprint,
    returnType = "V",
    strings = listOf("MedialibPlayer.loadVideo("),
)

/**
 * REPEAT_SINGLE detection: MediaSessionLoopStateAdapter (kyb.a(Ljava/lang/Object;)V).
 * This adapter converts YTM's loop-state enum (Lnwu: LOOP_OFF=0, LOOP_ALL=1,
 * LOOP_ONE=2, LOOP_DISABLED=3) into the Android MediaSession repeat int and pushes
 * it to the session — so it fires on every loop-state change (and init).  Hooking
 * its entry lets the crossfade manager track the live repeat mode (the native LOCAL
 * repeat state isn't reachable from the player classes the patch holds).  Anchored
 * by the globally-unique log string in this method.
 */
internal object LoopStateAdapterFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("attempted to update repeat mode but media session was null"),
)

/**
 * #1671: the DismissWatchEvent handler (iqr.handleDismissWatchEvent) — the single
 * point that processes a watch-page / queue dismissal, regardless of source (stock
 * "Dismiss queue" menu, swipe-to-dismiss miniplayer).  A normal skip never posts a
 * DismissWatchEvent, so this is dismiss-UNIQUE (unlike clearQueue, which also runs
 * in the normal skip's queue-advance chain).  The handler is anchored by its call
 * to the UNobfuscated WatchWhileLayout class plus the preserved handler name.
 *
 * If this ever fails to match on a future build, crossfade still works — the dismiss
 * just falls back to the slower poll-STATE_IDLE recovery in pollForNewTrackReady.
 */
internal object HandleDismissWatchEventFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lcom/google/android/apps/youtube/music/watchpage/ui/WatchWhileLayout;",
        ),
    ),
    custom = { method, _ ->
        method.name == "handleDismissWatchEvent" && method.parameterTypes.size == 1
    },
)

internal fun factoryMethodFingerprint(factoryClassType: String, exoPlayerType: String) = Fingerprint(
    definingClass = factoryClassType,
    returnType = exoPlayerType,
    custom = { method, _ ->
        method.parameterTypes.size == 3 &&
                method.parameterTypes[2].toString() == "I"
    }
)

internal fun sharedStateClassFingerprint(sharedStateFieldType: String) = Fingerprint(
    custom = { _, classDef ->
        !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)
                && sharedStateFieldType in classDef.interfaces
    }
)

internal fun sharedCallbackClassFingerprint(sharedCallbackFieldType: String) = Fingerprint(
    custom = { _, classDef ->
        !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)
                && (sharedCallbackFieldType in classDef.interfaces
                || classDef.superclass == sharedCallbackFieldType)
    }
)

internal fun videoSurfaceClassFingerprint(
    coordinatorFieldTypes: List<String>,
    knownFieldTypes: Set<String>,
    sharedStateFieldType: String,
    sharedCallbackFieldType: String,
    exoPlayerType: String) = Fingerprint(
    custom = { _, classDef ->
        !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                && classDef.fields.any { it.type == exoPlayerType }
                && coordinatorFieldTypes.any { it == classDef.type }
                && classDef.type !in knownFieldTypes
                && classDef.type != sharedStateFieldType
                && classDef.type != sharedCallbackFieldType
    }
)

internal fun setVolumeNameFingerprint(playerInterfaceType: String) = Fingerprint(
    definingClass = playerInterfaceType,
    returnType = "V",
    parameters = listOf("F"),
)

internal fun setPlayWhenReadyNameFingerprint(playerInterfaceType: String) = Fingerprint(
    definingClass = playerInterfaceType,
    returnType = "V",
    parameters = listOf("Z"),
)

internal fun releaseNameFingerprint(exoPlayerType: String) = Fingerprint(
    definingClass = exoPlayerType,
    returnType = "V",
    parameters = emptyList(),
    custom = { method, _ ->
        !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
    }
)

internal fun playbackInfoClassFingerprint(exoImplFieldTypes: List<String>) = Fingerprint(
    custom = { _, classDef ->
        classDef.interfaces.isEmpty()
                && classDef.fields.count { it.type == "I" } >= 3
                && classDef.fields.count { it.type == "J" } >= 1
                && exoImplFieldTypes.any { it == classDef.type }
    }
)

internal fun getPlaybackStateNameFingerprint(
    exoPlayerImplClassType: String,
    playbackInfoClassType: String,
    playbackStateFieldName: String,
    isInHierarchy: (String, String) -> Boolean) = Fingerprint(
    returnType = "I",
    parameters = emptyList(),
    custom = { method, classDef ->
        isInHierarchy(classDef.type, exoPlayerImplClassType)
                && method.implementation?.instructions?.let { instructions ->
            instructions.any { insn ->
                insn is ReferenceInstruction
                        && insn.opcode == Opcode.IGET_OBJECT
                        && (insn.reference as? FieldReference)?.type == playbackInfoClassType
            } && instructions.any { insn ->
                insn is ReferenceInstruction
                        && insn.opcode == Opcode.IGET
                        && (insn.reference as? FieldReference)?.name == playbackStateFieldName
            }
        } ?: false
    }
)

internal fun getDurationNameFingerprint(
    exoPlayerImplClassType: String,
    isInHierarchy: (String, String) -> Boolean) = Fingerprint(
    returnType = "J",
    parameters = emptyList(),
    filters = listOf(
        literal(-9223372036854775807L)
    ),
    custom = { _, classDef ->
        isInHierarchy(classDef.type, exoPlayerImplClassType)
    }
)

internal fun getCurrentPositionNameFingerprint(
    exoPlayerImplClassType: String,
    playbackInfoClassType: String,
    getDurationMethodName: String,
    isInHierarchy: (String, String) -> Boolean) = Fingerprint(
    returnType = "J",
    parameters = emptyList(),
    custom = { method, classDef ->
        isInHierarchy(classDef.type, exoPlayerImplClassType)
                && method.name != getDurationMethodName
                && method.implementation?.instructions?.any { insn ->
            insn is ReferenceInstruction
                    && (insn.opcode == Opcode.INVOKE_DIRECT || insn.opcode == Opcode.INVOKE_VIRTUAL)
                    && insn.reference.toString().let { ref ->
                ref.contains("($playbackInfoClassType)") && ref.endsWith("J")
            }
        } ?: false
    }
)

internal fun listenerWrapperClassFingerprint(exoPlayerImplClassFieldTypes: List<String>) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    custom = { _, classDef ->
        !classDef.type.contains("ExoPlayer")
                && classDef.fields.any { it.type == "Ljava/util/concurrent/CopyOnWriteArraySet;" }
                && exoPlayerImplClassFieldTypes.any { it == classDef.type }
    }
)

internal fun suppressCwhUFingerprint(cwhLctrType: String, cgdType: String) = Fingerprint(
    name = "U",
    returnType = "V",
    parameters = emptyList(),
    custom = { _, classDef ->
        cwhLctrType in classDef.interfaces && classDef.fields.any { f ->
            f.type == cgdType && !AccessFlags.STATIC.isSet(f.accessFlags)
        }
    }
)

internal fun getStateMethodFingerprint(stateProviderClassType: String) = Fingerprint(
    definingClass = stateProviderClassType,
    parameters = listOf(),
    custom = { method, _ ->
        !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                method.returnType != "Ljava/lang/Object;"
    }
)

internal fun isAudioModeMethodFingerprint(stateProviderClassType: String, stateType: String) = Fingerprint(
    definingClass = stateProviderClassType,
    returnType = "Z",
    parameters = listOf(stateType),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags)
    }
)

internal fun setStateMethodFingerprint(stateProviderClassType: String, stateType: String) = Fingerprint(
    definingClass = stateProviderClassType,
    returnType = "V",
    parameters = listOf(stateType),
    filters = listOf(
        opcode((Opcode.IGET_OBJECT))
    ),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
    }
)

internal fun broadcastMethodFingerprint(chxpType: String, broadcastMethodName: String) = Fingerprint(
    definingClass = chxpType,
    name = broadcastMethodName,
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE),
            returnType = "V",
            parameters = listOf("Ljava/lang/Object;"),
        )
    )
)
