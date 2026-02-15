package app.morphe.patches.reddit.misc.fix.signature

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/SpoofSignaturePatch;"

@Suppress("unused")
val spoofSignaturePatch = bytecodePatch(
    name = "Spoof signature",
    description = "Spoofs the signature of the app to fix notification issues."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(sharedExtensionPatch)

    execute {
        ApplicationFingerprint.classDef.setSuperClass(EXTENSION_CLASS_DESCRIPTOR)
    }
}
