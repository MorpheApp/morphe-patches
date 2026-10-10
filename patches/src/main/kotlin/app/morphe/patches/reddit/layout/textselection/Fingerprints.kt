package app.morphe.patches.reddit.layout.textselection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// Compose group keys are used as anchors where possible, they survive R8 renaming.

/**
 * Compose rich text renderer, used for post and comment bodies.
 */
internal object ComposeRichTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        literal(1546229805L)
    )
)

/**
 * Compose foundation SelectionContainer(modifier, content).
 * Shape: (int changed, int default, Composer, Modifier, ComposableLambdaImpl) -> void.
 */
internal object ComposeSelectionContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "I", "L", "L", "Landroidx/compose/runtime/internal/a;"),
    filters = listOf(
        literal(1949207773L)
    )
)

/**
 * Compose runtime rememberComposableLambda(key, block, composer).
 */
internal object ComposeComposableLambdaFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Landroidx/compose/runtime/internal/a;",
    parameters = listOf("I", "L", "L"),
    filters = listOf(
        newInstance("Landroidx/compose/runtime/internal/a;")
    )
)

/**
 * Comment click event handler. Clicking a comment collapses or expands it.
 */
internal object CommentClickEventHandlerFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        newInstance($$"Lcom/reddit/comments/events/handler/OnClickCommentEventHandler$handle$1;")
    )
)

/**
 * Selection container tap handler. A touch that does not move more than the touch slop
 * is a tap, which clears the selection.
 */
internal object SelectionTapDetectionFingerprint : Fingerprint(
    definingClass = $$"Landroidx/compose/foundation/text/selection/SelectionManager$onClearSelectionRequested$1$1;",
    name = "invokeSuspend",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "Ljava/lang/Object;",
            parameters = listOf(
                "L",
                "L",
                "Landroidx/compose/ui/input/pointer/PointerEventPass;",
                "Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;"
            )
        )
    )
)

/**
 * awaitAllPointersUpWithSlopDetection(scope, down, pass). Reads the touch slop
 * through a static helper that returns a float.
 */
internal object PointersUpSlopDetectionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "L",
        "L",
        "Landroidx/compose/ui/input/pointer/PointerEventPass;",
        "Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;"
    ),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "F",
            parameters = listOf("L", "I")
        )
    )
)

/**
 * SelectionManager.onRelease(): clears the selection and hides the toolbar.
 */
internal object SelectionReleaseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    custom = { _, classDef ->
        classDef.type.startsWith("Landroidx/compose/foundation/text/selection/")
    },
    filters = listOf(
        methodCall(name = "setValue"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            definingClass = "Lkotlin/jvm/functions/Function1;",
            name = "invoke"
        )
    )
)

/**
 * SelectionManager.setSelection(selection).
 */
internal object SelectionChangedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    custom = { _, classDef ->
        classDef.type.startsWith("Landroidx/compose/foundation/text/selection/")
    },
    filters = listOf(
        methodCall(name = "setValue"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf(), returnType = "V")
    )
)

/**
 * FocusOwner.clearFocus(force). Reddit's post detail screen clears focus on every touch,
 * which also clears the text selection.
 */
internal object FocusClearFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    custom = { _, classDef ->
        classDef.type.startsWith("Landroidx/compose/ui/focus/")
    },
    filters = listOf(
        literal(8L),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("I", "Z", "Z"), returnType = "Z")
    )
)

/**
 * AndroidComposeView.dispatchTouchEvent(MotionEvent): every touch that enters Compose.
 */
internal object ComposeDispatchTouchEventFingerprint : Fingerprint(
    name = "dispatchTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    custom = { _, classDef ->
        classDef.type.startsWith("Landroidx/compose/ui/platform/")
    }
)
