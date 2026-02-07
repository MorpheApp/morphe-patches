package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal val communityRecommendationSectionFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        string("feedContext")
    )
)

internal val communityRecommendationSectionParentFingerprint = Fingerprint(
    definingClass = "Lcom/reddit/onboardingfeedscomponents/communityrecommendation/impl/",
    name = "key",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    filters = listOf(
        string("community_recomendation_section_")
    )
)