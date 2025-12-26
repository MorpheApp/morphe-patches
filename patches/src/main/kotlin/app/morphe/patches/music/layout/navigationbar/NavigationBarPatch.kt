package app.morphe.patches.music.layout.navigationbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/NavigationBarPatch;"

@Suppress("unused")
val navigationBarPatch = bytecodePatch(
    name = "Navigation bar",
    description = "Adds options to hide navigation bar, labels and buttons."
) {
    dependsOn(
        resourceMappingPatch,
        sharedExtensionPatch,
        settingsPatch,
        resourcePatch {
            execute {
                // Ensure the first ImageView has 'layout_weight' to stay properly sized
                // when the TextView is hidden.
                document("res/layout/image_with_text_tab.xml").use { document ->
                    val imageView = document.getElementsByTagName("ImageView").item(0)
                    imageView?.let {
                        if (it.attributes.getNamedItem("android:layout_weight") == null) {
                            val attr = document.createAttribute("android:layout_weight")
                            attr.value = "0.5"
                            it.attributes.setNamedItem(attr)
                        }
                    }
                }
            }
        }
    )

    compatibleWith(
        "com.google.android.apps.youtube.music"(
            "7.29.52",
            "8.10.52",
            "8.37.56",
        )
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_navigation_bar_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_navigation_bar_home_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_samples_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_explore_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_library_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_upgrade_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar"),
                    SwitchPreference("morphe_music_hide_navigation_bar_labels"),
                )
            )
        )

        TabLayoutTextFingerprint.let {
            it.method.apply {
                // Modify in reverse order to preserve match indices.

                // Hide navigation buttons.
                val pivotTabIndex = it.instructionMatches.last().index
                val pivotTabRegister = getInstruction<FiveRegisterInstruction>(pivotTabIndex).registerC

                addInstruction(
                    pivotTabIndex,
                    "invoke-static { v$pivotTabRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideNavigationButton(Landroid/view/View;)V"
                )


                // Set navigation enum and hide navigation buttons.
                val enumIndex = it.instructionMatches[7].index
                val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

                addInstruction(
                    enumIndex + 1,
                    "invoke-static { v$enumRegister }, $EXTENSION_CLASS_DESCRIPTOR->setLastAppNavigationEnum(Ljava/lang/Enum;)V"
                )


                // Hide navigation labels.
                val labelIndex = it.instructionMatches[3].index
                val targetParameter = getInstruction<ReferenceInstruction>(labelIndex).reference
                val targetRegister = getInstruction<OneRegisterInstruction>(labelIndex).registerA

                addInstruction(
                    labelIndex + 1,
                    "invoke-static { v$targetRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideNavigationLabel(Landroid/widget/TextView;)V"
                )
            }
        }
    }
}
