/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.playlistsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

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

internal object SearchSubmitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        anyInstruction(
            methodCall(
                parameters = listOf(
                    "Ljava/lang/String;", "[B", "Ljava/lang/String;", "I", "L", "L",
                    "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
                    "Ljava/lang/String;", "Z"
                ),
                returnType = "V"
            ),
            methodCall(
                parameters = listOf(
                    "Ljava/lang/String;", "[B", "Ljava/lang/String;", "I", "L", "L",
                    "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
                    "Ljava/lang/String;"
                ),
                returnType = "V"
            )
        )
    ),
    custom = { method, _ ->
        parametersMatch(
            method.parameters,
            listOf(
                "Ljava/lang/String;", "I", "Ljava/lang/String;", "Ljava/lang/String;",
                "Ljava/lang/String;", "Ljava/lang/String;", "Z"
            )
        ) || parametersMatch(
            method.parameters,
            listOf(
                "Ljava/lang/String;", "I", "Ljava/lang/String;", "Ljava/lang/String;",
                "Ljava/lang/String;", "Ljava/lang/String;"
            )
        )
    }
)
