package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/reddit/patches/OpenLinksDirectlyPatch;"

@Suppress("unused")
val openLinksDirectlyPatch = bytecodePatch(
    name = "Open links directly",
    description =  "Adds an option to skip over redirection URLs in external links."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        screenNavigatorMethodResolverPatch
    )

    execute {
        screenNavigatorMethod.addInstructions(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->parseRedirectUri(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object p2
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
