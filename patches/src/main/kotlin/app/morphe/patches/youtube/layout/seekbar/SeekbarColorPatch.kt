package app.morphe.patches.youtube.layout.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoColorOverrideHook
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_34_or_greater
import app.morphe.patches.youtube.misc.playservice.is_19_49_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_34_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.MainActivityOnCreateFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/theme/SeekbarColorPatch;"

val seekbarColorPatch = bytecodePatch(
    description = "Hide or set a custom seekbar color",
) {
    dependsOn(
        sharedExtensionPatch,
        lithoColorHookPatch,
        resourceMappingPatch,
        versionCheckPatch
    )

    execute {
        fun MutableMethod.addColorChangeInstructions(index: Int) {
            insertLiteralOverride(
                index,
                "$EXTENSION_CLASS_DESCRIPTOR->getVideoPlayerSeekbarColor(I)I"
            )
        }

        PlayerSeekbarColorFingerprint.let {
            it.method.apply {
                addColorChangeInstructions(it.instructionMatches.last().index)
                addColorChangeInstructions(it.instructionMatches.first().index)
            }
        }

        ShortsSeekbarColorFingerprint.let {
            it.method.addColorChangeInstructions(it.instructionMatches.first().index)
        }

        SetSeekbarClickedColorFingerprint.originalMethod.let {
            val setColorMethodIndex = SetSeekbarClickedColorFingerprint.instructionMatches.first().index + 1

            navigate(it).to(setColorMethodIndex).stop().apply {
                val colorRegister = getInstruction<TwoRegisterInstruction>(0).registerA
                addInstructions(
                    0,
                    """
                        invoke-static { v$colorRegister }, $EXTENSION_CLASS_DESCRIPTOR->getVideoPlayerSeekbarClickedColor(I)I
                        move-result v$colorRegister
                    """
                )
            }
        }

        lithoColorOverrideHook(EXTENSION_CLASS_DESCRIPTOR, "getLithoColor")

        // 19.25+ changes

        var handleBarColorFingerprints = mutableListOf<Fingerprint>(PlayerSeekbarHandle1ColorFingerprint)
        if (!is_20_34_or_greater) {
            handleBarColorFingerprints += PlayerSeekbarHandle2ColorFingerprint
        }
        handleBarColorFingerprints.forEach {
            it.method.addColorChangeInstructions(it.instructionMatches.last().index)
        }

        // If hiding feed seekbar thumbnails, then turn off the cairo gradient
        // of the watch history menu items as they use the same gradient as the
        // player and there is no easy way to distinguish which to use a transparent color.
        if (is_19_34_or_greater) {
            WatchHistoryMenuUseProgressDrawableFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->showWatchHistoryProgressDrawable(Z)Z
                            move-result v$register            
                        """
                    )
                }
            }
        }

        LithoLinearGradientFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p4 .. p5 },  $EXTENSION_CLASS_DESCRIPTOR->getLithoLinearGradient([I[F)[I
                move-result-object p4   
            """
        )

        val PlayerFingerprint: Fingerprint
        val checkGradientCoordinates: Boolean
        if (is_19_49_or_greater) {
            PlayerFingerprint = PlayerLinearGradientFingerprint
            checkGradientCoordinates = true
        } else {
            PlayerFingerprint = PlayerLinearGradientLegacyFingerprint
            checkGradientCoordinates = false
        }

        PlayerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    if (checkGradientCoordinates) {
                        """
                           invoke-static { v$register, p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->getPlayerLinearGradient([III)[I
                           move-result-object v$register
                        """
                    } else {
                        """
                           invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getPlayerLinearGradient([I)[I
                           move-result-object v$register
                        """
                    }
                )
            }
        }

        // region apply seekbar custom color to splash screen animation.

        if (!is_19_34_or_greater) {
            return@execute // 19.25 does not have a cairo launch animation.
        }

        // Hook the splash animation to set the a seekbar color.
        MainActivityOnCreateFingerprint.method.apply {
            val setAnimationIntMethodName =
                LottieAnimationViewSetAnimationIntFingerprint.originalMethod.name

            findInstructionIndicesReversedOrThrow {
                val reference = getReference<MethodReference>()
                reference?.definingClass == LOTTIE_ANIMATION_VIEW_CLASS_TYPE
                        && reference.name == setAnimationIntMethodName
            }.forEach { index ->
                val instruction = getInstruction<FiveRegisterInstruction>(index)

                replaceInstruction(
                    index,
                    "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->setSplashAnimationLottie(Lcom/airbnb/lottie/LottieAnimationView;I)V"
                )
            }
        }

        // Add non obfuscated method aliases for `setAnimation(int)`
        // and `setAnimation(InputStream, String)` so extension code can call them.
        LottieAnimationViewSetAnimationIntFingerprint.classDef.methods.apply {
            val addedMethodName = "patch_setAnimation"
            val setAnimationIntName = LottieAnimationViewSetAnimationIntFingerprint
                .originalMethod.name

            add(ImmutableMethod(
                LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
                addedMethodName,
                listOf(ImmutableMethodParameter("I", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    """
                        invoke-virtual { p0, p1 }, Lcom/airbnb/lottie/LottieAnimationView;->$setAnimationIntName(I)V
                        return-void
                    """
                )
            })

            val factoryStreamClass: CharSequence
            val factoryStreamName: CharSequence
            val factoryStreamReturnType: CharSequence
            LottieCompositionFactoryFromJsonInputStreamFingerprint.match(
                LottieCompositionFactoryZipFingerprint.originalClassDef
            ).originalMethod.apply {
                factoryStreamClass = definingClass
                factoryStreamName = name
                factoryStreamReturnType = returnType
            }

            val LottieAnimationViewSetAnimationStreamFingerprint = Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                returnType = "V",
                parameters = listOf(factoryStreamReturnType.toString()),
                custom = { _, classDef ->
                    classDef.type == LottieAnimationViewSetAnimationIntFingerprint.originalClassDef.type
                }
            )
            val setAnimationStreamName = LottieAnimationViewSetAnimationStreamFingerprint
                .originalMethod.name

            add(ImmutableMethod(
                LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
                addedMethodName,
                listOf(
                    ImmutableMethodParameter("Ljava/io/InputStream;", null, null),
                    ImmutableMethodParameter("Ljava/lang/String;", null, null)
                ),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructions(
                    """
                        invoke-static { p1, p2 }, $factoryStreamClass->$factoryStreamName(Ljava/io/InputStream;Ljava/lang/String;)$factoryStreamReturnType
                        move-result-object v0
                        invoke-virtual { p0, v0}, Lcom/airbnb/lottie/LottieAnimationView;->$setAnimationStreamName($factoryStreamReturnType)V
                        return-void
                    """
                )
            })
        }

        // endregion
    }
}
