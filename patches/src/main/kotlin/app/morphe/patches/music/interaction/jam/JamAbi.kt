/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.instanceOf
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
private const val HANDLER = "Landroid/os/Handler;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"

/**
 * Native members resolved from stable queue relationships. The patch installs bridges from these
 * references; extension code never observes a YouTube Music implementation name.
 */
internal data class JamQueueAbi(
    val managerType: String,
    val constructor: MethodReference,
    val enqueue: MethodReference,
    val command: ProtoAbi,
    val executor: FieldReference,
    val storage: QueueStorageAbi,
    val displays: QueueDisplaysAbi,
    val callback: QueueCallbackAbi,
    val item: QueueItemAbi,
    val remove: MethodReference,
    val menu: QueueMenuAbi,
    val selection: MethodReference,
    val mutation: QueueMutationAbi,
)

internal data class ProtoAbi(
    val type: String,
    val defaultInstance: FieldReference,
    val parser: MethodReference,
)

internal data class QueueStorageAbi(
    val field: FieldReference,
    val type: String,
    val items: MethodReference,
    val lane: MethodReference,
    val currentIndex: MethodReference,
    val mode: MethodReference,
    val localMode: FieldReference,
    val laneType: String,
    val laneMove: MethodReference,
    val listenerType: String,
    val attachListener: MethodReference,
    val detachListener: MethodReference,
)

internal data class QueueDisplaysAbi(val primary: QueueDisplayAbi, val autoplay: QueueDisplayAbi)

internal data class QueueDisplayAbi(
    val managerField: FieldReference,
    val type: String,
    val list: FieldReference,
    val handler: FieldReference,
    val refresh: MethodReference,
    val currentIndex: MethodReference,
    val commitMove: MethodReference,
    val pendingMove: FieldReference,
)

internal data class QueueCallbackAbi(
    val type: String,
    val constructor: MethodReference,
    val manager: FieldReference,
    val success: MethodReference,
    val failure: MethodReference,
    val responseType: String,
    val responseItems: FieldReference,
)

internal data class QueueItemAbi(
    val type: String,
    val videoId: MethodReference,
    val persistentId: MethodReference,
    val metadataType: String,
    val title: MethodReference,
    val artist: MethodReference,
    val artwork: MethodReference,
    val artworkList: FieldReference,
    val thumbnailType: String,
    val thumbnailUrl: FieldReference,
    val implementations: List<QueueItemImplementationAbi>,
    val createItem: QueueItemImplementationAbi,
    val itemProto: ProtoAbi,
    val factory: FieldReference,
    val menuPayload: QueueItemMenuPayloadAbi? = null,
)

internal data class QueueItemImplementationAbi(val type: String)

internal data class QueueItemMenuPayloadAbi(val type: String)

internal data class QueueMenuAbi(
    val dispatcher: FieldReference,
    val dispatch: MethodReference,
    val responseType: String,
    val responseItems: FieldReference,
)

internal data class QueueMutationAbi(
    val provider: FieldReference,
    val providerMethod: MethodReference,
    val notifierType: String,
    val move: MethodReference,
)

/**
 * Resolves Jam's native queue ABI once, from the queue-operation fingerprint outward. Every
 * candidate is constrained by an observed relationship and fails with a subsystem-specific message
 * instead of silently selecting an arbitrary host member.
 */
internal fun BytecodePatchContext.resolveJamQueueAbi(): JamQueueAbi {
  val queueMatch = QueueEnqueueFingerprint.matchSingle()
  val manager = queueMatch.originalClassDef
  val enqueue = queueMatch.originalMethod
  val command = resolveProto(enqueue.parameters().singleOrNull(), "queue command")
  val managerConstructor =
      queueManagerConstructorFingerprint(manager.type).matchSingle().originalMethod
  val executor =
      manager.fields.filter { it.type == EXECUTOR }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam queue executor")
  val storage = resolveStorage(manager, enqueue)
  val displays = resolveDisplays(manager, managerConstructor, storage)
  val remove = queueRemovalFingerprint(manager.type, displays).matchSingle().originalMethod
  val item = resolveItem(manager, remove, command)
  val callback = resolveCallback(enqueue, manager.type)
  val menu = resolveMenu(manager, enqueue, command)
  val selection = resolveSelection(item.type, item.persistentId)
  val mutation = resolveMutation(manager, remove, displays.primary, item.type)

  return JamQueueAbi(
      manager.type,
      managerConstructor,
      enqueue,
      command,
      executor,
      storage,
      displays,
      callback,
      item,
      remove,
      menu,
      selection,
      mutation,
  )
}

