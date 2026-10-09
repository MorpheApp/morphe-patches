/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1065
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
private const val LISTENER_WRAPPER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/CrossfadePatch$ListenerWrapperAccess;"

private const val EXO_PLAYER_TYPE = "Landroidx/media3/exoplayer/ExoPlayer;"

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
            log.warning("Loop-state adapter not found, REPEAT_SINGLE " +
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
        val cauAddParamType = cauAddMethod.parameterTypes.first().toString()
        val cauAddInstructions = cauAddMethod.implementation!!.instructions.toList()
        val holderIndex = cauAddInstructions.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE }
        val listenerElementType =
            (cauAddInstructions[holderIndex] as ReferenceInstruction).reference.toString()
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
                "9.x: Lctr (cwh) field not found on coordinator, crh.j fix skipped"
            ) else log.fine { "9.x: coordinator cwh field (auih.c) = $f" }
        }

        val lctrType = forwardingPlayerField9x?.type
        if (lctrType != null) {
            exoPlayerCwhField9x = exoPlayerImplClass.fields.firstOrNull { f ->
                !AccessFlags.STATIC.isSet(f.accessFlags) && f.type == lctrType
            }.also { f ->
                if (f == null) log.warning("9.x: crh.j (Lctr on ExoPlayer) not found, crh.j fix skipped")
                else log.fine { "9.x: ExoPlayer cwh field (crh.j) = $f" }
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
            log.fine { "9.x: cwh listener interface (Lctu) = $cwhListenerType via $cwhAddListenerMethod" }

            if (cwhListenerType != null) {
                coordinatorCwhListenerField9x = coordinatorClass.fields.firstOrNull { f ->
                    !AccessFlags.STATIC.isSet(f.accessFlags)
                            && f.type != exoPlayerField.type
                            && f.type != lctrType
                            && try { cwhListenerType in classDefBy(f.type).interfaces } catch (_: Exception) { false }
                }.also { f ->
                    if (f == null) log.warning("9.x: coordinator cwh listener field (auih.k) not found, crh.j fix skipped")
                    else log.fine { "9.x: coordinator cwh listener field (auih.k) = $f" }
                }
            }

            // cwh is removed from the outgoing player's ListenerSet before release, so the
            // release's isPlayingChanged(false) does not reach the MediaSession.
            eventDispatchField9x = listenerWrapperField
            log.fine { "9.x: ExoPlayer event dispatch field (crh.h:Lcgd) = $eventDispatchField9x" }
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

        // R8 keeps declaration order, and the internal listener is declared right after the clock.
        val allExoFields = exoPlayerImplClass.fields.toList()
        val dltIdx = allExoFields.indexOf(dltFieldOnExo)
        val internalListenerField = allExoFields.getOrNull(dltIdx + 1)
            ?: error("Internal listener field (after DLT at index $dltIdx) not found on ${exoPlayerImplClass.type}")

        // The shared state's timeline field, saved and restored around player creation.
        // Its type is the non-Looper parameter of a (X, Looper) method somewhere in the hierarchy.
        val sharedStateMethodPool = buildList {
            addAll(sharedStateClass.methods)
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
        var bxkType = sharedStateMethodPool.firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size != 2) return@firstNotNullOfOrNull null
            val types = method.parameterTypes.map { it.toString() }
            when {
                types[1] == "Landroid/os/Looper;" -> types[0]
                types[0] == "Landroid/os/Looper;" -> types[1]
                else -> null
            }
        }

        // 9.x has no such method, there the first non-library instance field is used.
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

        // The listener holder keeps the listener in its first Object field.
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
                listenerWrap   = $listenerWrapperField -> $listenerSetInWrapper
                playerChain    = $playerChainField
                guardField     = ${guardField?.let { "$guardAbstractType->${it.name}:${it.type}" } ?: "n/a (8.x)"}
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

        // MedialibPlayer.playNextInQueue goes through an interface the coordinator does not
        // implement, so it never reaches the hooked coordinator method.
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

        // A coordinator setter that also moves its listeners to the new player is preferred
        // over a raw field write. None of the supported 9.x versions has one.
        val exoFieldName = exoPlayerField.name
        val exoCompatibleTypes = buildSet {
            add(EXO_PLAYER_TYPE)
            add(playerInterfaceType)
            add("Ljava/lang/Object;")
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
                "Coordinator player-transition method NOT found, patch_setPlayerWithBindings uses raw iput-object fallback"
        }

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
                    } else if (forwardingPlayerField9x != null && exoPlayerCwhField9x != null && coordinatorCwhListenerField9x != null && cwhAddListenerMethod != null) {
                        // Registers the MediaSession listener on the new player's cwh, see above.
                        val lctrType = forwardingPlayerField9x.type
                        val concreteExoType = exoPlayerImplClass.type
                        // 9.28+ has no Lctr interface, cwh is a plain class there.
                        val invokeOpcode = if (AccessFlags.INTERFACE.isSet(classDefBy(lctrType).accessFlags))
                            "invoke-interface" else "invoke-virtual"
                        val addListenerName = cwhAddListenerMethod.name
                        """
                            check-cast p1, $concreteExoType
                            iget-object v0, p1, $exoPlayerCwhField9x
                            iget-object v1, p0, $coordinatorCwhListenerField9x
                            $invokeOpcode { v0, v1 }, $lctrType->$addListenerName($cwhListenerType)V
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

        log.fine {
            "patch_addListener -> ${listenerWrapperClass.type}->${cauAddMethod.name}($cauAddParamType) [via wrapper]"
        }

        // The audio offload listener set. The coordinator's listener lives here and
        // has to move to the new player on a swap.
        val directListenerSetField =
            exoImplFields.firstOrNull {
                it.type == copyOnWriteSetType
            }.also { f ->
                if (f == null) log.warning("9.x: direct listener set field (Lcrh.N) not found on ${exoPlayerImplClass.type}")
                else log.fine { "9.x: direct listener set field = $f" }
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
        exoPlayerImplClass.methods.add(
            ImmutableMethod(
                exoPlayerImplClass.type, "patch_addListener",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $listenerWrapperField
                        check-cast p1, $cauAddParamType
                        invoke-virtual { v0, p1 }, $cauAddMethod
                        return-void
                    """
                )
            }
        )

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

        if (coordinatorListenerField != null) {
            coordinatorClass.addFieldGetter("patch_getCoordinatorListener", coordinatorListenerField)
            log.fine { "9.x: injected patch_getCoordinatorListener on ${coordinatorClass.type} (field: $coordinatorListenerField)" }
        }

        if (directListenerSetField != null) {
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

        if (eventDispatchField9x != null && exoPlayerCwhField9x != null) {
            log.fine { "9.x: Lcgd remove method resolved -> $cauRemoveMethod" }

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
                            invoke-virtual { v0, v1 }, $cauRemoveMethod
                            return-void
                        """
                    )
                }
            )
            log.fine { "9.x: injected patch_detachCwhFromEventDispatch on ${exoPlayerImplClass.type} (crh.h=${eventDispatchField9x}, cwh=${exoPlayerCwhField9x})" }
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
                    log.fine { "9.x: injected suppressCwhU into cwh.${cwhReleaseRef.name}()V (lctrType=$cwhLctrType)" }
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
                // Clearing the guard needs locals that do not overlap the 4 parameter registers.
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
        // 5 is REASON_DIRECTOR_RESET.
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
        // stopVideo(1) is what starts loadVideo on the swapped player, stopVideo(5) alone does not.
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
        // Repeat-one reloads the cached descriptor instead of advancing the queue.
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

        val videoToggleClassStateProviderField = videoToggleClass.fields.first { field ->
            field.type.startsWith("L") && try {
                classDefBy(field.type).getStateMethodOrNull() != null
            } catch (_: Exception) { false }
        }
        val stateProviderClass = mutableClassDefBy(videoToggleClassStateProviderField.type)

        val getStateMethod = stateProviderClass.getStateMethodOrNull()!!
        val stateType = getStateMethod.returnType
        // The audio check compares 3 states and the video check 2, so the longer one is audio.
        val isAudioModeMethod = stateProviderClass.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.returnType == "Z"
                    && method.parameterTypes.map { it.toString() } == listOf(stateType)
        }.maxBy { it.implementation?.instructions?.count() ?: 0 }

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

        // ATV_PREFERRED (audio) is the first constant.
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

        // OMV_PREFERRED (video) is the second constant.
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
            """
        }

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
                        check-cast v0, $chxpType
                        invoke-virtual {v0, p1}, $silentSetOnChxp
                        return-void
                    """
                )
            }
        )

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

        // Broadcasting variant, so subscribers left stale by silent changes resync. Otherwise, the
        // next video toggle is skipped as a no-op and shows a black screen.
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

        listenerElementClass.apply {
            interfaces.add(LISTENER_WRAPPER_INTERFACE)
            addFieldGetter("patch_getWrappedListener", listenerElementField)
        }
    }
}
