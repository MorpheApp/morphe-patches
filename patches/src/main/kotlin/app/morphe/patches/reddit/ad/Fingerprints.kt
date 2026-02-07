package app.morphe.patches.reddit.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val listingFingerprint = Fingerprint(
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
    ),
    custom = { _, classDef ->
        classDef.type.endsWith("/Listing;")
    },
)

internal val submittedListingFingerprint = Fingerprint(
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
    ),
    custom = { _, classDef ->
        classDef.type.endsWith("/SubmittedListing;")
    },
)

internal val adPostSectionConstructorFingerprint = Fingerprint(
    returnType = "V",
    filters = listOf(
        string("sections")
    ),
    custom = { methodDef, _ ->
        methodDef.name == "<init>"
    }
)

internal val adPostSectionToStringFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    strings = listOf(
        "AdPostSection(linkId=",
        ", sections=",
    ),
    custom = { methodDef, _ ->
        methodDef.name == "toString"
    }
)

internal val commentsViewModelConstructorFingerprint = Fingerprint(
    returnType = "V",
    custom = { methodDef, classDef ->
        classDef.superclass == "Lcom/reddit/screen/presentation/CompositionViewModel;" &&
                methodDef.definingClass.endsWith("/CommentsViewModel;") &&
                methodDef.name == "<init>"
    },
)

internal val immutableListBuilderFingerprint = Fingerprint(
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
    ),
    custom = { methodDef, _ ->
        methodDef.name == "<clinit>"
    }
)

internal val postDetailAdLoaderFingerprint = Fingerprint(
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L"),
    custom = { methodDef, classDef ->
        methodDef.name == "invokeSuspend" &&
            classDef.type.contains("/RedditPostDetailAdLoader\$loadPostDetailAds$")
    }
)