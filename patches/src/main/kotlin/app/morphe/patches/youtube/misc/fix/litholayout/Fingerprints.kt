/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5331
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.litholayout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The layouts of the components are calculated on the main thread instead of the layout threads.
 */
internal const val LAYOUTS_ON_MAIN_THREAD_FLAG = 45532131L

/**
 * A layout calculated on a layout thread can be stopped, and resumed on the main thread when
 * the main thread needs it, so the same layout runs on two threads.
 */
internal const val INTERRUPTIBLE_LAYOUTS_FLAG = 45650902L

/**
 * The items of a list whose height follows its tallest item, such as a horizontal shelf, measure their
 * height again after each layout, not only after the state updates. The item is measured again by laying
 * out its mounted component tree with unspecified sizes, which the list then measures again with its height,
 * so the two layouts replace each other in a loop. YouTube 21.28+.
 */
internal const val MEASURE_ITEMS_AFTER_EACH_LAYOUT_FLAG = 45807884L

/**
 * The heights of the items of a list are removed when the items are released.
 * Otherwise, the heights of the released items stay and the list keeps the height of the tallest item ever shown.
 * YouTube 21.23+.
 */
internal const val REMOVE_RELEASED_ITEM_HEIGHTS_FLAG = 45795775L

/**
 * A Litho view that gets another component tree detaches its old tree only if the old tree is still in this view.
 * Otherwise, a tree moved meanwhile to another view, such as a recycled list item, is detached from that view.
 * YouTube 20.51+.
 */
internal const val DETACH_ONLY_OWN_TREE_FLAG = 45708674L

/**
 * State updates dispatched while a layout is calculated after which Litho reports a loop.
 */
internal const val MAX_STATE_UPDATES_DURING_LAYOUT = 50L

/**
 * RecyclerBinder, which lays out the items of the Litho lists.
 */
internal object RecyclerBinderFingerprint : Fingerprint(
    filters = listOf(
        string("Too many retries -- RecyclerView is stuck in layout. Batch size: ")
    )
)

/**
 * Creates the map of the heights of the list items, which the layout threads and the main thread change.
 */
internal object RecyclerBinderConstructorFingerprint : Fingerprint(
    classFingerprint = RecyclerBinderFingerprint,
    name = "<init>",
    filters = listOf(
        newInstance("Ljava/util/HashMap;"),
        methodCall(smali = "Ljava/util/HashMap;-><init>()V", location = MatchAfterWithin(5)),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/Map;",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Called when a list item is measured, on a layout thread or the main thread. Puts the height of the item
 * in the map of the heights of the list items, unless the map already has another height of the item.
 */
internal object ItemMeasureListenerFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        fieldAccess(
            // Field of the map of the heights.
            reference = { RecyclerBinderConstructorFingerprint.instructionMatches.last().fieldReference },
            opcodes = listOf(Opcode.IGET_OBJECT)
        ),
        methodCall(
            smali = "Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z",
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.IF_EQZ, location = MatchAfterWithin(5)),
        methodCall(
            smali = "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
            location = MatchAfterWithin(5)
        ),
        checkCast("Ljava/lang/Integer;", location = MatchAfterWithin(5))
    )
)

/**
 * Calculates the layout again after a state update. The state updates dispatched while a layout is calculated
 * are counted, and the 50th is reported as a loop, but the layouts go on.
 */
internal object ComponentTreeUpdateStateFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/litho/ComponentTree;",
    returnType = "V",
    parameters = listOf("Z", "Ljava/lang/String;", "Z"),
    filters = listOf(
        // Count of the state updates of the current layout.
        fieldAccess(
            opcode = Opcode.IPUT,
            definingClass = "this",
            type = "I"
        ),
        literal(MAX_STATE_UPDATES_DURING_LAYOUT, location = MatchAfterWithin(5)),
        string("State update loop during layout detected. Most recent attribution: ")
    )
)

/**
 * Sets the Litho configuration from the A/B flags, such as the layout threads.
 */
internal object LithoConfigurationFeatureFlagsFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        literal(LAYOUTS_ON_MAIN_THREAD_FLAG),
        literal(INTERRUPTIBLE_LAYOUTS_FLAG)
    )
)
