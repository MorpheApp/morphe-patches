/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.information

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.toInstructions
import app.morphe.patches.shared.misc.textcomponent.hookSpannableString
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.patches.shared.misc.videoinformation.PlayerControllerSetTimeReferenceFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.context.conversionContextPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.InitializePlaybackSpeedValuesFingerprint
import app.morphe.patches.youtube.video.playerresponse.Hook
import app.morphe.patches.youtube.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.youtube.video.playerresponse.playerResponseMethodHookPatch
import app.morphe.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.morphe.patches.youtube.video.videoid.hookPlayerResponsePlaylistId
import app.morphe.patches.youtube.video.videoid.hookPlayerResponseVideoId
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/VideoInformation;"
private const val EXTENSION_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoInformation$PlaybackController;"
internal const val EXTENSION_PLAYBACK_SPEED_MENU_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoInformation$PlaybackSpeedMenuInterface;"
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

private lateinit var speedSelectionInsertMethodRef : WeakReference<MutableMethod>
private var speedSelectionInsertIndex = -1
private var speedSelectionValueRegister = -1

private lateinit var formattedSpeedStringInsertMethodRef : WeakReference<MutableMethod>
private var formattedSpeedStringInsertIndex = -1
private var formattedSpeedStringValueRegister = -1

// Change playback speed method.
private lateinit var setPlaybackSpeedMethodRef : WeakReference<MutableMethod>
private var setPlaybackSpeedMethodIndex = -1

internal lateinit var playerStatusMethodRef : WeakReference<MutableMethod>

