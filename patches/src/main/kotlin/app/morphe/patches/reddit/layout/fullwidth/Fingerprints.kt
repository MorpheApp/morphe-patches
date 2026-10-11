/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.fullwidth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * toString of a feed post style, which logs whether media is inset.
 */
internal object FeedPostStyleToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string(", mediaInsetEnabled="),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Getter of the feed post style 'mediaInsetEnabled' field.
 */
internal fun mediaInsetGetterFingerprint(definingClass: String, insetField: FieldReference) = Fingerprint(
    definingClass = definingClass,
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(insetField, Opcode.IGET_BOOLEAN, location = MatchFirst())
    )
)

/**
 * The 'mediaInsetEnabled' getter of every feed post style (regular, crosspost, ...),
 * which all extend the same base class.
 */
internal fun feedPostStyleMediaInsetFingerprint(baseClassType: String, getterName: String) = Fingerprint(
    name = getterName,
    returnType = "Z",
    parameters = listOf(),
    custom = { _, classDef -> classDef.superclass == baseClassType }
)

/**
 * Converts the 16dp side padding of inset media (32dp in total) to pixels.
 */
private fun mediaInsetFilters() = listOf(
    literal(32.0f),
    methodCall(
        parameters = listOf("F"),
        returnType = "I",
        location = MatchAfterWithin(3)
    )
)

/**
 * Caps the height of a feed image to 4:3 of the inset media width.
 */
internal object FeedImageMaxHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf("I", "L", "L"),
    filters = mediaInsetFilters() + methodCall(smali = "Ljava/lang/Integer;->min(II)I")
)

/**
 * Height of a feed video, from its aspect ratio and the inset media width.
 */
internal object FeedVideoHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "F",
    parameters = listOf("L", "L", "L"),
    filters = mediaInsetFilters() + methodCall(smali = "Ljava/lang/Integer;->min(II)I")
)

/**
 * Height of a feed gallery, from the size of its images and the inset media width.
 */
internal object FeedGalleryHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "F",
    parameters = listOf("Z", "I", "L", "L", "L"),
    filters = mediaInsetFilters()
)

/**
 * Size of a feed image, from its size, the screen size and whether the media is inset.
 * Without a maximum aspect ratio, the height is capped to 4:3 of the inset media width.
 */
internal object FeedImageSizeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("I", "I", "I", "I", "Z", "Ljava/lang/Float;", "Z", "L"),
    filters = mediaInsetFilters() + literal(4f / 3f)
)

/**
 * toString of the image component props, used in the post details,
 * which logs whether the image is inset.
 */
internal object PostImagePropsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("PostImageComponentProps(", StringComparisonType.STARTS_WITH),
        string(", applyInset="),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Constructor of the image component props, which sets whether the image is inset.
 */
internal fun postImagePropsConstructorFingerprint(definingClass: String, insetField: FieldReference) = Fingerprint(
    definingClass = definingClass,
    name = "<init>",
    filters = listOf(
        fieldAccess(insetField, Opcode.IPUT_BOOLEAN)
    )
)

/**
 * toString of the post details content props, which logs the post content first.
 */
internal object PostContentPropsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("PostUnitContentProps(data="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * toString of the GIF or video post content.
 */
internal object GifAndVideoContentToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("GifAndVideo(", StringComparisonType.STARTS_WITH)
    )
)

/**
 * Lambda showing the content of a post in the post details. Its 5th parameter pads
 * the content at the sides. Videos are padded by it, while images inset themselves.
 */
internal fun postContentLambdaConstructorFingerprint(propsType: String) = Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        propsType,
        "L",
        "L",
        "L",
        "Z",
        "L",
        "L",
        "L",
        "L",
        "Z",
        "Ljava/lang/Float;",
        "Z",
        "Z"
    )
)

/**
 * GIF or video in the post details: (videoInfo, autoplay, width, height, ...).
 * The width and height are the display size, for the screen width minus the side padding.
 * Its 15th parameter sizes the video to that width and height.
 */
internal object PostDetailVideoFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("media_content"),
        fieldAccess(
            definingClass = "Landroidx/compose/foundation/layout/IntrinsicSize;",
            name = "Min"
        )
    )
)

/**
 * Gallery in the post details, since 2026.36.0. Its 14th parameter insets the gallery
 * with side padding and rounded corners.
 */
internal object PostDetailGalleryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "Z",
        "Lkotlin/jvm/functions/Function1;",
        "Ljava/lang/String;",
        "L",
        "I",
        "Z",
        "Z",
        "Z",
        "Z",
        "Z",
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "Z",
        "Ljava/lang/Float;",
        "L",
        "I",
        "I"
    ),
    filters = listOf(
        string("post_media_gallery_content")
    )
)
