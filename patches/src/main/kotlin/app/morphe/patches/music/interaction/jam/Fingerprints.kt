/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val OBJECT = "Ljava/lang/Object;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val VIEW = "Landroid/view/View;"

/**
 * The queue operation emits stable diagnostic strings from YouTube Music's queue manager. Every
 * other queue member is derived from this semantic entry point in [JamAbi].
 */
internal object QueueEnqueueFingerprint :
    Fingerprint(
        returnType = "V",
        parameters = listOf("L"),
        strings =
            listOf(
                "com/google/android/apps/youtube/music/player/queue/MusicPlaybackQueueOperationsManager",
                "enqueue",
                "enqueue item, QueueTarget: %s, position: %s",
            ),
    )

/** The platform MediaSession call is stable even when YouTube Music's state adapter is renamed. */
internal object MediaSessionStateFingerprint :
    Fingerprint(
        returnType = "V",
        parameters = listOf("Landroid/support/v4/media/session/PlaybackStateCompat;"),
        filters =
            listOf(
                methodCall(
                    definingClass = "Landroid/media/session/MediaSession;",
                    name = "setPlaybackState",
                    parameters = listOf("Landroid/media/session/PlaybackState;"),
                    returnType = "V",
                    opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
                )
            ),
    )

/**
 * The now-playing presenter writes a bitmap into YouTube Music's stable artwork view. The field
 * access ties the call to the presenter itself rather than a reusable image helper.
 */
internal fun nowPlayingArtworkFingerprint(imageType: String) =
    Fingerprint(
        returnType = "V",
        parameters = listOf("Landroid/graphics/Bitmap;"),
        filters =
            listOf(
                fieldAccess(definingClass = "this", type = imageType, opcode = Opcode.IGET_OBJECT),
                methodCall(
                    definingClass = imageType,
                    name = "setImageBitmap",
                    parameters = listOf("Landroid/graphics/Bitmap;"),
                    returnType = "V",
                    opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
                ),
            ),
    )

/** Identify the native full-player presenter by its stable view resources. */
internal object PlayerMetadataViewsFingerprint :
    Fingerprint(
        name = "<init>",
        filters =
            listOf(
                resourceLiteral(ResourceType.ID, "player_page"),
                resourceLiteral(ResourceType.ID, "mini_player_title"),
                resourceLiteral(ResourceType.ID, "mini_player_subtitle"),
            ),
    )

/** Rebind the presenter from the current-item source when a Jam snapshot changes. */
internal fun nowPlayingRefreshFingerprint(currentAccessor: MethodReference) =
    Fingerprint(
        classFingerprint = PlayerMetadataViewsFingerprint,
        returnType = "V",
        parameters = listOf("L"),
        filters = listOf(methodCall(reference = currentAccessor)),
    )

/** Only the resolved player presenter is intercepted, never unrelated app labels. */
internal fun nowPlayingTextWritesFingerprint(presenterType: String) =
    Fingerprint(
        definingClass = presenterType,
        filters =
            listOf(
                methodCall(
                    name = "setText",
                    parameters = listOf("Ljava/lang/CharSequence;"),
                    returnType = "V",
                    opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
                )
            ),
    )

/**
 * Account-scoped endpoint routing uses these stable diagnostics while resolving the fragment
 * delegate. The matched method's return type is the peer router implementation.
 */
internal object AccountScopedCommandRouterFingerprint :
    Fingerprint(
        returnType = "L",
        parameters = listOf("L"),
        strings =
            listOf(
                "com.google.android.apps.youtube.app.endpoint.routers.AccountScopeCommandRouterFragment",
                "Expected delegate to be AccountScopedCommandRouterImpl, but was ",
            ),
    )

/** Finds the current-item comparator from the resolved queue item identity contract. */
internal fun queueItemSelectionFingerprint(itemType: String, persistentId: MethodReference) =
    Fingerprint(
        returnType = "Z",
        parameters = listOf(itemType, "Z"),
        filters =
            listOf(
                fieldAccess(definingClass = "this", type = itemType, opcode = Opcode.IGET_OBJECT),
                methodCall(
                    definingClass = itemType,
                    name = persistentId.name,
                    parameters = persistentId.parameterTypes.map { it.toString() },
                    returnType = persistentId.returnType,
                ),
            ),
    )

