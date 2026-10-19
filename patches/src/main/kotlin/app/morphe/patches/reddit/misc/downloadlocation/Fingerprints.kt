/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3870
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.downloadlocation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private const val CONTENT_RESOLVER_CLASS = "Landroid/content/ContentResolver;"

/**
 * Main Link constructor, the only one that sets the url.
 */
internal object LinkConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "url",
            type = "Ljava/lang/String;"
        )
    )
)

/**
 * Download model of a post, which is obfuscated in some app versions.
 */
internal object LinkDownloadModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string(", author="),
        string("LinkDownloadModel(subreddit="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        )
    )
)

private object ImageDownloadParamsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf(", isGif=", ", linkModel=")
)

/**
 * The url is the only string parameter.
 */
internal object ImageDownloadParamsConstructorFingerprint : Fingerprint(
    classFingerprint = ImageDownloadParamsToStringFingerprint,
    name = "<init>",
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.count { it == "Ljava/lang/String;" } == 1 &&
                // Kotlin default arguments constructor, which calls the main constructor.
                method.parameterTypes.none { it == "Lkotlin/jvm/internal/DefaultConstructorMarker;" }
    }
)

internal object DownloadVideoFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            definingClass = "Landroid/app/DownloadManager\$Request;",
            name = "<init>"
        ),
        methodCall(
            definingClass = "Landroid/app/DownloadManager\$Request;",
            name = "setDestinationInExternalPublicDir"
        ),
        string("DownloadManager_not_available"),
        methodCall(
            definingClass = "Landroid/app/DownloadManager;",
            name = "enqueue"
        )
    )
)

/**
 * Downloads and saves an image. The first read of the parameters is at the start of the download.
 */
internal object DownloadImageFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/media/usecase/RedditDownloadMediaUseCase\$downloadMediaFile\$2;",
    name = "invokeSuspend",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            name = "\$params"
        )
    )
)

internal object SaveImageFingerprint : Fingerprint(
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/net/Uri;", "Ljava/io/File;", "Ljava/lang/String;"),
    filters = listOf(
        string("relative_path"),
        string("datetaken"),
        methodCall(
            definingClass = CONTENT_RESOLVER_CLASS,
            name = "insert"
        )
    )
)

internal object SaveToGalleryFingerprint : Fingerprint(
    name = "invokeSuspend",
    filters = listOf(
        string("reddit_image_"),
        string("datetaken"),
        methodCall(
            definingClass = CONTENT_RESOLVER_CLASS,
            name = "insert"
        )
    )
)
