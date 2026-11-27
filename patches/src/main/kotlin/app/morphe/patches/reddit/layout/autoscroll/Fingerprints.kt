/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.autoscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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

/**
 * Inflates the view of the post detail pager.
 */
internal object PostDetailPagerInflateFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "fragment_pager"),
        methodCall(
            definingClass = "Landroid/view/LayoutInflater;",
            name = "inflate"
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Attached callback of a screen in the navigation stack.
 */
internal object ScreenAttachFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/navstack/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "DESTROYED"),
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "RESUMED")
    )
)

/**
 * Attached callback of a screen in the navigation stack, for app versions before 2026.37.0.
 */
internal object ScreenAttachLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/navstack/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$Event;", name = "ON_RESUME")
    )
)

/**
 * Detached callback of a screen in the navigation stack.
 */
internal object ScreenDetachFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/navstack/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "RESUMED"),
        methodCall(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "isAtLeast"),
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "STARTED")
    )
)

/**
 * Detached callback of a screen in the navigation stack, for app versions before 2026.37.0.
 */
internal object ScreenDetachLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/navstack/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "RESUMED"),
        methodCall(definingClass = "Landroidx/lifecycle/Lifecycle\$State;", name = "isAtLeast"),
        fieldAccess(definingClass = "Landroidx/lifecycle/Lifecycle\$Event;", name = "ON_PAUSE")
    )
)
