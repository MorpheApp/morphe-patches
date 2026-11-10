/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.loop

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SleepTimerCancelMethodFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "sleeptimer_snackbar_canceled_text")
    )
)

internal object SleepTimerConstructorFingerprint : Fingerprint(
    classFingerprint = SleepTimerCancelMethodFingerprint,
    name = "<init>",
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_DIRECT, name = "<init>")
    )
)

internal object SleepTimerVideoEndedEventFingerprint : Fingerprint(
    classFingerprint = SleepTimerCancelMethodFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "Z"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.SGET_OBJECT)
    )
)

/**
 * Key of the loop video item of the player menu with the additional settings.
 * The app reads the key every time it creates that menu, but creates the item itself only once.
 */
internal object LoopVideoMenuItemKeyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("menu_item_single_video_playback_loop"),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * Closes a bottom sheet menu. The menu closes a moment later, after its view handles the current event.
 */
internal object BottomSheetMenuCloseFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        parameters = listOf("Landroid/os/Bundle;"),
        filters = listOf(
            string("BaseBottomSheetDialogFragment.peekHeightEnabled"),
            string("BaseBottomSheetDialogFragment.largeFormWidthDp")
        )
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/view/ViewGroup;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/view/ViewGroup;->post(Ljava/lang/Runnable;)Z"
        )
    )
)

/**
 * Method of the navigator interface that sets the repeat mode.
 * The navigator interface is the only one with an abstract method that plays a video with the
 * same parameters as the navigation request has after its intent.
 *
 * @param videoParameters Parameters of the navigation request constructor after the intent.
 */
internal fun navigatorSetRepeatModeFingerprint(videoParameters: List<String>) = Fingerprint(
    classFingerprint = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.ABSTRACT),
        returnType = "V",
        parameters = videoParameters
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.ABSTRACT),
    returnType = "V",
    parameters = listOf("I")
)

/**
 * Checks if a navigator can navigate. The player checks this when the video starts,
 * long before the navigator plays another video.
 */
internal fun navigatorCanNavigateFingerprint(requestType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(requestType)
)
