/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.playlistpanel

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * YouTube's rule whether a panel opened in fullscreen exits fullscreen (true on any tablet).
 */
internal object PanelExitsFullscreenFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(
        string("[EngagementPanel] Cannot cannot determine if shouldExitFullscreen because EngagementPanelController has not been initialized.")
    )
)

/**
 * YouTube's rule where a panel opens: beside the player, or over the fullscreen player.
 * The overlay needs a server config, read inline (the boolean read after the static config).
 */
internal object PanelModeFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;",
        "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        opcode(Opcode.SGET_OBJECT),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        literal(45717387L)
    )
)

/**
 * Before 21.43 the server config of [PanelModeFingerprint] is read by a static boolean call.
 */
internal object PanelModeLegacyFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;",
        "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC, returnType = "Z", parameters = listOf("L")),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        literal(45717387L)
    )
)

/**
 * YouTube's rule whether a panel is refused for the current mode. Over the fullscreen player it
 * refuses most panels unless a server config allows them (the boolean read after the config call).
 */
internal object PanelRefusedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L", "L", "L", "L", "L"),
    filters = listOf(
        literal(45673598L),
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("L"), returnType = "L"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, location = MatchAfterImmediately())
    )
)
