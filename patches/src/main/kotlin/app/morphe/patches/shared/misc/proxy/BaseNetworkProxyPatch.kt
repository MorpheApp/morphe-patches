/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1823
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.proxy

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/NetworkProxyPatch;"

internal fun baseNetworkProxyPatch(
    preferenceScreen: BasePreferenceScreen.Screen,
    targetUsesProxyListInt: BytecodePatchBuilder.() -> Boolean,
    patchNotCompatibleMessage: BytecodePatchBuilder.() -> String?,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {}
) = bytecodePatch(
    name = "Network proxy",
    description = "Adds settings to route supported network requests through an HTTP or HTTPS proxy."
) {

    block()

    execute {
        patchNotCompatibleMessage()?.let {
            return@execute Logger.getLogger(this::class.java.name).warning(it)
        }

        val fromProxyListFingerprint = if (targetUsesProxyListInt())
            FromProxyListLegacyFingerprint
        else FromProxyListFingerprint

        // Ensure all required Cronet proxy API fingerprints resolve on supported versions.
        listOf(
            SetProxyOptionsFingerprint,
            CreateHttpProxyFingerprint,
            fromProxyListFingerprint,
        ).forEach { it.method }

        ExtensionProxyPatchUsProxyListIntFingerprint.method.returnEarly(
            targetUsesProxyListInt()
        )

        preferenceScreen.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_proxy_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_proxy_enabled", summary = true),
                    TextPreference("morphe_proxy_host"),
                    TextPreference("morphe_proxy_port", inputType = InputType.NUMBER),
                    SwitchPreference("morphe_proxy_https", summary = true),
                    SwitchPreference("morphe_proxy_auth_enabled", summary = true),
                    TextPreference("morphe_proxy_auth_username"),
                    TextPreference("morphe_proxy_auth_password", inputType = InputType.TEXT_PASSWORD),
                    SwitchPreference("morphe_proxy_allow_direct_fallback", summary = true),
                )
            )
        )

        BuildExperimentalFingerprint.method.apply {
            addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->applyProxyOptions(Lorg/chromium/net/CronetEngine\$Builder;)V"
            )

            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->recordProxyConfiguredCronetEngine($CRONET_ENGINE_CLASS)V"
                )
            }
        }

        MainCronetEngineFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setMainCronetEngine($CRONET_ENGINE_CLASS)V"
                )
            }
        }

        executeBlock()
    }
}
