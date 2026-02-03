package app.morphe.patches.shared.misc.checks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.RecommendedAppVersionUtilsFingerprint
import app.morphe.util.returnEarly

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/shared/patches/ExperimentalAppNoticePatch;"

internal fun experimentalAppNoticePatch(
    mainActivityFingerprint: Fingerprint,
    recommendedAppVersion: String
) = bytecodePatch(
    description = "Shows a use dialog message the first time a user launches an experimentally patched app",
) {
    execute {
        RecommendedAppVersionUtilsFingerprint.method.returnEarly(recommendedAppVersion)

        mainActivityFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->showExperimentalNoticeIfNeeded(Landroid/app/Activity;)V",
        )
    }
}
