/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val ADD_METHOD_CALL = methodCall(
    opcode = Opcode.INVOKE_INTERFACE,
    smali = "Ljava/util/List;->add(Ljava/lang/Object;)Z"
)

internal object BottomNavScreenListBuilderFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L"),
    filters = listOf(
        newInstance("Ljava/util/ArrayList;"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Ljava/util/Iterator;->hasNext()Z"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lcom/reddit/launch/bottomnav/BottomNavTab;"
        ),
        string(
            string = "tab",
            location = MatchAfterWithin(3)
        )
    )
)

internal object BottomNavScreenResourceBuilderFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/res/Resources;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            parameters = listOf("Ljava/lang/String;", "L")
        ),
        ADD_METHOD_CALL
    ),
    strings = listOf("answersFeatures")
)
