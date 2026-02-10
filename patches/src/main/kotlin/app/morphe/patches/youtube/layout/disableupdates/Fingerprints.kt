package app.morphe.patches.youtube.layout.disableupdates

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object CronetHeaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        string("Accept-Encoding")
    ),
    // In YouTube 19.16.39 or earlier, there are two methods with almost the same structure.
    // Check the fields of the class to identify them correctly.
    custom = { _, classDef ->
        classDef.fields.find { it.type == "J" } != null
    }
)
