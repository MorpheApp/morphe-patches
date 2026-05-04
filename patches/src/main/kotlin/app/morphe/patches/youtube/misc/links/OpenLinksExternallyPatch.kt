package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/OpenLinksExternallyPatch;"

val openLinksExternallyPatch = bytecodePatch(
    name = "Open links externally",
    description = "Adds an option to always open links in your browser instead of with the in-app browser.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_external_browser"),
        )

        val filter = string("android.support.customtabs.action.CustomTabsService")

        Fingerprint(
            filters = listOf(filter),
            custom = { _, classDef ->
                !classDef.type.startsWith("Lapp/morphe/")
            }
        ).matchAll().forEach { match ->
            match.method.apply {
                findInstructionIndicesReversedOrThrow(filter).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->getIntent(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """
                    )
                }
            }
        }
    }
}