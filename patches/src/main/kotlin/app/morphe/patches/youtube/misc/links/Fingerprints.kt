package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * 20.36 and lower.
 */
internal object AbUriParserLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        string("Found entityKey=`"),
        string("that does not contain a PlaylistVideoEntityId", StringComparisonType.CONTAINS),
        methodCall(smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;")
    )
)

/**
 * 20.37+
 */
internal object AbUriParserFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Method is a switch statement of unrelated code,
        // and there's no strings or anything unique to fingerprint.
        methodCall(smali = "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"),
        methodCall(smali = "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"),
        methodCall(smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;"),
        methodCall(smali = "Ljava/util/List;->get(I)Ljava/lang/Object;"),
    )
)

internal object HttpUriParserFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;"),
        string("https"),
        string("://"),
        string("https:"),
    )
)

