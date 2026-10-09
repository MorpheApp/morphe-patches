/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1065
 * https://github.com/MorpheApp/morphe-patches/pull/3635
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/CrossfadePatch;"

private const val COORDINATOR_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$PlayerCoordinatorAccess;"
private const val EXO_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$ExoPlayerAccess;"
private const val SESSION_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$SessionAccess;"
private const val FACTORY_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$PlayerFactoryAccess;"
private const val SHARED_STATE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$SharedStateAccess;"
private const val SHARED_CALLBACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$SharedCallbackAccess;"
private const val VIDEO_SURFACE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$VideoSurfaceAccess;"
private const val MEDIALIB_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$MedialibPlayerAccess;"
private const val VIDEO_TOGGLE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$VideoToggleAccess;"
private const val DELEGATE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$DelegateAccess;"

private const val EXO_PLAYER_TYPE = "Landroidx/media3/exoplayer/ExoPlayer;"

private fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    smali: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, smali) }
    )
}

private fun FieldReference.isStaticField() = (this as? Field)?.let { AccessFlags.STATIC.isSet(it.accessFlags) } == true

private fun MutableClass.addFieldGetter(methodName: String, fieldRef: FieldReference) {
    val read = if (fieldRef.isStaticField()) "sget-object v0, $fieldRef" else "iget-object v0, p0, $fieldRef"
    addBridge(methodName, emptyList(), "Ljava/lang/Object;", 2, "$read\nreturn-object v0")
}

private fun MutableClass.addFieldSetter(methodName: String, fieldRef: FieldReference) {
    val write = if (fieldRef.isStaticField()) "sput-object p1, $fieldRef" else "iput-object p1, p0, $fieldRef"
    addBridge(methodName, listOf("Ljava/lang/Object;"), "V", 2, "check-cast p1, ${fieldRef.type}\n$write\nreturn-void")
}

