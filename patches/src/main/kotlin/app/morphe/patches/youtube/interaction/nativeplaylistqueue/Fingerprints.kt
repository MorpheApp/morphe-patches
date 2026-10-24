/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterAnywhere
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.instanceOf
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** A generated protobuf message creates its default instance in its static initializer. */
internal fun protoDefaultInstanceFingerprint(messageType: String) = Fingerprint(
    definingClass = messageType,
    name = "<clinit>",
    filters = listOf(
        fieldAccess(definingClass = messageType, type = messageType, opcode = Opcode.SPUT_OBJECT)
    )
)

/** The native batch request, not the modern panel's single-video parameter. */
internal object QueueSaveRequestFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(string("playlist/get_add_to_playlist"))
)

/** The picker fragment reads its serialized endpoint from its arguments. */
internal fun queueSavePickerFingerprint(requestType: String) = Fingerprint(
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
    filters = listOf(
        fieldAccess(type = "Landroid/os/Bundle;", opcode = Opcode.IGET_OBJECT),
        string("navigation_endpoint", location = MatchAfterImmediately()),
        methodCall(definingClass = requestType, name = "<init>")
    )
)

/** Opens the picker with the endpoint serialized into its arguments. */
internal fun queueSavePickerFactoryFingerprint(pickerType: String, endpointType: String) = Fingerprint(
    parameters = listOf(endpointType),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = pickerType, name = "<init>"),
        methodCall(name = "toByteArray", parameters = emptyList(), returnType = "[B"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("navigation_endpoint", location = MatchAfterImmediately()),
        methodCall(
            "Landroid/os/Bundle;->putByteArray(Ljava/lang/String;[B)V",
            location = MatchAfterImmediately()
        )
    )
)

/** Response callback owned by the picker request; the renderer discriminator is stable. */
internal object QueueSaveResponseFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "V",
    filters = listOf(opcode(Opcode.CHECK_CAST), literal(88978004L))
)

internal fun queueSaveResponsePickerFingerprint(pickerType: String) = Fingerprint(
    classFingerprint = QueueSaveResponseFingerprint,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = pickerType, opcode = Opcode.IPUT_OBJECT))
)

/** Native edit callback error diagnostics identify the same request's success listener. */
internal object QueueSaveEditErrorFingerprint : Fingerprint(
    filters = listOf(string("Error editing the playlist"))
)

internal fun queueSaveEditCreationFingerprint(callbackType: String, endpointType: String) = Fingerprint(
    parameters = listOf(endpointType, "Ljava/lang/Object;"),
    returnType = "V",
    filters = listOf(methodCall(definingClass = callbackType, name = "<init>"))
)

internal fun queueSaveEditSuccessFingerprint(callbackType: String) = Fingerprint(
    definingClass = callbackType,
    parameters = listOf("L"),
    returnType = "V",
    filters = listOf(methodCall("Ljava/util/List;->iterator()Ljava/util/Iterator;"))
)

/**
 * The native playback queue's navigation descriptor lookup. It wraps the delegated
 * queue call in the queue's own change transaction.
 */
internal object NativeQueueDescriptorFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        // Begin the change transaction.
        methodCall(
            definingClass = "this",
            opcode = Opcode.INVOKE_DIRECT,
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Queue storage.
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // End the change transaction.
        methodCall(
            definingClass = "this",
            opcode = Opcode.INVOKE_DIRECT,
            returnType = "V",
            location = MatchAfterWithin(1)
        ),
        // 21.16 concatenates this diagnostic; 21.20+ formats it in one string.
        string("getNavigationDescriptor for ", StringComparisonType.STARTS_WITH)
    )
)

/** Ends a queue change: releases the change guard, then refreshes the queue state. */
internal fun nativeQueueChangeEndFingerprint(end: MethodReference) = Fingerprint(
    definingClass = end.definingClass,
    name = end.name,
    parameters = end.parameterTypes.map { it.toString() },
    returnType = "V",
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        methodCall(definingClass = "this", parameters = listOf("Z"), returnType = "V")
    )
)

/** Refreshes the queue state and notifies its listeners. */
internal fun nativeQueueRefreshFingerprint(refresh: MethodReference) = Fingerprint(
    definingClass = refresh.definingClass,
    name = refresh.name,
    parameters = refresh.parameterTypes.map { it.toString() },
    returnType = refresh.returnType
)

/** Accepts a watch response into the queue, inside a change transaction. */
internal fun nativeQueueAcceptFingerprint(queueType: String, responseType: String, begin: MethodReference) =
    Fingerprint(
        definingClass = queueType,
        returnType = "V",
        parameters = listOf(responseType),
        filters = listOf(
            methodCall(reference = begin),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            fieldAccess(
                definingClass = "this",
                type = responseType,
                opcode = Opcode.IPUT_OBJECT,
                location = MatchAfterImmediately()
            )
        )
    )

/** Disposes the queue and drops its weak registration. */
internal fun nativeQueueDisposeFingerprint(queueType: String) = Fingerprint(
    definingClass = queueType,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(methodCall("Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;"))
)

