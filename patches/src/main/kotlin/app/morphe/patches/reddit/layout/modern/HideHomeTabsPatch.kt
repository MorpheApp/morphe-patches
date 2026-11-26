/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5044
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.modern

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideHomeTabsPatch;"

private const val EXTENSION_HOME_TAB_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideHomeTabsPatch$HomeTabInterface;"

@Suppress("unused")
val hideHomeTabsPatch = patch(
    name = "Hide home tabs",
    description = "Adds options to hide the For You, Following and Popular tabs of the home feed. " +
            "This patch works with Reddit 2026.40.0 and later."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Hide home tabs' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        // Add an interface to the tabs to allow extension code to get the obfuscated tab id
        // (home, following, popular).
        mutableClassDefBy(HOME_PAGER_SCREEN_TAB_CLASS).apply {
            val idField = fields.single { field -> field.type == "Ljava/lang/String;" }

            addPatchInterface(
                EXTENSION_HOME_TAB_INTERFACE,
                "patch_getTabId",
                """
                    iget-object v0, p0, $idField
                    return-object v0
                """
            )
        }

        HomePagerTabUiModelsFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->hideTabs(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