/** The watch page restores the current item when its Android lifecycle starts. */
internal object CurrentPlaybackItemSourceFingerprint :
    Fingerprint(
        classFingerprint = WatchPageStateFingerprint,
        name = "onStart",
        returnType = "V",
        parameters = emptyList(),
        filters =
            listOf(
                methodCall(parameters = emptyList(), returnType = OPTIONAL),
                methodCall(
                    definingClass = OPTIONAL,
                    name = "isPresent",
                    parameters = emptyList(),
                    returnType = "Z",
                ),
            ),
    )

/** Finds components that own the stable time-bar view. */
internal fun timeBarOwnerFingerprint(timeBarType: String) =
    Fingerprint(
        filters =
            listOf(
                fieldAccess(definingClass = "this", type = timeBarType, opcode = Opcode.IGET_OBJECT)
            )
    )

/** Constructor signatures retain the control relationship when merged fields become Object. */
internal fun seekForwarderConstructorFingerprint(ownerType: String, controlTypes: Set<String>) =
    Fingerprint(
        definingClass = ownerType,
        name = "<init>",
        custom = { method, _ -> method.parameterTypes.any { it.toString() in controlTypes } },
    )

/** Finds the seek callback that forwards a position and native seek context. */
internal fun BytecodePatchContext.seekForwarderFingerprint(controlTypes: Set<String>) =
    Fingerprint(
        returnType = "V",
        parameters = listOf("J", "L"),
        filters = listOf(methodCall(parameters = listOf("J", "L"), returnType = "V")),
        custom = { method, classDef ->
          val holdsControls = classDef.fields.any { it.type in controlTypes }
          val receivesControls =
              seekForwarderConstructorFingerprint(classDef.type, controlTypes)
                  .matchAllOrNull(classDef) != null
          (holdsControls || receivesControls) &&
              method.indexOfFirstInstruction(
                  methodCall(
                      parameters = method.parameterTypes.map { it.toString() },
                      returnType = "V",
                  )
              ) >= 0
        },
    )

/** Finds the palette source's publish-then-apply entry point. */
internal object PalettePublicationFingerprint :
    Fingerprint(
        returnType = "V",
        parameters = emptyList(),
        filters =
            listOf(
                methodCall(parameters = listOf(OBJECT), returnType = "V"),
                methodCall(definingClass = "this", parameters = listOf("L"), returnType = "V"),
            ),
    )

/** Finds the stable playback-control click callback. */
internal fun playbackControlsClickFingerprint(controlsType: String) =
    Fingerprint(
        definingClass = controlsType,
        name = "onClick",
        returnType = "V",
        parameters = listOf(VIEW),
    )

/** Finds click wrappers that delegate to the stable playback controls. */
internal fun playbackButtonClickFingerprint(controlsType: String) =
    Fingerprint(
        name = "onClick",
        returnType = "V",
        parameters = listOf(VIEW),
        custom = { method, classDef ->
          // Merged listeners store their target as Object and cast it in the click branch.
          classDef.fields.any { it.type == controlsType } ||
              method.indexOfFirstInstruction(checkCast(controlsType)) >= 0
        },
    )

/** Finds an observable queue lane's listener-set mutation implementation. */
internal fun queueLaneListenerMutationFingerprint(listenerType: String, operation: String) =
    Fingerprint(
        returnType = "V",
        parameters = listOf(listenerType),
        filters =
            listOf(
                methodCall(
                    definingClass = "Ljava/util/Set;",
                    name = operation,
                    parameters = listOf(OBJECT),
                    returnType = "Z",
                    opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
                )
            ),
    )

/** Finds methods that consume the resolved artwork-list field. */
internal fun thumbnailEntryUsageFingerprint(artworkList: FieldReference) =
    Fingerprint(filters = listOf(fieldAccess(reference = artworkList)))