/** The navigator wrapper remembers whether its initial navigator is the native queue. */
internal fun nativeQueueWrapperConstructorFingerprint(queueType: String) = Fingerprint(
    name = "<init>",
    filters = listOf(
        instanceOf(queueType),
        fieldAccess(
            definingClass = "this",
            type = "Z",
            opcode = Opcode.IPUT_BOOLEAN,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * The wrapper receives a watch response. With a playlist panel it switches
 * to a native queue built from the response's watch command.
 */
internal fun nativeQueueReceiveFingerprint(queueFlag: FieldReference) = Fingerprint(
    definingClass = queueFlag.definingClass,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(reference = queueFlag, opcode = Opcode.IGET_BOOLEAN),
        // Response playlist panel.
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        // Active navigator and queue container.
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        // Descriptor builder with the response's watch command.
        opcode(Opcode.NEW_INSTANCE, location = MatchAfterImmediately()),
        methodCall(name = "<init>", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Queue container creates the native queue.
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Switch navigators.
        methodCall(definingClass = "this", opcode = Opcode.INVOKE_DIRECT, location = MatchAfterImmediately()),
        opcode(Opcode.CONST_4, location = MatchAfterImmediately()),
        fieldAccess(reference = queueFlag, opcode = Opcode.IPUT_BOOLEAN, location = MatchAfterImmediately()),
        // Stored response, delivered to the active navigator.
        fieldAccess(definingClass = "this", opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately())
    )
)

/** The wrapper delegates the repeat mode to the active navigator. */
internal fun nativeQueueRepeatSetterFingerprint(wrapperType: String) = Fingerprint(
    definingClass = wrapperType,
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("I"),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)

internal fun nativeQueueRepeatGetterFingerprint(wrapperType: String) = Fingerprint(
    definingClass = wrapperType,
    returnType = "I",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = emptyList(),
            returnType = "I",
            location = MatchAfterImmediately()
        )
    )
)

/** Server autoplay replaces the active navigator with an autoplay navigator. */
internal fun nativeQueueAutoplayFingerprint(navigator: FieldReference, switchNavigator: MethodReference) =
    Fingerprint(
        definingClass = navigator.definingClass,
        returnType = "V",
        parameters = listOf("L"),
        filters = listOf(
            fieldAccess(reference = navigator, opcode = Opcode.IGET_OBJECT),
            opcode(Opcode.INSTANCE_OF, location = MatchAfterImmediately()),
            methodCall(reference = switchNavigator)
        )
    )

internal fun nativeQueueAutoplayConstructorFingerprint(autoplayType: String) = Fingerprint(
    definingClass = autoplayType,
    name = "<init>",
    parameters = List(3) { "Lj\$/util/Optional;" }
)

/** The wrapper disposes its active navigator. */
internal fun nativeQueueWrapperDisposeFingerprint(navigator: FieldReference, disposeName: String) = Fingerprint(
    definingClass = navigator.definingClass,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(reference = navigator, opcode = Opcode.IGET_OBJECT),
        methodCall(
            definingClass = navigator.type,
            name = disposeName,
            parameters = emptyList(),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)

/** The watch response is a parcelable envelope around the server response. */
internal fun nativeQueueResponseConstructorFingerprint(responseType: String) = Fingerprint(
    definingClass = responseType,
    name = "<init>",
    parameters = listOf("L")
)

internal fun nativeQueueResponseSourceFingerprint(responseType: String, sourceType: String) = Fingerprint(
    definingClass = responseType,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = sourceType, opcode = Opcode.IPUT_OBJECT))
)

/** The response constructor initializes the same navigation-set type used by the UI model. */
internal fun nativeQueueResponseNavigationFingerprint(responseType: String, navigationType: String) = Fingerprint(
    definingClass = responseType,
    name = "<init>",
    parameters = listOf("L"),
    filters = listOf(fieldAccess(definingClass = responseType, type = navigationType, opcode = Opcode.IPUT_OBJECT))
)

/**
 * The mutable queue builds its shuffle order with the framework shuffler,
 * storing successor and predecessor index arrays.
 */
internal object NativeQueueShuffleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("I"), returnType = "I"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        // Selected position.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "I",
            location = MatchAfterImmediately()
        ),
        methodCall("Ljava/util/Collections;->shuffle(Ljava/util/List;)V"),
        opcode(Opcode.NEW_ARRAY, location = MatchAfterImmediately()),
        fieldAccess(definingClass = "this", type = "[I", opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.NEW_ARRAY, location = MatchAfterImmediately()),
        fieldAccess(definingClass = "this", type = "[I", opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately())
    )
)

/** The mutable queue only inserts descriptors without a playlist ID. */
internal fun nativeQueueLocalLoadFingerprint(mutableType: String, descriptorType: String, requestType: String) =
    Fingerprint(
        definingClass = mutableType,
        returnType = requestType,
        parameters = listOf(descriptorType, "L"),
        filters = listOf(
            methodCall(definingClass = descriptorType, parameters = emptyList(), returnType = "Ljava/lang/String;"),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            methodCall("Ljava/lang/String;->isEmpty()Z", location = MatchAfterImmediately())
        )
    )

/** The playback service loads a descriptor into its active sequencer. */
internal fun nativeQueueLoaderFingerprint(serviceType: String) = Fingerprint(
    definingClass = serviceType,
    returnType = "V",
    filters = listOf(
        fieldAccess(type = "Ljava/lang/Object;", opcode = Opcode.IGET_OBJECT),
        opcode(Opcode.IF_EQZ, location = MatchAfterWithin(1)),
        opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(1)),
        string("Initializing a PlaybackSequencer in loaderNavigator, but it does not exist")
    )
)

