package app.morphe.patches.youtube.layout.hide.player.flyoutmenu

import app.morphe.patcher.Fingerprint

internal object RecyclerViewOnAttachedToWindowFingerprint : Fingerprint(
    definingClass = "Landroidx/recyclerview/widget/RecyclerView;",
    name = "onAttachedToWindow",
    returnType = "V"
)