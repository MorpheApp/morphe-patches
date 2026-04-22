package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_8_05_or_greater
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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS_DESCRIPTOR =
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


@Suppress("unused")
val crossfadePatch = bytecodePatch(
    name = "Track crossfade",
    description = "Adds a true dual-player crossfade between consecutive tracks. This patch currently requires YouTube 8.x",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val log = Logger.getLogger(this::class.java.name)
        if (!is_8_05_or_greater || is_9_00_or_greater) {
            return@execute log.warning(
                "Track crossfade is not yet available for YouTube Music 9.x. " +
                    "Patch YouTube Music 8.44.54–8.50.51 for crossfade.",
            )
        }

        fun allMethodsInHierarchy(
            startType: String,
        ): List<com.android.tools.smali.dexlib2.iface.Method> {
            val result = mutableListOf<com.android.tools.smali.dexlib2.iface.Method>()
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
                    SwitchPreference("morphe_music_crossfade_on_skip"),
                    SwitchPreference("morphe_music_crossfade_on_auto_advance"),
                    SwitchPreference("morphe_music_crossfade_session_control"),
                    NonInteractivePreference("morphe_music_crossfade_about")
                )
            )
        )

        StopVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->onBeforeStopVideo(Ljava/lang/Object;I)Z
                move-result v0
                if-eqz v0, :allow_stop
                return-void
                :allow_stop
                nop
            """
        )

        PlayNextInQueueFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onBeforePlayNext(Ljava/lang/Object;)Z
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
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockVideoToggle(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :allow_toggle
                return-void
                :allow_toggle
                nop
            """
        )

        PauseVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->onPauseVideo()Z
                move-result v0
                if-eqz v0, :allow_pause
                return-void
                :allow_pause
                nop
            """
        )

        PlayVideoFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onPlayVideo(Ljava/lang/Object;)V
            """,
        )

        val musicActivityClass = MusicActivityOnCreateFingerprint.classDef
        musicActivityClass.methods.first { it.name == "onStop" && it.parameterTypes.isEmpty() }
            .addInstructions(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->onActivityStop()V
                """,
            )
        musicActivityClass.methods.first { it.name == "onStart" && it.parameterTypes.isEmpty() }
            .addInstructions(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->onActivityStart()V
                """,
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
            .reference as FieldReference
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

        val exoPlayerImplClass = mutableClassDefBy(ExoPlayerImplFingerprint.classDef.type)
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
                literal(-9223372036854775807L)
            ),
            custom = { _, classDef ->
                isInHierarchyOf(classDef.type, exoPlayerImplClass.type)
            }
        ).method.name

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

        val allExoFields = exoPlayerImplClass.fields.toList()
        val dltIdx = allExoFields.indexOf(dltFieldOnExo)
        val internalListenerField = allExoFields.getOrNull(dltIdx + 1)
            ?: error("Internal listener field (after DLT at index $dltIdx) not found on ${exoPlayerImplClass.type}")

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
        var bxkType = sharedStateMethodPool.firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size != 2) return@firstNotNullOfOrNull null
            val types = method.parameterTypes.map { it.toString() }
            when {
                types[1] == "Landroid/os/Looper;" -> types[0]
                types[0] == "Landroid/os/Looper;" -> types[1]
                else -> null
            }
        }

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

        val playerChainInterfaceType = playerChainField.type
        val delegateBaseClass = Fingerprint(
            custom = { _, classDef ->
                classDef.type != playerChainInterfaceType
                    && !AccessFlags.INTERFACE.isSet(classDef.accessFlags)
                    && playerChainInterfaceType in classDef.interfaces
                    && classDef.fields.any { it.type == playerChainInterfaceType }
            }
        ).classDef
        val delegateField = delegateBaseClass.fields.first { it.type == playerChainInterfaceType }
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
                    classDefBy(type).fields.any { it.name == "a" && it.type == "Ljava/lang/Object;" }
                } catch (_: Exception) { false }
            }
        val listenerElementClass = mutableClassDefBy(listenerElementType)
        val listenerElementField = listenerElementClass.fields.first {
            it.name == "a" && it.type == "Ljava/lang/Object;"
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
                delegateBase   = ${delegateBaseClass.type} (field: $delegateField)
                listenerElem   = ${listenerElementClass.type} (field: $listenerElementField)
                timelineField  = $timelineField (bxk type: $bxkType)
                cqbField       = $cqbField (definingClass: ${cqbField.definingClass})
                dltOnShared    = $dltCallbackTypeOnShared
                dltOnExo       = $dltFieldOnExo
                internalLsnr   = $internalListenerField
                listenerWrap   = $listenerWrapperField → $listenerSetInWrapper
                playerChain    = $playerChainField
            """.trimIndent()
        }

        coordinatorClass.interfaces.add(COORDINATOR_INTERFACE)
        coordinatorClass.addFieldGetter("patch_getExoPlayer", exoPlayerField)
        coordinatorClass.addFieldSetter("patch_setExoPlayer", exoPlayerField)
        coordinatorClass.addFieldGetter("patch_getSession", sessionFieldRef)
        coordinatorClass.addFieldGetter("patch_getLoadControl", loadControlField)
        coordinatorClass.addFieldGetter("patch_getSharedState", sharedStateFieldRef)
        coordinatorClass.addFieldGetter("patch_getSharedCallback", sharedCallbackFieldRef)
        coordinatorClass.addFieldGetter("patch_getVideoSurface", videoSurfaceField)

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

        sessionClass.interfaces.add(SESSION_INTERFACE)
        sessionClass.addFieldGetter("patch_getFactory", factoryFieldRef)

        factoryClass.interfaces.add(FACTORY_INTERFACE)
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
                MutableMethodImplementation(4)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p1, $coordinatorType
                        check-cast p2, $loadControlType
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

        videoToggleClass.interfaces.add(VIDEO_TOGGLE_INTERFACE)
        val videoToggleClassStateProviderField = videoToggleClass.fields.first {
            it.type.startsWith("L")
        }
        val stateProviderClass = mutableClassDefBy(videoToggleClassStateProviderField.type)
        val getStateMethod = Fingerprint(
            definingClass = stateProviderClass.type,
            parameters = listOf(),
            custom = { method, _ ->
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                        method.returnType != "Ljava/lang/Object;"
            }
        ).method
        val stateType = getStateMethod.returnType
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

        val chxpFieldRef = setStateMethodFingerprint.instructionMatches.first()
            .getInstruction<ReferenceInstruction>().getReference<FieldReference>()!!
        val chxpType = chxpFieldRef.type
        val broadcastMethodRef = setStateMethod.instructions
            .filterIsInstance<ReferenceInstruction>()
            .first {
                it.opcode == Opcode.INVOKE_VIRTUAL
                    || it.opcode == Opcode.INVOKE_INTERFACE
            }
            .reference as MethodReference

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

        delegateBaseClass.apply {
            interfaces.add(DELEGATE_INTERFACE)
            addFieldGetter("patch_getDelegate", delegateField)
        }

        listenerElementClass.apply {
            interfaces.add(LISTENER_WRAPPER_INTERFACE)
            addFieldGetter("patch_getWrappedListener", listenerElementField)
        }
    }
}