internal fun nativeQueueSequencerOwnerFingerprint(serviceType: String, holderType: String) = Fingerprint(
    definingClass = serviceType,
    returnType = "V",
    filters = listOf(
        fieldAccess(definingClass = "this", type = holderType, opcode = Opcode.IGET_OBJECT),
        string("Initializing a PlaybackSequencer in loaderNavigator, but it does not exist")
    )
)

internal fun nativeQueueSequencerNavigatorFingerprint(sequencerType: String, navigatorType: String) = Fingerprint(
    definingClass = sequencerType,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = navigatorType, opcode = Opcode.IPUT_OBJECT))
)

internal object PlaylistModelDescriptionFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("EngagementPanelPlaylistPanelModel{videoId="))
)

internal object PlaylistControllerConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(string("engagement-panel-playlist"))
)

/** Binds the fullscreen quick actions container view to its controller. */
internal object QuickActionsContainerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "quick_actions_element_container"),
        checkCast("Landroid/view/ViewGroup;", location = MatchAfterWithin(10)),
        // Stored on the controller. Before 21.42 the controller is read from an Object field
        // and cast. Quick actions margin can insert one more instruction after the view cast.
        fieldAccess(
            type = "Landroid/view/ViewGroup;",
            opcode = Opcode.IPUT_OBJECT,
            location = MatchAfterWithin(4)
        )
    )
)

internal fun quickActionsModelCallbackFingerprint(controller: String) = Fingerprint(
    definingClass = controller,
    parameters = listOf("Ljava/lang/Object;", "L"),
    returnType = "L",
    filters = listOf(opcode(Opcode.CHECK_CAST), methodCall(definingClass = controller,
        returnType = "V", location = MatchAfterImmediately()))
)

internal fun quickActionsResetFingerprint(controller: String, setter: MethodReference) = Fingerprint(
    definingClass = controller,
    parameters = listOf("L"),
    returnType = "V",
    filters = listOf(
        methodCall(reference = setter),
        fieldAccess(definingClass = controller, type = "Z", opcode = Opcode.IPUT_BOOLEAN,
            location = MatchAfterWithin(3))
    )
)

internal fun quickActionsSizeFingerprint(controller: String) = Fingerprint(
    name = "onLayoutChange",
    parameters = listOf("Landroid/view/View;") + List(8) { "I" },
    returnType = "V",
    filters = listOf(
        methodCall("Landroid/util/Size;-><init>(II)V"),
        fieldAccess(definingClass = controller, opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(8)),
        methodCall(parameters = listOf("Ljava/lang/Object;"), returnType = "V",
            location = MatchAfterImmediately())
    )
)

internal fun acceptedPlaylistModelFingerprint(
    controllerType: String,
    modelType: String,
    responseVideoField: FieldReference
) = Fingerprint(
    definingClass = controllerType,
    returnType = "V",
    parameters = listOf(modelType),
    filters = listOf(
        fieldAccess(reference = responseVideoField, opcode = Opcode.IGET_OBJECT),
        methodCall(
            "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z",
            location = MatchAfterImmediately()
        ),
        // The response's optional video ID is checked against the active video's ID.
        fieldAccess(
            definingClass = controllerType,
            type = "Ljava/lang/String;",
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(2)
        ),
        methodCall("Ljava/lang/String;->length()I", location = MatchAfterWithin(1)),
        methodCall("Ljava/lang/String;->equals(Ljava/lang/Object;)Z", location = MatchAfterWithin(2)),
        // The accepted model.
        fieldAccess(definingClass = "this", type = modelType, opcode = Opcode.IPUT_OBJECT)
    )
)

/** The controller compares its active playlist identity after the stale-video check. */
internal fun playlistControllerIdentityFingerprint(accept: MethodReference) = Fingerprint(
    definingClass = accept.definingClass,
    name = accept.name,
    parameters = accept.parameterTypes.map { it.toString() },
    returnType = "V",
    filters = listOf(
        fieldAccess(definingClass = "this", type = "Ljava/lang/String;", opcode = Opcode.IGET_OBJECT),
        methodCall(
            "Landroid/text/TextUtils;->equals(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/** AutoValue builder of the playlist model; its missing-property check names queueMode. */
internal fun playlistModelBuildFingerprint(modelType: String) = Fingerprint(
    returnType = modelType,
    parameters = emptyList(),
    filters = listOf(string(" queueMode"))
)

/** The model's static builder factory, which sets the default queue mode and tracking. */
internal fun playlistModelFactoryFingerprint(modelType: String, builderType: String) = Fingerprint(
    definingClass = modelType,
    returnType = builderType,
    parameters = emptyList()
)

/** The AutoValue constructor stores each of its parameters in a field. */
internal fun playlistModelConstructorFingerprint(modelType: String) = Fingerprint(
    definingClass = modelType,
    name = "<init>",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "L", "L", "L", "I", "L"),
    filters = List(7) { index ->
        fieldAccess(
            definingClass = "this",
            opcodes = listOf(Opcode.IPUT_OBJECT, Opcode.IPUT),
            location = if (index == 0) MatchAfterAnywhere() else MatchAfterImmediately()
        )
    }
)

/** The active video transition can complete without any playlist model being accepted. */
internal fun playlistVideoTransitionFingerprint(activeVideoField: FieldReference) = Fingerprint(
    definingClass = activeVideoField.definingClass,
    returnType = "V",
    parameters = List(3) { "Ljava/lang/String;" },
    filters = listOf(fieldAccess(reference = activeVideoField, opcode = Opcode.IPUT_OBJECT))
)

/** Renders the accepted model. The literal is the native panel's logging ID. */
internal fun playlistPanelRenderFingerprint(modelField: FieldReference) = Fingerprint(
    definingClass = modelField.definingClass,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(reference = modelField, opcode = Opcode.IGET_OBJECT),
        literal(0x19b4b)
    )
)

/** Native playlist item factory; the education label belongs to its reorder/delete controller. */
internal object PlaylistItemsConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(resourceLiteral(ResourceType.STRING, "playlist_reorder_delete_edu"))
)

