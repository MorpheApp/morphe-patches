package app.morphe.patches.youtube.layout.hide.player.popup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object PlayerPopupPanelsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;", "L"),
    filters = listOf(
        string(
            "triggered_on_ui_ready",
            location = MatchAfterWithin(6),
        ),
        methodCall(
            smali = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            location = MatchAfterImmediately(),
        ),
        methodCall(
            smali = "Ljava/util/Iterator;->hasNext()Z",
            location = MatchAfterWithin(4),
        ),
    )
)
