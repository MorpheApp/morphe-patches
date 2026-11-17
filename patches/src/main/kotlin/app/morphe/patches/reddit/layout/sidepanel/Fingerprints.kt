/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4978
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidepanel

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

internal object ScreenCreateViewFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/screen/BaseScreen;",
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/reddit/screen/BaseScreen;", parameters = listOf(), location = MatchFirst()),
        methodCall(definingClass = "Lcom/reddit/screen/dialog/ModalBackdropView;", name = "setOnClickedOutside"),
        methodCall(definingClass = "Lcom/reddit/screen/BaseScreen;", returnType = "Landroidx/appcompat/widget/Toolbar;", parameters = listOf()),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterWithin(10))
    )
)

internal object ModalPresentationFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/incognito/screens/welcome/WelcomeIncognitoModeScreen;",
    name = "<init>",
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("Z", "Lkotlin/jvm/functions/Function0;", "Lkotlin/jvm/functions/Function2;", "I")
        )
    )
)

internal object FeedCardOpenPostFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "L",
        "Lcom/reddit/frontpage/presentation/listing/linkpager/refactor/PostDetailPagerScreen;",
        "Lcom/reddit/listing/common/ListingType;",
        "L"
    ),
    filters = listOf(
        newInstance("Lcom/reddit/frontpage/presentation/detail/transition/BaliDetailScreenChangeHandler;")
    )
)

internal object NavigateToScreenFingerprint : Fingerprint(
    classFingerprint = FeedCardOpenPostFingerprint,
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf("Landroid/content/Context;", "Lcom/reddit/screen/BaseScreen;", "L")
        )
    )
)
