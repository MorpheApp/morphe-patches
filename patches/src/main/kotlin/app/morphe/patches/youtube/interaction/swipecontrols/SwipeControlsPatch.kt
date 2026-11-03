/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.swipecontrols

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.interaction.seekbar.SeekbarUpdatePointFingerprint
import app.morphe.patches.youtube.interaction.seekbar.SlideSeekbarGetViewControllerFingerprint
import app.morphe.patches.youtube.interaction.seekbar.SlideSeekbarHandlerConstructorFingerprint
import app.morphe.patches.youtube.layout.player.icons.copyPlayerIcons
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_34_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeMainActivityConstructorFingerprint
import app.morphe.patches.youtube.video.audio.soundBoostPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.cloneMutable
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.transformMethods
import app.morphe.util.traverseClassHierarchy
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/swipecontrols/SwipeControlsHostActivity;"

private const val EXTENSION_SEEK_CLASS =
    "Lapp/morphe/extension/youtube/swipecontrols/controller/SwipeSeekController;"

private const val EXTENSION_SCRUBBER_INTERFACE =
    "Lapp/morphe/extension/youtube/swipecontrols/controller/SeekbarScrubber;"

private val swipeControlsResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        versionCheckPatch,
        soundBoostPatch
    )

    execute {
        // If fullscreen swipe is enabled in newer versions the app can crash.
        // It likely is caused by conflicting experimental flags that are never enabled together.
        // Flag was completely removed in 20.34+
        if (!is_20_34_or_greater) {
            PreferenceScreen.SWIPE_CONTROLS.addPreferences(
                SwitchPreference("morphe_swipe_change_video", summary = true)
            )
        }

        PreferenceScreen.SWIPE_CONTROLS.addPreferences(
            ListPreference(
                "morphe_swipe_left_zone",
                entriesKey = "morphe_swipe_zone_action_entries",
                entryValuesKey = "morphe_swipe_zone_action_entry_values"
            ),
            ListPreference(
                "morphe_swipe_right_zone",
                entriesKey = "morphe_swipe_zone_action_entries",
                entryValuesKey = "morphe_swipe_zone_action_entry_values"
            ),
            ListPreference(
                "morphe_swipe_top_zone",
                entriesKey = "morphe_swipe_zone_action_entries",
                entryValuesKey = "morphe_swipe_zone_action_entry_values"
            ),
            SwitchPreference("morphe_swipe_seek", summary = true),
            NonInteractivePreference(
                key = "morphe_swipe_seek_sensitivity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_swipe_zone_width",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_swipe_speed_zone_height",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_swipe_zone_preview",
                summaryKey = null,
                tag = "app.morphe.extension.youtube.settings.preference.SwipeZonePreference"
            ),
            NonInteractivePreference(
                key = "morphe_swipe_brightness_sensitivity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_swipe_volume_distance",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            ListPreference(
                "morphe_swipe_volume_steps",
                tag = "app.morphe.extension.youtube.settings.preference.SwipeVolumeStepsPreference"
            ),
            SwitchPreference("morphe_volume_boost", summary = true),
            NonInteractivePreference(
                key = "morphe_swipe_speed_sensitivity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            ListPreference("morphe_swipe_speed_step"),
            SwitchPreference("morphe_swipe_ignore_when_locked", summary = true),
            SwitchPreference("morphe_swipe_press_to_engage", summary = true),
            SwitchPreference("morphe_swipe_haptic_feedback"),
            SwitchPreference("morphe_swipe_save_and_restore_brightness", summary = true),
            SwitchPreference("morphe_swipe_lowest_value_enable_auto_brightness", summary = true),
            ListPreference("morphe_swipe_overlay_style"),
            NonInteractivePreference(
                key = "morphe_swipe_overlay_background_opacity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            TextPreference("morphe_swipe_overlay_progress_brightness_color",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS
            ),
            TextPreference("morphe_swipe_overlay_progress_volume_color",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS
            ),
            TextPreference("morphe_swipe_overlay_progress_speed_color",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS
            ),
            NonInteractivePreference(
                key = "morphe_swipe_text_overlay_size",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            TextPreference("morphe_swipe_overlay_timeout", inputType = InputType.NUMBER),
            TextPreference("morphe_swipe_threshold", inputType = InputType.NUMBER)
        )

        copyPlayerIcons(
            "swipecontrols",
            "morphe_ic_sc_brightness_auto",
            "morphe_ic_sc_brightness_full",
            "morphe_ic_sc_brightness_high",
            "morphe_ic_sc_brightness_low",
            "morphe_ic_sc_brightness_medium",
            "morphe_ic_sc_volume_high",
            "morphe_ic_sc_volume_low",
            "morphe_ic_sc_volume_mute",
            "morphe_ic_sc_volume_normal",
            "morphe_ic_sc_speed"
        )
    }
}

@Suppress("unused")
val swipeControlsPatch = bytecodePatch(
    name = "Swipe controls",
    description = "Adds options to enable and configure volume and brightness swipe controls."
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        swipeControlsResourcePatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val wrapperClass = SwipeControlsHostActivityFingerprint.classDef
        val targetClass = YouTubeMainActivityConstructorFingerprint.classDef

        // Inject the wrapper class from the extension into the class hierarchy of MainActivity.
        wrapperClass.setSuperClass(targetClass.superclass)
        targetClass.setSuperClass(wrapperClass.type)

        // Ensure all classes and methods in the hierarchy are non-final, so we can override them in the extension.
        traverseClassHierarchy(targetClass) {
            accessFlags = accessFlags and AccessFlags.FINAL.value.inv()
            transformMethods {
                cloneMutable(accessFlags = accessFlags and AccessFlags.FINAL.value.inv())
            }
        }

        if (!is_20_34_or_greater) {
            SwipeChangeVideoFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.last().index,
                    "$EXTENSION_CLASS->allowSwipeChangeVideo(Z)Z"
                )
            }
        }

        PlayerOverlayContainerFingerprint.let {
            val overlayNameField = it.classDef.fields.first { field ->
                field.type == "Ljava/lang/String;"
            }

            it.method.addInstructions(
                0,
                """
                    iget-object v0, p0, $overlayNameField
                    invoke-static { p0, v0 }, $EXTENSION_CLASS->setPlayerOverlay(Landroid/view/View;Ljava/lang/String;)V
                """
            )
        }

        addSeekbarScrubber()
    }
}

