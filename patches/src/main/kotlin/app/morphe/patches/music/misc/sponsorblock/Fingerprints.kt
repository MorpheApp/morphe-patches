package app.morphe.patches.music.misc.sponsorblock

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Matches {@code MusicPlaybackControlsTimeBar.draw(Canvas)}.
 * Draws segment markers on the compact/mini player seekbar.
 */
internal object MusicTimeBarDrawFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.definingClass.endsWith("/MusicPlaybackControlsTimeBar;") &&
                method.name == "draw"
    },
)

/**
 * Matches {@code MusicPlaybackControlsTimeBar.onMeasure(int, int)}.
 * Resolves the Rect field used for seekbar bounds.
 */
internal object MusicTimeBarOnMeasureFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.definingClass.endsWith("/MusicPlaybackControlsTimeBar;") &&
                method.name == "onMeasure"
    },
)

internal fun indexOfInvalidateInstruction(method: Method) =
    method.indexOfFirstInstructionReversed {
        getReference<MethodReference>()?.name == "invalidate"
    }
