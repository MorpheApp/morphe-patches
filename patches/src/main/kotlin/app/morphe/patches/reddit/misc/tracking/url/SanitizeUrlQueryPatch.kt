package app.morphe.patches.reddit.misc.tracking.url

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/reddit/patches/SanitizeUrlQueryPatch;"

@Suppress("unused")
val sanitizeUrlQueryPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Adds an option to sanitize sharing links by removing tracking query parameters."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        shareLinkFormatterFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->stripQueryParameters()Z
                move-result v0
                if-eqz v0, :off
                return-object p0
                :off
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
