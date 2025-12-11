package app.morphe.patches.music.layout.navigationbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal var text1 = -1L
    private set

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
        addResourcesPatch,
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
            "8.46.57",
        )
    )

    execute {
        text1 = getResourceId(ResourceType.ID, "text1")

        addResources("music", "layout.navigationbar.navigationBarPatch")

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

        TabLayoutTextFingerprint.method.apply {
            // Set navigation enum and hide navigation buttons.
            val enumIndex = TabLayoutTextFingerprint.instructionMatches.first().index + 2
            val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

            addInstruction(
                enumIndex + 1,
                "invoke-static { v$enumRegister }, $EXTENSION_CLASS_DESCRIPTOR->setLastAppNavigationEnum(Ljava/lang/Enum;)V"
            )

            // Hide navigation labels.
            val constIndex = indexOfFirstLiteralInstructionOrThrow(text1)
            val labelIndex = indexOfFirstInstructionOrThrow(constIndex, Opcode.CHECK_CAST)
            val targetParameter = getInstruction<ReferenceInstruction>(labelIndex).reference
            val targetRegister = getInstruction<OneRegisterInstruction>(labelIndex).registerA

            if (!targetParameter.toString().endsWith("Landroid/widget/TextView;"))
                throw PatchException("Method signature parameter did not match: $targetParameter")

            addInstruction(
                labelIndex + 1,
                "invoke-static { v$targetRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideNavigationLabel(Landroid/widget/TextView;)V"
            )

            val pivotTabIndex = indexOfGetVisibilityInstruction(this)
            val pivotTabRegister = getInstruction<FiveRegisterInstruction>(pivotTabIndex).registerC

            addInstruction(
                pivotTabIndex,
                "invoke-static { v$pivotTabRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideNavigationButton(Landroid/view/View;)V"
            )
        }
    }
}
