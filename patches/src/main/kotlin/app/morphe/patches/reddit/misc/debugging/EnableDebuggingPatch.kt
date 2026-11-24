/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5201
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.debugging

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.flag.featureFlagHookPatch
import app.morphe.patches.reddit.misc.flag.hookFeatureFlag
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_46_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.ResourceGroup
import app.morphe.util.cloneMutable
import app.morphe.util.copyResources
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/EnableDebuggingPatch;"

@Suppress("unused")
val enableDebuggingPatch = patch(
    name = "Enable debugging",
    description = "Adds options for exporting Morphe logs and for blocking or forcing Reddit experiments."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        featureFlagHookPatch,
        versionCheckPatch
    )

    execute {
        copyResources(
            "settings",
            ResourceGroup("drawable",
                // Feature flags manager buttons.
                "morphe_settings_bisect.xml",
                "morphe_settings_copy_all.xml",
                "morphe_settings_deselect_all.xml",
                "morphe_settings_import_export.xml",
                "morphe_settings_select_all.xml",
                // Search bar of the feature flags manager and the debug logs.
                "morphe_settings_search_icon_bold.xml",
                "morphe_settings_search_remove_bold.xml"
            )
        )

        // Experiments that are on or off.
        hookFeatureFlag("$EXTENSION_CLASS->isBooleanExperimentEnabled")

        // Experiments with variants.
        val booleanParameters = when {
            is_2026_46_0_or_greater -> 3
            is_2026_45_0_or_greater -> 2
            else -> 1
        }

        ExperimentVariantFingerprint(booleanParameters).let {
            it.method.apply {
                // Not enough registers in the method. Clone the method and use the
                // original method as an intermediate to call extension code.
                val helperMethod = cloneMutable(name = "patch_getExperimentVariant")
                it.classDef.methods.add(helperMethod)

                val lastRegister = "p${parameters.size}"

                // The first boolean parameter is reused for the result.
                addInstructions(
                    0,
                    """
                        invoke-virtual/range { p0 .. $lastRegister }, $helperMethod
                        move-result-object p2
                        invoke-static { p1, p2 }, $EXTENSION_CLASS->getExperimentVariant(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                        move-result-object p2
                        return-object p2
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