internal fun BytecodePatchContext.resolveProto(type: String?, concept: String): ProtoAbi {
  require(!type.isNullOrBlank() && type.isReferenceType()) {
    "Unable to resolve Jam $concept protobuf type"
  }
  val message = classDefByOrNull(type) ?: error("Unable to resolve Jam $concept protobuf class")
  val defaultInstance =
      protoDefaultInstanceFingerprint(message.type)
          .matchSingle()
          .instructionMatches[0]
          .getFieldAccessed()
  require(
      AccessFlags.STATIC.isSet(defaultInstance.accessFlags) &&
          AccessFlags.PUBLIC.isSet(defaultInstance.accessFlags)
  ) {
    "Jam $concept protobuf default instance must be public and static"
  }
  val parser = protoParserFingerprint(interfaceClosure(message.type)).matchSingle().originalMethod
  return ProtoAbi(type, defaultInstance, parser)
}

private fun BytecodePatchContext.resolveStorage(
    manager: ClassDef,
    enqueue: Method,
): QueueStorageAbi {
  val snapshot = queueStorageFingerprint(manager).matchSingle()
  val storage = snapshot.originalClassDef
  val field =
      manager.fields.filter { it.type == storage.type }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam queue storage reachable from queue manager")
  val items = snapshot.originalMethod
  val lane = queueLaneAccessorFingerprint(storage.type).matchSingle().originalMethod
  val laneClass = classDefBy(lane.returnType)
  val laneMove = queueLaneMoveFingerprint(laneClass.type).matchSingle().originalMethod
  val currentIndex = queueStateAccessorFingerprint(storage.type, false).matchSingle().originalMethod
  val mode = queueStateAccessorFingerprint(storage.type, true).matchSingle().originalMethod
  val localMode = resolveLocalMode(mode)

  val listenerTypes =
      manager.fields.flatMap { field -> classDefByOrNull(field.type)?.interfaces.orEmpty() }.toSet()
  val displayListenerCandidates =
      queueDisplayListenerFingerprint(lane.returnType, listenerTypes).matchAll().map {
        it.originalMethod
      }
  val listenerType =
      displayListenerCandidates.map { it.parameters().single() }.distinct().singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam displayed-queue listener interface")
  val listenerMethods = displayListenerCandidates.filter { it.parameters() == listOf(listenerType) }
  fun listenerMutation(operation: String): List<Method> {
    val mutations =
        queueLaneListenerMutationFingerprint(listenerType, operation)
            .matchAll()
            .filter { match -> implementsType(match.originalClassDef.type, lane.returnType) }
            .map { it.originalMethod }
    return listenerMethods.filter { declaration ->
      mutations.any { implementation ->
        implementation.name == declaration.name &&
            implementation.parameters() == declaration.parameters() &&
            implementation.returnType == declaration.returnType
      }
    }
  }
  val attach =
      listenerMutation("add").singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam queue listener attach operation")
  val detach =
      listenerMutation("remove").singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam queue listener detach operation")

  check(manager.methods.any { it.indexOfFirstInstruction(fieldAccess(reference = field)) >= 0 }) {
    "Unable to confirm Jam queue storage from queue-operation path"
  }
  return QueueStorageAbi(
      field,
      storage.type,
      items,
      lane,
      currentIndex,
      mode,
      localMode,
      lane.returnType,
      laneMove,
      listenerType,
      attach,
      detach,
  )
}

private fun BytecodePatchContext.resolveLocalMode(mode: MethodReference): FieldReference {
  val enum = classDefBy(mode.returnType)
  require(AccessFlags.ENUM.isSet(enum.accessFlags)) { "Jam queue mode is not an enum" }
  return localQueueModeFingerprint(enum.type).matchSingle().instructionMatches[2].getFieldAccessed()
}

