/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.trending

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// 2026.16.0+
internal object LocaleLanguageManagerConstructorFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/res/Configuration;"),
    filters = listOf(
        methodCall(smali = "Landroid/content/Context;->getApplicationContext()Landroid/content/Context;"),
        methodCall(smali = "Ljava/util/Locale;->toLanguageTag()Ljava/lang/String;"),
        methodCall(smali = "Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V"),
        fieldAccess(smali = "Ljava/util/Locale;->ENGLISH:Ljava/util/Locale;"),
        string("UI_LANGUAGE_TAG")
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

internal object SearchSectionHeaderFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("search_section_header"),
        string("search_section_title")
    )
)

internal object TrendingItemFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("search_trending_item")
    )
)

internal object TrendingItemLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/typeahead/ui/zerostate/composables",
    returnType = "V",
    filters = listOf(
        string("search_trending_item")
    )
)

internal object TypeaheadSuggestionItemFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("typeahead_suggestion_item")
    )
)

internal object TrendingFeedUnitSectionFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("trending_feed_unit_section")
    )
)

internal object TrendingFeedUnitDismissedSectionFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("trending_feed_unit_dismissed_section")
    )
)
