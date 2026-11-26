package app.morphe.patches.youtube.video.buffer

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private val CONST_WIDE_OPCODES = setOf(
    Opcode.CONST_WIDE_16,
    Opcode.CONST_WIDE_32,
    Opcode.CONST_WIDE_HIGH16,
    Opcode.CONST_WIDE
)

/**
 * The player load control's getBackBufferDurationUs: `return 0`.
 */
internal object BackBufferDurationFingerprint : Fingerprint(
    classFingerprint = ShouldContinueLoadingFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "J",
    parameters = listOf(),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList()
        instructions != null &&
            instructions.size == 2 &&
            instructions[0].opcode in CONST_WIDE_OPCODES &&
            instructions[1].opcode == Opcode.RETURN_WIDE
    }
)

/**
 * ExoPlayerImplInternal.doSomeWork, where the back buffer is discarded.
 */
internal object DoSomeWorkFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("doSomeWork")
)

/**
 * Creates the media cache, which is stored on disk.
 */
internal object MediaCacheFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/io/File;", "Ljava/io/File;"),
    strings = listOf("media_cache_initialization_vector")
)
