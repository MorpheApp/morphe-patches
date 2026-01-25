package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

internal val bottomNavScreenFingerprint = Fingerprint(
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/res/Resources;"),
    filters = listOf(
        methodCall(smali = "Lkotlin/collections/builders/ListBuilder;->build()Ljava/util/List;"),
    ),
    strings = listOf("answersFeatures"),
    custom = { _, classDef ->
        classDef.type == "Lcom/reddit/launch/bottomnav/BottomNavScreen;"
    }
)