internal fun playlistItemFactoryFingerprint(videoType: String) = Fingerprint(
    classFingerprint = PlaylistItemsConstructorFingerprint,
    parameters = listOf("Ljava/util/List;"),
    returnType = "Ljava/util/List;",
    filters = listOf(fieldAccess(type = videoType, opcode = Opcode.IGET_OBJECT))
)

internal fun playlistContentsFingerprint(panelType: String, factory: MethodReference) = Fingerprint(
    definingClass = factory.definingClass,
    parameters = emptyList(),
    returnType = "V",
    filters = listOf(
        fieldAccess(definingClass = panelType, opcode = Opcode.IGET_OBJECT),
        methodCall(reference = factory, location = MatchAfterImmediately())
    )
)

/**
 * Native playlist row click listener. A veto predicate runs first,
 * then logging decorates the bound endpoint before dispatching it.
 */
internal object PlaylistRowClickFingerprint : Fingerprint(
    interfaces = listOf("Landroid/view/View\$OnClickListener;"),
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Landroid/view/View;"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        // Interaction logger.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        // Bound endpoint.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        // Logging decoration of the endpoint.
        methodCall(opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        string("com.google.android.libraries.youtube.logging.interaction_logger")
    )
)

/** The rich row binder reads the video's navigation endpoint after it reads the row listener. */
internal fun playlistRowEndpointFingerprint(listenerType: String, videoType: String, endpointType: String) =
    Fingerprint(
        classFingerprint = PlaylistRichRowConstructorFingerprint,
        filters = listOf(
            fieldAccess(type = listenerType, opcode = Opcode.IGET_OBJECT),
            fieldAccess(
                definingClass = videoType,
                type = "I",
                opcode = Opcode.IGET,
                location = MatchAfterImmediately()
            ),
            fieldAccess(
                definingClass = videoType,
                type = endpointType,
                opcode = Opcode.IGET_OBJECT,
                location = MatchAfterWithin(4)
            )
        )
    )

/** Binds the row listener to a video's endpoint, logger, context and callback. */
internal fun playlistEndpointBindFingerprint(endpoint: FieldReference) = Fingerprint(
    definingClass = endpoint.definingClass,
    returnType = "V",
    parameters = listOf("L", endpoint.type, "Ljava/util/Map;", "L"),
    filters = listOf(fieldAccess(reference = endpoint, opcode = Opcode.IPUT_OBJECT))
)

/** Clears the row listener's endpoint when its view is recycled. */
internal fun playlistEndpointUnbindFingerprint(endpoint: FieldReference) = Fingerprint(
    definingClass = endpoint.definingClass,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(fieldAccess(reference = endpoint, opcode = Opcode.IPUT_OBJECT))
)

/** The standard button click context retains the native enclosing row identity. */
internal object NativeButtonContextFingerprint : Fingerprint(
    interfaces = listOf("Landroid/view/View\$OnClickListener;"),
    returnType = "Ljava/util/Map;",
    parameters = listOf("L", "Z"),
    filters = listOf(string("anchor_view"))
)

internal object NativeButtonDispatchFingerprint : Fingerprint(
    classFingerprint = NativeButtonContextFingerprint,
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(methodCall(opcode = Opcode.INVOKE_INTERFACE, returnType = "V",
        parameters = listOf("L", "Ljava/util/Map;")))
)

/**
 * Native row overflow handler. It creates the endpoint context map before showing the menu.
 * The second parameter is the menu's endpoint callback.
 */
internal fun playlistRowMenuOpenFingerprint(videoType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf(videoType, "L", "L"),
    filters = listOf(
        string("com.google.android.libraries.youtube.logging.interaction_logger"),
        string("com.google.android.libraries.youtube.innertube.endpoint.tag", location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = List(4) { "Ljava/lang/Object;" },
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

/** The native menu sheet dispatches its endpoint callback immediately before dismissing itself. */
internal fun playlistMenuDispatchFingerprint(callbackType: String) = Fingerprint(
    interfaces = listOf("Landroid/widget/AdapterView\$OnItemClickListener;"),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall("Ljava/lang/CharSequence;->length()I"),
        methodCall(
            definingClass = callbackType,
            parameters = listOf("L", "Ljava/util/Map;"),
            returnType = "V"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    )
)

/** Rich playlist rows, as opposed to the compact/offline binders. */
internal object PlaylistRichRowConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(resourceLiteral(ResourceType.ID, "playlist_panel_video_item"))
)

/** The rich row's swipe layout getter. */
internal object PlaylistRowSwipeLayoutFingerprint : Fingerprint(
    classFingerprint = PlaylistRichRowConstructorFingerprint,
    returnType = "Lcom/google/android/libraries/youtube/common/ui/swipelayout/SwipeLayout;",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            type = "Lcom/google/android/libraries/youtube/common/ui/swipelayout/SwipeLayout;",
            opcode = Opcode.IGET_OBJECT
        )
    )
)

