/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.information

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.toInstructions
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_49_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.PlaybackSpeedOnItemClickParentFingerprint
import app.morphe.patches.youtube.video.playerresponse.Hook
import app.morphe.patches.youtube.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.youtube.video.playerresponse.playerResponseMethodHookPatch
import app.morphe.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.morphe.patches.youtube.video.videoid.hookPlayerResponsePlaylistId
import app.morphe.patches.youtube.video.videoid.hookPlayerResponseVideoId
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addStaticFieldToExtension
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/VideoInformation;"
private const val EXTENSION_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoInformation$PlaybackController;"
private const val EXTENSION_VIDEO_QUALITY_MENU_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoInformation$VideoQualityMenuInterface;"
internal const val EXTENSION_VIDEO_QUALITY_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoInformation$VideoQualityInterface;"

private lateinit var playerInitMethodRef : WeakReference<MutableMethod>
private var playerInitInsertIndex = -1
private var playerInitInsertRegister = -1

private lateinit var mdxInitMethodRef : WeakReference<MutableMethod>
private var mdxInitInsertIndex = -1
private var mdxInitInsertRegister = -1

private lateinit var timeMethodRef : WeakReference<MutableMethod>
private var timeInitInsertIndex = 2

// Old speed menu, where speeds are entries in a list. Method is also used by the player speed button.
private lateinit var legacySpeedSelectionInsertMethodRef : WeakReference<MutableMethod>
private var legacySpeedSelectionInsertIndex = -1
private var legacySpeedSelectionValueRegister = -1

// New speed menu, with preset buttons and 0.05x fine adjustments buttons.
private lateinit var speedSelectionInsertMethodRef : WeakReference<MutableMethod>
private var speedSelectionInsertIndex = -1
private var speedSelectionValueRegister = -1

// Change playback speed method.
private lateinit var setPlaybackSpeedMethodRef : WeakReference<MutableMethod>
private var setPlaybackSpeedMethodIndex = -1

internal lateinit var playerStatusMethodRef : WeakReference<MutableMethod>

// Used by other patches.
internal lateinit var setPlaybackSpeedContainerClassFieldReferenceClassTypeRef : WeakReference<ClassDef>
    private set
internal lateinit var setPlaybackSpeedContainerClassFieldReferenceRef : WeakReference<FieldReference>
    private set
internal lateinit var setPlaybackSpeedClassFieldReferenceRef : WeakReference<FieldReference>
    private set
internal lateinit var setPlaybackSpeedMethodReferenceRef : WeakReference<MethodReference>
    private set