/** Finds the watch-page current-item menu entry point. */
internal fun nowPlayingMenuEntryFingerprint(
    watchPageType: String,
    currentType: String,
    currentAccessor: MethodReference,
) =
    Fingerprint(
        returnType = "V",
        filters =
            listOf(
                methodCall(name = "requireActivity", parameters = emptyList()),
                fieldAccess(
                    definingClass = watchPageType,
                    type = currentType,
                    opcode = Opcode.IGET_OBJECT,
                ),
                methodCall(reference = currentAccessor),
                methodCall("Lj$/util/Optional;->isPresent()Z"),
                methodCall(
                    parameters = listOf("L", VIEW, "L", "L"),
                    returnType = "V",
                ),
            ),
    )

/** Finds now-playing queue binders that retrieve a concrete native queue item. */
internal fun queueBindingFingerprint(itemType: String) =
    Fingerprint(
        returnType = "V",
        parameters = listOf("L", "I"),
        filters = listOf(methodCall(parameters = listOf("I"), returnType = itemType)),
    )

/** Finds a queue-row binder for one of the resolved concrete queue item types. */
internal fun queueRowBindingFingerprint(itemType: String) =
    Fingerprint(
        returnType = "V",
        parameters = listOf("L", "L", itemType),
        filters =
            listOf(
                fieldAccess(definingClass = "this", type = itemType, opcode = Opcode.IPUT_OBJECT)
            ),
    )

/** The artwork view crops from bitmap pixels before displaying the bitmap. */
internal object ArtworkCropFingerprint :
    Fingerprint(
        name = "setImageBitmap",
        parameters = listOf("Landroid/graphics/Bitmap;"),
        returnType = "V",
        filters =
            listOf(
                methodCall("Landroid/graphics/Bitmap;->getHeight()I"),
                methodCall("Landroid/graphics/Bitmap;->getWidth()I"),
                methodCall("Landroid/graphics/Bitmap;->getPixels([IIIIIII)V"),
            ),
    )

/** Android state saving for the watch page's background playback attempt. */
internal object WatchPageStateFingerprint :
    Fingerprint(
        name = "onSaveInstanceState",
        parameters = listOf("Landroid/os/Bundle;"),
        returnType = "V",
        strings = listOf("background_failed", "background_start_time"),
    )

/** The player time bar exposes a time-formatted accessibility description. */
internal object PlaybackTimeBarFingerprint :
    Fingerprint(
        name = "onInitializeAccessibilityNodeInfo",
        parameters = listOf("Landroid/view/accessibility/AccessibilityNodeInfo;"),
        returnType = "V",
        strings = listOf("([0-9]+:)?([0-5]?[0-9]):[0-5][0-9]"),
    )

/** Native playback controls bind the transport buttons when their layout is inflated. */
internal object PlaybackControlViewsFingerprint :
    Fingerprint(
        name = "onFinishInflate",
        parameters = emptyList(),
        returnType = "V",
        filters =
            listOf(
                resourceLiteral(ResourceType.ID, "player_control_play_pause_replay_button"),
                resourceLiteral(ResourceType.ID, "player_control_previous_button"),
                resourceLiteral(ResourceType.ID, "player_control_next_button"),
            ),
    )

/** The operations manager has a single construction/capture point. */
internal fun queueManagerConstructorFingerprint(managerType: String) =
    Fingerprint(definingClass = managerType, name = "<init>")

/** Removal updates both native queue displays, unlike lane-specific operations. */
internal fun queueRemovalFingerprint(managerType: String, displays: QueueDisplaysAbi) =
    Fingerprint(
        definingClass = managerType,
        parameters = listOf("L"),
        returnType = "V",
        filters = listOf(fieldAccess(reference = displays.primary.managerField)),
        custom = { method, _ ->
          method.indexOfFirstInstruction(fieldAccess(reference = displays.autoplay.managerField)) >=
              0
        },
    )

internal fun queueSnapshotFingerprint(storageType: String) =
    Fingerprint(
        definingClass = storageType,
        parameters = listOf("I"),
        returnType = "Ljava/util/List;",
    )

