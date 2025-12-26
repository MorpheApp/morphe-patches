package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CreateDialogFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(smali = "Landroid/app/AlertDialog\$Builder;->setNegativeButton(ILandroid/content/DialogInterface\$OnClickListener;)Landroid/app/AlertDialog\$Builder;"),
        methodCall(smali = "Landroid/app/AlertDialog\$Builder;->setOnCancelListener(Landroid/content/DialogInterface\$OnCancelListener;)Landroid/app/AlertDialog\$Builder;"),
        methodCall(smali = "Landroid/app/AlertDialog\$Builder;->create()Landroid/app/AlertDialog;"),
        methodCall(smali = "Landroid/app/AlertDialog;->show()V")
    )
)

internal object CreateModernDialogFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.MOVE_RESULT),
        methodCall(smali = "Landroid/app/AlertDialog\$Builder;->setIcon(I)Landroid/app/AlertDialog\$Builder;"),
        methodCall(smali = "Landroid/app/AlertDialog\$Builder;->create()Landroid/app/AlertDialog;"),
    )
)

internal object PlayabilityStatusEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "OK",
        "ERROR",
        "UNPLAYABLE",
        "LOGIN_REQUIRED",
        "CONTENT_CHECK_REQUIRED",
        "AGE_CHECK_REQUIRED",
        "LIVE_STREAM_OFFLINE",
        "FULLSCREEN_ONLY",
        "GL_PLAYBACK_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
    )
)
