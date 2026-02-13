package app.morphe.patches.reddit.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val listingFingerprint = Fingerprint(
    definingClass = "/Listing;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_DIRECT,
        Opcode.IPUT_OBJECT
    ),
    // "children" are present throughout multiple versions
    strings = listOf(
        "children",
        "uxExperiences"
    )
)

internal val submittedListingFingerprint = Fingerprint(
    definingClass = "/SubmittedListing;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_DIRECT,
        Opcode.IPUT_OBJECT
    ),
    // "children" are present throughout multiple versions
    strings = listOf(
        "children",
        "videoUploads"
    )
)

internal val adPostSectionConstructorFingerprint = Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        string("sections")
    )
)

internal val adPostSectionToStringFingerprint = Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    strings = listOf(
        "AdPostSection(linkId=",
        ", sections=",
    )
)

internal val commentsViewModelConstructorFingerprint = Fingerprint(
    definingClass = "/CommentsViewModel;",
    name = "<init>",
    returnType = "V",
    custom = { _, classDef ->
        classDef.superclass == "Lcom/reddit/screen/presentation/CompositionViewModel;"
    }
)

internal val immutableListBuilderFingerprint = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Lcom/reddit/accessibility/AutoplayVideoPreviewsOption;",
            name = "getEntries"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/Iterable;")
        )
    )
)

internal val postDetailAdLoaderFingerprint = Fingerprint(
    definingClass = "/RedditPostDetailAdLoader\$loadPostDetailAds$",
    name = "invokeSuspend",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L")
)