internal fun queueLaneMoveFingerprint(laneType: String) =
    Fingerprint(definingClass = laneType, parameters = listOf("I", "I"), returnType = "V")

/** Enum initialization binds the stable LOCAL name to its native singleton. */
internal fun localQueueModeFingerprint(modeType: String) =
    Fingerprint(
        definingClass = modeType,
        name = "<clinit>",
        filters =
            listOf(
                string("LOCAL"),
                methodCall("Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V"),
                fieldAccess(definingClass = modeType, type = modeType, opcode = Opcode.SPUT_OBJECT),
            ),
    )

internal fun displayedQueueCurrentFingerprint(displayType: String, current: MethodReference) =
    Fingerprint(
        definingClass = displayType,
        parameters = emptyList(),
        returnType = "I",
        filters = listOf(methodCall(reference = current)),
    )

/** Refresh reconciles a sub-list through indexed List insertion and removal. */
internal fun displayedQueueRefreshFingerprint(displayType: String) =
    Fingerprint(
        definingClass = displayType,
        parameters = emptyList(),
        returnType = "V",
        filters =
            listOf(
                methodCall(
                    name = "subList",
                    parameters = listOf("I", "I"),
                    returnType = "Ljava/util/List;",
                )
            ),
        custom = { method, _ ->
          method.indexOfFirstInstruction(
              methodCall("Ljava/util/List;->add(ILjava/lang/Object;)V")
          ) >= 0 &&
              method.indexOfFirstInstruction(
                  methodCall("Ljava/util/List;->remove(I)Ljava/lang/Object;")
              ) >= 0
        },
    )

internal fun displayedQueueMoveFingerprint(displayType: String, move: MethodReference) =
    Fingerprint(
        definingClass = displayType,
        parameters = listOf("I", "I"),
        returnType = "V",
        filters = listOf(methodCall(reference = move)),
    )

/** A callback constructor stores its enclosing operations manager. */
internal fun queueCallbackConstructorFingerprint(callbackType: String, managerType: String) =
    Fingerprint(
        definingClass = callbackType,
        name = "<init>",
        filters = listOf(fieldAccess(type = managerType, opcode = Opcode.IPUT_OBJECT)),
    )

/** Runtime progress/remaining colors applied when the time bar removes its shader. */
internal fun clockOverrideColorsFingerprint(timeBarType: String, modelType: String) =
    Fingerprint(
        definingClass = timeBarType,
        returnType = "V",
        parameters = emptyList(),
        filters =
            listOf(
                literal(0),
                methodCall(
                    "Landroid/graphics/Paint;->setShader(Landroid/graphics/Shader;)Landroid/graphics/Shader;",
                    location = MatchAfterImmediately(),
                ),
                methodCall(definingClass = modelType, parameters = emptyList(), returnType = "I"),
                methodCall("Landroid/graphics/Paint;->setColor(I)V"),
                methodCall(definingClass = modelType, parameters = emptyList(), returnType = "I"),
                methodCall("Landroid/graphics/Paint;->setColor(I)V"),
            ),
    )

/** The drawn time label is gated by the model before the native dragging decision. */
internal fun clockLabelEnabledFingerprint(
    timeBarType: String,
    modelType: String,
    dragging: MethodReference,
) =
    Fingerprint(
        definingClass = timeBarType,
        name = "draw",
        parameters = listOf("Landroid/graphics/Canvas;"),
        returnType = "V",
        filters =
            listOf(
                methodCall(definingClass = modelType, parameters = emptyList(), returnType = "Z"),
                opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
                opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
                methodCall(reference = dragging, location = MatchAfterImmediately()),
            ),
    )

/** Implement the previously resolved model capability with its native field. */
internal fun clockModelFieldFingerprint(modelType: String, accessor: MethodReference) =
    Fingerprint(
        definingClass = modelType,
        name = accessor.name,
        parameters = emptyList(),
        returnType = accessor.returnType,
        filters = listOf(fieldAccess(definingClass = modelType, type = accessor.returnType)),
    )

