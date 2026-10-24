/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** Notification skips must load through the service, not a deferred watch-screen endpoint. */
internal fun installPlaylistNotification(
    context: BytecodePatchContext,
    wrapperType: String,
    enumType: String
) = with(context) {
    val next = playlistNavigationIntentFingerprint(enumType, "NEXT").matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    val nextRequest = playlistNavigationSingletonFingerprint(wrapperType, next).matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!

    // The media owner's Next dispatch, after logging and before the optional foreground callback.
    val notification = notificationNextDispatchFingerprint(nextRequest).matchSingle()
    val owner = notification.originalClassDef.type

    // The media session callback delegates its skips to the media owner.
    val callback = notificationSkipCallbackFingerprint("onSkipToNext").matchSingle().instructionMatches.last()
        .instruction.getReference<MethodReference>()!!
    val callbackDelegate = notificationDelegateFingerprint(callback, owner, notification.originalMethod.name)
        .matchSingle()

    // The foreground callback that handles Next while a watch screen is open.
    val foregroundCallback = notification.instructionMatches.first().instruction.getReference<FieldReference>()!!
    val foregroundType = notificationForegroundSkipFingerprint(foregroundCallback.type, nextRequest).matchSingle()
        .originalClassDef.type

    // The same paths for Previous.
    val previousCallback = notificationSkipCallbackFingerprint("onSkipToPrevious").matchSingle()
        .instructionMatches.last().instruction.getReference<MethodReference>()!!
    val previousDelegate = notificationDelegateFingerprint(
        previousCallback,
        owner,
        delegateType = callbackDelegate.originalClassDef.type
    ).matchSingle().instructionMatches[1].instruction.getReference<MethodReference>()!!
    val previous = notificationPreviousDispatchFingerprint(previousDelegate).matchSingle()
    val previousForeground = previous.instructionMatches.first().instruction.getReference<FieldReference>()!!
    val previousIntent = playlistNavigationIntentFingerprint(enumType, "PREVIOUS").matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    val previousRequest = playlistNavigationSingletonFingerprint(wrapperType, previousIntent).matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    val previousForegroundType = notificationForegroundSkipFingerprint(previousForeground.type, previousRequest)
        .matchSingle().originalClassDef.type

    // The playback service that loads the queue's next and previous videos.
    val provider = notification.instructionMatches[2].instruction.getReference<FieldReference>()!!
    val getProvider = notification.instructionMatches[3].instruction.getReference<MethodReference>()!!
    val serviceType = notification.instructionMatches[5].instruction.getReference<TypeReference>()!!.type

    val service = playlistServiceNavigatorFingerprint(wrapperType).matchSingle()
    val navigatorType = service.originalClassDef.type
    val playerFields = service.instructionMatches.take(3).map { it.instruction.getReference<FieldReference>()!! }
    val videoId = service.instructionMatches[4].instruction.getReference<MethodReference>()!!
    check(playerFields.first().definingClass == navigatorType &&
            playerFields.zipWithNext().all { (parent, child) -> parent.type == child.definingClass }) {
        "Native playlist queue: current player must belong to the playback service"
    }

    installNativeQueueEngine(context, navigatorType, enumType)

    fun bridge(type: String, name: String, parameters: List<String>, returnType: String, locals: Int, body: String) =
        mutableClassDefBy(type).addBridgeMethod(name, parameters, returnType, locals, body)

    mutableClassDefBy(navigatorType).interfaces.add(SERVICE_NAVIGATOR_INTERFACE)
    bridge(navigatorType, "patch_getVideoId", emptyList(), "Ljava/lang/String;", 1, """
        iget-object v0, p0, ${playerFields[0]}
        iget-object v0, v0, ${playerFields[1]}
        iget-object v0, v0, ${playerFields[2]}
        if-eqz v0, :missing
        # 21.28 declares the player field as Object.
        check-cast v0, ${videoId.definingClass}
        invoke-interface {v0}, $videoId
        move-result-object v0
        return-object v0

        :missing
        const/4 v0, 0x0
        return-object v0
    """)

    val canNavigate = playlistServiceCanNavigateFingerprint(navigatorType, wrapperType).matchSingle().originalMethod
    for ((name, resultType, target) in listOf(
        Triple("patch_navigateQueue", "V", service.originalMethod),
        Triple("patch_canNavigateQueue", "Z", canNavigate)
    )) {
        val result = if (resultType == "Z") "move-result v0\nreturn v0" else "return-void"
        bridge(navigatorType, name, listOf("Z"), resultType, 1, """
            if-eqz p1, :previous
            sget-object v0, $nextRequest
            goto :dispatch

            :previous
            sget-object v0, $previousRequest

            :dispatch
            invoke-virtual {p0, v0}, $target
            $result
        """)
    }

    mutableClassDefBy(owner).interfaces.add(MEDIA_CONTROLS_INTERFACE)
    bridge(owner, "patch_getQueueNavigator", emptyList(), SERVICE_NAVIGATOR_INTERFACE, 2, """
        iget-object v0, p0, $foregroundCallback
        if-eqz v0, :previous
        instance-of v0, v0, $foregroundType
        if-eqz v0, :missing

        :previous
        iget-object v0, p0, $previousForeground
        if-eqz v0, :local
        instance-of v0, v0, $previousForegroundType
        if-eqz v0, :missing

        :local
        iget-object v0, p0, $provider
        invoke-interface {v0}, $getProvider
        move-result-object v0
        check-cast v0, $serviceType
        instance-of v1, v0, $SERVICE_NAVIGATOR_INTERFACE
        if-eqz v1, :missing
        check-cast v0, $SERVICE_NAVIGATOR_INTERFACE
        return-object v0

        :missing
        const/4 v0, 0x0
        return-object v0
    """)

    installPlaylistNotificationControls(context, owner)

    for ((match, hook) in listOf(notification to "onNotificationNext", previous to "onNotificationPrevious")) {
        val method = match.method
        val index = match.instructionMatches.first().index
        val result = method.findFreeRegister(index)
        method.addInstructionsAtControlFlowLabel(index, """
            invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->$hook($MEDIA_CONTROLS_INTERFACE)Z
            move-result v$result
            if-eqz v$result, :native_skip
            return-void

            :native_skip
            nop
        """)
    }
}
