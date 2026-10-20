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
 * Sets the image url, which is the only string field, and the download model of the post.
 */
internal fun imageDownloadParamsConstructorFingerprint(modelType: String) = Fingerprint(
    classFingerprint = ImageDownloadParamsToStringFingerprint,
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = modelType
        )
    )
)

internal object DownloadVideoFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            definingClass = $$"Landroid/app/DownloadManager$Request;",
            name = "<init>"
        ),
        methodCall(
            definingClass = $$"Landroid/app/DownloadManager$Request;",
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
    definingClass = $$"Lcom/reddit/domain/media/usecase/RedditDownloadMediaUseCase$downloadMediaFile$2;",
    name = "invokeSuspend",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            name = $$"$params"
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
