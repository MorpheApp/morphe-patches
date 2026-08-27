/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.potoken

import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.clone.setOrGetFallbackPackageName
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.registersUsed
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/PoTokenProviderPatch;"

private lateinit var resourceContext: ResourcePatchContext

private val poTokenProviderResourcePatch = resourcePatch {
    execute {
        resourceContext = this
    }
}

internal fun poTokenProviderPatch(
    originalAppPackageName: String,
    preferenceScreen: BasePreferenceScreen.Screen,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {},
) = bytecodePatch(
    name = "PoToken provider",
    description = "Adds option to get PoToken using an external PoToken minter app.",
    default = false
) {
    // The execute block still has to check the package name, because the CLI does not report one.
    availability { installer, _ ->
        when (installer) {
            InstallerType.MOUNT -> PatchAvailability.UNAVAILABLE
            InstallerType.STANDARD, InstallerType.SHIZUKU -> PatchAvailability.DISABLED
        }
    }

    block()

    dependsOn(poTokenProviderResourcePatch)

    execute {
        // TODO: Migrate to GmsCore support patch.
        val isRootInstall = setOrGetFallbackPackageName(originalAppPackageName) == originalAppPackageName
        if (isRootInstall) {
            return@execute Logger.getLogger(this::class.java.name).info(
                "PoToken provider is not required for root installation. No changes applied."
            )
        }

        resourceContext.document("AndroidManifest.xml").use { document ->
            val packageNode = document.createElement("package")
            packageNode.setAttribute("android:name", "app.morphe.pot.helper")

            document.getElementsByTagName("queries").item(0).appendChild(packageNode)
        }

        ServiceBindIntentUtilsFingerprint.let {
            it.method.apply {
                val serviceActionMatch = it.instructionMatches[2]
                val serviceActionIndex = serviceActionMatch.index
                val serviceActionRegister = serviceActionMatch.instruction.registersUsed[2]

                val authoritiesMatch = it.instructionMatches[3]
                val authoritiesIndex = authoritiesMatch.index
                val authoritiesRegister = authoritiesMatch.instruction.registersUsed[1]

                addInstructionsAtControlFlowLabel(
                    authoritiesIndex,
                    """
                        invoke-static { v$serviceActionRegister, v$authoritiesRegister }, $EXTENSION_CLASS->overrideAuthorities(Ljava/lang/String;Landroid/net/Uri;)Landroid/net/Uri;
                        move-result-object v$authoritiesRegister
                    """
                )

                addInstructionsAtControlFlowLabel(
                    serviceActionIndex,
                    """
                        invoke-static { v$serviceActionRegister }, $EXTENSION_CLASS->overrideServiceAction(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$serviceActionRegister
                    """
                )
            }
        }

        preferenceScreen.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_potoken_provider_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_external_potoken_provider", summary = true),
                    IntentPreference(
                        key = "morphe_external_potoken_provider_settings",
                        titleKey = "morphe_external_potoken_provider_about_title",
                        intent = IntentPreference.Intent("", "app.morphe.pot.helper.MainActivity") {
                            "app.morphe.pot.helper"
                        }
                    ),
                    NonInteractivePreference(
                        key = "morphe_external_potoken_provider_about",
                        tag = "app.morphe.extension.shared.settings.preference.ExternalPoTokenProviderAboutPreference",
                        selectable = true,
                    )
                )
            )
        )

        executeBlock()
    }
}
