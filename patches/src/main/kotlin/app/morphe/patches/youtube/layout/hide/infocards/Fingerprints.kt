package app.morphe.patches.youtube.layout.hide.infocards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterAnywhere
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private object InfoCardsIncognitoParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("player_overlay_info_card_teaser")
    )
)

internal object InfoCardsIncognitoFingerprint : Fingerprint(
    classFingerprint = InfoCardsIncognitoParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Boolean;",
    parameters = listOf("L", "J"),
    filters = listOf(
        string("vibrator")
    )
)

internal object InfoCardsMethodCallFingerprint : Fingerprint(
    filters = listOf(
        opcode(Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        resourceLiteral(ResourceType.ID, "info_cards_drawer_header")
    ),
    strings = listOf("Missing ControlsOverlayPresenter for InfoCards to work.")
)