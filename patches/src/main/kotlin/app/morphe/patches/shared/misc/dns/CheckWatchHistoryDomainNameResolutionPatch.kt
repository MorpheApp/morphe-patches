package app.morphe.patches.shared.misc.dns

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/CheckWatchHistoryDomainNameResolutionPatch;"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun checkWatchHistoryDomainNameResolutionPatch(
    block: PatchDefinition.() -> Unit = {},
    executeBlock: () -> Unit = {},
    mainActivityFingerprint: Fingerprint
) = patch(
    name = "Check watch history domain name resolution",
    description = "Checks if the device DNS server is preventing user watch history from being saved.",
) {
    block()

    execute {
        executeBlock()

        mainActivityFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->checkDnsResolver(Landroid/app/Activity;)V",
        )
    }
}
