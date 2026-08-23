package app.morphe.patches.meteomedia.misc.integrity

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.meteomedia.shared.Constants.COMPATIBILITY_METEOMEDIA
import app.morphe.util.returnEarly

@Suppress("unused")
val removePlayIntegrityCheckPatch = bytecodePatch(
    name = "Remove Play integrity check",
    description = "Removes the Google Play (PairIP) signature and license checks that block the patched app from starting.",
) {
    compatibleWith(COMPATIBILITY_METEOMEDIA)
    execute {
        PairipSignatureCheckFingerprint.method.returnEarly()
        PairipLicenseCheckFingerprint.method.returnEarly()
    }
}