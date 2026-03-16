/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.snackbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object BottomUIContainerFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/view/View;", "L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "removeAllViews"
        )
    ),
    custom = { _, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;"
    }
)

internal object BottomUIContainerPreFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    custom = { method, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;" &&
                method.parameters.size == 3 &&
                method.parameters.all { it.type.startsWith("L") }
    }
)

internal object LithoSnackbarFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "setBackgroundColor"
        ),
        checkCast("Landroid/widget/FrameLayout;")
    )
)

internal object QuantumSnackbarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    custom = { _, classDef ->
        classDef.type == "Lcom/google/android/libraries/quantum/snackbar/Snackbar;"
    }
)

internal object MaterialSnackbarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    custom = { _, classDef ->
        classDef.type == "Lcom/google/android/material/snackbar/Snackbar\$SnackbarLayout;"
    }
)

internal object AppSnackbarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;"),
    custom = { _, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/app/common/ui/bottomui/AppSnackbar;"
    }
)

internal object YouTubeSnackbarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    custom = { _, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/app/common/ui/bottomui/YouTubeSnackbar;"
    }
)

internal object MealbarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    custom = { _, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/app/common/ui/bottomui/Mealbar;"
    }
)