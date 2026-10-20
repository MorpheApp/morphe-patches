/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3872
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.animations

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * A/B test: the page transitions ask for the animation decision.
 * When off, they always use their animations.
 */
internal const val PAGE_TRANSITION_ANIMATION_HINTS_FEATURE_FLAG = 45694309L

/**
 * 20.41+
 *
 * A/B test: the Shorts player asks for the animation decision.
 * When off, it always uses its animations.
 */
internal const val SHORTS_ANIMATION_HINTS_FEATURE_FLAG = 45728204L

/**
 * 20.49+
 *
 * A/B test: the list below the player uses the flow layout manager, with an item animator
 * that does not animate its content when it loads.
 * When off, the content expands with its animation when it loads.
 */
internal const val FLOW_LAYOUT_WATCH_PANEL_FEATURE_FLAG = 45744104L

internal object PageTransitionAnimationHintsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(PAGE_TRANSITION_ANIMATION_HINTS_FEATURE_FLAG)
    )
)

internal object ShortsAnimationHintsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(SHORTS_ANIMATION_HINTS_FEATURE_FLAG)
    )
)

internal object FlowLayoutWatchPanelFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(FLOW_LAYOUT_WATCH_PANEL_FEATURE_FLAG)
    )
)

/**
 * YouTube's adaptive animation helper, which looks up whether an animation category
 * is on, throttled or off in a map the server sends. Every animation of the app that
 * the server can turn off asks this method.
 */
internal object AnimationDecisionForCategoryFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        // Has-bits of the category hint.
        fieldAccess(opcode = Opcode.IGET, type = "I"),
        // The animation category.
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterWithin(5)),
        string("#getAnimationDecisionForCategory: no category provided")
    )
)
