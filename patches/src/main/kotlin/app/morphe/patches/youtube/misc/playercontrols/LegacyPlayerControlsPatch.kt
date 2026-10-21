/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/954
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.playercontrols

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.Document
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoDrawableColorHook
import app.morphe.patches.shared.misc.litho.context.conversionContextClassDef
import app.morphe.patches.shared.misc.litho.context.conversionContextPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.spans.addSpanFilter
import app.morphe.patches.shared.misc.spans.inclusiveSpanPatch
import app.morphe.patches.youtube.misc.addon.EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR
import app.morphe.patches.youtube.misc.addon.LEGACY_BUTTON_SLOTS_RESOURCE_DIRECTORY
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_40_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_04_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_08_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_29_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_36_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_38_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_41_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.copyXmlNode
import app.morphe.util.findElementByAttributeValue
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.insertLiteralOverride
import app.morphe.util.matchAllMethodIndicesForEach
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Node
import java.lang.ref.WeakReference

/**
 * Add a new top to the bottom of the YouTube player.
 */
@Suppress("KDocUnresolvedReference")
internal lateinit var addTopControl: (String, String, String) -> Unit
    private set

private var insertElementId = "@id/player_video_heading"

/**
 * Add a new bottom to the bottom of the YouTube player.
 */
@Suppress("KDocUnresolvedReference")
lateinit var addLegacyBottomControl: (String) -> Unit
    private set

internal val legacyPlayerControlsResourcePatch = resourcePatch {
    /**
     * The element to the left of the element being added.
     */
    /**
     * The element to the left of the element being added.
     */
    var bottomLastLeftOf = "@id/fullscreen_button"

    lateinit var bottomTargetDocument: Document

    execute {
        bottomTargetDocument = document("res/layout/youtube_controls_bottom_ui_container.xml")

        val bottomTargetElementList = bottomTargetDocument
            .getElementsByTagName("android.support.constraint.ConstraintLayout")
            .takeIf { it.length > 0 }
            ?: bottomTargetDocument.getElementsByTagName("androidx.constraintlayout.widget.ConstraintLayout")
        val bottomTargetElement = bottomTargetElementList.item(0)


        val bottomTargetDocumentChildNodes = bottomTargetDocument.childNodes
        var bottomInsertBeforeNode: Node = bottomTargetDocumentChildNodes.findElementByAttributeValueOrThrow(
            "android:inflatedId",
            bottomLastLeftOf,
        )

        // Modify the fullscreen button stub attributes for correct positioning.
        // The fullscreen button is lower than the Morphe buttons (unpatched app bug).
        // Issue is only present in later app targets, but this change seems to
        // do no harm to earlier releases.
        bottomTargetDocumentChildNodes.findElementByAttributeValueOrThrow(
            "android:id",
            "@id/youtube_controls_fullscreen_button_stub"
        ).apply {
            setAttribute("android:layout_marginBottom", "6.0dip")
            setAttribute("android:layout_width", "48.0dip")
        }

        addTopControl = { resourceDirectoryName, startElementId, endElementId ->
            val resourceFileName = "host/layout/youtube_controls_layout.xml"
            val hostingResourceStream = inputStreamFromBundledResource(
                resourceDirectoryName,
                resourceFileName,
            ) ?: throw PatchException("Could not find $resourceFileName")

            val document = document("res/layout/youtube_controls_layout.xml")
            val androidId = "android:id"
            val androidLayoutToStartOf = "android:layout_toStartOf"

            "RelativeLayout".copyXmlNode(
                document(hostingResourceStream),
                document,
            ).use {
                val insertElement = document.childNodes.findElementByAttributeValueOrThrow(
                    androidId,
                    insertElementId,
                )
                val endElement = document.childNodes.findElementByAttributeValueOrThrow(
                    androidId,
                    endElementId,
                )
                val insertElementLayoutToStartOf =
                    insertElement.attributes.getNamedItem(androidLayoutToStartOf).nodeValue!!

                insertElement.attributes.getNamedItem(androidLayoutToStartOf).nodeValue =
                    startElementId
                endElement.attributes.getNamedItem(androidLayoutToStartOf).nodeValue =
                    insertElementLayoutToStartOf

                insertElementId = endElementId
            }
        }

        addLegacyBottomControl = { resourceDirectoryName ->
            val resourceFileName = "host/layout/youtube_controls_bottom_ui_container.xml"
            val sourceDocument = document(
                inputStreamFromBundledResource(resourceDirectoryName, resourceFileName)
                    ?: throw PatchException("Could not find $resourceFileName"),
            )

            val sourceElements = sourceDocument.getElementsByTagName(
                "android.support.constraint.ConstraintLayout",
            ).item(0).childNodes

            // Copy the patch layout XML into the target layout file.
            for (index in sourceElements.length - 1 downTo 1) {
                val element = sourceElements.item(index).cloneNode(true)

                // If the element has no attributes there's no point adding it to the destination.
                if (!element.hasAttributes()) continue

                element.attributes.getNamedItem("yt:layout_constraintRight_toLeftOf").nodeValue = bottomLastLeftOf
                bottomLastLeftOf = element.attributes.getNamedItem("android:id").nodeValue

                bottomTargetDocument.adoptNode(element)
                // Elements do not need to be added in the layout order since a layout constraint is used,
                // but in order is easier to make sense of while debugging.
                bottomTargetElement.insertBefore(element, bottomInsertBeforeNode)
                bottomInsertBeforeNode = element
            }

            sourceDocument.close()
        }

        // Button slots of add-on patch bundles, which cannot add a button of their own.
        addLegacyBottomControl(LEGACY_BUTTON_SLOTS_RESOURCE_DIRECTORY)
    }

    finalize {
        val childNodes = bottomTargetDocument.childNodes

        arrayOf(
            "@id/bottom_end_container",
            "@id/multiview_button",
        ).forEach {
            childNodes.findElementByAttributeValue(
                "android:id",
                it,
            )?.setAttribute("yt:layout_constraintRight_toLeftOf", bottomLastLeftOf)
        }

        bottomTargetDocument.close()
    }
}

