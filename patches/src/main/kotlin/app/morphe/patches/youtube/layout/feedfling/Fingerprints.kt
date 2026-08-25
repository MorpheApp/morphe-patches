package app.morphe.patches.youtube.layout.feedfling

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * SnappyRecyclerView keeps its real class name (referenced from XML layouts,
 * so LayoutInflater must be able to find it), unlike most classes in this
 * app, making it a stable target. Matched by name + signature rather than
 * by the method's own (per-build obfuscated) name.
 */
internal object SnappyRecyclerViewFlingFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/rendering/SnappyRecyclerView;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("I", "I"),
)
