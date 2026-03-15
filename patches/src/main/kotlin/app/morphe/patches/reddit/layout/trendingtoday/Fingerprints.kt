/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private object SearchTypeaheadListDefaultPresentationToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("OnSearchTypeaheadListDefaultPresentation(title=")
    )
)

internal object SearchTypeaheadListDefaultPresentationConstructorFingerprint : Fingerprint(
    classFingerprint = SearchTypeaheadListDefaultPresentationToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;")
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