/**
 * A separate resource patch because copyResources needs the resource patch context.
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
    name = "Crossfade",
    description = "Adds a true dual-player crossfade between consecutive tracks.",
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

        // 9.28+ keeps many fields private, and the bridges below read them from other
        // classes, which throws IllegalAccessError at runtime (#2106).
        fun makeFieldPublic(fieldRef: FieldReference) {
            val field = mutableClassDefBy(fieldRef.definingClass).fields.first {
                it.name == fieldRef.name && it.type == fieldRef.type
            }
            val hiddenFlags = AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value
            field.setAccessFlags((field.accessFlags and hiddenFlags.inv()) or AccessFlags.PUBLIC.value)
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
                    PreferenceScreenPreference(
                        key = "morphe_music_crossfade_about",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_crossfade_about_banner",
                                titleKey = "morphe_music_crossfade_about_banner_title",
                                summaryKey = null,
                                layout = "@layout/morphe_crossfade_about_banner",
                            ),
                            NonInteractivePreference("morphe_music_crossfade_about_how"),
                            NonInteractivePreference("morphe_music_crossfade_about_quirks"),
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

        // Runs before the dismissal's stopVideo(5), so that stop is let through instead of
        // starting a crossfade (#1671). Optional: the STATE_IDLE poll also recovers, only slower.
        runCatching {
            HandleDismissWatchEventFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->onQueueDismissed()V"
            )
        }.onFailure {
            log.warning(
                "DismissWatchEvent handler not found, dismiss handling falls back to " +
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

        // Range form because loadVideo has enough locals to push p0 past v15.
        // The descriptor is cached so repeat-one can reload the same track.
        LoadVideoFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->" +
                    "onBeforeLoadVideo(Ljava/lang/Object;Ljava/lang/Object;)V"
        )

        // Optional: without it repeat-one is not detected and the queue advances as usual.
        runCatching {
            LoopStateAdapterFingerprint.method.addInstruction(
                0,
                " invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->" +
                        "onLoopStateChanged(Ljava/lang/Object;)V"
            )
        }.onFailure {
            log.warning("Loop-state adapter not found, repeat-one is not crossfaded: ${it.message}")
        }

        // The end of song sleep timer waits for the track to end naturally, which a crossfade
        // never lets happen (#2017). Optional: without it the timer keeps being skipped.
        runCatching {
            val sleepTimerStateType = SleepTimerStateFingerprint.classDef.type
            // The UI also has a (state)V method, the timer manager is the class with the getter too.
            Fingerprint(
                returnType = "V",
                parameters = listOf(sleepTimerStateType),
                custom = { method, classDef ->
                    !AccessFlags.STATIC.isSet(method.accessFlags) && classDef.methods.any {
                        it.parameterTypes.isEmpty() && it.returnType == sleepTimerStateType
                    }
                }
            ).method.addInstruction(
                0,
                "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onSleepTimerStateChanged(Ljava/lang/Object;)V"
            )
        }.onFailure {
            log.warning("Sleep timer state setter not found, end of song timer is not detected (#2017): ${it.message}")
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
        // The process can outlive the activity through the foreground service,
        // so static player references must not leak into the next activity.
        musicActivityClass.methods.first { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
            .addInstruction(
                0,
                "invoke-static {}, $EXTENSION_CLASS->onActivityDestroy()V"
            )

        val coordinatorClass = PlayNextInQueueFingerprint.classDef
        val coordinatorType = coordinatorClass.type
        val medialibPlayerClass = StopVideoFingerprint.classDef
        val videoToggleClass = AudioVideoToggleFingerprint.classDef

        val playerInterfaceType = classDefBy(EXO_PLAYER_TYPE).interfaces.first()

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
        val exoPlayerImplClass = ExoPlayerImplFingerprint.classDef
        val exoImplMethods = allMethodsInHierarchy(exoPlayerImplClass.type)

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

        // Shared state and shared callback are the other coordinator fields the factory reads.
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

        // The ExoPlayer constructor refuses to attach while this TrackSelector listener is set,
        // so it is cleared before a second player is created.
        var guardField: Field? = null
        var guardAbstractType: String? = null
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
                "9.x guard field not found in ${sharedStateClass.type} superclass chain, " +
                        "second player creation may fail. Fields searched from superclass of ${sharedStateClass.type}",
            )
        }

        val videoSurfaceClass = Fingerprint(
            custom = { _, classDef ->
                !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                        && classDef.fields.any {
                    it.type == EXO_PLAYER_TYPE
                }
                        && coordinatorClass.fields.map { it.type }.any { it == classDef.type }
                        && classDef.type !in knownFieldTypes
                        && classDef.type != sharedStateFieldRef.type
                        && classDef.type != sharedCallbackFieldRef.type
            }
        ).classDef
        val videoSurfaceField = coordinatorClass.fields.first { it.type == videoSurfaceClass.type }
        val videoSurfaceExoField = videoSurfaceClass.fields.first {
            it.type == EXO_PLAYER_TYPE
        }

        // ExoPlayer's ListenerSet. 9.28+ merges it with unrelated classes and types the set field
        // as AbstractCollection, so only its (CopyOnWriteArraySet, Looper, Thread, ...) constructor is stable.
        val copyOnWriteSetType = "Ljava/util/concurrent/CopyOnWriteArraySet;"
        val listenerWrapperField = exoPlayerImplClass.fields.firstOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type.startsWith("L") && try {
                classDefBy(field.type).methods.any { method ->
                    val params = method.parameterTypes.map { it.toString() }
                    method.name == "<init>"
                            && params.firstOrNull() == copyOnWriteSetType
                            && "Landroid/os/Looper;" in params
                            && "Ljava/lang/Thread;" in params
                }
            } catch (_: Exception) { false }
        } ?: error("ListenerSet field not found on ${exoPlayerImplClass.type}")
        val listenerWrapperClass = classDefBy(listenerWrapperField.type)

        fun Method.callsCopyOnWriteSet(name: String) = implementation?.instructions?.any { insn ->
            insn is ReferenceInstruction
                    && insn.reference.toString().startsWith("$copyOnWriteSetType->$name(")
        } == true

        fun Method.isObjectVoidMethod() = returnType == "V"
                && parameterTypes.size == 1
                && parameterTypes[0].toString() == "Ljava/lang/Object;"

        val cauAddMethod = listenerWrapperClass.methods.first { method ->
            method.isObjectVoidMethod()
                    && method.callsCopyOnWriteSet("add")
                    && method.implementation!!.instructions.any { it.opcode == Opcode.NEW_INSTANCE }
        }
        val cauAddInstructions = cauAddMethod.implementation!!.instructions.toList()
        val holderIndex = cauAddInstructions.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE }
        // The last wrapper field read before the holder is allocated, the earlier one is the lock.
        val listenerSetInWrapper = cauAddInstructions.subList(0, holderIndex)
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .last { it.definingClass == listenerWrapperClass.type }
        val cauRemoveMethod = listenerWrapperClass.methods.first { method ->
            method.isObjectVoidMethod() && method.callsCopyOnWriteSet("remove")
        }

        // Each player dispatches events to its own AnalyticsCollector (cwh), so the MediaSession
        // listener has to be registered on the new player's cwh, or the session stays PAUSED.
        var exoPlayerCwhField9x: Field? = null
        var cwhListenerType: String? = null
        var cwhAddListenerMethod: Method? = null
        var coordinatorCwhListenerField9x: Field? = null
        var eventDispatchField9x: Field? = null
        val forwardingPlayerField9x = coordinatorClass.fields.firstOrNull {
            it.name == sharedCallbackFieldRef.name && it.type == sharedCallbackFieldRef.type
        }.also { f ->
            if (f == null) log.warning(
                "AnalyticsCollector field not found on coordinator, MediaSession listener fix skipped"
            ) else log.fine { "Coordinator AnalyticsCollector field = $f" }
        }

        val lctrType = forwardingPlayerField9x?.type
        if (lctrType != null) {
            exoPlayerCwhField9x = exoPlayerImplClass.fields.firstOrNull { f ->
                !AccessFlags.STATIC.isSet(f.accessFlags) && f.type == lctrType
            }.also { f ->
                if (f == null) log.warning("AnalyticsCollector field not found on ExoPlayer, MediaSession listener fix skipped")
                else log.fine { "ExoPlayer AnalyticsCollector field = $f" }
            }

            // cwh.addListener, recognized by forwarding to ListenerSet.add instead of by name.
            cwhAddListenerMethod = sharedCallbackClass.methods.firstOrNull { m ->
                m.returnType == "V"
                        && m.parameterTypes.size == 1
                        && m.parameterTypes[0].toString() != "Ljava/lang/Object;"
                        && m.implementation?.instructions?.any { insn ->
                    insn is ReferenceInstruction
                            && (insn.reference as? MethodReference)?.let {
                        it.definingClass == listenerWrapperClass.type && it.name == cauAddMethod.name
                    } == true
                } == true
            }
            cwhListenerType = cwhAddListenerMethod?.parameterTypes?.first()?.toString()
            log.fine { "AnalyticsCollector listener type = $cwhListenerType via $cwhAddListenerMethod" }

            if (cwhListenerType != null) {
                coordinatorCwhListenerField9x = coordinatorClass.fields.firstOrNull { f ->
                    !AccessFlags.STATIC.isSet(f.accessFlags)
                            && f.type != exoPlayerField.type
                            && f.type != lctrType
                            && try { cwhListenerType in classDefBy(f.type).interfaces } catch (_: Exception) { false }
                }.also { f ->
                    if (f == null) log.warning("Coordinator MediaSession listener field not found, MediaSession listener fix skipped")
                    else log.fine { "Coordinator MediaSession listener field = $f" }
                }
            }

            // cwh is removed from the outgoing player's ListenerSet before release, so the
            // release's isPlayingChanged(false) does not reach the MediaSession.
            eventDispatchField9x = listenerWrapperField
            log.fine { "ExoPlayer ListenerSet field = $eventDispatchField9x" }
        }

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
        val setVideoSurfaceName = Fingerprint(
            definingClass = playerInterfaceType,
            returnType = "V",
            parameters = listOf("Landroid/view/Surface;"),
        ).method.name
        val releaseName = Fingerprint(
            definingClass = EXO_PLAYER_TYPE,
            returnType = "V",
            parameters = emptyList(),
            custom = { method, _ ->
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
            }
        ).method.name
        // PlaybackInfo. The interface check rules out the internal player, which has similar fields.
        val exoImplFields = allFieldsInHierarchy(exoPlayerImplClass.type)
        val playbackInfoClass = Fingerprint(
            custom = { _, classDef ->
                classDef.interfaces.isEmpty()
                        && classDef.fields.count { it.type == "I" } >= 3
                        && classDef.fields.count { it.type == "J" } >= 1
                        && exoImplFields.map { it.type }
                    .any { it == classDef.type }
            }
        ).classDef
        val playbackStateFieldName = playbackInfoClass.fields.first { it.type == "I" }.name
        // The getters below can be declared on a superclass of the impl, hence no definingClass.
        val getPlaybackStateName = Fingerprint(
            returnType = "I",
            parameters = emptyList(),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    type = playbackInfoClass.type
                ),
                fieldAccess(
                    opcode = Opcode.IGET,
                    name = playbackStateFieldName
                )
            ),
            custom = { _, classDef ->
                isInHierarchyOf(classDef.type, startType = exoPlayerImplClass.type)
            }
        ).method.name

        val getDurationName = Fingerprint(
            returnType = "J",
            parameters = emptyList(),
            filters = listOf(
                // C.TIME_UNSET
                literal(-9223372036854775807L)
            ),
            custom = { _, classDef ->
                isInHierarchyOf(classDef.type, startType = exoPlayerImplClass.type)
            }
        ).method.name

        val getCurrentPositionName = Fingerprint(
            returnType = "J",
            parameters = emptyList(),
            custom = { method, classDef ->
                isInHierarchyOf(classDef.type, startType = exoPlayerImplClass.type)
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

        // The player field of cwh. The ExoPlayer constructor throws unless it is null,
        // so it is cleared around player creation and restored afterward.
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

        // The Clock of cwh, which a release also clears and has to be restored.
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

        // The shared state's timeline field, saved and restored around player creation.
        // Its type is the non-Looper parameter of a (X, Looper) method, and 9.x without one
        // falls back to the first non-library instance field.
        val sharedStateMethodPool = buildList {
            addAll(allMethodsInHierarchy(sharedStateClass.type))
            if (sharedStateFieldRef.type != sharedStateClass.type) {
                try { addAll(classDefBy(sharedStateFieldRef.type).methods) } catch (_: Exception) {}
            }
            for (iface in sharedStateClass.interfaces) {
                try { addAll(classDefBy(iface).methods) } catch (_: Exception) {}
            }
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
        val standardTypes = setOf(
            "Ljava/lang/Object;", "Ljava/lang/String;",
            "Ljava/util/List;", "Ljava/util/Map;", "Ljava/util/Set;",
            "Ljava/util/ArrayList;", "Ljava/util/HashMap;",
            "Landroid/util/SparseArray;", "Landroid/os/Handler;",
            "Landroid/os/Looper;", "Ljava/util/concurrent/CopyOnWriteArraySet;",
        )
        val bxkType = sharedStateMethodPool.firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size != 2) return@firstNotNullOfOrNull null
            val types = method.parameterTypes.map { it.toString() }
            when {
                types[1] == "Landroid/os/Looper;" -> types[0]
                types[0] == "Landroid/os/Looper;" -> types[1]
                else -> null
            }
        } ?: sharedStateClass.fields.firstOrNull { field ->
            field.type.startsWith("L")
                    && field.type !in standardTypes
                    && !AccessFlags.STATIC.isSet(field.accessFlags)
        }?.type ?: error(
            "bxk type not found on ${sharedStateClass.type}, " +
                    "fields: ${sharedStateClass.fields.map { "${it.name}:${it.type}" }}"
        )
        val timelineField = sharedStateClass.fields.firstOrNull { it.type == bxkType }
            ?: error("Timeline field of type $bxkType not found on ${sharedStateClass.type}")

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

        // Every decorator in the player chain needs DelegateAccess, or the runtime walk to the
        // coordinator stops at the first one without it (9.23+ has several decorators).
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
                timelineField  = $timelineField (bxk type: $bxkType)
                cqbField       = $cqbField (definingClass: ${cqbField.definingClass})
                dltOnShared    = $dltCallbackTypeOnShared
                dltOnExo       = $dltFieldOnExo
                listenerWrap   = $listenerWrapperField -> $listenerSetInWrapper
                playerChain    = $playerChainField
                guardField     = ${guardField?.let { "$guardAbstractType->${it.name}:${it.type}" } ?: "n/a"}
            """
        }

        // These fields are accessed from other classes or through a subclass.
        listOfNotNull(
            guardField,
            sharedStateFieldRef,
            listenerSetInWrapper,
            exoPlayerCwhField9x,
            cqbField,
            dltCallbackTypeOnShared,
        ).forEach(::makeFieldPublic)

        coordinatorClass.interfaces.add(COORDINATOR_INTERFACE)
        coordinatorClass.addFieldGetter("patch_getExoPlayer", exoPlayerField)
        coordinatorClass.addFieldSetter("patch_setExoPlayer", exoPlayerField)
        coordinatorClass.addFieldGetter("patch_getSession", sessionFieldRef)
        coordinatorClass.addFieldGetter("patch_getLoadControl", loadControlField)
        coordinatorClass.addFieldGetter("patch_getSharedState", sharedStateFieldRef)
        coordinatorClass.addFieldGetter("patch_getSharedCallback", sharedCallbackFieldRef)
        coordinatorClass.addFieldGetter("patch_getVideoSurface", videoSurfaceField)

        // A coordinator setter that also moves its listeners to the new player is preferred
        // over a raw field write. None of the supported 9.x versions has one.
        val exoFieldName = exoPlayerField.name
        val exoCompatibleTypes = setOf(EXO_PLAYER_TYPE, playerInterfaceType, "Ljava/lang/Object;", exoPlayerField.type)
        val coordinatorPlayerTransitionMethod = coordinatorClass.methods.firstOrNull { method ->
            val instructions = method.implementation?.instructions
            !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
                    && !AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.parameterTypes.size == 1
                    && method.parameterTypes.first().toString() in exoCompatibleTypes
                    && instructions != null
                    && instructions.any { insn ->
                insn.opcode == Opcode.IPUT_OBJECT
                        && ((insn as ReferenceInstruction).reference as FieldReference).let {
                    it.name == exoFieldName && it.definingClass == coordinatorType
                }
            }
                    && instructions.any { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_INTERFACE }
        }
        val transitionParamType = coordinatorPlayerTransitionMethod?.parameterTypes?.first()?.toString()
            ?: exoPlayerField.type

        log.fine {
            if (coordinatorPlayerTransitionMethod != null)
                "Coordinator player-transition method found: $coordinatorPlayerTransitionMethod"
            else
                "Coordinator player-transition method NOT found, patch_setPlayerWithBindings uses raw iput-object fallback"
        }

        coordinatorClass.addBridge(
            "patch_setPlayerWithBindings", listOf("Ljava/lang/Object;"), "V", 4,
            if (coordinatorPlayerTransitionMethod != null) {
                """
                    check-cast p1, $transitionParamType
                    invoke-virtual { p0, p1 }, $coordinatorPlayerTransitionMethod
                    return-void
                """
            } else if (forwardingPlayerField9x != null && exoPlayerCwhField9x != null && coordinatorCwhListenerField9x != null && cwhAddListenerMethod != null) {
                // Registers the MediaSession listener on the new player's cwh, see above.
                val lctrType = forwardingPlayerField9x.type
                // 9.28+ has no Lctr interface, cwh is a plain class there.
                val invokeOpcode = if (AccessFlags.INTERFACE.isSet(classDefBy(lctrType).accessFlags))
                    "invoke-interface" else "invoke-virtual"
                """
                    check-cast p1, ${exoPlayerImplClass.type}
                    iget-object v0, p1, $exoPlayerCwhField9x
                    iget-object v1, p0, $coordinatorCwhListenerField9x
                    $invokeOpcode { v0, v1 }, $lctrType->${cwhAddListenerMethod.name}($cwhListenerType)V
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

        exoPlayerImplClass.interfaces.add(EXO_PLAYER_INTERFACE)

        fun addExoBridge(bridgeName: String, targetName: String, returnType: String, paramType: String? = null) {
            val parameters = listOfNotNull(paramType)
            val target = exoImplMethods.firstOrNull { method ->
                method.name == targetName && method.returnType == returnType
                        && method.parameterTypes.map { it.toString() } == parameters
            } ?: error("Bridge target $targetName($parameters)$returnType not found in ${exoPlayerImplClass.type} hierarchy")
            val invoke = if (paramType != null) "invoke-virtual { p0, p1 }, $target" else "invoke-virtual { p0 }, $target"
            val (registers, result) = when (returnType) {
                "I" -> 2 to "move-result v0\nreturn v0"
                "J" -> 3 to "move-result-wide v0\nreturn-wide v0"
                else -> 2 to "return-void"
            }
            exoPlayerImplClass.addBridge(bridgeName, parameters, returnType, registers, "$invoke\n$result")
        }

        addExoBridge("patch_getPlaybackState", getPlaybackStateName, "I")
        addExoBridge("patch_getCurrentPosition", getCurrentPositionName, "J")
        addExoBridge("patch_getDuration", getDurationName, "J")
        addExoBridge("patch_setVolume", setVolumeName, "V", "F")
        addExoBridge("patch_setPlayWhenReady", setPlayWhenReadyName, "V", "Z")
        addExoBridge("patch_release", releaseName, "V")
        addExoBridge("patch_setVideoSurface", setVideoSurfaceName, "V", "Landroid/view/Surface;")

        // The audio offload listener set. The coordinator's listener lives here and
        // has to move to the new player on a swap.
        val directListenerSetField = exoImplFields.firstOrNull {
            it.type == copyOnWriteSetType
        }.also { f ->
            if (f == null) log.warning("Audio offload listener set not found on ${exoPlayerImplClass.type}")
            else log.fine { "Audio offload listener set = $f" }
        }

        // Typed by the method that adds to that set. Matching cau.add's Object parameter
        // instead picks the coordinator's lock object.
        val directListenerType = directListenerSetField?.let { setField ->
            exoImplMethods.firstOrNull { method ->
                method.returnType == "V"
                        && method.parameterTypes.size == 1
                        && method.callsCopyOnWriteSet("add")
                        && method.implementation!!.instructions.any { insn ->
                    insn.opcode == Opcode.IGET_OBJECT
                            && (insn as ReferenceInstruction).reference.toString() == setField.toString()
                }
            }?.parameterTypes?.first()?.toString()
        }
        val coordinatorListenerField = directListenerType?.let { listenerType ->
            coordinatorClass.fields.firstOrNull { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == listenerType
            }
        }.also { f ->
            if (f == null) log.warning("9.x: coordinator listener field (type $directListenerType) not found on ${coordinatorClass.type}")
            else log.fine { "9.x: coordinator listener field = $f" }
        }

        exoPlayerImplClass.addBridge(
            "patch_getListenerSet", emptyList(), "Ljava/lang/Object;", 2,
            """
                iget-object v0, p0, $listenerWrapperField
                iget-object v0, v0, $listenerSetInWrapper
                return-object v0
            """
        )
        exoPlayerImplClass.addFieldSetter("patch_setDltCallback", dltFieldOnExo)

        if (coordinatorListenerField != null) {
            coordinatorClass.addFieldGetter("patch_getCoordinatorListener", coordinatorListenerField)
        }

        if (directListenerSetField != null) {
            for ((bridgeName, setMethod) in listOf("patch_addDirectListener" to "add", "patch_removeDirectListener" to "remove")) {
                exoPlayerImplClass.addBridge(
                    bridgeName, listOf("Ljava/lang/Object;"), "V", 3,
                    """
                        iget-object v0, p0, $directListenerSetField
                        invoke-virtual { v0, p1 }, $copyOnWriteSetType->$setMethod(Ljava/lang/Object;)Z
                        return-void
                    """
                )
            }
        }

        if (eventDispatchField9x != null && exoPlayerCwhField9x != null) {
            exoPlayerImplClass.addBridge(
                "patch_detachCwhFromEventDispatch", emptyList(), "V", 3,
                """
                    iget-object v0, p0, $eventDispatchField9x
                    iget-object v1, p0, $exoPlayerCwhField9x
                    invoke-virtual { v0, v1 }, $cauRemoveMethod
                    return-void
                """
            )
            log.fine { "9.x: patch_detachCwhFromEventDispatch removes $exoPlayerCwhField9x from $eventDispatchField9x via $cauRemoveMethod" }
        }

        // Releasing a player also releases its cwh listener set, which the incoming player shares,
        // and the MediaSession would stop getting events. Skipped while releasing an outgoing player.
        if (eventDispatchField9x != null && forwardingPlayerField9x != null) {
            val cwhLctrType = forwardingPlayerField9x.type
            try {
                val releaseMethod = exoPlayerImplClass.methods.first {
                    it.name == releaseName && it.returnType == "V" && it.parameterTypes.isEmpty()
                }
                val releaseInstructions = releaseMethod.instructions.toList()
                // Up to 9.26 release calls cwh.U()V (found by the call, not by the name "U").
                val cwhReleaseRef = releaseInstructions.firstNotNullOfOrNull { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                        it.definingClass == cwhLctrType && it.returnType == "V" && it.parameterTypes.isEmpty()
                    }
                }
                if (cwhReleaseRef != null) {
                    sharedCallbackClass.methods.first {
                        it.name == cwhReleaseRef.name && it.returnType == "V" && it.parameterTypes.isEmpty()
                    }.addInstructions(
                        0,
                        """
                            sget-boolean v0, $EXTENSION_CLASS->suppressCwhU:Z
                            if-eqz v0, :no_suppress
                            return-void
                            :no_suppress
                            nop
                        """
                    )
                    log.fine { "Injected suppressCwhU into $cwhLctrType->${cwhReleaseRef.name}()V" }
                } else {
                    // 9.28+ inlines cwh.U() into release, so the posted Runnable is swapped instead.
                    val cwhHandlerIndex = releaseInstructions.indexOfFirst { insn ->
                        insn.opcode == Opcode.IGET_OBJECT
                                && ((insn as ReferenceInstruction).reference as FieldReference).definingClass == cwhLctrType
                    }
                    if (cwhHandlerIndex < 0) error("cwh handler read not found in $releaseMethod")
                    val postIndex = releaseInstructions.withIndex().first { (index, insn) ->
                        index > cwhHandlerIndex
                                && (insn.opcode == Opcode.INVOKE_INTERFACE || insn.opcode == Opcode.INVOKE_VIRTUAL)
                                && ((insn as ReferenceInstruction).reference as MethodReference).let {
                            it.returnType == "V" && it.parameterTypes.map { type -> type.toString() } ==
                                    listOf("Ljava/lang/Runnable;")
                        }
                    }.index
                    val runnableRegister = releaseMethod.getInstruction<FiveRegisterInstruction>(postIndex).registerD
                    releaseMethod.addInstructions(
                        postIndex,
                        """
                            invoke-static/range { v$runnableRegister .. v$runnableRegister }, $EXTENSION_CLASS->filterAnalyticsRelease(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                            move-result-object v$runnableRegister
                        """
                    )
                    log.fine { "9.x: filtered inlined cwh release Runnable in $releaseMethod at $postIndex" }
                }
            } catch (e: Exception) {
                log.warning("9.x: suppressCwhU injection failed: ${e.message}")
            }
        }

        sessionClass.interfaces.add(SESSION_INTERFACE)
        sessionClass.addFieldGetter("patch_getFactory", factoryFieldRef)

        factoryClass.interfaces.add(FACTORY_INTERFACE)
        val guardClearSmali = if (guardField != null) {
            """
                iget-object v0, p1, $sharedStateFieldRef
                check-cast v0, $guardAbstractType
                const/4 v1, 0x0
                iput-object v1, v0, $guardField
            """
        } else ""
        factoryClass.addBridge(
            "patch_createPlayer", listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "I"), "Ljava/lang/Object;",
            // Clearing the guard needs locals that do not overlap the 4 parameter registers.
            if (guardField != null) 7 else 4,
            """
                check-cast p1, $coordinatorType
                check-cast p2, $loadControlType
                $guardClearSmali
                invoke-virtual { p0, p1, p2, p3 }, $factoryMethod
                move-result-object v0
                return-object v0
            """
        )

        sharedStateClass.interfaces.add(SHARED_STATE_INTERFACE)
        sharedStateClass.addFieldGetter("patch_getTimeline", timelineField)
        sharedStateClass.addFieldSetter("patch_setTimeline", timelineField)

        sharedCallbackClass.interfaces.add(SHARED_CALLBACK_INTERFACE)
        sharedCallbackClass.addFieldGetter("patch_getCqb", cqbField)
        sharedCallbackClass.addFieldSetter("patch_setCqb", cqbField)
        sharedCallbackClass.addFieldGetter("patch_getDlt", dltCallbackTypeOnShared)
        sharedCallbackClass.addFieldSetter("patch_setDlt", dltCallbackTypeOnShared)

        videoSurfaceClass.interfaces.add(VIDEO_SURFACE_INTERFACE)
        videoSurfaceClass.addFieldSetter("patch_setPlayerReference", videoSurfaceExoField)

        medialibPlayerClass.interfaces.add(MEDIALIB_PLAYER_INTERFACE)
        medialibPlayerClass.addFieldGetter("patch_getPlayerChain", playerChainField)
        medialibPlayerClass.addBridge(
            "patch_playNextInQueue", emptyList(), "V", 1,
            """
                invoke-virtual { p0 }, $playNextInQueueMethod
                return-void
            """
        )
        // Repeat-one reloads the cached descriptor instead of advancing the queue.
        val loadVideoMethod = LoadVideoFingerprint.method
        medialibPlayerClass.addBridge(
            "patch_loadVideoWith", listOf("Ljava/lang/Object;"), "V", 2,
            """
                check-cast p1, ${loadVideoMethod.parameterTypes.first()}
                invoke-virtual { p0, p1 }, $loadVideoMethod
                return-void
            """
        )

        videoToggleClass.interfaces.add(VIDEO_TOGGLE_INTERFACE)
        // 9.28+ merges the audio/video state provider with unrelated classes, so it is found
        // by its enum getter and static mode checks instead of by field position.
        fun ClassDef.getStateMethodOrNull() = methods.firstOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.parameterTypes.isEmpty()
                    && method.returnType.startsWith("L")
                    && try {
                AccessFlags.ENUM.isSet(classDefBy(method.returnType).accessFlags)
                        && methods.any { check ->
                    AccessFlags.STATIC.isSet(check.accessFlags)
                            && check.returnType == "Z"
                            && check.parameterTypes.map { it.toString() } == listOf(method.returnType)
                }
            } catch (_: Exception) { false }
        }

        val stateProviderField = videoToggleClass.fields.first { field ->
            field.type.startsWith("L") && try {
                classDefBy(field.type).getStateMethodOrNull() != null
            } catch (_: Exception) { false }
        }
        val stateProviderClass = mutableClassDefBy(stateProviderField.type)

        val getStateMethod = stateProviderClass.getStateMethodOrNull()!!
        val stateType = getStateMethod.returnType
        // The audio check compares 3 states and the video check 2, so the longer one is audio.
        val isAudioModeMethod = stateProviderClass.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.returnType == "Z"
                    && method.parameterTypes.map { it.toString() } == listOf(stateType)
        }.maxBy { it.implementation?.instructions?.count() ?: 0 }

        videoToggleClass.addBridge(
            "patch_isAudioMode", emptyList(), "Z", 3,
            """
                iget-object v0, p0, $stateProviderField
                invoke-virtual { v0 }, $getStateMethod
                move-result-object v0
                invoke-static { v0 }, $isAudioModeMethod
                move-result v0
                return v0
            """
        )

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

        // ATV_PREFERRED (audio) is the first constant and OMV_PREFERRED (video) the second.
        val stateEnumStaticFields = classDefBy(stateType).fields.filter { field ->
            field.type == stateType
                    && AccessFlags.STATIC.isSet(field.accessFlags)
                    && AccessFlags.FINAL.isSet(field.accessFlags)
        }
        val atvPreferredField = stateEnumStaticFields[0]
        val omvPreferredField = stateEnumStaticFields[1]

        // setState notifies a subscriber that fires stopVideo(5) and breaks loading on repeated
        // video to audio crossfades, so the broadcast's internal setter is used to skip notifying.
        val chxpFieldRef = setStateMethodFingerprint.instructionMatches.first()
            .getInstruction<ReferenceInstruction>().getReference<FieldReference>()!!

        val broadcastMethodRef = setStateMethod.instructions
            .filterIsInstance<ReferenceInstruction>()
            .first {
                it.opcode == Opcode.INVOKE_VIRTUAL
                        || it.opcode == Opcode.INVOKE_INTERFACE
            }
            .reference as MethodReference
        // 9.28+ types the chxp field as Object and casts it before the call.
        val chxpType = broadcastMethodRef.definingClass

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

        val silentSetMethodRef = broadcastMethodFingerprint.instructionMatches.first()
            .getInstruction<ReferenceInstruction>().getReference<MethodReference>()!!

        log.fine {
            """
                Silent mode discovery:
                chxpField       = $chxpFieldRef
                chxpType        = $chxpType
                broadcastMethod = ${broadcastMethodRef.definingClass}->${broadcastMethodRef.name}
                silentSetMethod = $silentSetMethodRef
                omvPreferred    = $omvPreferredField
            """
        }

        mutableClassDefBy(chxpType).addBridge(
            "patch_silentSet", listOf("Ljava/lang/Object;"), "V", 3,
            """
                invoke-virtual { p0, p1 }, $silentSetMethodRef
                return-void
            """
        )

        stateProviderClass.addBridge(
            "patch_silentSetState", listOf("Ljava/lang/Object;"), "V", 3,
            """
                iget-object v0, p0, $chxpFieldRef
                check-cast v0, $chxpType
                invoke-virtual { v0, p1 }, $chxpType->patch_silentSet(Ljava/lang/Object;)V
                return-void
            """
        )

        fun addSetStateBridge(bridgeName: String, stateField: Field, setter: String) {
            videoToggleClass.addBridge(
                bridgeName, emptyList(), "V", 3,
                """
                    iget-object v0, p0, $stateProviderField
                    sget-object v1, $stateField
                    invoke-virtual { v0, v1 }, $setter
                    return-void
                """
            )
        }

        val silentSetOnProvider = "${stateProviderClass.type}->patch_silentSetState(Ljava/lang/Object;)V"
        addSetStateBridge("patch_forceAudioModeSilent", atvPreferredField, silentSetOnProvider)
        addSetStateBridge("patch_restoreVideoModeSilent", omvPreferredField, silentSetOnProvider)
        // Broadcasting variant, so subscribers left stale by silent changes resync. Otherwise, the
        // next video toggle is skipped as a no-op and shows a black screen.
        addSetStateBridge("patch_restoreVideoMode", omvPreferredField, setStateMethod.toString())

        // The toggle hook only runs when the user taps the toggle, so without this the
        // toggle instance is never captured for tracks started from the feed.
        videoToggleClass.methods
            .filter { AccessFlags.CONSTRUCTOR.isSet(it.accessFlags) && it.name == "<init>" }
            .maxByOrNull { it.implementation?.instructions?.size ?: 0 }
            ?.addInstructions(
                // After the super constructor call.
                1,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS->onNbaCreated(Ljava/lang/Object;)V
                """,
            ) ?: error("nba <init> not found in ${videoToggleClass.type}")

        for ((delegateClass, delegateField) in delegateClasses) {
            delegateClass.apply {
                interfaces.add(DELEGATE_INTERFACE)
                addFieldGetter("patch_getDelegate", delegateField)
            }
        }
    }
}