/** Native selection updates replace the swipe foreground's background. */
internal object PlaylistRowBackgroundFingerprint : Fingerprint(
    classFingerprint = PlaylistRichRowConstructorFingerprint,
    parameters = emptyList(),
    returnType = "V",
    filters = listOf(
        fieldAccess(type = "Landroid/view/View;", opcode = Opcode.IGET_OBJECT),
        methodCall("Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V",
            location = MatchAfterImmediately()),
        methodCall("Landroid/widget/TextView;->setTextColor(I)V", location = MatchAfterWithin(8))
    )
)

/** SwipeLayout is an unobfuscated native widget; attachment caches its foreground drawable. */
internal object PlaylistSwipeAttachedFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/common/ui/swipelayout/SwipeLayout;",
    name = "onAttachedToWindow",
    parameters = emptyList(),
    returnType = "V",
    filters = listOf(
        fieldAccess(type = "Landroid/view/View;", opcode = Opcode.IPUT_OBJECT),
        methodCall("Landroid/view/View;->getBackground()Landroid/graphics/drawable/Drawable;",
            location = MatchAfterImmediately()),
        fieldAccess(type = "Landroid/graphics/drawable/Drawable;", opcode = Opcode.IPUT_OBJECT,
            location = MatchAfterWithin(2))
    )
)

/** The native binder configures the drag handle's touch target from resources. */
internal object PlaylistDragBinderFingerprint : Fingerprint(
    classFingerprint = PlaylistRichRowConstructorFingerprint,
    returnType = "V",
    filters = listOf(resourceLiteral(ResourceType.DIMEN, "playlist_drag_handle_touch_padding_start"))
)

internal fun playlistDragStartFingerprint(holderType: String) = Fingerprint(
    definingClass = holderType,
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    filters = listOf(
        // Holder identity guard. 21.42+ inverts the branch.
        anyInstruction(opcode(Opcode.IF_EQ), opcode(Opcode.IF_NE)),
        fieldAccess(type = "Landroid/view/View;", opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(1)),
        methodCall("Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V",
            location = MatchAfterWithin(1))
    )
)

internal fun playlistDragEndFingerprint(holderType: String) = Fingerprint(
    definingClass = holderType,
    returnType = "V",
    parameters = listOf("L", "L", "I", "I"),
    filters = listOf(
        anyInstruction(opcode(Opcode.IF_EQ), opcode(Opcode.IF_NE)),
        methodCall(definingClass = holderType, opcode = Opcode.INVOKE_DIRECT,
            parameters = emptyList(), returnType = "V", location = MatchAfterWithin(1))
    )
)

internal fun playlistDragUnbindFingerprint(holderType: String) = Fingerprint(
    definingClass = holderType,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall("Landroid/view/View;->setOnTouchListener(Landroid/view/View\$OnTouchListener;)V"),
        methodCall("Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V")
    )
)

/** Native edit events read the row's video through the adapter row interface. */
internal fun playlistEditEventsFingerprint(videoType: String) = Fingerprint(
    classFingerprint = PlaylistItemsConstructorFingerprint,
    parameters = listOf("Ljava/lang/Class;", "Ljava/lang/Object;", "I"),
    returnType = "[Ljava/lang/Class;",
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = videoType)
    )
)

internal fun playlistNavigationRequestFingerprint(enumType: String) = Fingerprint(
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(enumType, "L", "L")
)

internal fun playlistNavigationIntentFingerprint(enumType: String, intent: String) = Fingerprint(
    definingClass = enumType,
    name = "<clinit>",
    filters = listOf(
        string(intent),
        fieldAccess(type = enumType, opcode = Opcode.SPUT_OBJECT, location = MatchAfterWithin(3))
    )
)

