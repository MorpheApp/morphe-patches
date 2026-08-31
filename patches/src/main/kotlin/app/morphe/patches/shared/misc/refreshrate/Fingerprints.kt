/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.refreshrate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

internal object ActivityOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    custom = { _, classDef ->
        classDef.superclass == "Landroid/app/Activity;"
    }
)

internal object DisplayGetRefreshRateFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/view/Display;->getRefreshRate()F"
        )
    ),
    custom = { _, classDef ->
        !classDef.type.startsWith("Lapp/morphe/extension/")
    }
)

internal object DisplayModeGetRefreshRateFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Landroid/view/Display$Mode;->getRefreshRate()F"
        )
    ),
    custom = { _, classDef ->
        !classDef.type.startsWith("Lapp/morphe/extension/")
    }
)
