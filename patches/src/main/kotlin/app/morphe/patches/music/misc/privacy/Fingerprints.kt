/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Shares a link with the system share sheet.
 * Used by the share entity service endpoint when there is no network connection,
 * with a music.youtube.com link that has a 'feature=share' query parameter.
 */
internal object ShareLinkWithSystemShareSheetFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/net/Uri;"),
    filters = listOf(
        string("android.intent.extra.TEXT"),
        methodCall(
            smali = "Landroid/net/Uri;->toString()Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            smali = "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Landroid/content/Intent;->createChooser(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;"
        )
    )
)