private fun BytecodePatchContext.resolveDisplays(
    manager: ClassDef,
    constructor: Method,
    storage: QueueStorageAbi,
): QueueDisplaysAbi {
  val fields =
      manager.fields.filter { field ->
        val display = classDefByOrNull(field.type) ?: return@filter false
        display.fields.any { it.type == storage.type } &&
            display.fields.any { it.type == HANDLER } &&
            display.fields.any { it.type == storage.laneType }
      }
  require(fields.size == 2) {
    "Unable to resolve Jam displayed queue controllers: expected main and autoplay, found ${fields.size}"
  }
  val lanes = displayLaneStores(constructor, fields)
  val primary = resolveDisplay(fields.single { lanes.getValue(it.toString()) == 0 }, storage)
  val autoplay = resolveDisplay(fields.single { lanes.getValue(it.toString()) == 1 }, storage)
  return QueueDisplaysAbi(primary, autoplay)
}

private fun BytecodePatchContext.displayLaneStores(
    constructor: Method,
    fields: List<FieldReference>,
): Map<String, Int> {
  val instructions =
      constructor.instructionsOrNull?.toList()
          ?: error("Unable to inspect Jam displayed queue controller construction")
  return fields.associate { field ->
    val storeIndex =
        constructor
            .findInstructionIndicesReversedOrThrow(
                fieldAccess(reference = field, opcode = Opcode.IPUT_OBJECT)
            )
            .single()
    val store =
        instructions[storeIndex] as? TwoRegisterInstruction
            ?: error("Unable to read Jam displayed queue controller lane store")
    val resultIndex = storeIndex - 1
    val result =
        instructions.getOrNull(resultIndex) as? OneRegisterInstruction
            ?: error("Unable to read Jam displayed queue controller factory result")
    require(
        instructions[resultIndex].opcode == Opcode.MOVE_RESULT_OBJECT &&
            result.registerA == store.registerA
    ) {
      "Unable to link Jam displayed queue controller factory result"
    }
    val factoryCall =
        instructions.getOrNull(resultIndex - 1) as? ReferenceInstruction
            ?: error("Unable to read Jam displayed queue controller factory call")
    val factory =
        factoryCall.getReference<MethodReference>()
            ?: error("Unable to resolve Jam displayed queue controller factory")
    require(factory.parameters() == listOf("I") && factory.returnType == field.type) {
      "Unable to confirm Jam displayed queue controller lane factory"
    }
    val laneRegister =
        factoryCall.argumentRegister(
            AccessFlags.STATIC.isSet(factory.getMutableMethod().accessFlags)
        ) ?: error("Unable to resolve Jam displayed queue controller lane argument")
    val laneIndex =
        constructor.indexOfFirstInstructionReversedOrThrow(resultIndex - 2) {
          this is NarrowLiteralInstruction &&
              this is OneRegisterInstruction &&
              registerA == laneRegister
        }
    val lane = constructor.getInstruction<NarrowLiteralInstruction>(laneIndex).narrowLiteral
    require(lane in 0..1) { "Unexpected Jam displayed queue controller lane $lane" }
    field.toString() to lane
  }
}

private fun ReferenceInstruction.argumentRegister(staticCall: Boolean): Int? =
    registerAt(if (staticCall) 0 else 1)

private fun BytecodePatchContext.resolveDisplay(
    managerField: FieldReference,
    storage: QueueStorageAbi,
): QueueDisplayAbi {
  val display = classDefBy(managerField.type)
  val list =
      display.fields.filter { it.type == storage.laneType }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam displayed queue list field")
  val handler =
      display.fields.filter { it.type == HANDLER }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam displayed queue UI handler")
  val current =
      displayedQueueCurrentFingerprint(display.type, storage.currentIndex)
          .matchSingle()
          .originalMethod
  val refresh = displayedQueueRefreshFingerprint(display.type).matchSingle().originalMethod
  val move =
      displayedQueueMoveFingerprint(display.type, storage.laneMove).matchSingle().originalMethod
  val pending =
      move
          .findInstructionIndicesReversedOrThrow(
              fieldAccess(definingClass = display.type, opcode = Opcode.IPUT_OBJECT)
          )
          .map { move.getInstruction(it).getReference<FieldReference>()!! }
          .singleOrNull() ?: error("Missing or ambiguous Jam pending move field")
  return QueueDisplayAbi(
      managerField,
      display.type,
      list,
      handler,
      refresh,
      current,
      move,
      pending,
  )
}

