package app.morphe.patches.youtube.interaction.swipecontrols

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_19_43_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_34_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.MainActivityConstructorFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.insertLiteralOverride
import app.morphe.util.transformMethods
import app.morphe.util.traverseClassHierarchy
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/swipecontrols/SwipeControlsHostActivity;"

private val swipeControlsResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        // If fullscreen swipe is enabled in newer versions the app can crash.
        // It likely is caused by conflicting experimental flags that are never enabled together.
        // Flag was completely removed in 20.34+
        if (is_19_43_or_greater && !is_20_22_or_greater) {
            PreferenceScreen.SWIPE_CONTROLS.addPreferences(
                SwitchPreference("morphe_swipe_change_video")
            )
        }

        PreferenceScreen.SWIPE_CONTROLS.addPreferences(
            SwitchPreference("morphe_swipe_brightness"),
            SwitchPreference("morphe_swipe_volume"),
            SwitchPreference("morphe_swipe_press_to_engage"),
            SwitchPreference("morphe_swipe_haptic_feedback"),
            SwitchPreference("morphe_swipe_save_and_restore_brightness"),
            SwitchPreference("morphe_swipe_lowest_value_enable_auto_brightness"),
            ListPreference("morphe_swipe_overlay_style"),
            TextPreference("morphe_swipe_overlay_background_opacity", inputType = InputType.NUMBER),
            TextPreference("morphe_swipe_overlay_progress_brightness_color",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS),
            TextPreference("morphe_swipe_overlay_progress_volume_color",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS),
            TextPreference("morphe_swipe_text_overlay_size", inputType = InputType.NUMBER),
            TextPreference("morphe_swipe_overlay_timeout", inputType = InputType.NUMBER),
            TextPreference("morphe_swipe_threshold", inputType = InputType.NUMBER),
            TextPreference("morphe_swipe_volume_sensitivity", inputType = InputType.NUMBER),
        )

        copyResources(
            "swipecontrols",
            ResourceGroup(
                "drawable",
                "morphe_ic_sc_brightness_auto.xml",
                "morphe_ic_sc_brightness_full.xml",
                "morphe_ic_sc_brightness_high.xml",
                "morphe_ic_sc_brightness_low.xml",
                "morphe_ic_sc_brightness_medium.xml",
                "morphe_ic_sc_volume_high.xml",
                "morphe_ic_sc_volume_low.xml",
                "morphe_ic_sc_volume_mute.xml",
                "morphe_ic_sc_volume_normal.xml",
            ),
        )
    }
}

@Suppress("unused")
val swipeControlsPatch = bytecodePatch(
    name = "Swipe controls",
    description = "Adds options to enable and configure volume and brightness swipe controls.",
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        swipeControlsResourcePatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        val wrapperClass = SwipeControlsHostActivityFingerprint.classDef
        val targetClass = MainActivityConstructorFingerprint.classDef

        // Inject the wrapper class from the extension into the class hierarchy of MainActivity.
        wrapperClass.setSuperClass(targetClass.superclass)
        targetClass.setSuperClass(wrapperClass.type)

        // Ensure all classes and methods in the hierarchy are non-final, so we can override them in the extension.
        traverseClassHierarchy(targetClass) {
            accessFlags = accessFlags and AccessFlags.FINAL.value.inv()
            transformMethods {
                ImmutableMethod(
                    definingClass,
                    name,
                    parameters,
                    returnType,
                    accessFlags and AccessFlags.FINAL.value.inv(),
                    annotations,
                    hiddenApiRestrictions,
                    implementation,
                ).toMutable()
            }
        }

        // region patch to enable/disable swipe to change video.

        if (is_19_43_or_greater && !is_20_34_or_greater) {
            SwipeChangeVideoFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.last().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->allowSwipeChangeVideo(Z)Z"
                )
            }
        }

        // endregion
    }
}
