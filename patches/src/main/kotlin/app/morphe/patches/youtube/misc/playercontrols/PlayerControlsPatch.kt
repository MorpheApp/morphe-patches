/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.playercontrols

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.Document
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_25_or_greater
import app.morphe.patches.youtube.misc.playservice.is_19_35_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_19_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_20_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_03_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.copyXmlNode
import app.morphe.util.findElementByAttributeValue
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.returnEarly
import app.morphe.util.returnLate
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Node
import java.lang.ref.WeakReference

/**
 * Add a new top to the bottom of the YouTube player.
 */
@Suppress("KDocUnresolvedReference")
// Internal until this is modified to work with any patch (and not just SponsorBlock).
internal lateinit var addTopControl: (String, String, String) -> Unit
    private set

private var insertElementId = "@id/player_video_heading"

/**
 * Add a new bottom to the bottom of the YouTube player.
 */
@Suppress("KDocUnresolvedReference")
lateinit var addBottomControl: (String) -> Unit
    private set

internal val playerControlsResourcePatch = resourcePatch {
    /**
     * The element to the left of the element being added.
     */
    /**
     * The element to the left of the element being added.
     */
    var bottomLastLeftOf = "@id/fullscreen_button"

    lateinit var bottomTargetDocument: Document

    execute {
        val targetResourceName = "youtube_controls_bottom_ui_container.xml"

        bottomTargetDocument = document("res/layout/$targetResourceName")

        val bottomTargetElement: Node = bottomTargetDocument.getElementsByTagName(
            "android.support.constraint.ConstraintLayout",
        ).item(0)

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

        addBottomControl = { resourceDirectoryName ->
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

/**
 * Injects the code to initialize the controls.
 * @param descriptor The descriptor of the method which should be called.
 */
internal fun initializeTopControl(descriptor: String) {
    inflateTopControlMethodRef.get()!!.addInstruction(
        inflateTopControlInsertIndex++,
        "invoke-static { v$inflateTopControlRegister }, $descriptor->initializeButton(Landroid/view/View;)V",
    )
}

/**
 * Injects the code to initialize the controls.
 * @param descriptor The descriptor of the method which should be called.
 */
fun initializeBottomControl(descriptor: String) {
    inflateBottomControlMethodRef.get()!!.addInstruction(
        inflateBottomControlInsertIndex++,
        "invoke-static { v$inflateBottomControlRegister }, $descriptor->initializeButton(Landroid/view/View;)V",
    )
}

/**
 * Injects the code to change the visibility of controls.
 * @param descriptor The descriptor of the method which should be called.
 */
fun injectVisibilityCheckCall(descriptor: String) {
    if (!visibilityImmediateCallbacksExistModified) {
        visibilityImmediateCallbacksExistModified = true
        visibilityImmediateCallbacksExistMethodRef.get()!!.returnEarly(true)
    }

    visibilityMethodRef.get()!!.addInstruction(
        visibilityInsertIndex++,
        "invoke-static { p1 , p2 }, $descriptor->setVisibility(ZZ)V",
    )

    visibilityImmediateMethodRef.get()!!.addInstruction(
        visibilityImmediateInsertIndex++,
        "invoke-static { p0 }, $descriptor->setVisibilityImmediate(Z)V",
    )

    // Patch works without this hook, but it is needed to use the correct fade out animation
    // duration when tapping the overlay to dismiss.
    visibilityNegatedImmediateMethodRef.get()!!.addInstruction(
        visibilityNegatedImmediateInsertIndex++,
        "invoke-static { }, $descriptor->setVisibilityNegatedImmediate()V",
    )
}

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/PlayerControlsPatch;"

private lateinit var inflateTopControlMethodRef : WeakReference<MutableMethod>
private var inflateTopControlInsertIndex = -1
private var inflateTopControlRegister = -1

private lateinit var inflateBottomControlMethodRef : WeakReference<MutableMethod>
private var inflateBottomControlInsertIndex = -1
private var inflateBottomControlRegister = -1

private lateinit var visibilityImmediateCallbacksExistMethodRef : WeakReference<MutableMethod>
private var visibilityImmediateCallbacksExistModified = false

private lateinit var visibilityMethodRef : WeakReference<MutableMethod>
private var visibilityInsertIndex = 0

private lateinit var visibilityImmediateMethodRef : WeakReference<MutableMethod>
private var visibilityImmediateInsertIndex = 0

private lateinit var visibilityNegatedImmediateMethodRef : WeakReference<MutableMethod>
private var visibilityNegatedImmediateInsertIndex = 0

val playerControlsPatch = bytecodePatch(
    description = "Manages the code for the player controls of the YouTube player.",
) {
    dependsOn(
        playerControlsResourcePatch,
        sharedExtensionPatch,
        resourceMappingPatch, // Used by fingerprints.
        playerControlsOverlayVisibilityPatch,
        versionCheckPatch
    )

    execute {
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

        visibilityMethodRef = WeakReference(
            ControlsOverlayVisibilityFingerprint.match(
                PlayerTopControlsInflateFingerprint.originalClassDef,
            ).method
        )

        // Hook the fullscreen close button. Used to fix visibility
        // when seeking and other situations.
        OverlayViewInflateFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->setFullscreenCloseButton(Landroid/widget/ImageView;)V",
                )
            }
        }

        visibilityImmediateCallbacksExistMethodRef = WeakReference(
            PlayerControlsExtensionHookListenersExistFingerprint.method
        )
        visibilityImmediateMethodRef = WeakReference(PlayerControlsExtensionHookFingerprint.method)

        MotionEventFingerprint.match(YoutubeControlsOverlayFingerprint.originalClassDef).let {
            visibilityNegatedImmediateMethodRef = WeakReference(it.method)
            visibilityNegatedImmediateInsertIndex = it.instructionMatches.first().index + 1
        }

        // A/B test for a slightly different bottom overlay controls,
        // that uses layout file youtube_video_exploder_controls_bottom_ui_container.xml
        // The change to support this is simple and only requires adding buttons to both layout files,
        // but for now force this different layout off since it's still an experimental test.
        if (is_19_35_or_greater) {
            PlayerBottomControlsExploderFeatureFlagFingerprint.method.returnLate(false)
        }

        // A/B test of different top overlay controls. Two different layouts can be used:
        // youtube_cf_navigation_improvement_controls_layout.xml
        // youtube_cf_minimal_impact_controls_layout.xml
        //
        // Flag was removed in 20.19+
        if (is_19_25_or_greater && !is_20_19_or_greater) {
            PlayerTopControlsExperimentalLayoutFeatureFlagFingerprint.method.apply {
                val index = indexOfFirstInstructionOrThrow(Opcode.MOVE_RESULT_OBJECT)
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(index + 1, "const-string v$register, \"default\"")
            }
        }

        // Turn off a/b tests of ugly player buttons that don't match the style of custom player buttons.
        if (is_20_20_or_greater) {
            PlayerControlsFullscreenLargeButtonsFeatureFlagFingerprint.method.returnLate(false)

            if (is_20_28_or_greater) {
                PlayerControlsLargeOverlayButtonsFeatureFlagFingerprint.method.returnLate(false)
            }

            if (is_20_30_or_greater) {
                PlayerControlsButtonStrokeFeatureFlagFingerprint.method.returnLate(false)
            }
        }

        if (is_21_03_or_greater) {
            // If enabled it can show a black gradient on lower part of screen in fullscreen mode.
            // This override may not be needed if the new bold player overlay icons are in use.
            PlayerOverlayOpacityGradientFeatureFlagFingerprint.method.returnLate(false)
        }
    }
}
