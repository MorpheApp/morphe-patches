/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4990
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.interactive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.Opcode

/**
 * The custom post feed element, which shows a Reddit Developer Platform (Devvit) post in a feed,
 * says which web view resource it loads ahead of time. It is the only feed element of this source.
 *
 * 2026.24.0+
 */
internal object CustomPostFeedElementResourcesFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            smali = "Lcom/reddit/qsf/components/QsfContentType;->CUSTOM_POST_WEBVIEW:Lcom/reddit/qsf/components/QsfContentType;"
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            smali = "Lcom/reddit/qsf/components/QsfSourceName;->CUSTOM_POST_FEED_ELEMENT:Lcom/reddit/qsf/components/QsfSourceName;"
        )
    )
)

internal object CustomPostFeedElementConstructorFingerprint : Fingerprint(
    classFingerprint = CustomPostFeedElementResourcesFingerprint,
    name = "<init>",
    returnType = "V"
)
