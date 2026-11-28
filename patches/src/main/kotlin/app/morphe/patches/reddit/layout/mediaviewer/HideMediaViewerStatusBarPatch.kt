package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideMediaViewerStatusBarPatch;"

@Suppress("unused")
val hideMediaViewerStatusBarPatch = patch(
    name = "Hide media viewer status bar",
    description = "Adds an option to fade the system status bar with the full-screen media viewer controls. " +
            "This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Hide media viewer status bar' requires Reddit 2026.40.0+"
            )
        }

        MediaViewerOnResumeFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onViewerResumed(Landroid/app/Activity;)V"
        )

        ActiveMediaViewerChromeFingerprint.let {
            val visibility = it.instructionMatches[2]
            val register = visibility.getInstruction<OneRegisterInstruction>().registerA
            it.method.addInstruction(
                visibility.index + 1,
                "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->onOverlayVisibilityChanged(Z)V"
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