/** The manager owns a store that exposes both snapshots and observable, movable lanes. */
internal fun BytecodePatchContext.queueStorageFingerprint(manager: ClassDef) =
    Fingerprint(
        parameters = listOf("I"),
        returnType = "Ljava/util/List;",
        custom = { _, owner ->
          manager.fields.any { it.type == owner.type } &&
              queueLaneAccessorFingerprint(owner.type).matchAllOrNull() != null
        },
    )

internal fun BytecodePatchContext.queueLaneAccessorFingerprint(storageType: String) =
    Fingerprint(
        definingClass = storageType,
        parameters = listOf("I"),
        returnType = "L",
        custom = { method, _ ->
          method.returnType != "Ljava/util/List;" &&
              classDefByOrNull(method.returnType) != null &&
              queueLaneMoveFingerprint(method.returnType).matchAllOrNull() != null &&
              queueLaneSliceFingerprint(method.returnType).matchAllOrNull() != null
        },
    )

internal fun queueLaneSliceFingerprint(laneType: String) =
    Fingerprint(
        definingClass = laneType,
        parameters = listOf("I", "I"),
        returnType = "Ljava/util/List;",
    )

/** State accessors forward to an interface held by the resolved native store. */
internal fun BytecodePatchContext.queueStateAccessorFingerprint(
    storageType: String,
    mode: Boolean,
) =
    Fingerprint(
        definingClass = storageType,
        parameters = emptyList(),
        returnType = if (mode) "L" else "I",
        custom = { method, owner ->
          (!mode ||
              classDefByOrNull(method.returnType)?.let { AccessFlags.ENUM.isSet(it.accessFlags) } ==
                  true) &&
              owner.fields.any { field ->
                method.indexOfFirstInstruction(
                    methodCall(
                        definingClass = field.type,
                        parameters = emptyList(),
                        returnType = method.returnType,
                        opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
                    )
                ) >= 0
              }
        },
    )

internal fun queueDisplayListenerFingerprint(laneType: String, listenerTypes: Set<String>) =
    Fingerprint(
        definingClass = laneType,
        parameters = listOf("L"),
        returnType = "V",
        custom = { method, _ -> method.parameterTypes[0].toString() in listenerTypes },
    )

internal fun clockModelTimestampFingerprint(modelType: String) =
    Fingerprint(definingClass = modelType, parameters = emptyList(), returnType = "J")

internal fun BytecodePatchContext.clockModelSetterFingerprint(timeBarType: String) =
    Fingerprint(
        parameters = listOf("L"),
        returnType = "V",
        custom = { method, owner ->
          owner.type in interfaceClosure(timeBarType) &&
              owner.fields.any { it.type == method.parameterTypes[0].toString() } &&
              clockModelTimestampFingerprint(method.parameterTypes[0].toString())
                  .matchAllOrNull()
                  .orEmpty()
                  .size >= 3
        },
    )

internal fun clockDraggingFingerprint(timeBaseType: String) =
    Fingerprint(definingClass = timeBaseType, parameters = emptyList(), returnType = "Z")

internal fun BytecodePatchContext.clockMutableModelFingerprint(modelType: String) =
    Fingerprint(
        parameters = listOf("J", "J", "J", "J"),
        returnType = "V",
        custom = { _, owner ->
          !AccessFlags.ABSTRACT.isSet(owner.accessFlags) && implementsType(owner.type, modelType)
        },
    )

internal fun accountRouterDispatchFingerprint(routerType: String) =
    Fingerprint(
        definingClass = routerType,
        parameters = listOf("L", "Ljava/util/Map;"),
        returnType = "V",
        filters =
            listOf(
                methodCall(
                    parameters = listOf("L", "Ljava/util/Map;"),
                    returnType = "V",
                    opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
                )
            ),
        custom = { method, _ ->
          method.findInstructionIndicesReversed(methodCall(returnType = "Z")).any { index ->
            val parameters =
                method.getInstruction(index).getReference<MethodReference>()!!.parameterTypes
            parameters.getOrNull(parameters.size - 1) == method.parameterTypes[0]
          }
        },
    )

