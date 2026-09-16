/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Every browse page is shown by this one fragment, and the endpoint it is handed names the page.
 * The browse request cannot be used instead, because a page served from cache makes no request.
 */
internal object BrowseFragmentOnCreateViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;"
    ),
    filters = listOf(
        string("Browse Fragment was given a navigation endpoint without browse data.")
    )
)

/**
 * The search feed is its own fragment, so it does not go through the browse fragment when
 * the user returns to it from a channel.
 */
internal object SearchResultsFragmentOnCreateViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;"
    ),
    filters = listOf(
        string("search_cache_key")
    )
)

/**
 * Every search submit path funnels through this method, including suggestions and filter chips.
 */
internal object SearchSubmitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        anyInstruction(
            methodCall( // 21.31+
                parameters = listOf(
                    "Ljava/lang/String;",
                    "[B",
                    "Ljava/lang/String;",
                    "I",
                    "L",
                    "L",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Z"
                ),
                returnType = "V"
            ),
            methodCall( // 21.30 and older.
                parameters = listOf(
                    "Ljava/lang/String;",
                    "[B",
                    "Ljava/lang/String;",
                    "I",
                    "L",
                    "L",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;"
                ),
                returnType = "V"
            )
        )
    ),
    custom = { method, _ ->
        parametersMatch( // 21.31+
            method.parameters,
            listOf(
                "Ljava/lang/String;",
                "I",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Z"
            )
        ) || parametersMatch( // 21.30 and older.
            method.parameters,
            listOf(
                "Ljava/lang/String;",
                "I",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;"
            )
        )
    }
)