/** Static navigation request for one intent. 21.41+ also passes null descriptors to the constructor. */
internal fun playlistNavigationSingletonFingerprint(wrapperType: String, intent: FieldReference) = Fingerprint(
    definingClass = wrapperType,
    name = "<clinit>",
    filters = listOf(
        fieldAccess(reference = intent, opcode = Opcode.SGET_OBJECT),
        methodCall(definingClass = wrapperType, name = "<init>", location = MatchAfterWithin(1)),
        fieldAccess(type = wrapperType, opcode = Opcode.SPUT_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * Creates the playlist controls with their navigator, after binding the collapsed header click.
 * 21.28+ creates one of two implementations, chosen by a server flag.
 */
internal fun playlistControlsCreationFingerprint(controllerType: String) = Fingerprint(
    definingClass = controllerType,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        // The collapsed panel view.
        fieldAccess(definingClass = "this", type = "Landroid/view/View;", opcode = Opcode.IGET_OBJECT),
        methodCall(
            "Landroid/view/View;->setOnClickListener(Landroid/view/View\$OnClickListener;)V",
            location = MatchAfterWithin(3)
        ),
        // The navigator provider.
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = "Ljava/lang/Object;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(1)),
        methodCall(name = "<init>", opcode = Opcode.INVOKE_DIRECT_RANGE),
        fieldAccess(
            definingClass = controllerType,
            opcode = Opcode.IPUT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/** Before 21.28 the playlist controls are a single class. */
internal fun playlistControlsConstructorFingerprint(navigatorType: String, controlsType: String) = Fingerprint(
    definingClass = controlsType,
    name = "<init>",
    filters = listOf(fieldAccess(type = navigatorType, opcode = Opcode.IPUT_OBJECT))
)

/** 21.28+ implementations of the playlist controls interface. */
internal fun playlistControlsImplementationFingerprint(navigatorType: String, controlsInterface: String) = Fingerprint(
    interfaces = listOf(controlsInterface),
    name = "<init>",
    filters = listOf(fieldAccess(type = navigatorType, opcode = Opcode.IPUT_OBJECT))
)

/** Native player control eligibility, including its alternate history callbacks. */
internal fun playlistControlAvailabilityFingerprint(navigatorType: String, request: FieldReference) = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = "Z",
            location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        fieldAccess(type = navigatorType, opcode = Opcode.IGET_OBJECT),
        fieldAccess(reference = request, opcode = Opcode.SGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(definingClass = navigatorType, parameters = listOf(request.type), returnType = "Z",
            location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQ, location = MatchAfterImmediately())
    )
)

/** The press callback logs first, then dispatches to its delegate or the navigator. */
internal fun playlistControlPressFingerprint(controlsType: String, delegate: FieldReference, request: FieldReference) =
    Fingerprint(
        definingClass = controlsType,
        returnType = "V",
        parameters = emptyList(),
        filters = listOf(
            // Optional logging callback.
            methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = "V"),
            fieldAccess(reference = delegate, opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
            fieldAccess(reference = request, opcode = Opcode.SGET_OBJECT)
        )
    )

/** The player controls keep their navigator. */
internal fun playlistPlayerControlsConstructorFingerprint(controlsType: String, navigatorType: String) = Fingerprint(
    definingClass = controlsType,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = navigatorType, opcode = Opcode.IPUT_OBJECT))
)

/** Dedicated playlist engagement header; ordinary engagement headers use another layout. */
internal object PlaylistTitleCreationFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = emptyList(),
    filters = listOf(resourceLiteral(ResourceType.LAYOUT, "playlist_engagement_panel_header"))
)

internal object PlaylistTitleViewFingerprint : Fingerprint(
    classFingerprint = PlaylistTitleCreationFingerprint,
    returnType = "Landroid/view/View;",
    parameters = emptyList(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "title"),
        methodCall("Landroid/view/View;->findViewById(I)Landroid/view/View;", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast("Landroid/widget/TextView;", location = MatchAfterImmediately()),
        fieldAccess(definingClass = "this", type = "Landroid/widget/TextView;", opcode = Opcode.IPUT_OBJECT,
            location = MatchAfterImmediately())
    )
)

internal fun playlistTitleBindingFingerprint(headerType: String, panelType: String) = Fingerprint(
    definingClass = headerType,
    returnType = "V",
    parameters = listOf(panelType),
    filters = listOf(fieldAccess(definingClass = "this", type = panelType, opcode = Opcode.IPUT_OBJECT))
)

internal object PlaylistHeaderConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(resourceLiteral(ResourceType.LAYOUT, "compact_playlist_panel_header"))
)

internal object PlaylistShuffleViewFingerprint : Fingerprint(
    classFingerprint = PlaylistHeaderConstructorFingerprint,
    name = "<init>",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "shuffle"),
        methodCall("Landroid/view/View;->findViewById(I)Landroid/view/View;", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast("Landroid/widget/ImageView;", location = MatchAfterImmediately()),
        fieldAccess(type = "Landroid/widget/ImageView;", opcode = Opcode.IPUT_OBJECT,
            location = MatchAfterImmediately())
    )
)

/** The playlist header keeps the playlist's navigator. */
internal fun playlistHeaderNavigatorFingerprint(navigatorType: String) = Fingerprint(
    classFingerprint = PlaylistHeaderConstructorFingerprint,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = navigatorType, opcode = Opcode.IPUT_OBJECT))
)

internal object PlaylistHeaderRepeatViewFingerprint : Fingerprint(
    classFingerprint = PlaylistHeaderConstructorFingerprint,
    name = "<init>",
    filters = listOf(
        checkCast("Lcom/google/android/apps/youtube/app/watch/panel/ui/PlaylistLoopButtonView;"),
        fieldAccess(
            definingClass = "this",
            type = "Lcom/google/android/apps/youtube/app/watch/panel/ui/PlaylistLoopButtonView;",
            opcode = Opcode.IPUT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/** The header binder stores the bound playlist panel. */
internal fun playlistHeaderPanelFingerprint(binder: Method) = Fingerprint(
    definingClass = binder.definingClass,
    name = binder.name,
    parameters = binder.parameterTypes.map { it.toString() },
    returnType = binder.returnType,
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            type = binder.parameterTypes.first().toString(),
            opcode = Opcode.IPUT_OBJECT
        )
    )
)

/** The fallback header action reads its service and shuffle view before toggling. */
internal fun playlistShufflePressFingerprint(navigator: FieldReference, view: FieldReference) = Fingerprint(
    name = "onClick",
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
    filters = listOf(
        fieldAccess(reference = navigator, opcode = Opcode.IGET_OBJECT),
        fieldAccess(reference = view, opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall("Landroid/widget/ImageView;->isSelected()Z", location = MatchAfterImmediately())
    )
)

/** Both native repeat controls observe this exact mode/shuffle pair. */
internal object PlaylistRepeatBindingFingerprint : Fingerprint(
    classFingerprint = PlaylistHeaderConstructorFingerprint,
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        fieldAccess(type = "I", opcode = Opcode.IGET, location = MatchAfterImmediately()),
        fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN, location = MatchAfterImmediately()),
        methodCall(parameters = listOf("I", "Z"), returnType = "V", location = MatchAfterImmediately())
    )
)

