/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ShareSheetPanelContentInitializationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
        ),
        opcode(opcode = Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(type = "Landroid/content/pm/ResolveInfo;", location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            smali = "Landroid/content/pm/ResolveInfo;->activityInfo:Landroid/content/pm/ActivityInfo;",
            location = MatchAfterImmediately(),
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            smali = "Landroid/content/pm/ActivityInfo;->applicationInfo:Landroid/content/pm/ApplicationInfo;",
            location = MatchAfterImmediately(),
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            smali = "Landroid/content/pm/ApplicationInfo;->packageName:Ljava/lang/String;",
            location = MatchAfterImmediately(),
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            smali = "Landroid/content/pm/ResolveInfo;->activityInfo:Landroid/content/pm/ActivityInfo;",
            location = MatchAfterImmediately(),
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            smali = "Landroid/content/pm/ActivityInfo;->name:Ljava/lang/String;",
            location = MatchAfterImmediately(),
        ),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z",
            location = MatchAfterImmediately(),
        )
    )
)
