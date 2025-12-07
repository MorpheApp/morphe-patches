package app.morphe.patches.shared.misc.checks

import app.morphe.patcher.Fingerprint

internal object PatchInfoFingerprint : Fingerprint(
    custom = { _, classDef ->
        classDef.type == "Lapp/morphe/extension/shared/checks/PatchInfo;"
    }
)

internal object PatchInfoBuildFingerprint : Fingerprint(
    custom = { _, classDef ->
        classDef.type == "Lapp/morphe/extension/shared/checks/PatchInfo\$Build;"
    }
)
