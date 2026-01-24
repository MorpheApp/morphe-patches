package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.extension.Constants.PATCHES_PATH
import app.morphe.patches.reddit.utils.patch.PatchList.OPEN_LINKS_DIRECTLY
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.patches.reddit.utils.settings.updatePatchStatus

private const val EXTENSION_METHOD_DESCRIPTOR =
    "$PATCHES_PATH/OpenLinksDirectlyPatch;" +
            "->" +
            "parseRedirectUri(Landroid/net/Uri;)Landroid/net/Uri;"

@Suppress("unused")
val openLinksDirectlyPatch = bytecodePatch(
    OPEN_LINKS_DIRECTLY.title,
    OPEN_LINKS_DIRECTLY.summary,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(
        settingsPatch,
        screenNavigatorMethodResolverPatch
    )

    execute {
        screenNavigatorMethod.addInstructions(
            0, """
                invoke-static {p2}, $EXTENSION_METHOD_DESCRIPTOR
                move-result-object p2
                """
        )

        updatePatchStatus(
            "enableOpenLinksDirectly",
            OPEN_LINKS_DIRECTLY
        )
    }
}
