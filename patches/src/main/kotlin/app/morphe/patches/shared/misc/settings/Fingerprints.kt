package app.morphe.patches.shared.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patches.all.misc.extension.SHARED_UTILS_EXTENSION_CLASS
import com.android.tools.smali.dexlib2.AccessFlags

internal object ThemeLightColorResourceNameFingerprint : Fingerprint(
    definingClass = SHARED_UTILS_EXTENSION_CLASS,
    name = "getThemeLightColorResourceName",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

internal object ThemeDarkColorResourceNameFingerprint : Fingerprint(
    definingClass = SHARED_UTILS_EXTENSION_CLASS,
    name = "getThemeDarkColorResourceName",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

internal object RecommendedAppVersionUtilsFingerprint : Fingerprint(
    definingClass = SHARED_UTILS_EXTENSION_CLASS,
    name = "getRecommendedAppVersion",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)
