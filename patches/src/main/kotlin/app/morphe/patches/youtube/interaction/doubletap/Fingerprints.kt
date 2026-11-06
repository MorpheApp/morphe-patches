package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

internal object SeekTypeEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "SEEK_SOURCE_SEEK_TO_NEXT_CHAPTER",
        "SEEK_SOURCE_SEEK_TO_PREVIOUS_CHAPTER"
    )
)

internal object DoubleTapInfoCtorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Landroid/view/MotionEvent;",
        "I",
        "Z",
        "Lj$/time/Duration;"
    )
)

/**
 * A/B flag that turns off the two-finger double tap chapter skip.
 */
internal object DoubleTapChapterSkipFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45767151L)
    )
)
