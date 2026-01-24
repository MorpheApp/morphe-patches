package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val communityDrawerPresenterConstructorFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    strings = listOf("communityDrawerSettings"),
    custom = { methodDef, _ ->
        indexOfHeaderItemInstruction(methodDef) >= 0
    }
)

internal val communityDrawerPresenterFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.XOR_INT_2ADDR,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
    ),
    custom = { methodDef, _ ->
        indexOfKotlinCollectionInstruction(methodDef) >= 0
    }
)

internal fun indexOfKotlinCollectionInstruction(
    methodDef: Method,
    startIndex: Int = 0
) = methodDef.indexOfFirstInstruction(startIndex) {
    val reference = getReference<MethodReference>()
    opcode == Opcode.INVOKE_STATIC &&
            reference?.returnType == "Ljava/util/ArrayList;" &&
            reference.definingClass.startsWith("Lkotlin/collections/") &&
            reference.parameterTypes.size == 2 &&
            reference.parameterTypes[0].toString() == "Ljava/lang/Iterable;" &&
            reference.parameterTypes[1].toString() == "Ljava/util/Collection;"
}

internal val redditProLoaderFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    custom = { methodDef, _ ->
        methodDef.parameterTypes.firstOrNull() == "Ljava/lang/Object;" &&
                indexOfHeaderItemInstruction(methodDef, "REDDIT_PRO") >= 0
    }
)

internal fun indexOfHeaderItemInstruction(
    methodDef: Method,
    fieldName: String = "RECENTLY_VISITED",
) = methodDef.indexOfFirstInstruction {
    getReference<FieldReference>()?.name == fieldName
}

internal val sidebarComponentsPatchFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    custom = { methodDef, _ ->
        methodDef.definingClass.endsWith("/SidebarComponentsPatch;") &&
                methodDef.name == "getHeaderItemName"
    }
)

internal val headerItemUiModelToStringFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "HeaderItemUiModel(uniqueId=",
        ", type="
    ),
    custom = { methodDef, _ ->
        methodDef.name == "toString"
    }
)
