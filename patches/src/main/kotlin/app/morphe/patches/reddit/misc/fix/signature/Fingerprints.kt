/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.fix.signature

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object ApplicationFingerprint : Fingerprint(
    name = "attachBaseContext",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/app/Application;"
    }
)
