package app.morphe.patches.reddit.layout.statusbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.extension.hooks.redditApplicationOnCreateHook
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/patches/ImmersiveModePatch;"

@Suppress("unused")
val immersiveModePatch = patch(
    name = "Immersive mode",
    description = "Adds an option to hide the system status bar throughout Reddit."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // The shared extension prepends its context hook during finalization.
        redditApplicationOnCreateHook.fingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Application;)V"
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