internal object PlaylistRepeatCapabilitiesFingerprint : Fingerprint(
    classFingerprint = PlaylistHeaderConstructorFingerprint,
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    filters = listOf(
        fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall("Lcom/google/android/apps/youtube/app/watch/panel/ui/PlaylistLoopButtonView;->setEnabled(Z)V")
    )
)

/** Observe the native notification before it compares old state or notifies either control. */
internal fun notificationSkipCallbackFingerprint(callbackName: String) = Fingerprint(
    superclass = "Landroid/media/session/MediaSession\$Callback;",
    name = callbackName,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)

/** The media session callback's delegate forwards a skip to the media owner. */
internal fun notificationDelegateFingerprint(
    callback: MethodReference,
    ownerType: String,
    ownerMethod: String? = null,
    delegateType: String? = null
) = Fingerprint(
    definingClass = delegateType,
    superclass = callback.definingClass,
    name = callback.name,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            definingClass = ownerType,
            name = ownerMethod,
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    )
)

/** Observe after media logging, before the optional foreground callback takes over. */
internal fun notificationNextDispatchFingerprint(next: FieldReference) = Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(5)),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(),
            returnType = "Ljava/lang/Object;", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        fieldAccess(reference = next, opcode = Opcode.SGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(parameters = listOf(next.type), returnType = "V", location = MatchAfterImmediately())
    )
)

/** The service navigator recognizes an ended same-video target before starting playback. */
internal fun playlistServiceNavigatorFingerprint(wrapperType: String) = Fingerprint(
    parameters = listOf(wrapperType),
    returnType = "V",
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = "Ljava/lang/String;"),
        methodCall("Landroid/text/TextUtils;->equals(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z",
            location = MatchAfterWithin(1))
    )
)

/** Only the ordinary watch-page callback is replaced; casting/no-op delegates keep control. */
internal fun notificationForegroundSkipFingerprint(callbackType: String, request: FieldReference) = Fingerprint(
    interfaces = listOf(callbackType),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(fieldAccess(reference = request, opcode = Opcode.SGET_OBJECT))
)

/** The playback service checks whether a navigation request can be dispatched. */
internal fun playlistServiceCanNavigateFingerprint(serviceType: String, requestType: String) = Fingerprint(
    definingClass = serviceType,
    returnType = "Z",
    parameters = listOf(requestType)
)

/** Load a new watch target; native JUMP only accepts targets in an existing playlist. */
internal fun notificationPreviousDispatchFingerprint(previous: MethodReference) = Fingerprint(
    definingClass = previous.definingClass,
    name = previous.name,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(definingClass = previous.definingClass, opcode = Opcode.IGET_OBJECT),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately())
    )
)

/** Stable broadcast actions identify the native notification's paired skip controls. */
internal object NotificationSkipActionsFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(
        // The notification capabilities model.
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        string("com.google.android.libraries.youtube.player.action.controller_notification_prev"),
        string("com.google.android.libraries.youtube.player.action.controller_notification_next")
    )
)

/** Publishes the Previous and Next capabilities, returning the changed flags. */
internal fun notificationCapabilitiesSetterFingerprint(modelType: String) = Fingerprint(
    definingClass = modelType,
    returnType = "I",
    parameters = listOf("Z", "Z")
)

/** Both native adapter variants publish Previous/Next through the same model setter. */
internal fun notificationCapabilitiesFingerprint(setter: MethodReference, adapterType: String? = null) =
    Fingerprint(
        definingClass = adapterType,
        parameters = emptyList(),
        filters = listOf(methodCall(reference = setter))
    )

/** The adapter keeps the notification's media owner. */
internal fun notificationAdapterConstructorFingerprint(adapterType: String, ownerType: String) = Fingerprint(
    definingClass = adapterType,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = ownerType, opcode = Opcode.IPUT_OBJECT))
)

/** The newer adapter dispatches the setter's returned change flags through its native notifier. */
internal fun notificationCapabilitiesDispatchFingerprint(computation: MethodReference) = Fingerprint(
    definingClass = computation.definingClass,
    filters = listOf(
        methodCall(reference = computation),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(definingClass = computation.definingClass, parameters = listOf("I"), returnType = "V",
            location = MatchAfterImmediately())
    )
)

/** PCU is the native player-controls update trace in the original notification adapter. */
internal fun notificationPlaybackStateFingerprint(adapterType: String) = Fingerprint(
    definingClass = adapterType,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(string("PCU"))
)

/** The newer adapter implements the same playback-state callback without the trace. */
internal fun notificationPlaybackStateFingerprint(adapterType: String, callback: MethodReference) = Fingerprint(
    definingClass = adapterType,
    name = callback.name,
    returnType = callback.returnType,
    parameters = callback.parameterTypes.map { it.toString() }
)