private fun BytecodePatchContext.resolveItem(
    manager: ClassDef,
    remove: Method,
    command: ProtoAbi,
): QueueItemAbi {
  val itemType = remove.parameters().singleOrNull().requireValue("Jam queue item type")
  val itemClasses = concreteImplementationsOf(itemType)
  require(itemClasses.isNotEmpty()) { "Unable to resolve concrete Jam queue item implementations" }
  val itemTypes = interfaceClosure(itemType)
  val persistentId =
      queuePersistentIdFingerprint(itemTypes, command.type).matchSingle().originalMethod
  val videoId = queueVideoIdFingerprint(itemTypes).matchSingle().originalMethod

  val sharedInterfaces =
      itemClasses.map { interfaceClosure(it.type) }.reduce { shared, next -> shared intersect next }
  val metadata =
      queueArtworkContractFingerprint(sharedInterfaces)
          .matchAll()
          .map { it.originalClassDef }
          .distinctBy { it.type }
          .singleOrNull() ?: error("Missing or ambiguous Jam queue metadata contract")
  val textMethods =
      queueTextAccessorFingerprint(metadata.type).matchAll(2..2).map { it.originalMethod }
  val nowPlayingTextBinding =
      nowPlayingMetadataBindingFingerprint(textMethods).matchSingle().originalMethod
  val title =
      textMethods
          .filter { accessor ->
            nowPlayingTextBinding
                .findInstructionIndicesReversedOrThrow(methodCall(reference = accessor))
                .size >= 2
          }
          .singleOrNull() ?: error("Missing or ambiguous â€“ Jam queue title metadata accessor")
  val artist =
      (textMethods - title).singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam queue artist metadata accessor")
  val artworkCandidates =
      queueArtworkAccessorFingerprint(metadata.type).matchAll().flatMap { match ->
        val method = match.originalMethod
        val artworkClass = classDefByOrNull(method.returnType) ?: return@flatMap emptyList()
        artworkClass.fields
            .filter { field -> field.type == LIST || implementsType(field.type, LIST) }
            .mapNotNull { artworkList ->
              val thumbnailType = resolveThumbnailType(artworkList) ?: return@mapNotNull null
              Triple(method, artworkList, thumbnailType)
            }
      }
  require(artworkCandidates.size == 1) {
    "Unable to resolve Jam queue artwork accessor: expected one candidate, found ${artworkCandidates.size}: " +
        "${artworkCandidates.joinToString()}"
  }
  val artworkCandidate = artworkCandidates.single()
  val artwork = artworkCandidate.first
  val artworkList = artworkCandidate.second
  val thumbnailType = artworkCandidate.third
  val thumbnailUrl =
      classDefBy(thumbnailType).fields.filter { it.type == STRING }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam thumbnail URL field")

  val implementationCandidates =
      itemClasses
          .filter { itemClass -> implementsType(itemClass.type, metadata.type) }
          .map { itemClass -> QueueItemImplementationAbi(itemClass.type) }
  require(implementationCandidates.isNotEmpty()) {
    "Unable to resolve concrete Jam queue item implementations with metadata access"
  }
  val constructor =
      queueItemConstructorFingerprint(
              implementationCandidates.map { it.type }.toSet(),
              manager.fields.map { it.type }.toSet(),
          )
          .matchSingle()
          .originalMethod
  val createItem = QueueItemImplementationAbi(constructor.definingClass) to constructor
  val itemProto = resolveProto(createItem.second.parameters()[1], "queue item")
  val factory =
      manager.fields.filter { it.type == createItem.second.parameters()[2] }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam native queue item factory")

  return QueueItemAbi(
      itemType,
      videoId,
      persistentId,
      metadata.type,
      title,
      artist,
      artwork,
      artworkList,
      thumbnailType,
      thumbnailUrl,
      implementationCandidates,
      createItem.first,
      itemProto,
      factory,
  )
}

private fun BytecodePatchContext.resolveThumbnailType(artworkList: FieldReference): String? {
  val usages =
      thumbnailEntryUsageFingerprint(artworkList).matchAllOrNull().orEmpty().map {
        it.originalMethod
      }
  val inspectedTypes = usages.flatMap(Method::typeReferences).distinct()
  val candidates =
      inspectedTypes.filter { type -> classDefByOrNull(type)?.isThumbnailEntry() == true }
  require(candidates.size <= 1) {
    "Ambiguous Jam thumbnail entry type: found ${candidates.size}: " +
        "${candidates.joinToString()}; inspected ${inspectedTypes.joinToString()}"
  }
  return candidates.singleOrNull()
}

