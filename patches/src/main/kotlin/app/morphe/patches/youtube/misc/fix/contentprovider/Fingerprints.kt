package app.morphe.patches.youtube.misc.fix.contentprovider

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal val unstableContentProviderFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/content/ContentResolver;", "[Ljava/lang/String;"),
    filters = listOf(
        // Early targets use HashMap and later targets use ConcurrentMap.
        methodCall(
            name = "putAll",
            parameters = listOf("Ljava/util/Map;")
        ),
        string("ContentProvider query returned null cursor")
    )
)
