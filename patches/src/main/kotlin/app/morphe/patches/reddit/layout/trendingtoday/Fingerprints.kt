/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// 2026.16.0+
internal object LocaleLanguageManagerConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        string("CN"),
        string("zh"),
        string("繁體中文")
    )
)

internal object LocaleLanguageManagerConstructorLegacyFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        string("localeLanguageManager"),
        opcode(Opcode.RETURN_VOID)
    )
)

internal object LocaleLanguageManagerContentLanguagesFingerprint : Fingerprint(
    // classDef is either LocaleLanguageManagerConstructorFingerprint or LocaleLanguageManagerConstructorLegacyFingerprint
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/", // 'Ljava/util/ArrayList;' or 'Ljava/util/List;'
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/ArrayList;",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

 private object SearchTypeaheadListDefaultPresentationToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("OnSearchTypeaheadListDefaultPresentation(", comparison = StringComparisonType.STARTS_WITH)
    )
)

internal object SearchTypeaheadListDefaultPresentationConstructorFingerprint : Fingerprint(
    classFingerprint = SearchTypeaheadListDefaultPresentationToStringFingerprint,
    name = "<init>",
    returnType = "V",
    custom = { method, _ ->
        parametersMatch(
            method.parameterTypes,
            listOf("Ljava/lang/String;")
        ) || parametersMatch( // 2026.16.0+
            method.parameterTypes,
            listOf("Ljava/lang/String;", "Ljava/lang/String;")
        )
    }
)

internal object TrendingTodayItemFingerprint : Fingerprint(
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
