package app.morphe.patches.youtube.layout.searchbar

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.youtube.layout.hide.general.yoodlesImageViewFingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val setWordmarkHeaderFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/widget/ImageView;"),
    filters = listOf(
        resourceLiteral(ResourceType.ATTR, "ytPremiumWordmarkHeader"),
        resourceLiteral(ResourceType.ATTR, "ytWordmarkHeader")
    )
)

/**
 * Matches the same method as [yoodlesImageViewFingerprint].
 */
internal val wideSearchbarLayoutFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "L"),
    filters = listOf(
         resourceLiteral(ResourceType.LAYOUT, "action_bar_ringo"),
    )
)
