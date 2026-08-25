package app.morphe.patches.youtube.layout.feedfling

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.Opcode

/**
 * [SnappyRecyclerViewFlingFingerprint]'s method only clamps velocity when its
 * own boolean field is true; that field is set from an unrelated, per-build
 * obfuscated constructor, so instead of chasing that constructor by name,
 * every write to the (type-resolved) field itself is turned into a no-op.
 *
 * Older versions predate the field entirely, so the lookup is optional and
 * this is a silent no-op there rather than a failure to apply.
 */
@Suppress("unused")
val disableFeedFlingClampPatch = bytecodePatch(
    name = "Disable feed fling velocity clamp",
    description = "Fixes YouTube's reduced scroll/fling speed by disabling SnappyRecyclerView's " +
            "server-configured velocity clamp.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val enabledField = SnappyRecyclerViewFlingFingerprint.classDef.fields.firstOrNull { field ->
            field.type == "Z"
        } ?: return@execute

        Fingerprint(
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IPUT_BOOLEAN,
                    reference = enabledField,
                )
            )
        ).matchAllMethodIndicesForEach { index ->
            replaceInstruction(index, "nop")
        }
    }
}
