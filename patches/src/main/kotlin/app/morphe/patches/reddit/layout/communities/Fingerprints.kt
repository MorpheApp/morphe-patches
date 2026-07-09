/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
@file:Suppress("ClassName")

package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private object CommunityRecommendationSectionParentFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("community_recomendation_section_")
    )
)

internal object CommunityRecommendationSection_2026_18_Fingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    strings = listOf(
        "section_title",
        "recommendation_chaining"
    )
)

internal object CommunityRecommendationSection_2026_16_Fingerprint : Fingerprint(
    classFingerprint = CommunityRecommendationSectionParentFingerprint,
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        literal(2140398383),
        literal(2043119215)
    )
)

internal object CommunityRecommendationSectionLegacyFingerprint : Fingerprint(
    classFingerprint = CommunityRecommendationSectionParentFingerprint,
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        string("feedContext")
    )
)

internal object CommunityRecommendationsComposeMethodFingerprint : Fingerprint(
    classFingerprint = CommunityRecommendationSectionParentFingerprint,
    returnType = "V",
    parameters = listOf("L", "L", "I")
)
