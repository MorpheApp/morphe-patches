package app.morphe.patches.shared.misc.checks

import app.morphe.patcher.Fingerprint

internal val patchInfoFingerprint = Fingerprint(
    custom = { _, classDef ->
        classDef.type == "Lapp/morphe/extension/shared/checks/PatchInfo;"
    }
)

internal val patchInfoBuildFingerprint = Fingerprint(
    custom = { _, classDef ->
        classDef.type == "Lapp/morphe/extension/shared/checks/PatchInfo\$Build;"
    }
)
