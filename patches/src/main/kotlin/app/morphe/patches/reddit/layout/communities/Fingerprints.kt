package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val communityRecommendationSectionFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    strings = listOf("feedContext"),
)

internal val communityRecommendationSectionParentFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    strings = listOf("community_recomendation_section_"),
    custom = { method, _ ->
        method.definingClass.startsWith("Lcom/reddit/onboardingfeedscomponents/communityrecommendation/impl/") &&
                method.name == "key"
    }
)