/**
 * Let's swipe to seek scrub the time bar, the same way slide to seek does,
 * by adding [EXTENSION_SCRUBBER_INTERFACE] to the touch handler of slide to seek.
 */
private fun BytecodePatchContext.addSeekbarScrubber() {
    // Handler -> slide to seek controller -> scrub state -> time bar.
    val (controllerField, scrubStateField, timeBarField) = listOf(0, 1, 3).map { index ->
        SlideSeekbarGetViewControllerFingerprint.instructionMatches[index]
            .getInstruction<ReferenceInstruction>().getReference<FieldReference>()!!
    }
    val timeBarType = timeBarField.type

    // The scrub state reads the scrubber position into its point, relative to the time bar bounds in its rect.
    val scrubStateClass = SeekbarUpdatePointFingerprint.classDef
    if (scrubStateField.type != scrubStateClass.type) {
        throw PatchException("Unexpected scrub state class: ${scrubStateField.type}")
    }
    val (pointField, rectField) = listOf(0, 2).map { index ->
        SeekbarUpdatePointFingerprint.instructionMatches[index]
            .getInstruction<ReferenceInstruction>().getReference<FieldReference>()!!
    }
    val updatePointMethod = SeekbarUpdatePointFingerprint.method

    val (getBoundsMethod, startUpdatePointMethod, getScrubTimeMethod) =
        SlideSeekbarStartScrubFingerprint.instructionMatches.map {
            it.getInstruction<ReferenceInstruction>().getReference<MethodReference>()!!
        }
    if (startUpdatePointMethod.definingClass != scrubStateClass.type
        || startUpdatePointMethod.name != updatePointMethod.name
        || getScrubTimeMethod.definingClass != timeBarType
    ) {
        throw PatchException("Unexpected scrub start: $startUpdatePointMethod $getScrubTimeMethod")
    }

    val (startScrubMethod, moveScrubMethod, completeScrubMethod) = listOf(
        TimeBarStartScrubFingerprint,
        TimeBarMoveScrubFingerprint,
        TimeBarCompleteScrubFingerprint
    ).map { fingerprint ->
        if (timeBarType !in fingerprint.classDef.interfaces) {
            throw PatchException("${fingerprint.classDef.type} does not implement $timeBarType")
        }
        "$timeBarType->${fingerprint.method.name}(I)V"
    }

    SlideSeekbarHandlerConstructorFingerprint.method.apply {
        addInstruction(
            indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
            "invoke-static/range { p0 .. p0 }, $EXTENSION_SEEK_CLASS->setScrubber($EXTENSION_SCRUBBER_INTERFACE)V"
        )
    }

    SlideSeekbarHandlerConstructorFingerprint.classDef.apply {
        interfaces.add(EXTENSION_SCRUBBER_INTERFACE)

        val getTimeBar = """
            iget-object v0, p0, $controllerField
            iget-object v0, v0, $scrubStateField
            iget-object v0, v0, $timeBarField
        """

        addScrubberMethod(
            "patch_startScrub", listOf(), "I", 3,
            """
                iget-object v0, p0, $controllerField
                iget-object v0, v0, $scrubStateField
                iget-object v1, v0, $timeBarField
                iget-object v2, v0, $rectField
                invoke-interface { v1, v2 }, $getBoundsMethod
                invoke-virtual { v0 }, $updatePointMethod
                iget-object v2, v0, $pointField
                iget v2, v2, Landroid/graphics/Point;->x:I
                invoke-interface { v1, v2 }, $startScrubMethod
                return v2
            """
        )
        addScrubberMethod(
            "patch_moveScrub", listOf("I"), "J", 2,
            """
                $getTimeBar
                invoke-interface { v0, p1 }, $moveScrubMethod
                invoke-interface { v0 }, $getScrubTimeMethod
                move-result-wide v0
                return-wide v0
            """
        )
        addScrubberMethod(
            "patch_completeScrub", listOf("I"), "V", 1,
            """
                $getTimeBar
                invoke-interface { v0, p1 }, $completeScrubMethod
                return-void
            """
        )
    }
}

private fun MutableClass.addScrubberMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    localRegisters: Int,
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
            // Locals, this, and the parameters.
            MutableMethodImplementation(localRegisters + 1 + parameters.size),
        ).toMutable().apply {
            addInstructions(0, smali)
        }
    )
}
