/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.potoken

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.all.misc.clone.setOrGetFallbackPackageName
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/PoTokenProviderPatch;"
private const val EXTENSION_HTTP_REQUEST_INTERFACE = $$"Lapp/morphe/extension/shared/patches/PoTokenProviderPatch$HttpRequest;"

internal fun poTokenProviderPatch(
    originalAppPackageName: String,
    preferenceScreen: BasePreferenceScreen.Screen,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {},
) = bytecodePatch(
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
        mutableClassDefBy(HTTP_REQUEST_CLASS_TYPE).apply {
            // The URL is the only String field of the request.
            val urlField = instanceFields.single { field -> field.type == "Ljava/lang/String;" }

            interfaces.add(EXTENSION_HTTP_REQUEST_INTERFACE)
            methods.add(
                ImmutableMethod(
                    type,
                    "patch_getUrl",
                    listOf(),
                    "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $urlField
                            return-object v0
                        """
                    )
                }
            )
        }

        NetFetchStartFetchTaskFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->" +
                    "onMediaRequest(${EXTENSION_HTTP_REQUEST_INTERFACE}Ljava/lang/Object;)V"
        )
        NetFetchResponseChunkFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onMediaResponseChunk(Ljava/lang/Object;Ljava/nio/ByteBuffer;)V"
        )

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
