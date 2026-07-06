package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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
 * Matches the method returning the seek source for double tap actions.
 * Caller must supply the seek type enum resolved from [SeekTypeEnumFingerprint].
 */
internal fun doubleTapInfoGetSeekSourceFingerprint(seekTypeEnumType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = seekTypeEnumType,
    parameters = listOf("Z"),
    filters = opcodesToFilters(
        Opcode.IF_EQZ,
        Opcode.SGET_OBJECT,
        Opcode.RETURN_OBJECT,
        Opcode.SGET_OBJECT,
        Opcode.RETURN_OBJECT,
    ),
    custom = { _, classDef ->
        classDef.fields.count() == 4
    }
)
