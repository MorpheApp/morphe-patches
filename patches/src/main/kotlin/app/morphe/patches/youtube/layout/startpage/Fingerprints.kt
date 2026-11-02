package app.morphe.patches.youtube.layout.startpage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object IntentActionFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Intent;"),
    filters = listOf(
        string("has_handled_intent")
    )
)

internal object BrowseIdFingerprint : Fingerprint(
    returnType = "L",

    //parameters() // 20.30 and earlier is no parameters = listOf(.  20.31+ parameter is L.),
    filters = listOf(
        string("FEwhat_to_watch"),
        literal(512),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/String;")
    )
)

/**
 * Prepares the request of the guide, the response with the buttons of the top bar and of the
 * navigation bar. The first instruction reads the flag of the startup milestone that the request
 * waits for. Older app versions have other numbers for the same flags.
 */
internal object GuideRequestFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            literal(1074004527L),
            literal(268698159L)
        ),
        anyInstruction(
            literal(1073880817L),
            literal(268574449L)
        )
    )
)
