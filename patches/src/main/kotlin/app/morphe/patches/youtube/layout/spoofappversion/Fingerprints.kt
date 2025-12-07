package app.morphe.patches.youtube.layout.spoofappversion

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.*
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val toolBarButtonFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "menu_item_view"),
        methodCall(returnType = "I", opcode = Opcode.INVOKE_INTERFACE),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        fieldAccess(type = "Landroid/widget/ImageView;", opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(6)),
        methodCall("Landroid/content/res/Resources;", "getDrawable", location = MatchAfterWithin(8)),
        methodCall("Landroid/widget/ImageView;", "setImageDrawable", location = MatchAfterWithin(4))
    ),
    custom = { method, _ ->
        // 20.37+ has second parameter of "Landroid/content/Context;"
        val parameterCount = method.parameterTypes.count()
        (parameterCount == 1 || parameterCount == 2)
                && method.parameterTypes.firstOrNull() == "Landroid/view/MenuItem;"
    }
)

internal val spoofAppVersionFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.GOTO,
        Opcode.CONST_STRING,
    ),
    // Instead of applying a bytecode patch, it might be possible to only rely on code from the extension and
    // manually set the desired version string as this keyed value in the SharedPreferences.
    // But, this bytecode patch is simple and it works.
    strings = listOf("pref_override_build_version_name")
)
