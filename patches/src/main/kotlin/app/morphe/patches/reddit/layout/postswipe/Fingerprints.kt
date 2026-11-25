/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5295
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.postswipe

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

/**
 * Synthetic lambda setting up the post detail ScreenPager and its adapter.
 */
internal object PostDetailPagerSetupFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "Lcom/reddit/frontpage/presentation/listing/linkpager/refactor/PostDetailPagerScreen;",
            type = "Lcom/reddit/screen/widget/ScreenPager;"
        ),
        methodCall(
            definingClass = "Lcom/reddit/screen/widget/ScreenPager;",
            name = "setAdapter"
        )
    )
)