val videoInformationPatch = bytecodePatch(
    description = "Hooks YouTube to get information about the current playing video.",
) {
    dependsOn(
        sharedExtensionPatch,
        videoIdPatch,
        playerResponseMethodHookPatch,
        versionCheckPatch,
    )

    execute {
        val playerInitMethod = PlayerInitFingerprint.classDef.methods.first { MethodUtil.isConstructor(it) }

        playerInitMethodRef = WeakReference(playerInitMethod)

        // Find the location of the first invoke-direct call and extract the register storing the 'this' object reference.
        val initThisIndex = playerInitMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_DIRECT && getReference<MethodReference>()?.name == "<init>"
        }
        playerInitInsertRegister = playerInitMethod.getInstruction<FiveRegisterInstruction>(initThisIndex).registerC
        playerInitInsertIndex = initThisIndex + 1

        val seekFingerprintResultMethod = SeekFingerprint.method
        val seekRelativeFingerprintResultMethod = SeekRelativeFingerprint.method
        val getVideoTimeMethodName = GetVideoTimeFingerprint.instructionMatches.first()
            .getMethodCalled().name

        // Create extension interface methods.
        addPlayerInterfaceMethods(
            PlayerInitFingerprint.classDef,
            seekFingerprintResultMethod,
            seekRelativeFingerprintResultMethod,
            getVideoTimeMethodName
        )

        with(MdxPlayerDirectorSetVideoStageFingerprint) {
            val mdxInitMethod = classDef.methods.first { MethodUtil.isConstructor(it) }
            mdxInitMethodRef = WeakReference(mdxInitMethod)

            val initThisIndex = mdxInitMethod.indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT && getReference<MethodReference>()?.name == "<init>"
            }
            mdxInitInsertRegister = mdxInitMethod.getInstruction<FiveRegisterInstruction>(initThisIndex).registerC
            mdxInitInsertIndex = initThisIndex + 1

            // Hook the MDX director for use through the extension.
            onCreateHookMDX(EXTENSION_CLASS, "initializeMDX")

            val mdxSeekFingerprintResultMethod = MdxSeekFingerprint.match(classDef).method
            val mdxSeekRelativeFingerprintResultMethod = MdxSeekRelativeFingerprint.match(classDef).method

            addPlayerInterfaceMethods(
                classDef,
                mdxSeekFingerprintResultMethod,
                mdxSeekRelativeFingerprintResultMethod,
                getVideoTimeMethodName
            )
        }

        VideoLengthFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<ThreeRegisterInstruction>(index).registerB

                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static { v$register, v${register + 1} }, $EXTENSION_CLASS->setVideoLength(J)V",
                )
            }
        }

        val playerStatusFingerprint = Fingerprint(
            classFingerprint = PlayerInitFingerprint,
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(PlayerStatusEnumFingerprint.originalClassDef.type),
            filters = listOf(
                // The opcode for the first index of the method is sget-object.
                // Even in sufficiently old versions, such as YT 17.34, the opcode for the first index is sget-object.
                opcode(Opcode.SGET_OBJECT),
                methodCall(
                    definingClass = "Lj$/time/Instant;",
                    name = "plus"
                )
            )
        )

        playerStatusMethodRef = WeakReference(playerStatusFingerprint.method)

        /*
         * Inject call for video IDs
         */
        val videoIdMethod = "$EXTENSION_CLASS->setVideoId(Ljava/lang/String;)V"
        hookVideoId(videoIdMethod)
        hookBackgroundPlayVideoId(videoIdMethod)
        hookPlayerResponsePlaylistId(
            "$EXTENSION_CLASS->setPlayerResponsePlaylistId(Ljava/lang/String;Z)V",
        )
        hookPlayerResponseVideoId(
            "$EXTENSION_CLASS->setPlayerResponseVideoId(Ljava/lang/String;Z)V",
        )
        // Call before any other video ID hooks,
        // so they can use VideoInformation and check if the video ID is for a Short.
        addPlayerResponseMethodHook(
            Hook.ProtoBufferParameterBeforeVideoId(
                "$EXTENSION_CLASS->" +
                    "newPlayerResponseSignature(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;",
            ),
        )

        /*
         * Set the video time method
         */
        timeMethodRef = WeakReference(
            PlayerControllerSetTimeReferenceFingerprint.instructionMatches.first()
                .getMethodCalled()
        )

        val setPlaybackSpeedMethodReference: MethodReference

        /*
         * Hook the user playback speed selection.
         */
        PlaybackSpeedOnItemClickFingerprint.method.apply {
            val speedSelectionValueInstructionIndex = indexOfFirstInstructionOrThrow(Opcode.IGET)

            legacySpeedSelectionInsertMethodRef = WeakReference(this)
            legacySpeedSelectionInsertIndex = speedSelectionValueInstructionIndex + 1
            legacySpeedSelectionValueRegister =
                getInstruction<TwoRegisterInstruction>(speedSelectionValueInstructionIndex).registerA

            setPlaybackSpeedMethodReference = getInstruction<ReferenceInstruction>(
                indexOfFirstInstructionOrThrow(speedSelectionValueInstructionIndex) {
                    val reference = getReference<MethodReference>()
                    reference?.parameterTypes?.size == 1 && reference.parameterTypes.first() == "F"
                }).reference as MethodReference
            setPlaybackSpeedMethodReferenceRef = WeakReference(setPlaybackSpeedMethodReference)

            val setPlaybackSpeedContainerClassFieldReference = getInstruction<ReferenceInstruction>(
                indexOfFirstInstructionOrThrow(Opcode.IF_EQZ) - 1
            ).reference as FieldReference
            setPlaybackSpeedContainerClassFieldReferenceRef = WeakReference(
                setPlaybackSpeedContainerClassFieldReference
            )

            val setPlaybackSpeedContainerClassFieldReferenceClassType : ClassDef
            if (is_20_49_or_greater) {
                // Only one class implements the interface. Patcher currently does not have a
                // 'first' accessor for looking up classes, so do it ourselves to verify
                // we're using the expected class type.
                var fieldReferenceType: ClassDef? = null
                classDefForEach { def ->
                    if (def.interfaces.contains(setPlaybackSpeedContainerClassFieldReference.type)) {
                        if (fieldReferenceType != null) {
                            throw PatchException("Found more than one playback speed interface: $def")
                        }
                        fieldReferenceType = def
                    }
                }
                setPlaybackSpeedContainerClassFieldReferenceClassType = fieldReferenceType!!
                setPlaybackSpeedContainerClassFieldReferenceClassTypeRef = WeakReference(
                    setPlaybackSpeedContainerClassFieldReferenceClassType
                )
            } else {
                setPlaybackSpeedContainerClassFieldReferenceClassType = classDefBy(
                    setPlaybackSpeedContainerClassFieldReference.type
                )
                setPlaybackSpeedContainerClassFieldReferenceClassTypeRef = WeakReference(
                    setPlaybackSpeedContainerClassFieldReferenceClassType
                )
            }

            val setPlaybackSpeedClassFieldReference = getInstruction<ReferenceInstruction>(
                indexOfFirstInstructionOrThrow(speedSelectionValueInstructionIndex) {
                    getReference<FieldReference>()?.type?.startsWith("L") == true
                }
            ).reference as FieldReference
            setPlaybackSpeedClassFieldReferenceRef = WeakReference(setPlaybackSpeedClassFieldReference)

            setPlaybackSpeedMethodRef = WeakReference(
                mutableClassDefBy(
                    setPlaybackSpeedMethodReference.definingClass
                ).methods.first { it.name == setPlaybackSpeedMethodReference.name }
            )

            setPlaybackSpeedMethodIndex = 0

            // Add override playback speed method.
            PlaybackSpeedOnItemClickParentFingerprint.classDef.methods.add(
                ImmutableMethod(
                    definingClass,
                    "overridePlaybackSpeed",
                    listOf(ImmutableMethodParameter("F", annotations, null)),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.PUBLIC.value,
                    annotations,
                    null,
                    ImmutableMethodImplementation(
                        4,
                        """
                            # Check if the playback speed is not auto (-2.0f)
                            const/4 v0, 0x0
                            cmpg-float v0, v3, v0
                            if-lez v0, :ignore
                            
                            # Get the container class field.
                            iget-object v0, v2, $setPlaybackSpeedContainerClassFieldReference
                            
                            # For some reason, in YouTube 19.44.39 this value is sometimes null.
                            if-eqz v0, :ignore
                            
                            # Required cast for 20.49+
                            check-cast v0, $setPlaybackSpeedContainerClassFieldReferenceClassType

                            # Get the field from its class.
                            iget-object v1, v0, $setPlaybackSpeedClassFieldReference
                            
                            # Invoke setPlaybackSpeed on that class.
                            invoke-virtual { v1, v3 }, $setPlaybackSpeedMethodReference

                            :ignore
                            return-void
                        """.toInstructions(), null, null
                    )
                ).toMutable()
            )
        }

        PlaybackSpeedClassFingerprint.method.apply {
            val index = indexOfFirstInstructionOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            val playbackSpeedClass = this.returnType

            // Set playback speed class.
            addInstructionsAtControlFlowLabel(
                index,
                "sput-object v$register, $EXTENSION_CLASS->playbackSpeedClass:$playbackSpeedClass"
            )

            val smaliInstructions =
                """
                    if-eqz v0, :ignore
                    invoke-virtual {v0, p0}, $playbackSpeedClass->overridePlaybackSpeed(F)V
                    return-void
                    :ignore
                    nop
                """

            addStaticFieldToExtension(
                EXTENSION_CLASS,
                "overridePlaybackSpeed",
                "playbackSpeedClass",
                playbackSpeedClass,
                smaliInstructions
            )
        }

        // Handle new playback speed menu.
        PlaybackSpeedMenuSpeedChangedFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index

                speedSelectionInsertMethodRef = WeakReference(this)
                speedSelectionInsertIndex = index + 1
                speedSelectionValueRegister = getInstruction<TwoRegisterInstruction>(index).registerA
            }
        }

        val videoQualityClassType : String
        VideoQualityFingerprint.let {
            videoQualityClassType = it.classDef.type

            // Fix bad data used by YouTube.
            it.method.addInstructions(
                0,
                """
                    invoke-static { p3, p1 }, $EXTENSION_CLASS->fixVideoQualityResolution(Ljava/lang/String;I)I    
                    move-result p1
                """
            )

            // Add methods to access obfuscated quality fields.
            it.classDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_VIDEO_QUALITY_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getQualityName",
                        listOf(),
                        "Ljava/lang/String;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        // Only one string field.
                        val qualityNameField = fields.single { field ->
                            field.type == "Ljava/lang/String;"
                        }

                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $qualityNameField
                                return-object v0
                            """
                        )
                    }
                )

                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getResolution",
                        listOf(),
                        "I",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        val resolutionField = fields.single { field ->
                            field.type == "I"
                        }

                        addInstructions(
                            0,
                            """
                                iget v0, p0, $resolutionField
                                return v0
                            """
                        )
                    }
                )
            }
        }

        // Detect video quality changes and override the current quality.
        SetVideoQualityFingerprint.let { match ->
            // This instruction refers to the field with the type that contains the setQuality method.
            val onItemClickListenerClassReference = match.method
                .getInstruction<ReferenceInstruction>(0).reference
            val setQualityFieldReference = match.method
                .getInstruction<ReferenceInstruction>(1).reference as FieldReference

            mutableClassDefBy(setQualityFieldReference.type).apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_VIDEO_QUALITY_MENU_INTERFACE)

                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_setQuality",
                        listOf(
                            ImmutableMethodParameter(EXTENSION_VIDEO_QUALITY_INTERFACE, null, null)
                        ),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        val setQualityMenuIndexMethod = methods.single { method ->
                            method.parameterTypes.firstOrNull() == videoQualityClassType
                        }

                        addInstructions(
                            0,
                            """
                                check-cast p1, $videoQualityClassType
                                invoke-virtual { p0, p1 }, $setQualityMenuIndexMethod
                                return-void
                            """
                        )
                    }
                )
            }

            VideoQualitySetterFingerprint.method.addInstructions(
                0,
                """
                    # Get object instance to invoke setQuality method.
                    iget-object v0, p0, $onItemClickListenerClassReference
                    iget-object v0, v0, $setQualityFieldReference
                    
                    invoke-static { p1, v0, p2 }, $EXTENSION_CLASS->setVideoQuality([$EXTENSION_VIDEO_QUALITY_INTERFACE${EXTENSION_VIDEO_QUALITY_MENU_INTERFACE}I)I
                    move-result p2
                """
            )
        }

        ChannelInformationFingerprint.let {
            val matches = it.matchAll(2 .. 3)

            val playerResponseType = matches.first().method.parameterTypes.first().toString()

            PlayerInitFingerprint.classDef.apply {
                val channelIdMethodCall = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                    returnType = "V",
                    parameters = listOf("Ljava/lang/Object;"),
                    filters = listOf(
                        methodCall(
                            definingClass = playerResponseType,
                            returnType = "Ljava/lang/String;"
                        ),
                        string(
                            string = "com.google.android.apps.youtube.mdx.watch.LAST_MEALBAR_PROMOTED_LIVE_FEED_CHANNELS",
                            location = MatchAfterWithin(20)
                        )
                    )
                ).instructionMatches.first().getInstruction<ReferenceInstruction>().getReference<MethodReference>()

                methods.add(
                    ImmutableMethod(
                        type,
                        "setChannelInformation",
                        listOf(
                            ImmutableMethodParameter(
                                playerResponseType,
                                annotations,
                                null
                            )
                        ),
                        "V",
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                        annotations,
                        null,
                        ImmutableMethodImplementation(
                            3,
                            """
                                invoke-interface { p1 }, $channelIdMethodCall
                                move-result-object v0
                                invoke-static { v0 }, $EXTENSION_CLASS->setChannelId(Ljava/lang/String;)V

                                return-void
                            """.toInstructions(),
                            null,
                            null
                        )
                    ).toMutable()
                )
            }

            matches.forEach { match ->
                match.method.addInstruction(
                    0,
                    "invoke-direct { p0, p1 }, ${match.classDef.type}->setChannelInformation($playerResponseType)V"
                )
            }
        }

        onCreateHook(EXTENSION_CLASS, "initialize")
        videoSpeedChangedHook(EXTENSION_CLASS, "videoSpeedChanged")
        userSelectedPlaybackSpeedHook(EXTENSION_CLASS, "userSelectedPlaybackSpeed")
    }
}

private fun addPlayerInterfaceMethods(
    targetClass: MutableClass,
    seekToMethod: Method,
    seekToRelativeMethod: Method,
    getVideoTimeMethodName: String
) {
    // Add the interface and methods that extension calls.
    targetClass.interfaces.add(EXTENSION_PLAYER_INTERFACE)

    arrayOf(
        Triple(seekToMethod, "patch_seekTo", true),
        Triple(seekToRelativeMethod, "patch_seekToRelative", false),
    ).forEach { (method, name, returnsBoolean) ->
        // Add interface method.
        // Get enum type for the seek helper method.
        val seekSourceEnumType = method.parameterTypes[1].toString()

        val interfaceImplementation = ImmutableMethod(
            targetClass.type,
            name,
            listOf(ImmutableMethodParameter("J", null, "time")),
            if (returnsBoolean) "Z" else "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(4),
        ).toMutable()

        var instructions = """
            # First enum (field a) is SEEK_SOURCE_UNKNOWN.
            sget-object v0, $seekSourceEnumType->a:$seekSourceEnumType
            invoke-virtual { p0, p1, p2, v0 }, $method
        """

        instructions += if (returnsBoolean) {
            """
                move-result p1
                return p1                
            """
        } else {
            "return-void"
        }

        // Insert helper method instructions.
        interfaceImplementation.addInstructions(
            0,
            instructions,
        )

        targetClass.methods.add(interfaceImplementation)
    }

    targetClass.methods.add(
        ImmutableMethod(
            targetClass.type,
            "patch_getVideoTime",
            listOf(),
            "J",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-virtual { p0 }, ${targetClass.type}->$getVideoTimeMethodName()J 
                    move-result-wide v0
                    return-wide v0
                """
            )
        }
    )
}

