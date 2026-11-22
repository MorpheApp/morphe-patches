/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.effects

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Creates the items that the app adds to the end of the player menu, such as Quality,
 * Captions and Sleep timer. Each item comes from a provider that returns null if the
 * item is not shown.
 */
internal object PlayerMenuAppItemsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    // Optional with the menu renderer.
    parameters = listOf("L", "Z"),
    filters = listOf(
        methodCall(name = "isEmpty", parameters = listOf(), returnType = "Z"),
        // Providers of items that are left out or replaced by an item of the menu.
        opcode(Opcode.INSTANCE_OF),
        opcode(Opcode.INSTANCE_OF),
        // The item of the provider.
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = listOf(), returnType = "L")
    )
)
