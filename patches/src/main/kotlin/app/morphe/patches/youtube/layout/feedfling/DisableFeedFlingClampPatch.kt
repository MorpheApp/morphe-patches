package app.morphe.patches.youtube.layout.feedfling

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.Opcode

/**
 * Real fix for morphe-patches issue #94 (scroll-speed throttling).
 *
 * SnappyRecyclerView.fling(int,int) - see [SnappyRecyclerViewFlingFingerprint] -
 * only applies its velocity clamp when its own boolean field is true. Every
 * constructor already initializes that field to false; it is only flipped to
 * true from one call site, in an otherwise-unrelated (and per-build
 * obfuscated) class's constructor, which reads a server-delivered config
 * proto field and does:
 *
 *     if (i > 0) {
 *         snappyRecyclerView.af = true;
 *         snappyRecyclerView.ag = i;
 *     }
 *
 * Rather than chase that obfuscated constructor by name (it renames every
 * build), this finds the write generically: the *field being written* is
 * SnappyRecyclerView's own boolean field, resolved by type (it's the only
 * boolean instance field on that class) from the one stable class in this
 * whole chain. Every IPUT_BOOLEAN instruction anywhere in the app that
 * targets that exact field is then replaced with a no-op, so the field
 * keeps its constructor default of false and the clamp branch in fling()
 * never engages - regardless of what the server sends.
 *
 * Does not touch `ag`; it's irrelevant once `af` can never become true.
 *
 * Older supported versions (e.g. 20.21.37, 20.31.42) predate this clamp
 * entirely and SnappyRecyclerView has no boolean field at all on those
 * builds, so the field lookup below is intentionally optional: if it's
 * absent, this patch has nothing to do and is a silent no-op rather than
 * failing to apply on unaffected versions.
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
