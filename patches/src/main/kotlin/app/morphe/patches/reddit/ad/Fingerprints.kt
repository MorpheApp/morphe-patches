package app.morphe.patches.reddit.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
    strings = listOf("sections"),
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
    custom = { methodDef, _ ->
        methodDef.name == "<clinit>" &&
                indexOfAutoplayVideoPreviewsOptionInstruction(methodDef) >= 0 &&
                indexOfImmutableListBuilderInstruction(methodDef) >= 0
    }
)

internal val postDetailAdLoaderFingerprint = Fingerprint(
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L"),
    custom = { methodDef, _ ->
        methodDef.definingClass.contains("/RedditPostDetailAdLoader\$loadPostDetailAds$")
                && methodDef.name == "invokeSuspend"
    }
)

internal fun indexOfAutoplayVideoPreviewsOptionInstruction(methodDef: Method) =
    methodDef.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_STATIC &&
                reference?.name == "getEntries" &&
                reference.definingClass == "Lcom/reddit/accessibility/AutoplayVideoPreviewsOption;"
    }

internal fun indexOfImmutableListBuilderInstruction(methodDef: Method) =
    methodDef.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_STATIC &&
                reference?.parameterTypes?.size == 1 &&
                reference.parameterTypes.firstOrNull() == "Ljava/lang/Iterable;"
    }