/** The generated builder's missing-property diagnostic identifies duration finalization. */
internal fun queueSnackbarDurationFingerprint(builderType: String) = Fingerprint(
    definingClass = builderType,
    parameters = emptyList(),
    returnType = "L",
    filters = listOf(
        // Without an action, the default duration is set to zero.
        methodCall("Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        opcode(Opcode.CONST_4, location = MatchAfterImmediately()),
        methodCall(
            definingClass = "this",
            parameters = listOf("I"),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        string("Property \"duration\" has not been set")
    )
)

/** Both snackbar factories live together; the watch-screen factory is its native sibling. */
internal object QueueSnackbarFactoryFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(string("feedback_undo"))
)

/** The mini player's close click, rather than programmatic reloads using the same dismiss API. */
internal fun queuePlayerCloseClickFingerprint(dismiss: MethodReference) = Fingerprint(
    interfaces = listOf("Landroid/view/View\$OnClickListener;"),
    name = "onClick",
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
    filters = listOf(methodCall(reference = dismiss))
)

/** A fling past the native threshold dismisses the mini player. */
internal fun queuePlayerCloseFlingFingerprint(dismiss: MethodReference) = Fingerprint(
    name = "onFling",
    parameters = listOf("Landroid/view/MotionEvent;", "Landroid/view/MotionEvent;", "F", "F"),
    returnType = "Z",
    filters = listOf(methodCall(reference = dismiss))
)

/** Before 21.32 the fling has two separate dismissal branches. */
internal fun queuePlayerCloseFlingLegacyFingerprint(dismiss: MethodReference) = Fingerprint(
    name = "onFling",
    parameters = listOf("Landroid/view/MotionEvent;", "Landroid/view/MotionEvent;", "F", "F"),
    returnType = "Z",
    filters = listOf(
        methodCall(reference = dismiss),
        methodCall(reference = dismiss)
    )
)

/** A slow drag can close on touch release without going through onFling. */
internal fun queuePlayerCloseTouchFingerprint(dismiss: MethodReference) = Fingerprint(
    parameters = listOf("Landroid/view/View;", "Landroid/view/MotionEvent;"),
    returnType = "Z",
    filters = listOf(methodCall(reference = dismiss))
)

/** The playlist controller dispatches commands through the activity's command resolver. */
internal fun playlistControllerDispatcherFingerprint(controllerType: String, dispatcherType: String) = Fingerprint(
    definingClass = controllerType,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(definingClass = "this", type = dispatcherType, opcode = Opcode.IGET_OBJECT),
        methodCall(definingClass = dispatcherType, returnType = "V")
    )
)

/** Snackbar factory for modern buttons, whose click listener is a shared lambda class. */
internal object QueueSnackbarModernFactoryFingerprint : Fingerprint(
    classFingerprint = QueueSnackbarFactoryFingerprint,
    returnType = "L",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = List(4) { "Ljava/lang/Object;" } + listOf("I", "[S")
        ),
        methodCall(
            parameters = listOf("Ljava/lang/CharSequence;", "Landroid/view/View\$OnClickListener;"),
            returnType = "V"
        ),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterImmediately())
    )
)

/** The snackbar factory keeps the activity's command dispatcher. */
internal fun queueSnackbarDispatcherFingerprint(dispatcherType: String) = Fingerprint(
    classFingerprint = QueueSnackbarFactoryFingerprint,
    name = "<init>",
    filters = listOf(fieldAccess(definingClass = "this", type = dispatcherType, opcode = Opcode.IPUT_OBJECT))
)

/** The modern snackbar button dispatches its service, navigation or command endpoint. */
internal fun queueUndoClickFingerprint(
    listenerType: String,
    dispatcher: FieldReference,
    callback: MethodReference
) = Fingerprint(
    definingClass = listenerType,
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(string("com.google.android.libraries.youtube.innertube.endpoint.tag")) +
            List(3) {
                listOf(
                    fieldAccess(reference = dispatcher, opcode = Opcode.IGET_OBJECT),
                    methodCall(reference = callback, location = MatchAfterWithin(6))
                )
            }.flatten()
)

/** Both menu sheets read their renderer before iterating its item list. */
internal object QueueFlyoutRendererFingerprint : Fingerprint(
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(3)),
        methodCall("Ljava/util/List;->iterator()Ljava/util/Iterator;", location = MatchAfterImmediately())
    ),
    strings = listOf("Bottom Sheet Menu is empty. No menu items were supported.")
)

/** Native visibility filtering lives beside the popup menu list builder. */
internal object QueueFlyoutResolverFingerprint : Fingerprint(
    filters = listOf(string("Unknown menu visibility condition: %d"))
)

/** Popup menus filter the same renderer in the native visibility resolver's class. */
internal fun queueFlyoutPopupFingerprint(resolverType: String, menuType: String, items: FieldReference) = Fingerprint(
    definingClass = resolverType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(menuType, "L", "L", "L"),
    returnType = "L",
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, reference = items),
        methodCall("Ljava/util/List;->iterator()Ljava/util/Iterator;", location = MatchAfterImmediately())
    )
)

/** Sheet item dispatch bypasses the model's Runnable in both native sheet variants. */
internal fun queueFlyoutClickFingerprint(sheetType: String, buttonType: String, itemType: String) = Fingerprint(
    definingClass = sheetType,
    returnType = "V",
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = buttonType, type = itemType),
        // Dismissal is inherited from the native fragment superclass.
        methodCall(parameters = emptyList(), returnType = "V"),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    )
)

/** The concrete button stores its native menu item; Runnable belongs to a superclass. */
internal fun queueFlyoutButtonConstructorFingerprint(factory: MethodReference, itemType: String) = Fingerprint(
    definingClass = factory.definingClass,
    name = factory.name,
    parameters = factory.parameterTypes.map { it.toString() },
    returnType = factory.returnType,
    filters = listOf(methodCall(
        opcode = Opcode.INVOKE_DIRECT,
        name = "<init>",
        parameters = listOf("Ljava/lang/String;", itemType),
        returnType = "V"
    ))
)
