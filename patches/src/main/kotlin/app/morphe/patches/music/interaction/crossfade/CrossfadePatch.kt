/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_00_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/CrossfadeManager;"

private const val COORDINATOR_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$PlayerCoordinatorAccess;"
private const val EXO_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$ExoPlayerAccess;"
private const val SESSION_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$SessionAccess;"
private const val FACTORY_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$PlayerFactoryAccess;"
private const val SHARED_STATE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$SharedStateAccess;"
private const val SHARED_CALLBACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$SharedCallbackAccess;"
private const val VIDEO_SURFACE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$VideoSurfaceAccess;"
private const val MEDIALIB_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$MedialibPlayerAccess;"
private const val VIDEO_TOGGLE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$VideoToggleAccess;"
private const val DELEGATE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$DelegateAccess;"
private const val LISTENER_WRAPPER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadeManager$ListenerWrapperAccess;"

private const val EXO_PLAYER_TYPE = "Landroidx/media3/exoplayer/ExoPlayer;"

/**
 * Adds a bridge method returning an object field to the target class.
 * Handles both static and instance fields.
 */
private fun MutableClass.addFieldGetter(
    methodName: String,
    fieldRef: Any,
) {
    val isStatic = (fieldRef as? Field)?.let {
        AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: false

    methods.add(
        ImmutableMethod(
            type,
            methodName,
            listOf(),
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(2)
        ).toMutable().apply {
            addInstructions(
                0,
                if (isStatic) {
                    """
                        sget-object v0, $fieldRef
                        return-object v0
                    """
                } else {
                    """
                        iget-object v0, p0, $fieldRef
                        return-object v0
                    """
                }
            )
        }
    )
}

/**
 * Adds a bridge method setting an object field on the target class.
 * Handles both static and instance fields.
 */
private fun MutableClass.addFieldSetter(
    methodName: String,
    fieldRef: Any,
) {
    val fieldType = (fieldRef as FieldReference).type
    val isStatic = (fieldRef as? Field)?.let {
        AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: false
    methods.add(
        ImmutableMethod(
            type, methodName,
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                if (isStatic) {
                    """
                        check-cast p1, $fieldType
                        sput-object p1, $fieldRef
                        return-void
                    """
                } else {
                    """
                        check-cast p1, $fieldType
                        iput-object p1, p0, $fieldRef
                        return-void
                    """
                }
            )
        }
    )
}


/**
 * Ships the crossfade About-screen header graphic: the banner WebP drawable and the
 * full-width ImageView layout it's shown through.  Kept as a resource patch because
 * copyResources needs the resource-patch context; crossfadePatch depends on it.
 */
private val crossfadeBannerResourcePatch = resourcePatch {
    execute {
        copyResources(
            "crossfade",
            ResourceGroup("drawable-nodpi", "morphe_crossfade_about_banner.webp"),
            ResourceGroup("layout", "morphe_crossfade_about_banner.xml"),
        )
    }
}

@Suppress("unused")
val crossfadePatch = bytecodePatch(
    name = "Track crossfade",
    description = "Adds a true dual-player crossfade between consecutive tracks. " +
        "Requires YouTube Music 9.00 or newer; on older versions the patch is a no-op.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        crossfadeBannerResourcePatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val log = Logger.getLogger(this::class.java.name)
        if (!is_9_00_or_greater) {
            return@execute log.warning(
                "Track crossfade requires YouTube Music 9.00 or newer. " +
                    "The 8.x ExoPlayer listener architecture is incompatible " +
                    "with this patch.",
            )
        }

        fun allMethodsInHierarchy(
            startType: String,
        ): List<Method> {
            val result = mutableListOf<Method>()
            var current: String? = startType
            while (current != null && current != "Ljava/lang/Object;") {
                val classDef = try { classDefBy(current) } catch (_: Exception) { break }
                result.addAll(classDef.methods)
                current = classDef.superclass
            }
            return result
        }

        fun allFieldsInHierarchy(
            startType: String,
        ): List<Field> {
            val result = mutableListOf<Field>()
            var current: String? = startType
            while (current != null && current != "Ljava/lang/Object;") {
                val classDef = try { classDefBy(current) } catch (_: Exception) { break }
                result.addAll(classDef.fields)
                current = classDef.superclass
            }
            return result
        }

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_crossfade_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_crossfade_enabled"),
                    ListPreference("morphe_music_crossfade_curve"),
                    NonInteractivePreference(
                        key = "morphe_music_crossfade_curve_preview",
                        summaryKey = null,
                        tag = "app.morphe.extension.music.settings.preference.CrossfadeCurvePreference",
                    ),
                    ListPreference("morphe_music_crossfade_duration"),
                    SwitchPreference("morphe_music_crossfade_on_skip", summary = true),
                    SwitchPreference("morphe_music_crossfade_on_auto_advance", summary = true),
                    SwitchPreference("morphe_music_crossfade_session_control", summary = true),
                    // About: tappable sub-screen explaining how crossfade works, when it
                    // works best, its quirks, and where it isn't supported at all.
                    PreferenceScreenPreference(
                        key = "morphe_music_crossfade_about",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            // Header banner (full-width crossfade graphic) — custom
                            // ImageView layout, no title/summary.
                            NonInteractivePreference(
                                key = "morphe_music_crossfade_about_banner",
                                titleKey = "morphe_music_crossfade_about_banner_title",
                                summaryKey = null,
                                layout = "@layout/morphe_crossfade_about_banner",
                            ),
                            NonInteractivePreference("morphe_music_crossfade_about_how"),
                            NonInteractivePreference("morphe_music_crossfade_about_best"),
                            NonInteractivePreference("morphe_music_crossfade_about_quirks"),
                            NonInteractivePreference("morphe_music_crossfade_about_known"),
                            NonInteractivePreference("morphe_music_crossfade_about_unsupported"),
                            NonInteractivePreference("morphe_music_crossfade_about_credit"),
                        )
                    )
                )
            )
        )

        StopVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->onBeforeStopVideo(Ljava/lang/Object;I)Z
                move-result v0
                if-eqz v0, :allow_stop
                return-void
                :allow_stop
                nop
            """
        )

        // #1671: notify the crossfade manager the instant a watch-page / queue
        // dismissal is processed (DismissWatchEvent handler).  This is dismiss-UNIQUE
        // — the stock "Dismiss queue" menu and swipe-to-dismiss both post a
        // DismissWatchEvent, while a normal skip never does.  It fires before the
        // dismiss's stopVideo(5), so onQueueDismissed() arms a window that makes
        // onBeforeStopVideo pass that stop through instead of starting a phantom
        // crossfade.  (If this fingerprint ever misses, the poll-STATE_IDLE recovery
        // still cleans up — just a touch slower.)
        runCatching {
            HandleDismissWatchEventFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->onQueueDismissed()V"
            )
        }.onFailure {
            log.warning(
                "DismissWatchEvent handler not found — dismiss handling falls back to " +
                    "poll-STATE_IDLE recovery (#1671): ${it.message}",
            )
        }

        PlayNextInQueueFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->onBeforePlayNext(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :allow_next
                return-void
                :allow_next
                nop
            """
        )

        AudioVideoToggleFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->shouldBlockVideoToggle(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :allow_toggle
                return-void
                :allow_toggle
                nop
            """
        )

        PauseVideoFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->onPauseVideo()V"
        )

        PlayVideoFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onPlayVideo(Ljava/lang/Object;)V"
        )

        // On 9.20.52, atzq.loadVideo has enough locals that `p0` resolves past v15,
        // exceeding invoke-static's 4-bit register limit. Use invoke-static/range
        // which supports 16-bit registers so the injection holds on any version.
        // p0 = atzq (MedialibPlayer), p1 = aues (PlaybackStartDescriptor): pass both
        // so the manager can cache the current track's descriptor for REPEAT_SINGLE
        // crossfade-onto-self (re-issued via patch_loadVideo).
        LoadVideoFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->" +
                    "onBeforeLoadVideo(Ljava/lang/Object;Ljava/lang/Object;)V"
        )

        // REPEAT_SINGLE detection: capture the live loop-state from the MediaSession
        // loop adapter so the auto-advance monitor knows when to crossfade the song
        // onto itself instead of advancing the queue.  Graceful: if the adapter isn't
        // found, repeat-single simply isn't detected (crossfade behaves as before).
        runCatching {
            LoopStateAdapterFingerprint.method.addInstruction(
                0,
                " invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->" +
                        "onLoopStateChanged(Ljava/lang/Object;)V"
            )
        }.onFailure {
            log.warning("Loop-state adapter not found — REPEAT_SINGLE " +
                    "crossfade disabled (#repeat): ${it.message}")
        }

        val musicActivityClass = MusicActivityOnCreateFingerprint.classDef
        musicActivityClass.methods.first { it.name == "onStop" && it.parameterTypes.isEmpty() }
            .addInstruction(
                0,
                "invoke-static {}, $EXTENSION_CLASS->onActivityStop()V"
            )
        musicActivityClass.methods.first { it.name == "onStart" && it.parameterTypes.isEmpty() }
            .addInstruction(
                0,
                "invoke-static {}, $EXTENSION_CLASS->onActivityStart()V"
            )
        // Hook onDestroy so we can release in-flight crossfade state when the
        // user swipe-clears from recents (process may survive via foreground
        // service; without cleanup our statics inherit orphaned player refs
        // into the next activity instance).
        musicActivityClass.methods.first { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
            .addInstruction(
                0,
                "invoke-static {}, $EXTENSION_CLASS->onActivityDestroy()V"
            )

        val coordinatorClass = PlayNextInQueueFingerprint.classDef
        val coordinatorType = coordinatorClass.type
        val medialibPlayerClass = StopVideoFingerprint.classDef
        val videoToggleClass = AudioVideoToggleFingerprint.classDef

        // --- ExoPlayer / Player interface hierarchy ---
        val playerInterfaceType = classDefBy(EXO_PLAYER_TYPE).interfaces.first()

        // --- Coordinator fields ---

        val exoPlayerField = coordinatorClass.fields.singleOrNull {
            it.type == EXO_PLAYER_TYPE
        } ?: error("ExoPlayer field of type $EXO_PLAYER_TYPE not found on ${coordinatorClass.type}")

        val playNextMethod = PlayNextInQueueFingerprint.method
        val sessionFieldRef = playNextMethod.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>()
            .first { it.opcode == Opcode.IGET_OBJECT }
            .getReference<FieldReference>()!!
        val sessionClass = mutableClassDefBy(sessionFieldRef.type)

        val factoryFieldRef = sessionClass.fields.singleOrNull { field ->
            try {
                mutableClassDefBy(field.type).methods.any { method ->
                    method.returnType == EXO_PLAYER_TYPE && method.parameterTypes.size == 3
                }
            } catch (_: Exception) {
                false
            }
        } ?: error(
            "ExoPlayer factory field not found on ${sessionClass.type} - " +
                "no field whose type declares a ($EXO_PLAYER_TYPE, 3-param) factory method",
        )
        val factoryClass = mutableClassDefBy(factoryFieldRef.type)

        val factoryMethod = Fingerprint(
            definingClass = factoryClass.type,
            returnType = EXO_PLAYER_TYPE,
            custom = { method, _ ->
                method.parameterTypes.size == 3 &&
                    method.parameterTypes[2].toString() == "I"
            }
        ).method

        // ExoPlayer concrete impl - fingerprinted via the unique "ExoPlayerImpl" log tag.
        val exoPlayerImplClass = ExoPlayerImplFingerprint.classDef
        val exoImplMethods = allMethodsInHierarchy(exoPlayerImplClass.type)

        // Helper: checks if `type` appears anywhere in the superclass chain starting at `startType`.
        fun isInHierarchyOf(type: String, startType: String): Boolean {
            var current: String? = startType
            while (current != null && current != "Ljava/lang/Object;") {
                if (current == type) return true
                current = try { classDefBy(current).superclass } catch (_: Exception) { null }
            }
            return false
        }


        val loadControlType = factoryMethod.parameterTypes[1].toString()
        val loadControlField = coordinatorClass.fields.singleOrNull {
            it.type == loadControlType
        } ?: coordinatorClass.fields.firstOrNull { field ->
            field.type.startsWith("L") && try {
                loadControlType in classDefBy(field.type).interfaces
            } catch (_: Exception) { false }
        } ?: error("LoadControl field (type $loadControlType or implementor) not found on ${coordinatorClass.type}")

        // Shared state / shared callback - find from factory method body.
        val factoryBodyCoordinatorFields = factoryMethod.implementation!!.instructions
            .asSequence()
            .filterIsInstance<ReferenceInstruction>()
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .map { it.reference }
            .filterIsInstance<FieldReference>()
            .filter { it.definingClass == coordinatorType }
            .toList()

        val knownFieldTypes = setOf(
            sessionFieldRef.type, exoPlayerField.type, loadControlField.type,
        )

        val sharedStateFieldRef = factoryBodyCoordinatorFields.first {
            it.type !in knownFieldTypes
        }
        val sharedStateInterfaceClass = classDefBy(sharedStateFieldRef.type)

        // If the coordinator declares its shared-state field as an interface,
        // resolve to the concrete implementation via Fingerprint.
        val sharedStateClass = if (AccessFlags.INTERFACE.isSet(sharedStateInterfaceClass.accessFlags)) {
            Fingerprint(
                custom = { _, classDef ->
                    !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                        && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)
                        && sharedStateFieldRef.type in classDef.interfaces
                }
            ).classDef
        } else {
            mutableClassDefBy(sharedStateFieldRef.type)
        }

        val sharedCallbackFieldRef = factoryBodyCoordinatorFields.first {
            it.type !in knownFieldTypes && it.type != sharedStateFieldRef.type
        }
        val sharedCallbackInterfaceClass = classDefBy(sharedCallbackFieldRef.type)

        // Same for shared callback - resolve abstract/interface to concrete.
        val sharedCallbackClass = if (
            AccessFlags.INTERFACE.isSet(sharedCallbackInterfaceClass.accessFlags)
            || AccessFlags.ABSTRACT.isSet(sharedCallbackInterfaceClass.accessFlags)
        ) {
            Fingerprint(
                custom = { _, classDef ->
                    !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                        && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)
                        && (sharedCallbackFieldRef.type in classDef.interfaces
                            || classDef.superclass == sharedCallbackFieldRef.type)
                }
            ).classDef
        } else {
            mutableClassDefBy(sharedCallbackFieldRef.type)
        }

        // 9.x single-attachment guard field — present only on 9.x.
        // The ExoPlayer constructor checks this field (on an abstract superclass of
        // sharedState) and refuses to attach if already set. We clear it before
        // calling the factory so the new player can attach to the coordinator.
        var guardField: Field? = null
        var guardAbstractType: String? = null
        if (is_9_00_or_greater) {
            var current: String? = sharedStateClass.superclass
            while (current != null && current != "Ljava/lang/Object;") {
                val cls = try { classDefBy(current) } catch (_: Exception) { null } ?: break
                if (AccessFlags.ABSTRACT.isSet(cls.accessFlags)) {
                    val instanceField = cls.fields.firstOrNull {
                        !AccessFlags.STATIC.isSet(it.accessFlags)
                    }
                    if (instanceField != null) {
                        guardField = instanceField
                        guardAbstractType = current
                        break
                    }
                }
                current = cls.superclass
            }
            if (guardField == null) {
                log.warning(
                    "9.x guard field not found in ${sharedStateClass.type} superclass chain — " +
                        "second player creation may fail. Fields searched from superclass of ${sharedStateClass.type}",
                )
            }
        }

        // Video surface - resolved by finding a class that holds an ExoPlayer field
        // and is itself a field on the coordinator (not one of the already-known types).
        val videoSurfaceClass = Fingerprint(
            custom = { _, classDef ->
                !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                    && classDef.fields.any { it.type == EXO_PLAYER_TYPE }
                    && coordinatorClass.fields.any { it.type == classDef.type }
                    && classDef.type !in knownFieldTypes
                    && classDef.type != sharedStateFieldRef.type
                    && classDef.type != sharedCallbackFieldRef.type
            }
        ).classDef
        val videoSurfaceField = coordinatorClass.fields.first { it.type == videoSurfaceClass.type }
        val videoSurfaceExoField = videoSurfaceClass.fields.first {
            it.type == EXO_PLAYER_TYPE
        }

        // 9.x only: ForwardingPlayer (cwh) field on coordinator (auge.c:Lctr).
        // cwh wraps the ExoPlayer via cwh.g:Lcct (Player interface delegate field).
        // MediaSession queries player state through coordinator → cwh → cwh.g.isPlaying().
        // When patch_setPlayerWithBindings only writes auge.h (coordinator's ExoPlayer field),
        // cwh.g still points to the old released player → isPlaying()=false → MediaSession PAUSED
        // on skip 2+ (first skip works because the old player is still alive during its fade-out).
        // 9.x event dispatch fix: crh.j (ExoPlayer field) + auih.k (coordinator listener).
        // Factory ExoPlayer has crh.j = factory_cwh (final, set in constructor, never changes).
        // crb (ExoPlayer's ComponentListener) reads crh.j to dispatch playback events to cwh.
        // coordinator_cwh (auih.c) has auih.k registered on its listener set.
        // After patch_setPlayerWithBindings swaps auih.h to factory_exo, events go to
        // factory_cwh — NOT coordinator_cwh — so auih.k never gets notified and
        // MediaSession stays permanently PAUSED.
        // Fix: read factory_cwh from factory_exo.j, then register auih.k on factory_cwh.
        val forwardingPlayerField9x: Field?
        var exoPlayerCwhField9x: Field? = null
        var cwhListenerType: String? = null
        var coordinatorCwhListenerField9x: Field? = null
        // crh.h:Lcgd — per-player event dispatch set; coordinator_cwh is registered here via crh.C().
        // Removing coordinator_cwh from the outgoing player's crh.h before release prevents the
        // release's isPlayingChanged(false) from propagating to MediaSession via cwh.b.
        var eventDispatchField9x: Field? = null
        if (is_9_00_or_greater) {
            // Find coordinator's cwh field (auih.c:Lctr) to identify the Lctr interface type.
            val playerDelegateTypes = setOf(playerInterfaceType, EXO_PLAYER_TYPE)
            val looperType = "Landroid/os/Looper;"
            forwardingPlayerField9x = allFieldsInHierarchy(coordinatorType).firstOrNull { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags)
                    && field.type.startsWith("L")
                    && field.type != coordinatorType
                    && try {
                        classDefBy(field.type).methods.any { method ->
                            !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
                                && method.returnType == "V"
                                && method.parameterTypes.size == 2
                                && method.parameterTypes[1].toString() == looperType
                                && method.parameterTypes[0].toString() in playerDelegateTypes
                        }
                    } catch (_: Exception) { false }
            }.also { f ->
                if (f == null) log.warning(
                    "9.x: Lctr (cwh interface) field not found on coordinator — crh.j fix skipped"
                ) else log.fine { "9.x: coordinator cwh field (auih.c) = $f" }
            }

            val lctrType = forwardingPlayerField9x?.type
            if (lctrType != null) {
                // crh.j: Lctr-typed field on ExoPlayer — crb reads this to dispatch events.
                exoPlayerCwhField9x = exoPlayerImplClass.fields.firstOrNull { f ->
                    !AccessFlags.STATIC.isSet(f.accessFlags) && f.type == lctrType
                }.also { f ->
                    if (f == null) log.warning("9.x: crh.j (Lctr on ExoPlayer) not found — crh.j fix skipped")
                    else log.fine { "9.x: ExoPlayer cwh field (crh.j) = $f" }
                }

                // Lctu: listener interface — parameter of Lctr.B(Lctu)V (addListener).
                cwhListenerType = try {
                    classDefBy(lctrType).methods
                        .firstOrNull { m -> m.name == "B" && m.parameterTypes.size == 1 && m.returnType == "V" }
                        ?.parameterTypes?.first()?.toString()
                } catch (_: Exception) { null }
                log.fine { "9.x: cwh listener interface (Lctu) = $cwhListenerType" }

                // auih.k: coordinator field of type implementing Lctu (connects cwh to system).
                if (cwhListenerType != null) {
                    coordinatorCwhListenerField9x = coordinatorClass.fields.firstOrNull { f ->
                        !AccessFlags.STATIC.isSet(f.accessFlags)
                            && f.type != exoPlayerField.type
                            && f.type != lctrType
                            && try { cwhListenerType in classDefBy(f.type).interfaces } catch (_: Exception) { false }
                    }.also { f ->
                        if (f == null) log.warning("9.x: coordinator cwh listener field (auih.k) not found — crh.j fix skipped")
                        else log.fine { "9.x: coordinator cwh listener field (auih.k) = $f" }
                    }
                }

                // crh.h: the per-player Lcgd event dispatch set.
                // coordinator_cwh is registered here; we need to remove it before releasing the
                // outgoing player so its release-time isPlayingChanged(false) doesn't reach
                // MediaSession via cwh.b.
                //
                // Identify Lcgd structurally (avoid relying on R8-obfuscated method names
                // like "a" / "e" which can drift across major YTM versions): the class wraps a
                // CopyOnWriteArraySet and has at least two methods of signature (Object):V —
                // one adds, the other removes.  This is the listener-set wrapper pattern.
                eventDispatchField9x = exoPlayerImplClass.fields.firstOrNull { f ->
                    !AccessFlags.STATIC.isSet(f.accessFlags)
                        && f.type != lctrType
                        && f.type != exoPlayerField.type
                        && f.type != EXO_PLAYER_TYPE
                        && try {
                            val cls = classDefBy(f.type)
                            val hasCopyOnWriteSet = cls.fields.any { it.type == "Ljava/util/concurrent/CopyOnWriteArraySet;" }
                            val objectVoidMethodCount = cls.methods.count { m ->
                                m.parameterTypes.size == 1
                                    && m.parameterTypes[0].toString() == "Ljava/lang/Object;"
                                    && m.returnType == "V"
                            }
                            hasCopyOnWriteSet && objectVoidMethodCount >= 2
                        } catch (_: Exception) { false }
                }.also { f ->
                    if (f == null) log.warning("9.x: crh.h (Lcgd event dispatch) not found — detach-cwh fix skipped")
                    else log.fine { "9.x: ExoPlayer event dispatch field (crh.h:Lcgd) = $f" }
                }
            }
        } else {
            forwardingPlayerField9x = null
        }

        // --- Discover ExoPlayer method names from the media3 interfaces ---

        val setVolumeName = Fingerprint(
            definingClass = playerInterfaceType,
            returnType = "V",
            parameters = listOf("F"),
        ).method.name

        val setPlayWhenReadyName = Fingerprint(
            definingClass = playerInterfaceType,
            returnType = "V",
            parameters = listOf("Z"),
        ).method.name

        val releaseName = Fingerprint(
            definingClass = EXO_PLAYER_TYPE,
            returnType = "V",
            parameters = emptyList(),
            custom = { method, _ ->
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
            }
        ).method.name

        // PlaybackInfo class (crf) - field on ExoPlayer impl hierarchy with >=3 int + >=1 long fields
        // and no interfaces (rules out the inner engine handler cqb which also matches field counts).
        val exoImplFields = allFieldsInHierarchy(exoPlayerImplClass.type)
        val playbackInfoClass = Fingerprint(
            custom = { _, classDef ->
                classDef.interfaces.isEmpty()
                    && classDef.fields.count { it.type == "I" } >= 3
                    && classDef.fields.count { it.type == "J" } >= 1
                    && exoImplFields.any { it.type == classDef.type }
            }
        ).classDef
        val playbackStateFieldName = playbackInfoClass.fields.first { it.type == "I" }.name

        // getPlaybackState - ()I on the ExoPlayer impl hierarchy that reads PlaybackInfo + first int field.
        // Uses Option A: no definingClass, custom checks hierarchy membership.
        val getPlaybackStateName = Fingerprint(
            returnType = "I",
            parameters = emptyList(),
            custom = { method, classDef ->
                isInHierarchyOf(classDef.type, exoPlayerImplClass.type)
                    && method.implementation?.instructions?.let { instructions ->
                        instructions.any { insn ->
                            insn is ReferenceInstruction
                                && insn.opcode == Opcode.IGET_OBJECT
                                && (insn.reference as? FieldReference)?.type == playbackInfoClass.type
                        } && instructions.any { insn ->
                            insn is ReferenceInstruction
                                && insn.opcode == Opcode.IGET
                                && (insn.reference as? FieldReference)?.name == playbackStateFieldName
                        }
                    } ?: false
            }
        ).method.name


        val getDurationName = Fingerprint(
            returnType = "J",
            parameters = emptyList(),
            filters = listOf(
                // getDuration - ()J containing the C.TIME_UNSET literal (-9223372036854775807L).
                literal(-9223372036854775807L)
            ),
            custom = { _, classDef ->
                isInHierarchyOf(classDef.type, exoPlayerImplClass.type)
            }
        ).method.name

        // getCurrentPosition - ()J that invokes a helper taking PlaybackInfo and returning long.
        val getCurrentPositionName = Fingerprint(
            returnType = "J",
            parameters = emptyList(),
            custom = { method, classDef ->
                isInHierarchyOf(classDef.type, exoPlayerImplClass.type)
                    && method.name != getDurationName
                    && method.implementation?.instructions?.any { insn ->
                        insn is ReferenceInstruction
                            && (insn.opcode == Opcode.INVOKE_DIRECT || insn.opcode == Opcode.INVOKE_VIRTUAL)
                            && insn.reference.toString().let { ref ->
                                ref.contains("(${playbackInfoClass.type})") && ref.endsWith("J")
                            }
                    } ?: false
            }
        ).method.name

        // Listener wrapper (cau) - has a CopyOnWriteArraySet field and is
        // referenced as a field on the ExoPlayer impl class.
        val listenerWrapperClass = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            custom = { _, classDef ->
                !classDef.type.contains("ExoPlayer")
                    && classDef.fields.any { it.type == "Ljava/util/concurrent/CopyOnWriteArraySet;" }
                    && exoPlayerImplClass.fields.any { it.type == classDef.type }
            }
        ).classDef
        val listenerSetInWrapper = listenerWrapperClass.fields.first {
            it.type == "Ljava/util/concurrent/CopyOnWriteArraySet;"
        }
        val listenerWrapperField = exoPlayerImplClass.fields.firstOrNull {
            it.type == listenerWrapperClass.type
        } ?: error("Listener wrapper field of type ${listenerWrapperClass.type} not found on ${exoPlayerImplClass.type}")

        // cqbField - the dlk-typed field on the shared callback hierarchy.
        // cqb implements dlk, and dll.h is declared as type dlk.  The cqb
        // constructor checks checkState(dll.h == null), so we must null this.
        // Find it by locating the field whose type is an interface that cqb
        // (the class thrown in the stack trace at cqb.<init>) implements.
        val allCallbackFields = allFieldsInHierarchy(sharedCallbackClass.type)
        val cqbField = allCallbackFields.firstOrNull { field ->
            if (!field.type.startsWith("L") || field.type == "Ljava/lang/Object;") return@firstOrNull false
            try {
                val fieldClass = classDefBy(field.type)
                AccessFlags.INTERFACE.isSet(fieldClass.accessFlags)
                    && fieldClass.methods.none { it.name == "<clinit>" }
            } catch (_: Exception) { false }
        } ?: error("cqbField (interface-typed field for dlk) not found in ${sharedCallbackClass.type} hierarchy. " +
            "Fields: ${allCallbackFields.map { "${it.definingClass}->${it.name}:${it.type}" }}")

        // dltField - the dlt-typed field on the shared callback hierarchy.
        // dll.i is declared as type dlt.  Find it as the non-cqb, non-List,
        // non-session L-typed field with an abstract class type.
        val dltCallbackTypeOnShared = allCallbackFields.firstOrNull { field ->
            field != cqbField
                && field.type.startsWith("L")
                && field.type != "Ljava/lang/Object;"
                && field.type != "Ljava/util/List;"
                && field.type != sessionFieldRef.type
                && try {
                    val cls = classDefBy(field.type)
                    AccessFlags.ABSTRACT.isSet(cls.accessFlags)
                        || AccessFlags.INTERFACE.isSet(cls.accessFlags)
                } catch (_: Exception) { false }
        } ?: error("dltCallbackType not found in ${sharedCallbackClass.type} hierarchy. " +
            "Fields: ${allCallbackFields.map { "${it.definingClass}->${it.name}:${it.type}" }}")

        val dltFieldOnExo = exoPlayerImplClass.fields.firstOrNull { it.type == dltCallbackTypeOnShared.type }
            ?: error("DLT field of type ${dltCallbackTypeOnShared.type} not found on ${exoPlayerImplClass.type}")

        // Internal listener field - the field immediately after the dlt field.
        val allExoFields = exoPlayerImplClass.fields.toList()
        val dltIdx = allExoFields.indexOf(dltFieldOnExo)
        val internalListenerField = allExoFields.getOrNull(dltIdx + 1)
            ?: error("Internal listener field (after DLT at index $dltIdx) not found on ${exoPlayerImplClass.type}")

        // Shared state bxk field - the playlist/timeline handle that cup.V()
        // assigns. Find it by locating the V(bxk, Looper) method: the non-Looper
        // parameter type is bxk, and the field of that type is what VazerOG
        // nulls as "crz.g" before calling the factory.
        // Search the concrete class, its superclass hierarchy, its interfaces,
        // and the field's declared type for the V(bxk, Looper) method.
        val sharedStateMethodPool = buildList {
            addAll(sharedStateClass.methods)
            addAll(allMethodsInHierarchy(sharedStateClass.type))
            if (sharedStateFieldRef.type != sharedStateClass.type) {
                try { addAll(classDefBy(sharedStateFieldRef.type).methods) } catch (_: Exception) {}
            }
            for (iface in sharedStateClass.interfaces) {
                try { addAll(classDefBy(iface).methods) } catch (_: Exception) {}
            }
            // Also check interfaces of superclasses
            var sup = sharedStateClass.superclass
            while (sup != null && sup != "Ljava/lang/Object;") {
                try {
                    val supClass = classDefBy(sup)
                    for (iface in supClass.interfaces) {
                        try { addAll(classDefBy(iface).methods) } catch (_: Exception) {}
                    }
                    sup = supClass.superclass
                } catch (_: Exception) { break }
            }
        }
        // Strategy 1: look for V(bxk, Looper) in the full method pool.
        var bxkType = sharedStateMethodPool.firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size != 2) return@firstNotNullOfOrNull null
            val types = method.parameterTypes.map { it.toString() }
            when {
                types[1] == "Landroid/os/Looper;" -> types[0]
                types[0] == "Landroid/os/Looper;" -> types[1]
                else -> null
            }
        }

        // Strategy 2: if V(bxk, Looper) not found, look for any method with
        // a single Looper parameter - the other fields accessed in its body
        // may reveal the bxk type.  Fallback: find the field on the shared
        // state class whose type is a concrete (non-interface, non-abstract)
        // class NOT in the set of known types and NOT a standard Java type.
        if (bxkType == null) {
            val standardTypes = setOf(
                "Ljava/lang/Object;", "Ljava/lang/String;",
                "Ljava/util/List;", "Ljava/util/Map;", "Ljava/util/Set;",
                "Ljava/util/ArrayList;", "Ljava/util/HashMap;",
                "Landroid/util/SparseArray;", "Landroid/os/Handler;",
                "Landroid/os/Looper;", "Ljava/util/concurrent/CopyOnWriteArraySet;",
            )
            val knownTypes = setOf(
                sessionFieldRef.type, loadControlType, sharedCallbackFieldRef.type,
            )
            val candidate = sharedStateClass.fields.firstOrNull { field ->
                field.type.startsWith("L")
                    && field.type !in standardTypes
                    && !AccessFlags.STATIC.isSet(field.accessFlags)
            }
            bxkType = candidate?.type
            if (bxkType != null) {
                log.fine { "bxk fallback: found via concrete-field heuristic: $bxkType" }
            }
        }

        if (bxkType == null) {
            error("bxk type not found on ${sharedStateClass.type} - " +
                "no V(X,Looper) method and no concrete-field fallback. " +
                "Fields: ${sharedStateClass.fields.map { "${it.name}:${it.type}" }}")
        }
        val timelineField = sharedStateClass.fields.firstOrNull { it.type == bxkType }
            ?: error("Timeline field of type $bxkType not found on ${sharedStateClass.type}")

        // MedialibPlayer fields - must be an instance field.
        val playerChainField = medialibPlayerClass.fields.first {
            !AccessFlags.STATIC.isSet(it.accessFlags)
                && it.type.startsWith("L") && it.type != "Ljava/lang/Object;"
        }

        val playNextInQueueMethod = medialibPlayerClass.methods.first { method ->
            method.returnType == "V" && method.parameterTypes.isEmpty()
                && method.implementation?.instructions?.any { insn ->
                    insn is ReferenceInstruction
                        && insn.opcode == Opcode.CONST_STRING
                        && insn.reference.toString().contains("playNextInQueue")
                } == true
        }

        // Delegate chain classes - every class that DIRECTLY implements the
        // playerChain interface AND directly holds a self-typed "next" field
        // (the decorator pattern).  The runtime delegate-walk in
        // getCoordinatorFromAtad hops chain.patch_getDelegate() until it reaches
        // a non-DelegateAccess (the coordinator), so EVERY decorator class in the
        // chain must carry DelegateAccess.
        //
        // 9.10-9.21: a single recursively-wrapped decorator (atux/auyx) - one match.
        // 9.23+: multiple distinct decorators (e.g. avel + avfa) - all must match,
        // else the walk dies on the first uncovered hop (#9.23 "Traversed 0 → avfa").
        // Subclasses that only INHERIT the interface+field (e.g. avfg extends avel)
        // are intentionally NOT matched here - they inherit DelegateAccess and the
        // delegate field from their injected base.
        val playerChainInterfaceType = playerChainField.type
        val delegateClasses = mutableListOf<Pair<MutableClass, Field>>()
        classDefForEach { classDef ->
            if (classDef.type != playerChainInterfaceType &&
                !AccessFlags.INTERFACE.isSet(classDef.accessFlags) &&
                playerChainInterfaceType in classDef.interfaces &&
                classDef.fields.any { it.type == playerChainInterfaceType }
            ) {
                val field = classDef.fields.first { it.type == playerChainInterfaceType }
                delegateClasses.add(mutableClassDefBy(classDef.type) to field)
            }
        }
        if (delegateClasses.isEmpty()) {
            error(
                "No delegate chain class implementing $playerChainInterfaceType " +
                    "with a self-typed field was found"
            )
        }

        // Listener element class (cat) - stored inside cau's CopyOnWriteArraySet.
        // Found by looking for NEW_INSTANCE instructions in cau's methods.
        //
        // Match the element class structurally (avoid R8 name "a"): any class allocated
        // via NEW_INSTANCE inside cau's methods that has an Object-typed field — that
        // field holds the actual listener reference.  Use the FIRST Object-typed field
        // (typically there's only one; if there are more, the first is the listener slot
        // because R8 puts the constructor-assigned field first).
        val cauClass = classDefBy(listenerWrapperField.type)
        val listenerElementType = cauClass.methods
            .filter { it.name != "<clinit>" }
            .flatMap { method ->
                method.implementation?.instructions
                    ?.filterIsInstance<ReferenceInstruction>()
                    ?.filter { it.opcode == Opcode.NEW_INSTANCE }
                    ?.map { it.reference.toString() }
                    ?: emptyList()
            }
            .distinct()
            .first { type ->
                try {
                    classDefBy(type).fields.any { it.type == "Ljava/lang/Object;" }
                } catch (_: Exception) { false }
            }
        val listenerElementClass = mutableClassDefBy(listenerElementType)
        val listenerElementField = listenerElementClass.fields.first {
            it.type == "Ljava/lang/Object;"
        }

        log.fine {
            """
                CrossfadePatch discovery:
                coordinator    = ${coordinatorClass.type}
                exoPlayerImpl  = ${exoPlayerImplClass.type}
                session        = ${sessionClass.type}
                factory        = ${factoryClass.type}
                sharedState    = ${sharedStateClass.type} (field type: ${sharedStateFieldRef.type})
                sharedCallback = ${sharedCallbackClass.type} (field type: ${sharedCallbackFieldRef.type})
                videoSurface   = ${videoSurfaceClass.type}
                medialibPlayer = ${medialibPlayerClass.type}
                videoToggle    = ${videoToggleClass.type}
                delegateChain  = ${delegateClasses.joinToString { "${it.first.type}(${it.second})" }}
                listenerElem   = ${listenerElementClass.type} (field: $listenerElementField)
                timelineField  = $timelineField (bxk type: $bxkType)
                cqbField       = $cqbField (definingClass: ${cqbField.definingClass})
                dltOnShared    = $dltCallbackTypeOnShared
                dltOnExo       = $dltFieldOnExo
                internalLsnr   = $internalListenerField
                listenerWrap   = $listenerWrapperField → $listenerSetInWrapper
                playerChain    = $playerChainField
                guardField     = ${guardField?.let { "$guardAbstractType->${it.name}:${it.type}" } ?: "n/a (8.x)"}
            """.trimIndent()
        }

        // --- PlayerCoordinatorAccess on athu ---
        coordinatorClass.interfaces.add(COORDINATOR_INTERFACE)
        coordinatorClass.addFieldGetter("patch_getExoPlayer", exoPlayerField)
        coordinatorClass.addFieldSetter("patch_setExoPlayer", exoPlayerField)
        coordinatorClass.addFieldGetter("patch_getSession", sessionFieldRef)
        coordinatorClass.addFieldGetter("patch_getLoadControl", loadControlField)
        coordinatorClass.addFieldGetter("patch_getSharedState", sharedStateFieldRef)
        coordinatorClass.addFieldGetter("patch_getSharedCallback", sharedCallbackFieldRef)

        coordinatorClass.addFieldGetter("patch_getVideoSurface", videoSurfaceField)

        // patch_playNextInQueueDirect: calls the coordinator's own playNextInQueue (auih.y()V)
        // directly via invoke-virtual. This is required because the auto-advance monitor and
        // onBeforePlayNext re-invoke cannot use atad.patch_playNextInQueue() (atzq.p()V) —
        // that method calls invoke-interface Lausd->y()V, but auih does NOT implement Lausd,
        // so the call never reaches the hooked auih.y()V and onBeforePlayNext never fires.
        val coordinatorPlayNextMethod = PlayNextInQueueFingerprint.method
        coordinatorClass.methods.add(
            ImmutableMethod(
                coordinatorType,
                "patch_playNextInQueueDirect",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(1)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0 }, $coordinatorPlayNextMethod
                        return-void
                    """
                )
            }
        )

        // --- Coordinator player-transition bridge (9.x UI binding fix) ---
        // Find the coordinator's internal method that properly handles player transitions.
        // Unlike a raw iput-object, this method: reads the old player from the field,
        // calls removeListener on it, writes the new player, calls addListener on the new
        // player — keeping the coordinator's MedialibPlayerEvents listener properly bound.
        // Identified by: (a) writes to exoPlayerField, (b) has virtual/interface calls.
        val exoFieldName = exoPlayerField.name
        // Compatible types: the exact ExoPlayer type, the Player interface, or Object.
        // Also accept any type whose class hierarchy includes EXO_PLAYER_TYPE.
        val exoCompatibleTypes = buildSet {
            add(EXO_PLAYER_TYPE)
            add(playerInterfaceType)
            add("Ljava/lang/Object;")
            // Include the exoPlayerField.type itself (should be EXO_PLAYER_TYPE, but be safe)
            add(exoPlayerField.type)
        }
        val coordinatorPlayerTransitionMethod = coordinatorClass.methods
            .filter { method ->
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
                    && !AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.parameterTypes.size == 1
                    && method.implementation != null
                    && method.parameterTypes.first().toString() in exoCompatibleTypes
            }
            .firstOrNull { method ->
                val insns = method.implementation!!.instructions
                // The iput-object must write to the exact same field (match name AND defining class)
                val hasExoFieldWrite = insns.any { insn ->
                    insn is ReferenceInstruction
                        && insn.opcode == Opcode.IPUT_OBJECT
                        && (insn.reference as? FieldReference)?.let { fr ->
                            fr.name == exoFieldName && fr.definingClass == coordinatorType
                        } == true
                }
                val virtualCallCount = insns.count { insn ->
                    insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_INTERFACE
                }
                hasExoFieldWrite && virtualCallCount >= 1
            }

        val transitionParamType = coordinatorPlayerTransitionMethod
            ?.parameterTypes?.first()?.toString()
            ?: exoPlayerField.type

        log.fine {
            if (coordinatorPlayerTransitionMethod != null)
                "Coordinator player-transition method found: $coordinatorPlayerTransitionMethod"
            else
                "Coordinator player-transition method NOT found — patch_setPlayerWithBindings uses raw iput-object fallback"
        }

        // patch_setPlayerWithBindings: calls the internal transition method (preferred)
        // or falls back to raw iput-object if the method was not found.
        coordinatorClass.methods.add(
            ImmutableMethod(
                coordinatorType,
                "patch_setPlayerWithBindings",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(4)
            ).toMutable().apply {
                addInstructions(
                    0,
                    if (coordinatorPlayerTransitionMethod != null) {
                        """
                            check-cast p1, $transitionParamType
                            invoke-virtual { p0, p1 }, $coordinatorPlayerTransitionMethod
                            return-void
                        """
                    } else if (forwardingPlayerField9x != null && exoPlayerCwhField9x != null && coordinatorCwhListenerField9x != null && cwhListenerType != null) {
                        // 9.x fix: register coordinator's cwh listener (auih.k) on factory_cwh.
                        // crb (ExoPlayer's ComponentListener) dispatches ALL playback events to
                        // crh.j — a public final field on ExoPlayer set once in the constructor.
                        // After swapping auih.h to factory_exo, events go to factory_cwh (crh.j),
                        // but auih.k was only registered on coordinator_cwh.b — so MediaSession
                        // never got PLAYING events.
                        // Fix: read factory_cwh from factory_exo.j, then call factory_cwh.B(auih.k).
                        // Cast to concrete crh type so verifier allows iget-object on crh.j.
                        // transitionParamType is the ExoPlayer interface — not enough for
                        // iget on a field declared on the concrete impl class Lcrh;.
                        val lctrType = forwardingPlayerField9x.type
                        val concreteExoType = exoPlayerImplClass.type
                        """
                            check-cast p1, $concreteExoType
                            iget-object v0, p1, $exoPlayerCwhField9x
                            iget-object v1, p0, $coordinatorCwhListenerField9x
                            invoke-interface { v0, v1 }, $lctrType->B($cwhListenerType)V
                            iput-object p1, p0, $exoPlayerField
                            return-void
                        """
                    } else {
                        """
                            check-cast p1, $transitionParamType
                            iput-object p1, p0, $exoPlayerField
                            return-void
                        """
                    }
                )
            }
        )

        // --- ExoPlayerAccess on cpp ---
        exoPlayerImplClass.interfaces.add(EXO_PLAYER_INTERFACE)

        fun MutableClass.addExoBridgeInt(bridgeName: String, targetName: String) {
            val target = exoImplMethods.firstOrNull {
                it.name == targetName && it.returnType == "I" && it.parameterTypes.isEmpty()
            } ?: error("Bridge target $targetName()I not found in ${exoPlayerImplClass.type} hierarchy")

            methods.add(
                ImmutableMethod(
                    type,
                    bridgeName,
                    listOf(),
                    "I",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, $target
                            move-result v0
                            return v0
                        """
                    )
                }
            )
        }

        fun MutableClass.addExoBridgeLong(bridgeName: String, targetName: String) {
            val target = exoImplMethods.firstOrNull {
                it.name == targetName && it.returnType == "J" && it.parameterTypes.isEmpty()
            } ?: error("Bridge target $targetName()J not found in ${exoPlayerImplClass.type} hierarchy")

            methods.add(
                ImmutableMethod(
                    type,
                    bridgeName,
                    listOf(),
                    "J",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(3)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, $target
                            move-result-wide v0
                            return-wide v0
                        """
                    )
                }
            )
        }

        fun MutableClass.addExoBridgeVoid(
            bridgeName: String,
            targetName: String,
            paramType: String? = null,
        ) {
            val target = exoImplMethods.firstOrNull {
                it.name == targetName && it.returnType == "V"
                    && if (paramType != null) it.parameterTypes.toList() == listOf(paramType)
                    else it.parameterTypes.isEmpty()
            } ?: error("Bridge target $targetName(${paramType ?: ""})V not found in ${exoPlayerImplClass.type} hierarchy")

            val params = if (paramType != null)
                listOf(ImmutableMethodParameter(paramType, null, null))
            else listOf()

            methods.add(
                ImmutableMethod(
                    type,
                    bridgeName,
                    params,
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2)
                ).toMutable().apply {
                    val invoke = if (paramType != null) {
                        "invoke-virtual { p0, p1 }, $target"
                    } else {
                        "invoke-virtual { p0 }, $target"
                    }
                    addInstructions(
                        0,
                        """
                            $invoke
                            return-void
                        """
                    )
                }
            )
        }

        exoPlayerImplClass.addExoBridgeInt("patch_getPlaybackState", getPlaybackStateName)
        exoPlayerImplClass.addExoBridgeLong("patch_getCurrentPosition", getCurrentPositionName)
        exoPlayerImplClass.addExoBridgeLong("patch_getDuration", getDurationName)
        exoPlayerImplClass.addExoBridgeVoid("patch_setVolume", setVolumeName, "F")
        exoPlayerImplClass.addExoBridgeVoid("patch_setPlayWhenReady", setPlayWhenReadyName, "Z")
        exoPlayerImplClass.addExoBridgeVoid("patch_release", releaseName)

        // patch_addListener — calls cau.add(rawListener) on this player's listener wrapper,
        // creating a fresh cat wrapper properly bound to the new player.
        //
        // The NEW_INSTANCE of cat lives inside cau.add() (the ListenerHolderSet.add method),
        // NOT inside ExoPlayerImpl.addListener() which merely delegates to cau.add().
        // We therefore search cauClass.methods for the add method, then emit smali that:
        //   1. iget-object the listenerWrapperField (cau instance) from this player
        //   2. invoke-virtual cau.add(p1) to register the listener (creates fresh cat)
        val cauAddMethod = cauClass.methods.firstOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags)
                && !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
                && method.parameterTypes.size == 1
                && method.implementation?.instructions?.any { insn ->
                    insn is ReferenceInstruction
                        && insn.opcode == Opcode.NEW_INSTANCE
                        && insn.reference.toString() == listenerElementType
                } == true
        }
        val cauAddParamType = cauAddMethod?.parameterTypes?.first()?.toString()
        log.fine {
            if (cauAddMethod != null)
                "patch_addListener → ${listenerWrapperClass.type}->${cauAddMethod.name}($cauAddParamType) [via wrapper]"
            else
                "patch_addListener: cau.add method not found — injecting no-op fallback"
        }

        // 9.x: direct listener set (Lcrh.N) — a CopyOnWriteArraySet field directly on
        // ExoPlayerImpl (NOT the cau ListenerHolderSet). The coordinator's b:Lcou listener
        // is registered here via O(Lcou;)V. We need direct access to move it on player swap.
        val directListenerSetField = if (is_9_00_or_greater) {
            exoImplFields.firstOrNull {
                it.type == "Ljava/util/concurrent/CopyOnWriteArraySet;"
            }.also { f ->
                if (f == null) log.warning("9.x: direct listener set field (Lcrh.N) not found on ${exoPlayerImplClass.type}")
                else log.fine { "9.x: direct listener set field = $f" }
            }
        } else null

        // 9.x: coordinator's Player.Listener field (auge.b:Lcou) — Player.Listener type
        // is the same as cauAddParamType (the type cau.add() accepts).
        val coordinatorListenerField = if (is_9_00_or_greater && cauAddParamType != null) {
            (coordinatorClass.fields.firstOrNull { it.type == cauAddParamType }
                ?: coordinatorClass.fields.firstOrNull { field ->
                    field.type.startsWith("L") && try {
                        cauAddParamType in classDefBy(field.type).interfaces
                    } catch (_: Exception) { false }
                }
            ).also { f ->
                if (f == null) log.warning("9.x: coordinator listener field (type $cauAddParamType or implementor) not found on ${coordinatorClass.type}")
                else log.fine { "9.x: coordinator listener field = $f" }
            }
        } else null
        exoPlayerImplClass.methods.add(
            ImmutableMethod(
                exoPlayerImplClass.type, "patch_addListener",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    if (cauAddMethod != null) {
                        """
                            iget-object v0, p0, $listenerWrapperField
                            check-cast p1, $cauAddParamType
                            invoke-virtual { v0, p1 }, $cauAddMethod
                            return-void
                        """
                    } else {
                        "return-void" // no-op fallback; migrateListeners will warn
                    }
                )
            }
        )

        // patch_getListenerSet navigates through the wrapper: cpp.h → cau.c
        exoPlayerImplClass.methods.add(
            ImmutableMethod(
                exoPlayerImplClass.type,
                "patch_getListenerSet",
                listOf(),
                "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(2)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $listenerWrapperField
                        iget-object v0, v0, $listenerSetInWrapper
                        return-object v0
                    """
                )
            }
        )

        exoPlayerImplClass.addFieldGetter("patch_getInternalListener", internalListenerField)
        exoPlayerImplClass.addFieldSetter("patch_setDltCallback", dltFieldOnExo)

        // 9.x only: patch_getCoordinatorListener returns coordinator.b:Lcou (Player.Listener)
        // registered into ExoPlayer's direct N set. Not present on 8.x.
        if (is_9_00_or_greater && coordinatorListenerField != null) {
            coordinatorClass.addFieldGetter("patch_getCoordinatorListener", coordinatorListenerField)
            log.fine { "9.x: injected patch_getCoordinatorListener on ${coordinatorClass.type} (field: $coordinatorListenerField)" }
        }

        // 9.x only: patch_addDirectListener / patch_removeDirectListener operate on Lcrh.N
        // (the direct CopyOnWriteArraySet, bypassing the cau ListenerHolderSet).
        // Used to move the coordinator's b:Lcou listener between players on player swap.
        if (is_9_00_or_greater && directListenerSetField != null) {
            exoPlayerImplClass.methods.add(
                ImmutableMethod(
                    exoPlayerImplClass.type, "patch_addDirectListener",
                    listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                    "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    MutableMethodImplementation(3)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $directListenerSetField
                            invoke-virtual { v0, p1 }, Ljava/util/concurrent/CopyOnWriteArraySet;->add(Ljava/lang/Object;)Z
                            return-void
                        """
                    )
                }
            )
            exoPlayerImplClass.methods.add(
                ImmutableMethod(
                    exoPlayerImplClass.type, "patch_removeDirectListener",
                    listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                    "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    MutableMethodImplementation(3)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $directListenerSetField
                            invoke-virtual { v0, p1 }, Ljava/util/concurrent/CopyOnWriteArraySet;->remove(Ljava/lang/Object;)Z
                            return-void
                        """
                    )
                }
            )
            // patch_getDirectListenerCount — returns Lcrh.N.size() for diagnostic logging.
            // Lets us verify that patch_addDirectListener actually registered the listener.
            exoPlayerImplClass.methods.add(
                ImmutableMethod(
                    exoPlayerImplClass.type, "patch_getDirectListenerCount",
                    listOf(),
                    "I", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    MutableMethodImplementation(2)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $directListenerSetField
                            invoke-virtual { v0 }, Ljava/util/concurrent/CopyOnWriteArraySet;->size()I
                            move-result v0
                            return v0
                        """
                    )
                }
            )
            log.fine { "9.x: injected patch_addDirectListener / patch_removeDirectListener / patch_getDirectListenerCount on ${exoPlayerImplClass.type}" }
        }

        // 9.x only: patch_detachCwhFromEventDispatch — removes coordinator_cwh from this
        // ExoPlayer's crh.h:Lcgd event dispatch set. Called on the OUTGOING player before
        // it is released so its release-time isPlayingChanged(false) doesn't reach MediaSession
        // via cwh.b (the boolean is captured at source and never re-queried from cwh.g).
        //
        // The "remove" method on Lcgd is found by bytecode inspection (not by hardcoded
        // name "e"): among the (Object):V methods on Lcgd, the remove method is the one
        // whose body invokes CopyOnWriteArraySet.remove() on the wrapped set.  This makes
        // the patch resilient to R8 renaming "e" to something else across YTM versions.
        if (is_9_00_or_greater && eventDispatchField9x != null && exoPlayerCwhField9x != null) {
            val cgdType = eventDispatchField9x.type
            val cgdClass = classDefBy(cgdType)
            val cgdRemoveMethodName = cgdClass.methods.firstOrNull { m ->
                m.parameterTypes.size == 1
                    && m.parameterTypes[0].toString() == "Ljava/lang/Object;"
                    && m.returnType == "V"
                    && m.implementation?.instructions
                        ?.filterIsInstance<ReferenceInstruction>()
                        ?.any {
                            val ref = it.reference.toString()
                            ref.contains("Ljava/util/concurrent/CopyOnWriteArraySet;->remove(")
                        } == true
            }?.name ?: "e"  // fallback to historical name if bytecode scan fails

            log.fine { "9.x: Lcgd remove method resolved → $cgdType->$cgdRemoveMethodName(Object):V" }

            exoPlayerImplClass.methods.add(
                ImmutableMethod(
                    exoPlayerImplClass.type, "patch_detachCwhFromEventDispatch",
                    listOf(), "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null, null,
                    MutableMethodImplementation(3)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $eventDispatchField9x
                            iget-object v1, p0, $exoPlayerCwhField9x
                            invoke-virtual { v0, v1 }, $cgdType->$cgdRemoveMethodName(Ljava/lang/Object;)V
                            return-void
                        """
                    )
                }
            )
            log.fine { "9.x: injected patch_detachCwhFromEventDispatch on ${exoPlayerImplClass.type} (crh.h=${eventDispatchField9x}, cwh=${exoPlayerCwhField9x})" }
        }

        // 9.x only: suppress cwh.U() during crossfade releases.
        //
        // Root cause of the pause/seek UI regression:
        //   crh.P() (ExoPlayer.release()) calls crh.j.U()V on the SHARED singleton cwh.
        //   cwh.U() posts a Lcvu Runnable to cwh.h (the handler).
        //   cvu.run() then calls cwh.b.d() — releasing the Lcgd that holds cwh's Lctu
        //   listeners, including auih.k (the MediaSession listener).
        //   cgd.d() calls CopyOnWriteArraySet.clear() AND sets cgd.i = true (prevents
        //   future adds). All listeners are gone; MediaSession can no longer receive any
        //   events (isPlayingChanged, onPositionDiscontinuity, etc.).
        //
        // In normal (non-crossfade) operation there is only one ExoPlayer, so destroying
        // cwh.b on release is fine — there is no subsequent player to receive events.
        // In crossfade, two ExoPlayer instances share the SAME cwh singleton. Releasing
        // the old player must NOT destroy the shared listener infrastructure used by
        // the new player.
        //
        // Fix: inject an early-return at the top of cwh.U()V that checks the static
        // CrossfadeManager.suppressCwhU flag. releasePlayer() sets it true before
        // calling patch_release() and false in a finally block — synchronously blocking
        // the Runnable from ever being posted, leaving cwh.b intact.
        if (is_9_00_or_greater && eventDispatchField9x != null && forwardingPlayerField9x != null) {
            val cgdType = eventDispatchField9x.type
            val cwhLctrType = forwardingPlayerField9x.type  // = Lctr interface type
            try {
                // Find cwh.U()V: the method named "U" with no params/void return on the concrete
                // class that (a) implements the Lctr interface and (b) has a non-static Lcgd field.
                // cwh.U() posts a Lcvu Runnable that calls cwh.b.d() destroying the shared
                // listener set. Inject an early-return guard that checks suppressCwhU.
                Fingerprint(
                    name = "U",
                    returnType = "V",
                    parameters = emptyList(),
                    custom = { _, classDef ->
                        cwhLctrType in classDef.interfaces && classDef.fields.any { f ->
                            f.type == cgdType && !AccessFlags.STATIC.isSet(f.accessFlags)
                        }
                    }
                ).method.addInstructions(
                    0,
                    """
                        sget-boolean v0, $EXTENSION_CLASS->suppressCwhU:Z
                        if-eqz v0, :no_suppress
                        return-void
                        :no_suppress
                        nop
                    """,
                )
                log.fine { "9.x: injected suppressCwhU into cwh.U()V (lctrType=$cwhLctrType, cgdType=$cgdType)" }
            } catch (e: Exception) {
                log.warning("9.x: suppressCwhU injection failed: ${e.message}")
            }
        }

        // --- SessionAccess on atgd ---
        sessionClass.interfaces.add(SESSION_INTERFACE)
        sessionClass.addFieldGetter("patch_getFactory", factoryFieldRef)

        // --- PlayerFactoryAccess on atih ---
        // On 9.x, the ExoPlayer constructor checks a single-attachment guard field on
        // the sharedState's abstract superclass and refuses to attach if it's already
        // set. We clear it before calling the factory so the new player can attach.
        // On 8.x no guard exists — simple 4-register bridge.
        factoryClass.interfaces.add(FACTORY_INTERFACE)
        val needsGuardClear = guardField != null
        val guardClearSmali = if (needsGuardClear) {
            """
                iget-object v0, p1, $sharedStateFieldRef
                check-cast v0, $guardAbstractType
                const/4 v1, 0x0
                iput-object v1, v0, $guardField
            """
        } else ""
        factoryClass.methods.add(
            ImmutableMethod(
                factoryClass.type, "patch_createPlayer",
                listOf(
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                    ImmutableMethodParameter("I", null, null),
                ),
                "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                // 9.x needs 7 registers: v0-v2 free locals, p0-p3 = v3-v6
                // 8.x only needs 4: v0 result local, p0-p3 = v0-v3 (reused)
                MutableMethodImplementation(if (needsGuardClear) 7 else 4)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p1, $coordinatorType
                        check-cast p2, $loadControlType
                        $guardClearSmali
                        invoke-virtual { p0, p1, p2, p3 }, $factoryMethod
                        move-result-object v0
                        return-object v0
                    """
                )
            }
        )

        // --- SharedStateAccess on concrete shared state class ---
        sharedStateClass.interfaces.add(SHARED_STATE_INTERFACE)
        sharedStateClass.addFieldGetter("patch_getTimeline", timelineField)
        sharedStateClass.addFieldSetter("patch_setTimeline", timelineField)

        // --- SharedCallbackAccess on concrete shared callback class ---
        sharedCallbackClass.interfaces.add(SHARED_CALLBACK_INTERFACE)
        sharedCallbackClass.addFieldGetter("patch_getCqb", cqbField)
        sharedCallbackClass.addFieldSetter("patch_setCqb", cqbField)
        sharedCallbackClass.addFieldGetter("patch_getDlt", dltCallbackTypeOnShared)
        sharedCallbackClass.addFieldSetter("patch_setDlt", dltCallbackTypeOnShared)

        // --- VideoSurfaceAccess ---
        videoSurfaceClass.interfaces.add(VIDEO_SURFACE_INTERFACE)
        videoSurfaceClass.addFieldSetter("patch_setPlayerReference", videoSurfaceExoField)

        // --- MedialibPlayerAccess on atad ---
        medialibPlayerClass.interfaces.add(MEDIALIB_PLAYER_INTERFACE)
        medialibPlayerClass.addFieldGetter("patch_getPlayerChain", playerChainField)
        medialibPlayerClass.methods.add(
            ImmutableMethod(
                medialibPlayerClass.type,
                "patch_playNextInQueue",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(1)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0 }, $playNextInQueueMethod
                        return-void
                    """
                )
            }
        )
        // patch_forceStopVideo: calls atad.stopVideo(REASON_DIRECTOR_RESET=5) through the
        // hooked method. Used by the 8.x and 9.x auto-advance monitor to trigger early crossfade setup.
        medialibPlayerClass.methods.add(
            ImmutableMethod(
                medialibPlayerClass.type,
                "patch_forceStopVideo",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(2)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const/4 v0, 0x5
                        invoke-virtual { p0, v0 }, ${StopVideoFingerprint.method}
                        return-void
                    """
                )
            }
        )
        // patch_forceLoadVideo: calls atad.stopVideo(REASON_STOP=1) through the hooked method.
        // On 9.x the monitor calls this after patch_forceStopVideo to drive the loadVideo
        // chain on the freshly-swapped new player (since stopVideo(5) alone does not call
        // stopVideo(1), and patch_playNextInQueueDirect defers until natural track end).
        medialibPlayerClass.methods.add(
            ImmutableMethod(
                medialibPlayerClass.type,
                "patch_forceLoadVideo",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(2)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const/4 v0, 0x1
                        invoke-virtual { p0, v0 }, ${StopVideoFingerprint.method}
                        return-void
                    """
                )
            }
        )
        // patch_loadVideoWith: re-issue loadVideo (atzq.o) with a cached descriptor
        // (the aues PlaybackStartDescriptor).  Used by the REPEAT_SINGLE path to load
        // the SAME song onto the freshly-swapped crossfade player (crossfade-onto-self)
        // instead of advancing the queue.  The Object param is cast to the loadVideo
        // parameter type (the aues descriptor interface).
        val loadVideoMethod = LoadVideoFingerprint.method
        val loadVideoDescriptorType = loadVideoMethod.parameterTypes.first().toString()
        medialibPlayerClass.methods.add(
            ImmutableMethod(
                medialibPlayerClass.type,
                "patch_loadVideoWith",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(2)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p1, $loadVideoDescriptorType
                        invoke-virtual { p0, p1 }, $loadVideoMethod
                        return-void
                    """
                )
            }
        )

        // --- VideoToggleAccess on nba ---
        videoToggleClass.interfaces.add(VIDEO_TOGGLE_INTERFACE)
        val videoToggleClassStateProviderField = videoToggleClass.fields.first {
            it.type.startsWith("L")
        }
        val stateProviderClass = mutableClassDefBy(videoToggleClassStateProviderField.type)

        // getState returns an enum (nlv) representing the current playback
        // content mode.  We find it as the first no-arg method returning a
        // non-Object, non-primitive type.
        val getStateMethod = Fingerprint(
            definingClass = stateProviderClass.type,
            parameters = listOf(),
            custom = { method, _ ->
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                        method.returnType != "Ljava/lang/Object;"
            }
        ).method
        val stateType = getStateMethod.returnType

        // isAudioMode is the static (stateType)Z method with MORE enum
        // comparisons (checks 3 audio-only states vs 2 video states).
        // We pick the longest implementation among the static (T)Z methods.
        val isAudioModeMethod = Fingerprint(
            definingClass = stateProviderClass.type,
            returnType = "Z",
            parameters = listOf(stateType),
            custom = { method, _ ->
                AccessFlags.STATIC.isSet(method.accessFlags)
            }
        ).method

        videoToggleClass.methods.add(
            ImmutableMethod(
                videoToggleClass.type,
                "patch_isAudioMode",
                listOf(),
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $videoToggleClassStateProviderField
                        invoke-virtual { v0 }, $getStateMethod
                        move-result-object v0
                        invoke-static { v0 }, $isAudioModeMethod
                        move-result v0
                        return v0
                    """
                )
            }
        )

        // setState is the instance void method on nlw that takes one nlv param.
        val setStateMethodFingerprint = Fingerprint(
            definingClass = stateProviderClass.type,
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
        val setStateMethod = setStateMethodFingerprint.method

        // ATV_PREFERRED is the first enum constant (ordinal 0) on the nlv class.
        val atvPreferredField = classDefBy(stateType).fields.first { field ->
            field.type == stateType
                    && AccessFlags.STATIC.isSet(field.accessFlags)
                    && AccessFlags.FINAL.isSet(field.accessFlags)
        }

        videoToggleClass.methods.add(
            ImmutableMethod(
                videoToggleClass.type,
                "patch_forceAudioMode",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $videoToggleClassStateProviderField
                        sget-object v1, $atvPreferredField
                        invoke-virtual { v0, v1 }, $setStateMethod
                        return-void
                    """
                )
            }
        )

        // patch_triggerToggle calls the actual nba toggle method so the full
        // UI update path fires (reactive observers, button states, content mode).
        val toggleMethod = AudioVideoToggleFingerprint.method
        videoToggleClass.methods.add(
            ImmutableMethod(
                videoToggleClass.type,
                "patch_triggerToggle",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(2)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0 }, $toggleMethod
                        return-void
                    """
                )
            }
        )

        // --- Silent mode set: bypass reactive broadcast (chxp) ---
        //
        // patch_forceAudioMode() calls nlw.setState → chxp.mo6606iF which
        // broadcasts to ALL subscribers including nmi, which triggers a
        // navigation/jump that fires stopVideo(5). On repeated video→audio
        // crossfades, the blocked jumps corrupt the loading pipeline.
        //
        // We discover chxp.m34891ax - the internal setter that writes the
        // value via AtomicReference.lazySet WITHOUT iterating subscribers.
        // Bridge methods let CrossfadeManager silently set/restore mode.

        // 1. From setStateMethod's bytecode, find the chxp field on nlw
        val chxpFieldRef = setStateMethodFingerprint.instructionMatches.first()
            .getInstruction<ReferenceInstruction>().getReference<FieldReference>()!!
        val chxpType = chxpFieldRef.type

        // 2. Find the broadcast method (mo6606iF) called from setStateMethod
        val broadcastMethodRef = setStateMethod.instructions
            .filterIsInstance<ReferenceInstruction>()
            .first {
                it.opcode == Opcode.INVOKE_VIRTUAL
                    || it.opcode == Opcode.INVOKE_INTERFACE
            }
            .reference as MethodReference

        // 3. Find the broadcast method implementation on the chxp class
        val broadcastMethodFingerprint = Fingerprint(
            definingClass = chxpType,
            name = broadcastMethodRef.name,
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

        // 4. From broadcast method's bytecode, find the silent setter (m34891ax)
        //    - the first invoke on a non-Object class that takes Object
        val silentSetMethodRef = broadcastMethodFingerprint.instructionMatches.first()
            .getInstruction<ReferenceInstruction>().getReference<MethodReference>()!!

        // 5. Find OMV_PREFERRED - the second enum constant (ordinal 1)
        val stateEnumStaticFields = classDefBy(stateType).fields.filter { field ->
            field.type == stateType
                && AccessFlags.STATIC.isSet(field.accessFlags)
                && AccessFlags.FINAL.isSet(field.accessFlags)
        }
        val omvPreferredField = stateEnumStaticFields[1]

        log.fine {
            """
                Silent mode discovery:
                chxpField       = $chxpFieldRef
                chxpType        = $chxpType
                broadcastMethod = ${broadcastMethodRef.definingClass}->${broadcastMethodRef.name}
                silentSetMethod = $silentSetMethodRef.definingClass}->${silentSetMethodRef.name}
                omvPreferred    = $omvPreferredField    
            """.trimIndent()
        }

        // 6. Add public wrapper on chxp class for the silent setter
        val mutableChxpClass = mutableClassDefBy(chxpType)
        mutableChxpClass.methods.add(
            ImmutableMethod(
                chxpType,
                "patch_silentSet",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0, p1 }, $silentSetMethodRef
                        return-void
                    """
                )
            }
        )

        // 7. Add patch_silentSetState on the state provider class (nlw)
        val silentSetOnChxp = "$chxpType->patch_silentSet(Ljava/lang/Object;)V"
        stateProviderClass.methods.add(
            ImmutableMethod(
                stateProviderClass.type,
                "patch_silentSetState",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $chxpFieldRef
                        invoke-virtual {v0, p1}, $silentSetOnChxp
                        return-void
                    """
                )
            }
        )

        // 8. Add patch_forceAudioModeSilent and patch_restoreVideoModeSilent on nba
        val silentSetOnProvider = "${stateProviderClass.type}->patch_silentSetState(Ljava/lang/Object;)V"
        videoToggleClass.methods.add(
            ImmutableMethod(
                videoToggleClass.type,
                "patch_forceAudioModeSilent",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $videoToggleClassStateProviderField
                        sget-object v1, $atvPreferredField
                        invoke-virtual { v0, v1 }, $silentSetOnProvider
                        return-void
                    """
                )
            }
        )

        videoToggleClass.methods.add(
            ImmutableMethod(
                videoToggleClass.type,
                "patch_restoreVideoModeSilent",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $videoToggleClassStateProviderField
                        sget-object v1, $omvPreferredField
                        invoke-virtual { v0, v1 }, $silentSetOnProvider
                        return-void
                    """
                )
            }
        )

        // patch_restoreVideoMode (BROADCAST variant) — used when the user pauses crossfade
        // to resync YTM's subscribers (nmi etc.) that may have stale cached state from
        // prior silent toggles.  Calling the broadcast setStateMethod fires chxp.mo6606iF
        // which iterates subscribers; subscribers reconcile, and the next user-initiated
        // video toggle works properly (no black screen from a no-op short-circuit because
        // subscribers thought they were already in the target state).
        videoToggleClass.methods.add(
            ImmutableMethod(
                videoToggleClass.type,
                "patch_restoreVideoMode",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $videoToggleClassStateProviderField
                        sget-object v1, $omvPreferredField
                        invoke-virtual { v0, v1 }, $setStateMethod
                        return-void
                    """
                )
            }
        )

        // Hook nba constructor so we capture the instance immediately on creation.
        // Songs loaded from the main feed never trigger shouldBlockVideoToggle (which
        // only fires on an explicit audio/video toggle interaction), leaving lastNbaRef
        // null for the entire session. The constructor hook ensures onNbaCreated() runs
        // as soon as nba is instantiated — before any crossfade is attempted.
        videoToggleClass.methods
            .filter { AccessFlags.CONSTRUCTOR.isSet(it.accessFlags) && it.name == "<init>" }
            .maxByOrNull { it.implementation?.instructions?.size ?: 0 }
            ?.addInstructions(
                1, // position 1 = after super.<init> call
                """
                    invoke-static { p0 }, $EXTENSION_CLASS->onNbaCreated(Ljava/lang/Object;)V
                """,
            ) ?: error("nba <init> not found in ${videoToggleClass.type}")

        // --- DelegateAccess on every delegate chain class ---
        // One class on 9.10-9.21 (byte-for-byte identical to the old single-class
        // injection); multiple on 9.23+ (covers e.g. avel + avfa) so the runtime
        // delegate-walk can traverse every hop to the coordinator.
        for ((delegateClass, delegateField) in delegateClasses) {
            delegateClass.apply {
                interfaces.add(DELEGATE_INTERFACE)
                addFieldGetter("patch_getDelegate", delegateField)
            }
        }

        // --- ListenerWrapperAccess on cat (listener element class) ---
        listenerElementClass.apply {
            interfaces.add(LISTENER_WRAPPER_INTERFACE)
            addFieldGetter("patch_getWrappedListener", listenerElementField)
        }
    }
}
