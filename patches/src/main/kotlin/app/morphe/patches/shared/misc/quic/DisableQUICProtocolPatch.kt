/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.quic

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/patches/DisableQUICProtocolPatch;"

internal fun disableQUICProtocolPatch(
    block: BytecodePatchBuilder.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
) = bytecodePatch(
    name = "Disable QUIC protocol",
    description = "Adds an option to disable QUIC (Quick UDP Internet Connections) network protocol."
) {
    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference("morphe_disable_quic_protocol")
        )

        arrayOf(
            CronetEngineBuilderFingerprint,
            ExperimentalCronetEngineBuilderFingerprint
        ).forEach { fingerprint ->

            fingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->disableQUICProtocol(Z)Z
                    move-result p1
                """
            )
        }
    }
}