private fun MutableMethod.insert(insertIndex: Int, register: String, descriptor: String) =
    addInstruction(insertIndex, "invoke-static { $register }, $descriptor")

private fun MutableMethod.insertTimeHook(insertIndex: Int, descriptor: String) =
    insert(insertIndex, "p1, p2", descriptor)

/**
 * Hook the player controller. Called when a video is opened or the current video is changed.
 *
 * Note: This hook is called very early and is called before the video ID, video time, video length,
 * and many other data fields are set.
 *
 * @param targetMethodClass The descriptor for the class to invoke when the player controller is created.
 * @param targetMethodName The name of the static method to invoke when the player controller is created.
 */
internal fun onCreateHook(targetMethodClass: String, targetMethodName: String) =
    playerInitMethodRef.get()!!.insert(
        playerInitInsertIndex++,
        "v$playerInitInsertRegister",
        "$targetMethodClass->$targetMethodName($EXTENSION_PLAYER_INTERFACE)V",
    )

/**
 * Hook the MDX player director. Called when playing videos while casting to a big screen device.
 *
 * @param targetMethodClass The descriptor for the class to invoke when the player controller is created.
 * @param targetMethodName The name of the static method to invoke when the player controller is created.
 */
internal fun onCreateHookMDX(targetMethodClass: String, targetMethodName: String) =
    mdxInitMethodRef.get()!!.insert(
        mdxInitInsertIndex++,
        "v$mdxInitInsertRegister",
        "$targetMethodClass->$targetMethodName($EXTENSION_PLAYER_INTERFACE)V",
    )

