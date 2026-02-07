package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val searchTypeaheadListDefaultPresentationConstructorFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    custom = { methodDef, _ ->
        methodDef.name == "<init>"
    }
)

internal val searchTypeaheadListDefaultPresentationToStringFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    filters = listOf(
        string("OnSearchTypeaheadListDefaultPresentation(title=")
    ),
    custom = { methodDef, _ ->
        methodDef.name == "toString"
    }
)

internal val trendingTodayTitleFingerprint = Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(Opcode.AND_INT_LIT8),
    strings = listOf("trending_today_title"),
    custom = { _, classDef ->
        classDef.type.startsWith("Lcom/reddit/") &&
                classDef.type.contains("/composables/")
    },
)

internal val trendingTodayItemFingerprint = Fingerprint(
    returnType = "V",
    filters = listOf(
        string("search_trending_item")
    ),
    custom = { _, classDef ->
        classDef.type.startsWith("Lcom/reddit/search/combined/ui/composables")
    },
)

internal val trendingTodayItemLegacyFingerprint = Fingerprint(
    returnType = "V",
    filters = listOf(
        string("search_trending_item")
    ),
    custom = { _, classDef ->
        classDef.type.startsWith("Lcom/reddit/typeahead/ui/zerostate/composables")
    },
)
