package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object SearchTypeaheadListDefaultPresentationConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;")
)

internal object SearchTypeaheadListDefaultPresentationToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("OnSearchTypeaheadListDefaultPresentation(title=")
    )
)

internal object TrendingTodayItemFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/search/combined/ui/composables",
    returnType = "V",
    filters = listOf(
        string("search_trending_item")
    )
)

internal object TrendingTodayItemLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/typeahead/ui/zerostate/composables",
    returnType = "V",
    filters = listOf(
        string("search_trending_item")
    )
)