/**
 * Hook the video time.
 * The hook is usually called once per second.
 *
 * @param targetMethodClass The descriptor for the static method to invoke when the player controller is created.
 * @param targetMethodName The name of the static method to invoke when the player controller is created.
 */
fun videoTimeHook(targetMethodClass: String, targetMethodName: String) =
    timeMethodRef.get()!!.insertTimeHook(
        timeInitInsertIndex++,
        "$targetMethodClass->$targetMethodName(J)V",
    )

/**
 * Hook when the video speed is changed for any reason _except when the user manually selects a new speed_.
 */
fun videoSpeedChangedHook(targetMethodClass: String, targetMethodName: String) =
    setPlaybackSpeedMethodRef.get()!!.addInstruction(
        setPlaybackSpeedMethodIndex++,
        "invoke-static { p1 }, $targetMethodClass->$targetMethodName(F)V"
    )

/**
 * Hook the video speed selected by the user.
 */
fun userSelectedPlaybackSpeedHook(targetMethodClass: String, targetMethodName: String) {
    legacySpeedSelectionInsertMethodRef.get()!!.addInstruction(
        legacySpeedSelectionInsertIndex++,
        "invoke-static { v$legacySpeedSelectionValueRegister }, $targetMethodClass->$targetMethodName(F)V"
    )

    speedSelectionInsertMethodRef.get()!!.addInstruction(
        speedSelectionInsertIndex++,
        "invoke-static { v$speedSelectionValueRegister }, $targetMethodClass->$targetMethodName(F)V",
    )
}