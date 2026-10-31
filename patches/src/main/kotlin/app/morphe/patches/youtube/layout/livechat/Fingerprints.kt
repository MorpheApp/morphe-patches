/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.livechat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Renderers that draw the chat body as a view.
 *
 * Older builds lay out each chat row as a view, and the row renderer resolves the body
 * `TextView` by raw resource id. `R.id.chat_message` resolves to the literal `0x7f0b03c4`,
 * so matching the constructor by that literal scopes the patch to the row renderers without
 * depending on obfuscated class or method names.
 *
 * Current builds draw the chat with Litho, where no row view exists and this fingerprint
 * matches nothing. The patch therefore also hooks the element proto parser; see
 * [LiveChatTranslatePatch] for why both paths are needed.
 */
internal object LiveChatRowConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        literal(0x7f0b03c4),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById",
            returnType = "Landroid/view/View;"
        )
    )
)
