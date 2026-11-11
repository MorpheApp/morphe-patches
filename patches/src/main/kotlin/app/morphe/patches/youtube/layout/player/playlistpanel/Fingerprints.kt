/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.playlistpanel

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
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
 * Where a panel opens: beside the player, or over the fullscreen player.
 */
internal object PanelModeEnumFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("PORTRAIT_WATCH_PANEL", "FULLSCREEN_PLAYER_OVERLAY")
)

/**
 * YouTube's rule where a panel opens. Its first check is the server config that enables the panel
 * overlay over the fullscreen player, then the check for the fullscreen state. Depending on the app
 * build the config is read inline (a boolean field) or by a static call, so either read is matched.
 * Some versions have two variants of this method, chosen by a server flag.
 */
internal fun getPanelModeFingerprint(panelModeType: String) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        anyInstruction(
            opcode(Opcode.MOVE_RESULT),
            fieldAccess(opcode = Opcode.IGET_BOOLEAN)
        ),
        anyInstruction(
            opcode(Opcode.IF_EQZ),
            opcode(Opcode.IF_NEZ),
            location = MatchAfterImmediately()
        ),
        // The fullscreen state.
        literal(3, location = MatchAfterWithin(10)),
        opcode(Opcode.IF_EQ, location = MatchAfterImmediately()),
        // The panel beside the player.
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            type = panelModeType,
            location = MatchAfterImmediately()
        )
    )
) {}

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
