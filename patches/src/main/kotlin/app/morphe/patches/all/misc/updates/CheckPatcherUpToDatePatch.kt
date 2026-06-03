package app.morphe.patches.all.misc.updates

import app.morphe.patcher.Match
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch

// TODO: Delete this after Manager 1.17.0 has been released
internal val checkPatcherUpToDatePatch = bytecodePatch {
    execute {
        try {
            //noinspection CheckResult
            Match.InstructionMatch::class.java.getDeclaredMethod(
                "getMethodCalled",
                BytecodePatchContext::class.java
            )
        } catch (ex: NoSuchMethodException) {
            throw RuntimeException(
                "\n\n#####################################\n\n" +
                        "Your Morphe app is outdated." +
                        "\n\nPlease update Morphe by downloading from https://morphe.software\n\n" +
                        "#####################################\n\n"
                        + ex
            )
        }
    }
}
