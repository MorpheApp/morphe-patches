package app.morphe.patches.reddit.layout.screenshotpopup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private val SHOULD_SHOW_BANNER_FILTERS = listOf(
    fieldAccess(
        opcode = Opcode.IGET_OBJECT,
        definingClass = "this",
        name = "\$shouldShowBanner\$delegate"
    ),
    fieldAccess(
        opcode = Opcode.SGET_OBJECT,
        smali = "Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;",
        location = MatchAfterWithin(3)
    ),
    methodCall(
        opcode = Opcode.INVOKE_INTERFACE,
        name = "setValue",
        location = MatchAfterWithin(3)
    )
)

internal object RedditScreenshotTriggerSharingListenerFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/sharing/screenshot/RedditScreenshotTriggerSharingListener\$ScreenshotBanner$",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    filters = SHOULD_SHOW_BANNER_FILTERS
)

internal object ScreenshotTakenBannerFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/sharing/screenshot/composables/ScreenshotTakenBannerKt\$ScreenshotTakenBanner$",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    filters = SHOULD_SHOW_BANNER_FILTERS
)
