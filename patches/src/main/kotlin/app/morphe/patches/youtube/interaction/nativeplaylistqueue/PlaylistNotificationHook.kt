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
import app.morphe.patches.youtube.misc.playservice.is_21_42_or_greater
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
    val next = playlistNavigationIntentFingerprint(enumType, "NEXT")
        .instructionMatches.last().fieldReference
    val nextRequest = playlistNavigationSingletonFingerprint(wrapperType, next)
        .instructionMatches.last().fieldReference

    // The media owner's Next dispatch, after logging and before the optional foreground callback.
    val notification = notificationNextDispatchFingerprint(nextRequest).match()
    val owner = notification.originalClassDef.type

    // The media session callback delegates its skips to the media owner.
    val callback = notificationSkipCallbackFingerprint("onSkipToNext")
        .instructionMatches.last().methodReference
    val callbackDelegate = notificationDelegateFingerprint(
        callback, owner, notification.originalMethod.name
    ).match()

    // The foreground callback that handles Next while a watch screen is open.
    val foregroundCallback = notification.instructionMatches.first().fieldReference
    val foregroundType = notificationForegroundSkipFingerprint(
        foregroundCallback.type, nextRequest
    ).originalClassDef.type

    // The same paths for Previous.
    val previousCallback = notificationSkipCallbackFingerprint("onSkipToPrevious")
        .instructionMatches.last().methodReference
    val previousDelegate = notificationDelegateFingerprint(
        previousCallback,
        owner,
        delegateType = callbackDelegate.originalClassDef.type
    ).instructionMatches[1].methodReference
    val previous = notificationPreviousDispatchFingerprint(previousDelegate).match()
    val previousForeground = previous.instructionMatches.first().fieldReference
    val previousIntent = playlistNavigationIntentFingerprint(enumType, "PREVIOUS")
        .instructionMatches.last().fieldReference
    val previousRequest = playlistNavigationSingletonFingerprint(wrapperType, previousIntent)
        .instructionMatches.last().fieldReference
    val previousForegroundType = notificationForegroundSkipFingerprint(
        previousForeground.type, previousRequest
    ).originalClassDef.type

    // The playback service that loads the queue's next and previous videos.
    val provider = notification.instructionMatches[2].fieldReference
    val getProvider = notification.instructionMatches[3].methodReference
    val serviceType = notification.instructionMatches[5].instruction.getReference<TypeReference>()!!.type

    val service = playlistServiceNavigatorFingerprint(wrapperType).match()
    val navigatorType = service.originalClassDef.type
    val playerFields = service.instructionMatches.take(3).map {
        it.fieldReference
    }
    val videoId = service.instructionMatches[4].methodReference
    check(playerFields.first().definingClass == navigatorType &&
            playerFields.zipWithNext().all { (parent, child) -> parent.type == child.definingClass }) {
        "Native playlist queue: current player must belong to the playback service"
    }

    val playbackType = installNativeQueueEngine(context, navigatorType, enumType)

    fun bridge(type: String, name: String, parameters: List<String>, returnType: String, locals: Int, body: String) =
        mutableClassDefBy(type).addBridgeMethod(name, parameters, returnType, locals, body)

    fun installVideoIdBridge(type: String, playerOwner: FieldReference) {
        bridge(
            type,
            "patch_getVideoId",
            emptyList(),
            "Ljava/lang/String;",
            1,
            """
                iget-object v0, p0, $playerOwner
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
            """
        )
    }

    mutableClassDefBy(navigatorType).interfaces.add(SERVICE_NAVIGATOR_INTERFACE)
    installVideoIdBridge(navigatorType, playerFields[0])

    fun installNavigationBridges(type: String, navigate: MethodReference, canNavigate: MethodReference) {
        for ((name, resultType, target) in listOf(
            Triple("patch_navigateQueue", "V", navigate),
            Triple("patch_canNavigateQueue", "Z", canNavigate)
        )) {
            val result = if (resultType == "Z") "move-result v0\nreturn v0" else "return-void"
            bridge(
                type,
                name,
                listOf("Z"),
                resultType,
                1,
                """
                    if-eqz p1, :previous
                    sget-object v0, $nextRequest
                    goto :dispatch

                    :previous
                    sget-object v0, $previousRequest

                    :dispatch
                    invoke-virtual {p0, v0}, $target
                    $result
                """
            )
        }
    }

    val canNavigate = playlistServiceCanNavigateFingerprint(
        navigatorType, wrapperType
    ).originalMethod
    installNavigationBridges(navigatorType, service.originalMethod, canNavigate)

    // Earlier versions expose the service directly. Newer watch navigators can
    // wrap it while forwarding navigation and capability checks unchanged.
    // Discover it through its exact native request delegation. It is required
    // on 21.42; older versions can expose the service without a wrapper.
    val delegates = playlistServiceDelegateFingerprint(service.originalMethod).matchAllOrNull().orEmpty()
    check(delegates.size <= 1 && (!is_21_42_or_greater || delegates.size == 1)) {
        "Native playlist queue: missing or ambiguous playback service delegate"
    }
    delegates.forEach { match ->
        val delegate = match.instructionMatches.first().fieldReference
        val delegateType = match.originalClassDef.type
        mutableClassDefBy(delegateType).interfaces.add(SERVICE_NAVIGATOR_INTERFACE)
        for ((name, resultType) in listOf(
            "patch_getVideoId" to "Ljava/lang/String;",
            "patch_getQueuePlayback" to NATIVE_PLAYBACK_INTERFACE
        )) {
            bridge(
                delegateType,
                name,
                emptyList(),
                resultType,
                1,
                """
                    iget-object v0, p0, $delegate
                    invoke-interface {v0}, $SERVICE_NAVIGATOR_INTERFACE->$name()$resultType
                    move-result-object v0
                    return-object v0
                """
            )
        }
        // Navigation hooks from other patches belong to the wrapper's original
        // dispatch. Keep those effective instead of calling the service directly.
        val delegateCanNavigate = playlistServiceCanNavigateFingerprint(delegateType, wrapperType)
            .originalMethod
        installNavigationBridges(delegateType, match.originalMethod, delegateCanNavigate)
    }

    // This navigator owns the active backend directly, instead of reaching it
    // through the legacy service's sequencer. Read its current native field;
    // a global last-response cache could select an inline preview's backend.
    val directType = DirectQueueNavigatorFingerprint.originalClassDef.type
    val backend = directQueueBackendFingerprint(directType, playbackType)
        .instructionMatches.last().fieldReference
    val playerOwner = directQueuePlayerOwnerFingerprint(directType, playerFields[0].type)
        .instructionMatches.single().fieldReference
    mutableClassDefBy(directType).interfaces.add(SERVICE_NAVIGATOR_INTERFACE)
    bridge(
        directType,
        "patch_getQueuePlayback",
        emptyList(),
        NATIVE_PLAYBACK_INTERFACE,
        1,
        """
            iget-object v0, p0, $backend
            return-object v0
        """
    )
    installVideoIdBridge(directType, playerOwner)
    val directNavigate = directQueueNavigateFingerprint(directType, wrapperType).originalMethod
    val directCanNavigate = playlistServiceCanNavigateFingerprint(directType, wrapperType).originalMethod
    installNavigationBridges(
        directType,
        directNavigate,
        directCanNavigate
    )
    if (is_21_42_or_greater) {
        installNativeQueueWindow(context, backend, enumType, directNavigate, directCanNavigate)
    }

    mutableClassDefBy(owner).interfaces.add(MEDIA_CONTROLS_INTERFACE)
    bridge(
        owner,
        "patch_getQueueNavigator",
        emptyList(),
        SERVICE_NAVIGATOR_INTERFACE,
        2,
        """
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
        """
    )

    installPlaylistNotificationControls(context, owner)

    for ((match, hook) in listOf(notification to "onNotificationNext", previous to "onNotificationPrevious")) {
        val method = match.method
        val index = match.instructionMatches.first().index
        val result = method.findFreeRegister(index)
        method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->$hook($MEDIA_CONTROLS_INTERFACE)Z
                move-result v$result
                if-eqz v$result, :native_skip
                return-void

                :native_skip
                nop
            """
        )
    }
}