internal fun accountPeerDispatchFingerprint(peerType: String, forwarder: MethodReference) =
    Fingerprint(
        definingClass = peerType,
        parameters = forwarder.parameterTypes.map { it.toString() },
        returnType = "V",
        filters = listOf(methodCall(reference = forwarder)),
    )

internal fun protoDefaultInstanceFingerprint(messageType: String) =
    Fingerprint(
        definingClass = messageType,
        name = "<clinit>",
        filters =
            listOf(
                fieldAccess(
                    definingClass = messageType,
                    type = messageType,
                    opcode = Opcode.SPUT_OBJECT,
                )
            ),
    )

/** Generic protobuf parser inherited by the already resolved native message type. */
internal fun protoParserFingerprint(messageHierarchy: Set<String>) =
    Fingerprint(
        name = "parseFrom",
        parameters = listOf("L", "[B", "Lcom/google/protobuf/ExtensionRegistryLite;"),
        returnType = "L",
        custom = { method, owner ->
          owner.type in messageHierarchy &&
              method.returnType == owner.type &&
              method.parameterTypes[0].toString() == owner.type &&
              AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

/** A palette source owns an extractor producing the same type its local apply method consumes. */
internal fun paletteExtractorFingerprint(paletteType: String, ownedTypes: Set<String>) =
    Fingerprint(
        parameters = listOf("Landroid/graphics/Bitmap;"),
        returnType = paletteType,
        custom = { _, owner -> owner.type in ownedTypes },
    )

internal fun queueItemEndpointFingerprint(ownerType: String, commandType: String) =
    Fingerprint(definingClass = ownerType, parameters = emptyList(), returnType = commandType)

internal fun BytecodePatchContext.queuePersistentIdFingerprint(
    itemTypes: Set<String>,
    commandType: String,
) =
    Fingerprint(
        parameters = emptyList(),
        returnType = "J",
        custom = { _, owner ->
          owner.type in itemTypes &&
              queueItemEndpointFingerprint(owner.type, commandType).matchAllOrNull() != null
        },
    )

internal fun queueTextAccessorFingerprint(ownerType: String) =
    Fingerprint(
        definingClass = ownerType,
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
    )

internal fun BytecodePatchContext.queueVideoIdFingerprint(itemTypes: Set<String>) =
    Fingerprint(
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        custom = { _, owner ->
          owner.type in itemTypes && queueTextAccessorFingerprint(owner.type).matchAll().size == 1
        },
    )

internal fun nowPlayingMetadataBindingFingerprint(textAccessors: List<MethodReference>) =
    Fingerprint(
        classFingerprint = WatchPageStateFingerprint,
        parameters = listOf("Lj$/util/Optional;"),
        returnType = "V",
        custom = { method, _ ->
          textAccessors.all { method.indexOfFirstInstruction(methodCall(reference = it)) >= 0 }
        },
    )

internal fun queueRowRootFingerprint(rowType: String) =
    Fingerprint(
        definingClass = rowType,
        parameters = emptyList(),
        returnType = "Landroid/view/View;",
    )

internal fun queueRowLongClickFingerprint(rowType: String) =
    Fingerprint(
        definingClass = rowType,
        parameters = listOf("Landroid/view/View;"),
        returnType = "Z",
    )

internal fun queueMenuAccessorFingerprint(metadataType: String, menuType: String) =
    Fingerprint(definingClass = metadataType, parameters = emptyList(), returnType = menuType)

internal fun nowPlayingMenuDispatchFingerprint(entry: MethodReference) =
    Fingerprint(
        definingClass = entry.definingClass,
        name = entry.name,
        parameters = entry.parameterTypes.map { it.toString() },
        returnType = entry.returnType,
        filters =
            listOf(
                methodCall(
                    parameters = listOf("L", "Landroid/view/View;", "L", "L"),
                    returnType = "V",
                )
            ),
    )

/** A native item combines its persistent ID, message, and manager-owned factory. */
internal fun queueItemConstructorFingerprint(itemTypes: Set<String>, factoryTypes: Set<String>) =
    Fingerprint(
        name = "<init>",
        parameters = listOf("J", "L", "L"),
        custom = { method, owner ->
          owner.type in itemTypes && method.parameterTypes[2].toString() in factoryTypes
        },
    )

internal fun queueCallbackCaptureFingerprint(enqueue: Method, managerType: String) =
    Fingerprint(
        name = "<init>",
        filters = listOf(fieldAccess(type = managerType, opcode = Opcode.IPUT_OBJECT)),
        custom = { method, _ ->
          enqueue.indexOfFirstInstruction(methodCall(reference = method)) >= 0
        },
    )

internal fun BytecodePatchContext.queueSuccessCallbackFingerprint(callbackType: String) =
    Fingerprint(
        definingClass = callbackType,
        parameters = listOf("L"),
        returnType = "V",
        custom = { method, _ ->
          method.name != "<init>" &&
              method.findInstructionIndicesReversed(checkCast("L")).any { index ->
                val responseType = method.getInstruction(index).getReference<TypeReference>()!!.type
                classDefByOrNull(responseType)?.fields?.any { field ->
                  field.type == "Ljava/util/List;" || implementsType(field.type, "Ljava/util/List;")
                } == true
              }
        },
    )

internal fun queueFailureCallbackFingerprint(callbackType: String, success: MethodReference) =
    Fingerprint(
        definingClass = callbackType,
        parameters = listOf("L"),
        returnType = "V",
        custom = { method, _ -> method.name != "<init>" && method != success },
    )

internal fun queueMenuDispatcherFingerprint(dispatcherTypes: Set<String>, commandType: String) =
    Fingerprint(
        parameters = listOf(commandType, "Ljava/util/concurrent/Executor;"),
        returnType = "L",
        custom = { _, owner -> owner.type in dispatcherTypes },
    )

internal fun queueMoveNotifierFingerprint(notifierType: String, commit: Method) =
    Fingerprint(
        definingClass = notifierType,
        parameters = listOf("L", "L"),
        returnType = "V",
        custom = { method, _ ->
          method.parameterTypes[0] == method.parameterTypes[1] &&
              commit.indexOfFirstInstruction(methodCall(reference = method)) >= 0
        },
    )

internal fun queueResponseMapperConstructorFingerprint(
    mapperType: String,
    providerTypes: Set<String>,
) =
    Fingerprint(
        definingClass = mapperType,
        name = "<init>",
        custom = { method, _ -> method.parameterTypes.any { it.toString() in providerTypes } },
    )

internal fun BytecodePatchContext.queueResponseMapperFingerprint(
    mapperTypes: Set<String>,
    providerTypes: Set<String>,
) =
    Fingerprint(
        parameters = listOf("Ljava/lang/Object;"),
        returnType = "Ljava/lang/Object;",
        custom = { _, owner ->
          owner.type in mapperTypes &&
              queueResponseMapperConstructorFingerprint(owner.type, providerTypes)
                  .matchAllOrNull() != null
        },
    )

internal fun queuePresenterRefreshFingerprint(ownerTypes: Set<String>, callers: Iterable<Method>) =
    Fingerprint(
        parameters = emptyList(),
        returnType = "V",
        custom = { method, owner ->
          owner.type in ownerTypes &&
              method.name != "<init>" &&
              method.name != "<clinit>" &&
              callers.any { it.indexOfFirstInstruction(methodCall(reference = method)) >= 0 }
        },
    )

/** Artwork container holds one repeated thumbnail list; rich text has several repeated fields. */
internal fun BytecodePatchContext.queueArtworkAccessorFingerprint(metadataType: String) =
    Fingerprint(
        definingClass = metadataType,
        parameters = emptyList(),
        returnType = "L",
        custom = { method, _ ->
          classDefByOrNull(method.returnType)?.fields?.count {
            it.type == "Ljava/util/List;" || implementsType(it.type, "Ljava/util/List;")
          } == 1
        },
    )

internal fun BytecodePatchContext.queueArtworkContractFingerprint(sharedTypes: Set<String>) =
    Fingerprint(
        parameters = emptyList(),
        returnType = "L",
        custom = { method, owner ->
          owner.type in sharedTypes &&
              queueTextAccessorFingerprint(owner.type).matchAllOrNull().orEmpty().size == 2 &&
              classDefByOrNull(method.returnType)?.fields?.any {
                it.type == "Ljava/util/List;" || implementsType(it.type, "Ljava/util/List;")
              } == true
        },
    )

internal fun BytecodePatchContext.queueMenuResponseConstructorFingerprint(
    responseTypes: Set<String>
) =
    Fingerprint(
        name = "<init>",
        custom = { method, owner ->
          owner.type in responseTypes &&
              owner.fields.count {
                it.type == "Ljava/util/List;" || implementsType(it.type, "Ljava/util/List;")
              } == 1 &&
              method.parameterTypes.any {
                it.toString() == "Ljava/util/List;" ||
                    implementsType(it.toString(), "Ljava/util/List;")
              }
        },
    )

internal fun BytecodePatchContext.nativeImplementationFingerprint(contractType: String) =
    Fingerprint(
        name = "<init>",
        custom = { _, owner ->
          !AccessFlags.ABSTRACT.isSet(owner.accessFlags) &&
              !AccessFlags.INTERFACE.isSet(owner.accessFlags) &&
              implementsType(owner.type, contractType)
        },
    )

/** Native queue section owns both lane adapters and the user's local autoplay setting. */
internal fun autoplaySectionOwnerFingerprint(managerType: String, displayType: String) =
    Fingerprint(
        name = "<init>",
        strings = listOf("autoplay_enabled"),
        filters =
            listOf(
                methodCall(
                    definingClass = managerType,
                    parameters = listOf("I"),
                    returnType = displayType,
                )
            ),
    )

/** Projects the autoplay lane through a count-limited adapter and maintains its header. */
internal fun autoplaySectionRefreshFingerprint(ownerType: String) =
    Fingerprint(
        definingClass = ownerType,
        parameters = emptyList(),
        returnType = "V",
        filters =
            listOf(
                methodCall(name = "isEmpty", parameters = emptyList(), returnType = "Z"),
                methodCall(parameters = listOf("I"), returnType = "V"),
                methodCall(name = "clear", parameters = emptyList(), returnType = "V"),
            ),
    )

/** The native icon renderer owns play/pause accessibility text and drawable transitions. */
internal fun playbackIconFingerprint(ownerTypes: Set<String>) =
    Fingerprint(
        returnType = "V",
        parameters = listOf("L"),
        filters = listOf(
            resourceLiteral(ResourceType.STRING, "accessibility_play"),
            resourceLiteral(ResourceType.STRING, "accessibility_pause"),
            resourceLiteral(ResourceType.STRING, "accessibility_replay"),
        ),
        custom = { _, owner -> owner.type in ownerTypes },
    )

internal fun playbackIconModelConstructorFingerprint(modelType: String) =
    Fingerprint(
        definingClass = modelType,
        name = "<init>",
        parameters = listOf("L", "Z"),
        strings = listOf("controls can be in the buffering state only if in PLAYING or PAUSED video state"),
    )

/** Enum names survive obfuscation; the following store identifies the native state singleton. */
internal fun playbackIconStateFingerprint(stateType: String, stateName: String) =
    Fingerprint(
        definingClass = stateType,
        name = "<clinit>",
        filters = listOf(
            string(stateName),
            fieldAccess(definingClass = stateType, type = stateType, opcode = Opcode.SPUT_OBJECT),
        ),
    )

/** Locate header creation itself; feature-flag branches may precede this block. */
internal fun autoplayHeaderCreationFingerprint(header: FieldReference) =
    Fingerprint(
        filters =
            listOf(
                fieldAccess(reference = header, opcode = Opcode.IGET_OBJECT),
                methodCall(
                    name = "isEmpty",
                    parameters = emptyList(),
                    returnType = "Z",
                    location = MatchAfterImmediately(),
                ),
                methodCall(name = "add", parameters = listOf("I", OBJECT), returnType = "V"),
            ),
    )
