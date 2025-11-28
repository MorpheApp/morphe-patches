package app.morphe.patches.youtube.layout.searchbar

import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/WideSearchbarPatch;"

internal val wideSearchbarPatch = bytecodePatch(
    description = "Adds an option to replace the search icon with a wide search bar. " +
            "This will hide the YouTube logo when active.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        resourceMappingPatch,
        versionCheckPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            // 20.31.40+ not supported. YouTube code was removed.
        )
    )

    execute {
        if (is_20_31_or_greater) {
            // YT removed the legacy text search text field all code required to use it.
            // This functionality could be restored by adding a search text field to the toolbar
            // with a listener that artificially clicks the toolbar search button.
            return@execute
        }

        addResources("youtube", "layout.searchbar.wideSearchbarPatch")

        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_wide_searchbar"),
        )

        setWordmarkHeaderFingerprint.let {
            // Navigate to the method that checks if the YT logo is shown beside the search bar.
            val shouldShowLogoMethod = with(it.originalMethod) {
                val invokeStaticIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_STATIC &&
                            getReference<MethodReference>()?.returnType == "Z"
                }
                navigate(this).to(invokeStaticIndex).stop()
            }

            shouldShowLogoMethod.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->enableWideSearchbar(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Fix missing left padding when using wide searchbar.
        wideSearchbarLayoutFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow {
                val reference = getReference<MethodReference>()
                reference?.definingClass == "Landroid/view/LayoutInflater;"
                        && reference.name == "inflate"
            }.forEach { inflateIndex ->
                val register = getInstruction<OneRegisterInstruction>(inflateIndex + 1).registerA

                addInstruction(
                    inflateIndex + 2,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->setActionBar(Landroid/view/View;)V"
                )
            }
        }
    }
}
