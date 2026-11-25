/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5296
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.answers

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * Normal Reddit Answers preview section in search results.
 */
internal object SearchAnswersPreviewSectionToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("SearchAnswersPreviewSection(viewState=")
    )
)

/**
 * Streaming Reddit Answers preview section in search results.
 */
internal object SearchAnswersPreviewStreamingSectionToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("SearchAnswersPreviewStreamingSection(viewState=")
    )
)
