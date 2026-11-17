package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import com.android.tools.smali.dexlib2.Opcode

internal val nsfwCompatibility = COMPATIBILITY_REDDIT.excluding(
    *COMPATIBILITY_REDDIT.targets.mapNotNull {
        it.version.takeUnless { version -> version in setOf("2026.40.0", "2026.45.0") }
    }.toTypedArray()
)

// This repository getter also reads the active anonymous session's saved setting.
internal object NativeNsfwViewingFingerprint : Fingerprint(
    parameters = listOf(), returnType = "Z",
    filters = listOf(string("nsfw_over18_enabled"), methodCall(
        definingClass = "Lcom/reddit/domain/model/AccountPreferences;", name = "getOver18"
    ))
)

internal object NsfwHomeNavigationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        fieldAccess(type = "Lkotlin/jvm/functions/Function0;", opcode = Opcode.IGET_OBJECT),
        methodCall(smali = "Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;"),
        newInstance("Landroid/content/Intent;"),
        string("com.reddit.frontpage.main_toast_message")
    )
)

internal object NsfwReplaceScreenFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;", "Lcom/reddit/screen/BaseScreen;"),
    returnType = "V",
    filters = listOf(methodCall(
        parameters = listOf("Lcom/reddit/screen/BaseScreen;", "Lcom/reddit/screen/BaseScreen;"),
        returnType = "V"
    ))
)
