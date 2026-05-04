package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstructionOrNull
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

val openLinksExternallyPatch = bytecodePatch(
    name = "Open links externally",
    description = "Adds an option to always open links in your browser instead of with the in-app browser.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // TODO: Replace this with Fingerprint.matchAll()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/extension")) return@classDefForEach

            classDef.methods.forEach { method ->
                val instructionsIterable = method.implementation?.instructions ?: return@forEach
                val targetIndices = instructionsIterable.mapIndexedNotNull { index, instruction ->
                    if (instruction is ReferenceInstruction) {
                        val reference = instruction.reference as? StringReference
                        if (reference?.string == "android.support.customtabs.action.CustomTabsService") {
                            return@mapIndexedNotNull index
                        }
                    }
                    null
                }

                if (targetIndices.isNotEmpty()) {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)

                    targetIndices.reversed().forEach { index ->
                        val instruction = mutableMethod.getInstructionOrNull<OneRegisterInstruction>(index)
                            ?: return@forEach
                        val register = instruction.registerA

                        mutableMethod.addInstructions(
                            index + 1,
                            """
                                invoke-static {v$register}, Lapp/morphe/extension/youtube/patches/OpenLinksExternallyPatch;->getIntent(Ljava/lang/String;)Ljava/lang/String;
                                move-result-object v$register
                            """
                        )
                    }
                }
            }
        }

        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_external_browser"),
        )
    }
}