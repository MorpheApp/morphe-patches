package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.transformation.transformInstructionsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

val openLinksExternallyPatch = bytecodePatch(
    name = "Open links externally",
    description = "Adds an option to always open links in your browser instead of the in-app browser.",
) {
    dependsOn(
        transformInstructionsPatch(
            filterMap = filterMap@{ _, _, instruction, instructionIndex ->
                if (instruction !is ReferenceInstruction) return@filterMap null
                val reference = instruction.reference as? StringReference ?: return@filterMap null

                if (reference.string != "android.support.customtabs.action.CustomTabsService") return@filterMap null

                return@filterMap instructionIndex to (instruction as OneRegisterInstruction).registerA
            },
            transform = { mutableMethod, entry ->
                val (intentStringIndex, register) = entry

                // Hook the intent string.
                mutableMethod.addInstructions(
                    intentStringIndex + 1,
                    """
                        invoke-static {v$register}, Lapp/morphe/extension/youtube/patches/OpenLinksExternallyPatch;->getIntent(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """,
                )
            },
        ),
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_external_browser"),
        )
    }
}
