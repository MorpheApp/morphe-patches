package app.morphe.patches.youtube.layout.hide.infocards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.string
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val infocardsIncognitoFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Boolean;",
    parameters = listOf("L", "J"),
    filters = listOf(
        string("vibrator")
    )
)

internal val infocardsIncognitoParentFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("player_overlay_info_card_teaser")
    )
)

internal val infocardsMethodCallFingerprint = Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_INTERFACE,
    ),
    strings = listOf ("Missing ControlsOverlayPresenter for InfoCards to work."),
    custom = customLiteral { drawerResourceId }
)
