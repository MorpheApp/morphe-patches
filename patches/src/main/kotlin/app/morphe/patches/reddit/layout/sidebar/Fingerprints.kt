package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val communityDrawerPresenterConstructorFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        fieldAccess(name = "RECENTLY_VISITED")
    ),
    strings = listOf("communityDrawerSettings")
)

internal val communityDrawerPresenterFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
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

// TODO: Convert this to methodCall() instruction filter
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
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        fieldAccess(name = "REDDIT_PRO"),
        opcode(Opcode.IPUT_OBJECT)
    ),
    custom = { methodDef, _ ->
        methodDef.parameterTypes.firstOrNull() == "Ljava/lang/Object;"
    }
)

internal val sidebarComponentsPatchFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    custom = { methodDef, classDef ->
        methodDef.name == "getHeaderItemName" &&
                classDef.type.endsWith("/SidebarComponentsPatch;")
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

// TODO: Replace with fieldAccess() instruction filter usage.
internal fun indexOfHeaderItemInstruction(
    methodDef: Method,
    fieldName: String = "RECENTLY_VISITED",
) = methodDef.indexOfFirstInstruction {
    getReference<FieldReference>()?.name == fieldName
}
