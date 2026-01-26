package app.morphe.patches.youtube.layout.searchfilter

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/RestoreOldSearchFiltersPatch;"

@Suppress("unused")
val restoreOldSearchFiltersPatch = bytecodePatch(
    name = "Restore old search filters",
    description = "Adds an option to restore the old search filters.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    execute {
        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            SwitchPreference("morphe_restore_old_search_filters"),
        )

        // region Replace the search filter group renderer with the replacement renderer

        SearchFilterDialogFingerprint.let {
            val searchFilterGroupRendererField = SearchResponseParserFingerprint.instructionMatches
                .last().getInstruction<ReferenceInstruction>().reference
            val parseByteArrayMethod = ProtobufClassParseByteArrayFingerprint.method

            it.method.apply {
                val insertIndex = it.instructionMatches[1].index
                val rendererRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                val registerProvider = getFreeRegisterProvider(insertIndex, 2)
                val byteArrayRegister = registerProvider.getFreeRegister()
                val freeRegister = registerProvider.getFreeRegister()

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        # Get search filter group renderer.
                        invoke-static { v$rendererRegister }, $EXTENSION_CLASS_DESCRIPTOR->getSearchFilterGroupRenderer(Ljava/lang/Object;)[B
                        move-result-object v$byteArrayRegister
                        if-eqz v$byteArrayRegister, :do_not_parse

                        # Parse search filter group renderer.
                        sget-object v$freeRegister, $searchFilterGroupRendererField
                        invoke-static { v$freeRegister, v$byteArrayRegister }, $parseByteArrayMethod
                        move-result-object v$rendererRegister

                        :do_not_parse
                        nop
                    """
                )
            }
        }

        // endregion

        // region Set searchFormData

        SearchRequestBuildParametersFingerprint.let {
            it.method.apply {
                val byteArrayIndex = it.instructionMatches.last().index
                val byteArrayRegister = getInstruction<OneRegisterInstruction>(byteArrayIndex).registerA

                addInstruction(
                    byteArrayIndex + 1,
                    "invoke-static { v$byteArrayRegister }, $EXTENSION_CLASS_DESCRIPTOR" +
                            "->setLastFormValue([B)V"
                )

                addInstruction(
                    0,
                    "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->setLastFormValue()V"
                )
            }
        }

        // endregion

    }
}