private fun BytecodePatchContext.resolveCallback(
    enqueue: Method,
    managerType: String,
): QueueCallbackAbi {
  val capture = queueCallbackCaptureFingerprint(enqueue, managerType).matchSingle()
  val callback = capture.originalClassDef
  val constructor = capture.originalMethod
  val manager =
      callback.fields.filter { it.type == managerType }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam queue callback manager field")
  val success = queueSuccessCallbackFingerprint(callback.type).matchSingle().originalMethod
  val responseType =
      success
          .typeReferences()
          .filter { type ->
            classDefByOrNull(type)?.fields?.any {
              it.type == LIST || implementsType(it.type, LIST)
            } == true
          }
          .singleOrNull() ?: error("Missing or ambiguous â€“ Jam queue completion response type")
  val responseItems =
      classDefBy(responseType)
          .fields
          .filter { it.type == LIST || implementsType(it.type, LIST) }
          .singleOrNull() ?: error("Missing or ambiguous â€“ Jam queue completion item list")
  val failure = queueFailureCallbackFingerprint(callback.type, success).matchSingle().originalMethod
  return QueueCallbackAbi(
      callback.type,
      constructor,
      manager,
      success,
      failure,
      responseType,
      responseItems,
  )
}

private fun BytecodePatchContext.resolveMenu(
    manager: ClassDef,
    enqueue: Method,
    command: ProtoAbi,
): QueueMenuAbi {
  val dispatch =
      queueMenuDispatcherFingerprint(manager.fields.map { it.type }.toSet(), command.type)
          .matchSingle()
          .originalMethod
  // Class merging can give unrelated manager fields the same type. Use the native enqueue path.
  val dispatcher =
      enqueue
          .findInstructionIndicesReversed(
              fieldAccess(
                  definingClass = manager.type,
                  type = dispatch.definingClass,
                  opcode = Opcode.IGET_OBJECT,
              )
          )
          .map { enqueue.getInstruction(it).getReference<FieldReference>()!! }
          .distinct()
          .singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam native menu dispatcher field")
  val dispatcherClass = classDefBy(dispatcher.type)
  val mappers =
      queueResponseMapperFingerprint(
              dispatch.typeReferences().toSet(),
              dispatcherClass.fields.map { it.type }.toSet(),
          )
          .matchAll()
          .map { it.originalMethod }
  val response =
      queueMenuResponseConstructorFingerprint(mappers.flatMap(Method::typeReferences).toSet())
          .matchAll()
          .map { it.originalClassDef }
          .distinctBy { it.type }
          .singleOrNull() ?: error("Missing or ambiguous Jam menu response")
  val items =
      response.fields.filter { it.type == LIST || implementsType(it.type, LIST) }.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam native menu response items")
  return QueueMenuAbi(dispatcher, dispatch, response.type, items)
}

private fun BytecodePatchContext.resolveSelection(
    itemType: String,
    persistentId: MethodReference,
): MethodReference =
    queueItemSelectionFingerprint(itemType, persistentId).matchSingle().originalMethod

private fun BytecodePatchContext.resolveMutation(
    manager: ClassDef,
    remove: Method,
    display: QueueDisplayAbi,
    itemType: String,
): QueueMutationAbi {
  val removalCalls =
      anyInstruction(
          *interfaceClosure(itemType)
              .map { methodCall(parameters = listOf(it, "Z"), returnType = "V") }
              .toTypedArray()
      )
  val removalNotifier =
      remove
          .findInstructionIndicesReversedOrThrow(removalCalls)
          .map { remove.getInstruction(it).getReference<MethodReference>()!! }
          .distinct()
          .singleOrNull() ?: error("Missing or ambiguous â€“ Jam native queue removal notifier")
  val commitMove = display.commitMove.getMutableMethod()
  val move =
      queueMoveNotifierFingerprint(removalNotifier.definingClass, commitMove)
          .matchSingle()
          .originalMethod
  val instructions =
      remove.instructionsOrNull?.toList() ?: error("Unable to inspect Jam queue removal operation")
  val notifierIndices =
      remove.findInstructionIndicesReversedOrThrow(methodCall(reference = removalNotifier))
  val providers =
      notifierIndices
          .map { index ->
            resolveMutationProvider(manager, remove, instructions, index, removalNotifier)
          }
          .distinctBy { (provider, method) ->
            "${provider.definingClass}->${provider.name}:${provider.type}:${method.toString()}"
          }
  val (provider, providerMethod) =
      providers.singleOrNull()
          ?: error("Missing or ambiguous â€“ Jam native queue mutation provider")
  return QueueMutationAbi(provider, providerMethod, removalNotifier.definingClass, move)
}

