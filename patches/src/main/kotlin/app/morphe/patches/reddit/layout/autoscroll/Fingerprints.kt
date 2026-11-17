/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.autoscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FeedContextToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("FeedContext(onEvent="),
        string(", feedVisibilityFlow=")
    )
)

internal object MasonryGridStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("I", "I", "L"),
            returnType = "Landroidx/compose/foundation/lazy/staggeredgrid/"
        )
    )
)

internal object FeedVisibilityEventFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/impl/ui/events/FeedVisibilityEvent;",
    name = "<init>",
    parameters = listOf("Lcom/reddit/feeds/ui/FeedVisibility;")
)

internal object MainActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/main/MainActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf()
)

internal object MainActivityOnPauseFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/main/MainActivity;",
    name = "onPause",
    returnType = "V",
    parameters = listOf()
)

internal object NavBarStateChangeFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen\$onNavBarStateChange\$1;",
    name = "<init>"
)

internal object PostPagerInflateFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "fragment_pager"),
        methodCall(
            definingClass = "Landroid/view/LayoutInflater;",
            name = "inflate"
        )
    )
)
