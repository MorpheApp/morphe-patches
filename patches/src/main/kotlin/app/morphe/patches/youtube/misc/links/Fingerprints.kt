package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object VideoCommentsUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "Ljava/util/Map;"),
    returnType = "V",
    strings = listOf(
        "parentCsn",
        "URL_KEY",
        "navigation_endpoint",
        "WEB_VIEW_BOTTOM_SHEET_TAG",
    ),
    filters = listOf(
        string("android.intent.action.VIEW"),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            smali = "Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V",
            location = InstructionLocation.MatchAfterImmediately(),
        ),
    )
)

internal object DescriptionAndPostUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Landroid/net/Uri;"),
    returnType = "Z",
    strings = listOf(
        "android.intent.action.VIEW",
        "text/html",
        "Activity not found to view uri",
    ),
)