val videoInformationPatch = bytecodePatch(
    description = "Hooks YouTube to get information about the current playing video.",
) {
    dependsOn(
        sharedExtensionPatch,
        videoIdPatch,
        playerResponseMethodHookPatch,
        conversionContextPatch,
        textComponentPatch,
        versionCheckPatch,
    )

    execute {
        val playerInitMethod = PlayerInitFingerprint.classDef.methods.first {
            MethodUtil.isConstructor(it)
        }

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

        playerStatusMethodRef = WeakReference(
            getPlayerStatusFingerprint(
                PlayerStatusEnumFingerprint.originalClassDef.type
            ).method
        )

        // region Inject call for video IDs.

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

        // endregion.

        // region Set the video time method.

        timeMethodRef = WeakReference(
            PlayerControllerSetTimeReferenceFingerprint
                .instructionMatches.first().getMethodCalled()
        )

        // endregion.

        // region Hook the user playback speed selection.

        SetPlaybackSpeedFormattedStringFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index

                formattedSpeedStringInsertMethodRef = WeakReference(this)
                formattedSpeedStringInsertIndex = index
                formattedSpeedStringValueRegister =
                    getInstruction<FiveRegisterInstruction>(index).registerC

                // Prevent duplicate hooking.
                replaceInstruction(index, "nop")
            }
        }

        val setPlaybackSpeedMethodReference = with(PlaybackSpeedOnItemClickFingerprint) {
            val index = instructionMatches.first().index

            legacySpeedSelectionInsertMethodRef = WeakReference(method)
            legacySpeedSelectionInsertIndex = index + 1
            legacySpeedSelectionValueRegister =
                method.getInstruction<TwoRegisterInstruction>(index).registerA

            val setPlaybackSpeedMethod =
                instructionMatches.last().instruction.getReference<MethodReference>()!!

            setPlaybackSpeedMethodRef = WeakReference(
                mutableClassDefBy(
                    setPlaybackSpeedMethod.definingClass
                ).methods.first { it.name == setPlaybackSpeedMethod.name }
            )

            setPlaybackSpeedMethodIndex = 0

            setPlaybackSpeedMethod
        }

        InitializePlaybackSpeedValuesFingerprint.let {
            it.classDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_PLAYBACK_SPEED_MENU_INTERFACE)

                // Player controller field does not exist in YouTube 20.x, add the field.
                val playerControllerField: MutableField

                methods.first { method ->
                    MethodUtil.isConstructor(method)
                }.apply {
                    val playerControllerClass = setPlaybackSpeedMethodReference.definingClass
                    val playerControllerClassIndex = parameterTypes.indexOfFirst { parameterType ->
                        parameterType == playerControllerClass
                    }
                    if (playerControllerClassIndex < 0) {
                        throw PatchException("Could not find player controller index")
                    }
                    playerControllerField = ImmutableField(
                        type,
                        "patch_playerController",
                        playerControllerClass,
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        null,
                    ).toMutable()

                    instanceFields.add(playerControllerField)

                    val playerControllerClassRegister =
                        implementation!!.registerCount - parameters.size + playerControllerClassIndex

                    addInstructions(
                        2,
                        """
                            invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setPlaybackSpeedMenu($EXTENSION_PLAYBACK_SPEED_MENU_INTERFACE)V                            
                            iput-object v$playerControllerClassRegister, p0, $playerControllerField
                        """
                    )
                }

                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_setSpeed",
                        listOf(
                            ImmutableMethodParameter("F", null, null)
                        ),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-object v0, p0, $playerControllerField
                                
                                # Check if the player controller class is null.
                                if-eqz v0, :ignore
                                invoke-virtual { v0, p1 }, $setPlaybackSpeedMethodReference
                                
                                :ignore
                                return-void
                            """
                        )
                    }
                )
            }
        }

        // endregion.

        // region Handle new playback speed menu.

        PlaybackSpeedMenuSpeedChangedFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index

                speedSelectionInsertMethodRef = WeakReference(this)
                speedSelectionInsertIndex = index + 1
                speedSelectionValueRegister = getInstruction<TwoRegisterInstruction>(index).registerA
            }
        }

        hookSpannableString(
            classDescriptor = EXTENSION_CLASS,
            methodName = "onNativePlaybackSpeedPanelLoaded"
        )

        // endregion.

        // region Fix wrong video quality labels.

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

        // endregion.

        // region Detect video quality changes and override the current quality.

        val (onQualityItemClickListenerField, setQualityField) =
            with(SetVideoQualityFingerprint) {
                Pair(
                    instructionMatches[0].instruction.getReference<FieldReference>()!!,
                    instructionMatches[1].instruction.getReference<FieldReference>()!!
                )
            }

        mutableClassDefBy(setQualityField.type).apply {
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
                iget-object v0, p0, $onQualityItemClickListenerField
                iget-object v0, v0, $setQualityField
                
                invoke-static { p1, v0, p2 }, $EXTENSION_CLASS->setVideoQuality([${EXTENSION_VIDEO_QUALITY_INTERFACE}${EXTENSION_VIDEO_QUALITY_MENU_INTERFACE}I)I
                move-result p2
            """
        )

        // endregion.

        // region Set channel information.

        ChannelInformationFingerprint.let {
            val matches = it.matchAll(2 .. 3)
            val playerResponseType = matches.first().method.parameterTypes.first().toString()

            val channelIdMethodCall = getChannelIdFingerprint(playerResponseType).instructionMatches.first()
                .instruction.getReference<MethodReference>()!!

            val channelNameMethodCall = getChannelNameFingerprint(playerResponseType).instructionMatches.last()
                .instruction.getReference<MethodReference>()!!

            it.classDef.apply {
                val helperMethod = ImmutableMethod(
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
                            
                            invoke-interface { p1 }, $channelNameMethodCall
                            move-result-object v0
                            invoke-static { v0 }, $EXTENSION_CLASS->setChannelName(Ljava/lang/String;)V
                            
                            return-void
                        """.toInstructions(),
                        null,
                        null
                    )
                ).toMutable()

                methods.add(helperMethod)

                matches.forEach { match ->
                    match.method.addInstruction(
                        0,
                        "invoke-direct { p0, p1 }, $helperMethod"
                    )
                }
            }
        }

        // endregion.

        // region Inject call for video information and playback speed.

        onCreateHook(EXTENSION_CLASS, "initialize")
        videoSpeedChangedHook(EXTENSION_CLASS, "videoSpeedChanged")
        userSelectedPlaybackSpeedHook(EXTENSION_CLASS, "userSelectedPlaybackSpeed")

        // endregion.

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
    formattedSpeedStringInsertMethodRef.get()!!.addInstruction(
        formattedSpeedStringInsertIndex++,
        "invoke-static { v$formattedSpeedStringValueRegister }, $targetMethodClass->$targetMethodName(F)V"
    )

    legacySpeedSelectionInsertMethodRef.get()!!.addInstruction(
        legacySpeedSelectionInsertIndex++,
        "invoke-static { v$legacySpeedSelectionValueRegister }, $targetMethodClass->$targetMethodName(F)V"
    )

    speedSelectionInsertMethodRef.get()!!.addInstructionsAtControlFlowLabel(
        speedSelectionInsertIndex++,
        "invoke-static { v$speedSelectionValueRegister }, $targetMethodClass->$targetMethodName(F)V"
    )
}

fun playerStatusHook(targetMethodClass: String, targetMethodName: String) {
    playerStatusMethodRef.get()!!.apply {
        val insertIndex = indexOfFirstInstructionOrThrow(Opcode.SGET_OBJECT) + 1
        addInstruction(
            insertIndex,
            "invoke-static/range { p1 .. p1 }, $targetMethodClass->$targetMethodName(Ljava/lang/Enum;)V"
        )
    }
}
