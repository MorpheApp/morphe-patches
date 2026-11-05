/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.potoken

import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.all.misc.clone.setOrGetFallbackPackageName
import app.morphe.patches.shared.misc.request.hookMediaRequest
import app.morphe.patches.shared.misc.request.hookMediaResponseChunk
import app.morphe.patches.shared.misc.request.mediaRequestPatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/PoTokenProviderPatch;"

internal fun poTokenProviderPatch(
    originalAppPackageName: String,
    preferenceScreen: BasePreferenceScreen.Screen,
    block: PatchDefinition.() -> Unit,
    executeBlock: () -> Unit = {},
) = patch(
    name = "PoToken provider",
    description = "Adds option to get PoToken using an external PoToken minter app."
) {
    // The execute block still has to check the package name, because the CLI does not report one.
    availability { installer, _ ->
        when (installer) {
            InstallerType.MOUNT -> PatchAvailability.UNAVAILABLE
            InstallerType.STANDARD, InstallerType.SHIZUKU -> PatchAvailability.ENABLED
        }
    }

    block()

    dependsOn(mediaRequestPatch)

    execute {
        val isRootInstall = setOrGetFallbackPackageName(originalAppPackageName) == originalAppPackageName
        if (isRootInstall) {
            return@execute Logger.getLogger(this::class.java.name).info(
                "PoToken provider is not required for root installation. No changes applied."
            )
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

        // Watch media responses for the server rejecting the token the helper made.
        hookMediaRequest("$EXTENSION_CLASS->onMediaRequest")
        hookMediaResponseChunk("$EXTENSION_CLASS->onMediaResponseChunk")

        preferenceScreen.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_potoken_provider_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_external_potoken_provider",
                        summary = true,
                        tag = "app.morphe.extension.shared.settings.preference.ExternalPoTokenProviderPreference"
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
