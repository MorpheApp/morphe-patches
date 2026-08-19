package app.morphe.patches.youtube.misc.proxy

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patches.shared.misc.proxy.EXTENSION_CLASS
import app.morphe.patches.shared.misc.proxy.baseNetworkProxyPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.extension.hooks.applicationInitHook
import app.morphe.patches.youtube.misc.extension.hooks.applicationInitOnCrateHook
import app.morphe.patches.youtube.misc.playservice.is_20_47_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_12_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val networkProxyPatch = baseNetworkProxyPatch(
    preferenceScreen = PreferenceScreen.MISC,
    targetUsesProxyListInt = {
        is_21_12_or_greater
    },
    patchNotCompatibleMessage = {
        if (is_20_47_or_greater) {
            null
        } else {
            "Network proxy requires YouTube 20.47.62 or newer."
        }
    },
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    executeBlock = {
        listOf(applicationInitHook, applicationInitOnCrateHook).forEach { hook ->
            hook.fingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->initialize()V"
            )
        }
    }
)