private fun resolveMutationProvider(
    manager: ClassDef,
    remove: Method,
    instructions: List<Instruction>,
    notifierIndex: Int,
    notifier: MethodReference,
): Pair<FieldReference, MethodReference> {
  val notifierInstruction =
      instructions[notifierIndex] as? ReferenceInstruction
          ?: error("Unable to inspect Jam native queue removal notifier invocation")
  val notifierReceiver =
      notifierInstruction.registerAt(0)
          ?: error("Unable to resolve Jam native queue removal notifier receiver")
  val castIndex =
      remove.indexOfFirstInstructionReversedOrThrow(notifierIndex - 1) {
        opcode == Opcode.CHECK_CAST &&
            (this as? OneRegisterInstruction)?.registerA == notifierReceiver &&
            getReference<TypeReference>()?.type == notifier.definingClass
      }
  val resultIndex = castIndex - 1
  require(resultIndex >= 1) { "Unable to resolve Jam native queue mutation provider result" }
  val result = instructions[resultIndex] as? OneRegisterInstruction
  require(
      instructions[resultIndex].opcode == Opcode.MOVE_RESULT_OBJECT &&
          result?.registerA == notifierReceiver
  ) {
    "Unable to resolve Jam native queue mutation provider result"
  }
  val callIndex = resultIndex - 1
  val providerCallInstruction =
      instructions[callIndex] as? ReferenceInstruction
          ?: error("Unable to inspect Jam native queue mutation provider call")
  val providerMethod =
      providerCallInstruction.getReference<MethodReference>()?.takeIf {
        it.parameters().isEmpty() && it.returnType == OBJECT
      } ?: error("Unable to resolve Jam native queue mutation provider method")
  val providerReceiver =
      providerCallInstruction.registerAt(0)
          ?: error("Unable to resolve Jam native queue mutation provider receiver")
  val loadIndex = callIndex - 1
  val load = instructions[loadIndex] as? TwoRegisterInstruction
  val provider =
      instructions[loadIndex].getReference<FieldReference>()?.takeIf {
        instructions[loadIndex].opcode == Opcode.IGET_OBJECT &&
            load?.registerA == providerReceiver &&
            it.definingClass == manager.type &&
            it.type == providerMethod.definingClass
      } ?: error("Unable to resolve Jam native queue mutation provider field")
  return provider to providerMethod
}

internal fun BytecodePatchContext.concreteImplementationsOf(type: String): List<ClassDef> =
    nativeImplementationFingerprint(type)
        .matchAllOrNull()
        .orEmpty()
        .map { it.originalClassDef }
        .distinctBy { it.type }

internal fun BytecodePatchContext.interfaceClosure(type: String): Set<String> {
  val visited = mutableSetOf<String>()
  fun visit(candidate: String) {
    if (!visited.add(candidate)) return
    val classDef = classDefByOrNull(candidate) ?: return
    classDef.interfaces.forEach(::visit)
    classDef.superclass?.let(::visit)
  }
  visit(type)
  return visited
}

internal fun BytecodePatchContext.implementsType(type: String, parent: String): Boolean =
    parent in interfaceClosure(type)

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Method.typeReferences(): List<String> =
    findInstructionIndicesReversed(
            anyInstruction(checkCast("L"), newInstance("L"), instanceOf("L"))
        )
        .map { getInstruction(it).getReference<TypeReference>()!!.type }
        .distinct()

private fun ReferenceInstruction.registerAt(index: Int): Int? =
    when (this) {
      is FiveRegisterInstruction ->
          listOf(registerC, registerD, registerE, registerF, registerG)
              .take(registerCount)
              .getOrNull(index)
      is RegisterRangeInstruction -> (startRegister + index).takeIf { index < registerCount }
      else -> null
    }

private fun String.isReferenceType(): Boolean = startsWith("L") || startsWith("[")

private fun ClassDef.isThumbnailEntry(): Boolean {
  val instanceFields = fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
  return instanceFields.count { it.type == STRING } == 1 &&
      instanceFields.count { it.type == "I" } >= 2 &&
      instanceFields.all { it.type == STRING || it.type == "I" }
}

private fun <T> T?.requireValue(concept: String): T = this ?: error("Unable to resolve $concept")
