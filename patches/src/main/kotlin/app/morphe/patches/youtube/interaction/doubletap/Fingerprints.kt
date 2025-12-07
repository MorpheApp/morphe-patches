package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val seekTypeEnumFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "SEEK_SOURCE_SEEK_TO_NEXT_CHAPTER",
        "SEEK_SOURCE_SEEK_TO_PREVIOUS_CHAPTER"
    )
)

internal val doubleTapInfoCtorFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Landroid/view/MotionEvent;",
        "I",
        "Z",
        "Lj\$/time/Duration;"
    )
)
