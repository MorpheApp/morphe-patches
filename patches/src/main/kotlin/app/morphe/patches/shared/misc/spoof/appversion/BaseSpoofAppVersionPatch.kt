package app.morphe.patches.shared.misc.spoof.appversion

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
fun baseSpoofAppVersionPatch(
    defaultTargetString: () -> String,
    preferenceScreen: BasePreferenceScreen.Screen,
    listPreference: () -> ListPreference,
    sharedExtensionClass: String = "Lapp/morphe/extension/shared/spoof/SpoofAppVersionPatch;",
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {}
) = bytecodePatch(
    name = "Spoof app version",
    description = "Adds an option to trick the app into thinking you are running an older version."
) {
    block()

    execute {
        SpoofAppVersionFingerprint.apply {
            val index = instructionMatches.first().index
            val register = method.getInstruction<OneRegisterInstruction>(index).registerA

            method.addInstructions(
                index + 1,
                """
                    invoke-static { v$register }, $sharedExtensionClass->getUniversalAppVersionOverride(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """
            )
        }

        mutableClassDefBy("Lapp/morphe/extension/shared/settings/SharedYouTubeSettings;")
            .methods.first { it.name == "getDefaultSpoofAppVersionTarget" }
            .returnEarly(defaultTargetString())

        preferenceScreen.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_spoof_app_version", summary = true),
                listPreference()
            )
        )

        executeBlock()
    }
}
