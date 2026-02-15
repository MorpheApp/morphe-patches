package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object CommunityDrawerBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf(
        "L",
        "Ljava/util/List;",
        "Ljava/util/Collection;",
        "L",
        "L",
        "Z",
        "I"
    ),
    filters = listOf(
        methodCall("Ljava/util/Collection;->isEmpty()Z"),
    )
)

internal object CommunityDrawerBuilderParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics\$Section;",
    parameters = listOf("Lcom/reddit/screens/drawer/community/HeaderItem;"),
    filters = listOf(
        string("<this>"),
        methodCall("Ljava/lang/Enum;->ordinal()I"),
        fieldAccess("Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics\$Section;->ABOUT:Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics\$Section;")
    )
)

internal object HeaderItemUiModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("HeaderItemUiModel(uniqueId=")
    )
)
