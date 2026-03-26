/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private object CommunityRecommendationSectionParentFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/onboardingfeedscomponents/communityrecommendation/impl/",
    name = "key",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("community_recomendation_section_")
    )
)

internal object CommunityRecommendationSectionFingerprint : Fingerprint(
    classFingerprint = CommunityRecommendationSectionParentFingerprint,
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        string("feedContext")
    )
)
