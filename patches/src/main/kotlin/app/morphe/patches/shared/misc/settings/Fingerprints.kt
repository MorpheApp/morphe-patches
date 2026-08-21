/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patches.all.misc.extension.SHARED_UTILS_EXTENSION_CLASS
import com.android.tools.smali.dexlib2.AccessFlags

private const val THEME_UTILS_EXTENSION_CLASS = "Lapp/morphe/extension/shared/theme/ThemeUtils;"

internal object ThemeLightColorResourceNameFingerprint : Fingerprint(
    definingClass = THEME_UTILS_EXTENSION_CLASS,
    name = "getThemeLightColorResourceName",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

internal object ThemeDarkColorResourceNameFingerprint : Fingerprint(
    definingClass = THEME_UTILS_EXTENSION_CLASS,
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
