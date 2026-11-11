/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.audioducking

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object AudioFocusRequestFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("AudioFocus Requested"),
        // AudioFocusRequestCompat constructor, the last parameter is 'will pause when ducked'.
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf(
                $$"Landroid/media/AudioManager$OnAudioFocusChangeListener;",
                "Landroid/os/Handler;",
                "Landroidx/media/AudioAttributesCompat;",
                "Z"
            )
        )
    )
)

internal object AudioFocusChangeFingerprint : Fingerprint(
    name = "onAudioFocusChange",
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        string("AudioFocus DUCK")
    )
)
