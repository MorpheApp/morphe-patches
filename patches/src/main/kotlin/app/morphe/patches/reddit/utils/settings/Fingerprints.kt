package app.morphe.patches.reddit.utils.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patches.reddit.utils.extension.Constants.EXTENSION_PATH
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.TypeReference


const val EXTENSION_SETTING_CLASS_DESCRIPTOR = "Lapp/morphe/extension/shared/settings/Setting;"

internal val preferenceDestinationFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Lcom/reddit/domain/settings/Destination;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
    ),
    strings = listOf("settingIntentProvider"),
    custom = { methodDef, _ ->
        methodDef.definingClass.startsWith("Lcom/reddit/screen/settings/preferences/")
    }
)

internal val preferenceManagerFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    custom = { methodDef, _ ->
        fun indexOfPreferencesPresenterInstruction(methodDef: Method) =
            methodDef.indexOfFirstInstruction {
                opcode == Opcode.NEW_INSTANCE &&
                        getReference<TypeReference>()?.type?.contains("checkIfShouldShowImpressumOption") == true
            }
        indexOfPreferencesPresenterInstruction(methodDef) >= 0
    }
)

internal val preferenceManagerParentFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    strings = listOf("prefs_share_contacts_painted_door")
)

internal val redditInternalFeaturesFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    strings = listOf("RELEASE"),
    custom = { methodDef, _ ->
        !methodDef.definingClass.startsWith("Lcom/")
    }
)

internal val webBrowserActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("com.reddit.extra.initial_url"),
    custom = { methodDef, _ ->
        methodDef.definingClass.endsWith("/WebBrowserActivity;") &&
                methodDef.name == "onCreate"
    }
)

internal val settingsStatusLoadFingerprint = Fingerprint(
    custom = { methodDef, _ ->
        methodDef.definingClass.endsWith("$EXTENSION_PATH/settings/SettingsStatus;") &&
                methodDef.name == "load"
    }
)

internal val sharedSettingFingerprint = Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.definingClass == EXTENSION_SETTING_CLASS_DESCRIPTOR &&
                method.name == "<clinit>"
    }
)