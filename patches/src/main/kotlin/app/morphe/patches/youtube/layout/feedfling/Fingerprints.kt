package app.morphe.patches.youtube.layout.feedfling

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.google.android.apps.youtube.app.common.rendering.SnappyRecyclerView is
 * YouTube's own feed RecyclerView subclass. It keeps its real, unobfuscated
 * class name because it's referenced from XML layouts (section_list.xml
 * etc.) and must be found by LayoutInflater reflection - unlike almost every
 * other class in this app, which gets fully re-obfuscated every build. That
 * makes it a stable anchor to target directly by name.
 *
 * It overrides RecyclerView's public 2-arg fling(int,int) entry point and,
 * when its own boolean field is true, clamps both arguments to
 * [-ag, ag] via Math.max/Math.min before ever calling super.fling() - this
 * is the actual cause of morphe-patches issue #94 (YouTube feed/playlist
 * scroll feels artificially slow, starting around 20.37.48).
 *
 * `ag` is populated from a server-delivered config proto field, read in an
 * unrelated, per-build-obfuscated class's constructor, gated on the same
 * boolean field, which starts false in every SnappyRecyclerView constructor.
 *
 * Matched here by class name + signature (I, I) -> Z, which is stable
 * regardless of what letter R8 assigns the method itself.
 */
internal object SnappyRecyclerViewFlingFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/rendering/SnappyRecyclerView;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("I", "I"),
)