private var newPlayerControlsOverride = false

context(patchContext: BytecodePatchContext)
internal fun disableNewPlayerControlsFeatureFlag() {
    if (newPlayerControlsOverride) return
    newPlayerControlsOverride = true

    if (is_20_31_or_greater) {
        PlayerSeekbarFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->forcePlayerSeekbar(Z)Z"
            )
        }
    }

    if (is_21_04_or_greater && !is_21_41_or_greater) {
        val flagMethod = NewPlayerOverlaysFeatureFlagFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->disableNewPlayerOverlays(Z)Z"
            )
            methodCall(it.method)
        }

        if (is_21_36_or_greater) {
            // With the flag off the autonav end screen is laid out over the miniplayer
            // as if it was the full player, so it is given the value the app has.
            var appliedChanges = false
            AutonavEndscreenOverlayFingerprint.classDef.methods.forEach { method ->
                method.findInstructionIndicesReversed(flagMethod).forEach { index ->
                    val register = method.getInstruction<OneRegisterInstruction>(index + 1).registerA
                    method.addInstructions(
                        index + 2,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->getOriginalNewPlayerOverlays(Z)Z
                            move-result v$register
                        """
                    )
                    appliedChanges = true
                }
            }
            if (!appliedChanges) {
                throw PatchException("Did not apply autonav endscreen changes")
            }
        }
    }
}

/**
 * Injects the code to initialize the controls.
 * @param descriptor The descriptor of the method which should be called.
 */
internal fun initializeTopControl(descriptor: String) {
    inflateTopControlMethodRef.get()!!.addInstruction(
        inflateTopControlInsertIndex++,
        "invoke-static { v$inflateTopControlRegister }, $descriptor->" +
                "initializeLegacyButton(Landroid/view/View;)V",
    )
}

/**
 * Injects the code to initialize the controls.
 * @param descriptor The descriptor of the method which should be called.
 */
fun initializeLegacyBottomControl(descriptor: String) {
    inflateBottomControlMethodRef.get()!!.addInstruction(
        inflateBottomControlInsertIndex++,
        "invoke-static { v$inflateBottomControlRegister }, $descriptor->" +
                "initializeLegacyButton(Landroid/view/View;)V",
    )
}

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/LegacyPlayerControlsPatch;"

private const val METAPANEL_TEXT_COLOR_FILTER =
    "Lapp/morphe/extension/youtube/patches/spans/MetapanelTextColorFilter;"

private lateinit var inflateTopControlMethodRef : WeakReference<MutableMethod>
private var inflateTopControlInsertIndex = -1
private var inflateTopControlRegister = -1

private lateinit var inflateBottomControlMethodRef : WeakReference<MutableMethod>
private var inflateBottomControlInsertIndex = -1
private var inflateBottomControlRegister = -1

val legacyPlayerControlsPatch = bytecodePatch(
    description = "Manages the code for the player controls of the YouTube player.",
) {
    dependsOn(
        legacyPlayerControlsResourcePatch,
        sharedExtensionPatch,
        playerControlsOverlayVisibilityPatch,
        versionCheckPatch,
        settingsPatch,
        inclusiveSpanPatch,
        conversionContextPatch,
        lithoColorHookPatch { is_21_30_or_greater }
    )

    execute {
        if (is_20_31_or_greater && !is_21_36_or_greater) {
            PreferenceScreen.PLAYER.addPreferences(
                SwitchPreference("morphe_restore_old_player_buttons", summary = true)
            )
        }

        if (is_21_36_or_greater) {
            disableNewPlayerControlsFeatureFlag()
        }

        if (is_21_08_or_greater) {
            // The text of the video metadata panel can have the dark theme colors
            // of the elevated player, when the elevated player is turned off.
            addSpanFilter(METAPANEL_TEXT_COLOR_FILTER)

            // The icons can also have the dark theme tint. The tint is read when the image is loaded,
            // without the conversion context, so the image component registers its images
            // with their conversion context when it prepares them.
            val tintColorReference = ElementsImageTintFingerprint.instructionMatches.first()
                .getInstruction<ReferenceInstruction>().getReference<MethodReference>()!!
            val imageType = tintColorReference.parameterTypes.single().toString()

            ElementsImageComponentFingerprint.classDef.apply {
                val conversionContextField = fields.single { it.type == conversionContextClassDef.type }
                val imageFields = fields.filter { it.type == imageType }
                if (imageFields.isEmpty()) {
                    throw PatchException("No image fields in image component: $type")
                }

                methods.filter { method ->
                    method.returnType == "V" && method.parameterTypes.size == 3
                            && !AccessFlags.STATIC.isSet(method.accessFlags)
                            && method.instructionsOrNull?.any { instruction ->
                        instruction.opcode == Opcode.INVOKE_STATIC_RANGE
                                && instruction.getReference<MethodReference>()!!.parameterTypes
                            .contains("Landroid/content/Context;")
                    } == true
                }.also { methods ->
                    if (methods.size != 1) {
                        throw PatchException("Unexpected image component prepare methods: $methods")
                    }
                }.first().apply {
                    if (p0Register < 3) {
                        throw PatchException("Not enough registers: $this")
                    }

                    // The registers are not used yet at the start of the method.
                    addInstructions(
                        0,
                        """
                            move-object/from16 v0, p0
                            iget-object v1, v0, $conversionContextField
                        """ + imageFields.joinToString("") { field ->
                            """
                                iget-object v2, v0, $field
                                invoke-static { v1, v2 }, $EXTENSION_CLASS->registerImage(Ljava/lang/Object;Ljava/lang/Object;)V
                            """
                        }
                    )
                }
            }

            tintColorReference.getMutableMethod().apply {
                findInstructionIndicesReversed(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->getImageTintColor(I)I
                            move-result v$register
                        """
                    )
                }
                // The image parameter can be reused after it's read, so the image is handed at the start.
                addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setTintImage(Ljava/lang/Object;)V"
                )
            }

            // The page indicator of the comments button can also have the dark theme colors.
            lithoDrawableColorHook("$EXTENSION_CLASS->getBackgroundColor(Landroid/graphics/drawable/Drawable;I)I")

            // The elevated player shows the video metadata without a background over the
            // ambient mode colors, so the light theme text can not be read.
            // The metadata panel also has its own forced dark and light themes, which do not depend
            // on the elevated player. With the forced dark theme and the elevated player off, the
            // white text is drawn on the light background of the watch page, so turn all of them off
            // and the metadata panel uses the app theme.
            ElevatedPlayerFeatureFlagFingerprint.matchAll().forEach {
                // Overrides every read of the flag in the method.
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->useElevatedPlayer(Z)Z"
                )
            }

            arrayOf(
                MetapanelForceDarkThemeFeatureFlagFingerprint to is_21_29_or_greater,
                MetapanelForceLightThemeFeatureFlagFingerprint to is_21_38_or_greater
            ).forEach { (fingerprint, applyChanges) ->
                if (applyChanges) {
                    // A constant override only replaces the read at its index, so every read is overridden.
                    fingerprint.matchAllMethodIndicesForEach { index ->
                        insertLiteralOverride(index, false)
                    }
                }
            }
        }

        // Override flags that interfere with old player icons override.
        arrayOf(
            PlayerCommentTeaserFeatureFlagFingerprint to is_21_15_or_greater,
            RecycleViewScrollingFlagFingerprint to is_21_15_or_greater
        ).forEach { (fingerprint, applyChanges) ->
            if (applyChanges) {
                fingerprint.matchAll().forEach {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS->" +
                                "allowModernPlayerLayoutFlags(Z)Z"
                    )
                }
            }
        }

        fun overrideExploderLayout(fingerprint: Fingerprint) {
            fingerprint.matchAll().forEach {
                // 21.30+ inlines the flag lookup and must patch ~6 places.
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->" +
                            "usePlayerBottomControlsExploderLayout(Z)Z"
                )
            }
        }

        // A/B test for a slightly different bottom overlay controls,
        // that uses layout file youtube_video_exploder_controls_bottom_ui_container.xml
        // The change to support this is simple and only requires adding buttons to both layout files,
        // but for now force this different layout off since it's still an experimental test.
        overrideExploderLayout(PlayerBottomControlsExploderFeatureFlagFingerprint)

        // Turn off a/b tests of ugly player buttons that don't match the style of custom player buttons.
        if (!is_21_36_or_greater) {
            overrideExploderLayout(PlayerControlsFullscreenLargeButtonsFeatureFlagFingerprint)
        }

        if (is_20_28_or_greater) {
            overrideExploderLayout(PlayerControlsLargeOverlayButtonsFeatureFlagFingerprint)
        }

        if (is_20_30_or_greater) {
            overrideExploderLayout(PlayerControlsButtonStrokeFeatureFlagFingerprint)
        }

        if (is_20_40_or_greater) {
            // Clear bottom gradient.
            // This may not be needed if the new bold player overlay icons are in use.
            PlayerBottomGradientScrimFingerprint.let {
                it.method.apply {
                    val gradientFieldIndex = it.instructionMatches.last().index
                    val gradientFieldRegister =
                        getInstruction<TwoRegisterInstruction>(gradientFieldIndex).registerA

                    val gradientViewIndex = it.instructionMatches[1].index
                    val gradientViewRegister =
                        getInstruction<OneRegisterInstruction>(gradientViewIndex).registerA

                    val free = findFreeRegister(gradientFieldIndex, gradientFieldRegister)

                    // This field is Nullable, and if null, the bottom gradient is not set.
                    addInstructionsWithLabels(
                        gradientFieldIndex,
                        """
                            invoke-static { }, $EXTENSION_CLASS->useNullBottomGradient()Z
                            move-result v$free
                            if-eqz v$free, :show
                            const/4 v$gradientFieldRegister, 0x0
                            :show
                            nop
                        """
                    )

                    // Make the bottom gradient transparent and hide it.
                    addInstruction(
                        gradientViewIndex + 1,
                        "invoke-static { v$gradientViewRegister }, " +
                                "$EXTENSION_CLASS->hideBottomGradientScrim(Landroid/widget/ImageView;)V"
                    )
                }
            }
        }

        // Must get methods after overriding flags,
        // since flag overrides can add instructions and break the indexes used here.
        PlayerBottomControlsInflateFingerprint.let {
            it.method.apply {
                inflateBottomControlMethodRef = WeakReference(this)

                val inflateReturnObjectIndex = it.instructionMatches.last().index
                inflateBottomControlRegister = getInstruction<OneRegisterInstruction>(inflateReturnObjectIndex).registerA
                inflateBottomControlInsertIndex = inflateReturnObjectIndex + 1
            }
        }

        PlayerTopControlsInflateFingerprint.let {
            it.method.apply {
                inflateTopControlMethodRef = WeakReference(this)

                val inflateReturnObjectIndex = it.instructionMatches.last().index
                inflateTopControlRegister = getInstruction<OneRegisterInstruction>(inflateReturnObjectIndex).registerA
                inflateTopControlInsertIndex = inflateReturnObjectIndex + 1
            }
        }

        if (is_21_05_or_greater) {
            ModernPlayerTopControlsFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->useModernPlayerTopControls(Z)Z"
                )
            }
        }

        // Buttons of add-on patch bundles, which cannot add a button of their own.
        initializeLegacyBottomControl(EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR)
    }
}
