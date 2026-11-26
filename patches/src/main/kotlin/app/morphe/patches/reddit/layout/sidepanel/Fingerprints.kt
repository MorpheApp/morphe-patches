/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4978
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidepanel

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * Creates the view of a screen. Modal screens are wrapped in a ModalBackdropView.
 */
internal object ScreenCreateViewFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/screen/BaseScreen;",
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"),
    filters = listOf(
        // Presentation of the screen (full screen, dialog or bottom sheet).
        methodCall(
            definingClass = "Lcom/reddit/screen/BaseScreen;",
            parameters = listOf()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.INSTANCE_OF, location = MatchAfterImmediately()),
        methodCall(
            definingClass = "Lcom/reddit/screen/dialog/ModalBackdropView;",
            name = "setOnClickedOutside"
        ),
        methodCall(
            definingClass = "Lcom/reddit/screen/BaseScreen;",
            returnType = "Landroidx/appcompat/widget/Toolbar;",
            parameters = listOf()
        ),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterWithin(10))
    )
)

/**
 * Presentation of the post detail screen.
 */
internal object PostDetailPresentationFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/frontpage/presentation/listing/linkpager/refactor/PostDetailPagerScreen;",
    returnType = { ScreenCreateViewFingerprint.instructionMatches.first().methodReference.returnType },
    parameters = listOf()
)

/**
 * Creates the dialog presentation, with the default values for all parameters except the first.
 */
internal object DialogPresentationFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/emailcollection/screens/EmailCollectionPopupScreen;",
    name = "<init>",
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("Z", "L", "L", "I")
        )
    )
)

/**
 * Creates the dialog presentation, before 2026.48.0.
 */
internal object DialogPresentationLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/incognito/screens/welcome/WelcomeIncognitoModeScreen;",
    name = "<init>",
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("Z", "L", "L", "I")
        )
    )
)

/**
 * Opens a post from a feed card with a transition that expands the card.
 */
internal object OpenPostWithTransitionFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/reddit/listing/common/ListingViewMode;",
            name = "isClassic"
        ),
        methodCall(
            returnType = "Lcom/bluelinelabs/conductor/ScreenController;",
            parameters = listOf("L")
        )
    )
)

/**
 * Opens a post. Without a feed card it pushes the post screen without a transition.
 */
internal object OpenPostFingerprint : Fingerprint(
    classFingerprint = OpenPostWithTransitionFingerprint,
    returnType = "V",
    filters = listOf(
        newInstance("Lcom/reddit/frontpage/presentation/listing/linkpager/refactor/PostDetailPagerScreen;"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf("Landroid/content/Context;", "Lcom/reddit/screen/BaseScreen;", "L")
        )
    )
)
