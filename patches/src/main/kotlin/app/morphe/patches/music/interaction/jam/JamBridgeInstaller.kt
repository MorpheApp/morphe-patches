/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.util.cloneMutable
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getMutableMethod
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val BRIDGE = "Lapp/morphe/extension/music/jam/YtmBridge;"
private const val ACCESS = "Lapp/morphe/extension/music/jam/YtmBridge\$QueueAccess;"
private const val ITEM_ACCESS = "Lapp/morphe/extension/music/jam/YtmBridge\$ItemAccess;"
private const val ARTWORK_ACCESS = "Lapp/morphe/extension/music/jam/YtmBridge\$ArtworkAccess;"
private const val THUMBNAIL_ACCESS = "Lapp/morphe/extension/music/jam/YtmBridge\$ThumbnailAccess;"
private const val NATIVE_LIST = "Lapp/morphe/extension/music/jam/NativeQueueList;"
private const val JAM_MIRROR = "Lapp/morphe/extension/music/jam/JamMirror;"
private const val JAM_COMPLETION = "Lapp/morphe/extension/music/jam/JamCompletion;"
private const val JAM_UI = "Lapp/morphe/extension/music/jam/JamUi;"

/** Installs the queue-facing native bridges selected by [resolveJamQueueAbi]. */
internal fun BytecodePatchContext.installJamQueueBridges(queue: JamQueueAbi) {
  installQueueAccess(queue)
  installQueueCallback(queue)
  installNativeQueueListAdapter(queue.storage)
  installQueueItemAccess(queue.item)
  installQueueManagerCapture(queue)
}

private fun BytecodePatchContext.installQueueAccess(queue: JamQueueAbi) {
  val manager = mutableClassDefBy(queue.managerType)
  manager.interfaces.add(ACCESS)
  val enqueue = queue.enqueue.getMutableMethod()
  val localEnqueue = enqueue.name
  enqueue.setName("patch_jamLocalEnqueue")
  manager.addBridge(
      localEnqueue,
      listOf(queue.command.type),
      "V",
      4,
      enqueue.accessFlags,
      """
            invoke-virtual {p1}, ${queue.command.type}->toByteArray()[B
            move-result-object v0
            invoke-static {p0, v0}, $JAM_UI->offer($ACCESS[B)Z
            move-result v0
            if-eqz v0, :local
            return-void
            :local
            ${invokeKind(queue.enqueue)} {p0, p1}, $enqueue
            return-void
        """,
  )
  manager.addBridge(
      "patch_jamEnqueue",
      listOf("[B"),
      "V",
      4,
      body =
          queue.command.decode("p1", "v0") +
              """
            ${invokeKind(queue.enqueue)} {p0, v0}, $enqueue
            return-void
        """,
  )
  manager.addReferenceGetter("patch_jamExecutor", queue.executor)
  installQueueSnapshots(manager, queue)
  installQueueItemMetadata(manager, queue)
  installQueueCreation(manager, queue)
  installQueueDisplayAccess(manager, queue.displays, queue.storage)
  installQueueOperations(manager, queue)
  installQueueMenu(manager, queue)
  installQueueSelection(queue)
}

private fun BytecodePatchContext.installQueueSnapshots(manager: MutableClass, queue: JamQueueAbi) {
  manager.addBridge(
      "patch_jamLaneItems",
      listOf("I"),
      "[Ljava/lang/Object;",
      3,
      body =
          """
            iget-object v0, p0, ${queue.storage.field}
            ${invokeKind(queue.storage.items)} {v0, p1}, ${queue.storage.items}
            move-result-object v0
            invoke-interface {v0}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """,
  )
  installNativeAccessor(
      manager,
      "patch_jamCurrent",
      queue.storage.currentIndex,
      receiverField = queue.storage.field,
  )
  manager.addBridge(
      "patch_jamLocal",
      emptyList(),
      "Z",
      3,
      body =
          """
            iget-object v0, p0, ${queue.storage.field}
            ${invokeKind(queue.storage.mode)} {v0}, ${queue.storage.mode}
            move-result-object v0
            sget-object v1, ${queue.storage.localMode}
            if-ne v0, v1, :remote
            const/4 v0, 0x1
            return v0
            :remote
            const/4 v0, 0x0
            return v0
        """,
  )
}

private fun BytecodePatchContext.installQueueItemMetadata(
    manager: MutableClass,
    queue: JamQueueAbi,
) {
  val item = queue.item
  installNativeAccessor(manager, "patch_jamVideoId", item.videoId, opaqueReceiver = true)
  installNativeAccessor(manager, "patch_jamItemId", item.persistentId, opaqueReceiver = true)
}

private fun BytecodePatchContext.installQueueCreation(manager: MutableClass, queue: JamQueueAbi) {
  val item = queue.item
  manager.addBridge(
      "patch_jamCreateItem",
      listOf("[B", "J"),
      "Ljava/lang/Object;",
      8,
      body =
          item.itemProto.decode("p1", "v1") +
              """
            iget-object v3, p0, ${item.factory}
            new-instance v0, ${item.createItem.type}
            invoke-direct {v0, p2, p3, v1, v3}, ${item.createItem.type}-><init>(J${item.itemProto.type}${item.factory.type})V
            return-object v0
        """,
  )
}

private fun BytecodePatchContext.installQueueDisplayAccess(
    manager: MutableClass,
    displays: QueueDisplaysAbi,
    storage: QueueStorageAbi,
) {
  installDisplayAccess(manager, displays.primary, storage, "DisplayedList", "Display")
  installDisplayAccess(manager, displays.autoplay, storage, "DisplayedAutoplay", "Autoplay")
  listOf(displays.primary, displays.autoplay)
      .distinctBy { it.type }
      .forEach { display -> installDisplayInterception(display) }
}

private fun BytecodePatchContext.installDisplayAccess(
    manager: MutableClass,
    display: QueueDisplayAbi,
    storage: QueueStorageAbi,
    property: String,
    refresh: String,
) {
  manager.addBridge(
      "patch_jam$property",
      emptyList(),
      "Ljava/lang/Object;",
      2,
      body =
          """
            iget-object v0, p0, ${display.managerField}
            iget-object v0, v0, ${display.list}
            return-object v0
        """,
  )
  manager.addBridge(
      "patch_jam$property",
      listOf("Ljava/lang/Object;"),
      "V",
      4,
      body =
          """
            iget-object v0, p0, ${display.managerField}
            iget-object v1, v0, ${display.list}
            if-eqz v1, :set
            ${invokeKind(storage.detachListener)} {v1, v0}, ${storage.detachListener}
            :set
            check-cast p1, ${storage.laneType}
            iput-object p1, v0, ${display.list}
            ${invokeKind(storage.attachListener)} {p1, v0}, ${storage.attachListener}
            ${invokeKind(display.refresh)} {v0}, ${display.refresh}
            return-void
        """,
  )
  manager.addBridge(
      "patch_jamRefresh$refresh",
      emptyList(),
      "V",
      2,
      body =
          """
            iget-object v0, p0, ${display.managerField}
            ${invokeKind(display.refresh)} {v0}, ${display.refresh}
            return-void
        """,
  )
  if (property == "DisplayedList") {
    manager.addBridge(
        "patch_jamViewThread",
        listOf("Ljava/lang/Runnable;"),
        "V",
        3,
        body =
            """
                iget-object v0, p0, ${display.managerField}
                iget-object v0, v0, ${display.handler}
                invoke-virtual {v0, p1}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z
                return-void
            """,
    )
  }
}

private fun BytecodePatchContext.installDisplayInterception(display: QueueDisplayAbi) {
  val controller = mutableClassDefBy(display.type)
  val current = display.currentIndex.getMutableMethod()
  val currentName = current.name
  current.setName("patch_jamLocalCurrent")
  controller.addBridge(
      currentName,
      emptyList(),
      "I",
      3,
      current.accessFlags,
      """
            iget-object v0, p0, ${display.list}
            invoke-static {v0}, $JAM_MIRROR->current(Ljava/lang/Object;)I
            move-result v0
            const/4 v1, -0x2
            if-ne v0, v1, :done
            ${invokeKind(display.currentIndex)} {p0}, $current
            move-result v0
            :done
            return v0
        """,
  )
  val move = display.commitMove.getMutableMethod()
  val moveName = move.name
  move.setName("patch_jamLocalMove")
  controller.addBridge(
      moveName,
      listOf("I", "I"),
      "V",
      5,
      move.accessFlags,
      """
            iget-object v0, p0, ${display.list}
            invoke-static {v0, p1, p2}, $JAM_MIRROR->move(Ljava/lang/Object;II)Z
            move-result v0
            if-eqz v0, :local
            const/4 v0, 0x0
            iput-object v0, p0, ${display.pendingMove}
            return-void
            :local
            ${invokeKind(display.commitMove)} {p0, p1, p2}, $move
            return-void
        """,
  )
}

private fun BytecodePatchContext.installQueueOperations(manager: MutableClass, queue: JamQueueAbi) {
  val remove = queue.remove.getMutableMethod()
  val removeName = remove.name
  remove.setName("patch_jamLocalRemove")
  manager.addBridge(
      removeName,
      listOf(queue.item.type),
      "V",
      3,
      remove.accessFlags,
      """
            invoke-static {p1}, $JAM_MIRROR->remove(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :done
            ${invokeKind(queue.remove)} {p0, p1}, $remove
            :done
            return-void
        """,
  )
  manager.addBridge(
      "patch_jamRemoveItem",
      listOf("Ljava/lang/Object;"),
      "V",
      2,
      body =
          """
            check-cast p1, ${queue.item.type}
            ${invokeKind(queue.remove)} {p0, p1}, $remove
            return-void
        """,
  )
  manager.addBridge(
      "patch_jamMoveLane",
      listOf("I", "I", "I"),
      "V",
      5,
      body =
          """
            iget-object v0, p0, ${queue.storage.field}
            ${invokeKind(queue.storage.lane)} {v0, p1}, ${queue.storage.lane}
            move-result-object v0
            ${invokeKind(queue.storage.laneMove)} {v0, p2, p3}, ${queue.storage.laneMove}
            return-void
        """,
  )
  val comparable = queue.mutation.move.parameters().first()
  manager.addBridge(
      "patch_jamNotifyMove",
      listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
      "V",
      4,
      body =
          """
            check-cast p1, $comparable
            check-cast p2, $comparable
            iget-object v0, p0, ${queue.mutation.provider}
            ${invokeKind(queue.mutation.providerMethod)} {v0}, ${queue.mutation.providerMethod}
            move-result-object v0
            check-cast v0, ${queue.mutation.notifierType}
            ${invokeKind(queue.mutation.move)} {v0, p1, p2}, ${queue.mutation.move}
            return-void
        """,
  )
}

private fun BytecodePatchContext.installQueueMenu(manager: MutableClass, queue: JamQueueAbi) {
  manager.addBridge(
      "patch_jamRequestMenu",
      listOf("[B"),
      "Ljava/util/concurrent/Future;",
      5,
      body =
          queue.command.decode("p1", "v0") +
              """
            iget-object v1, p0, ${queue.menu.dispatcher}
            invoke-virtual {p0}, ${manager.type}->patch_jamExecutor()Ljava/util/concurrent/Executor;
            move-result-object v2
            ${invokeKind(queue.menu.dispatch)} {v1, v0, v2}, ${queue.menu.dispatch}
            move-result-object v0
            return-object v0
        """,
  )
  manager.addBridge(
      "patch_jamMenuItems",
      listOf("Ljava/lang/Object;"),
      "[Ljava/lang/Object;",
      2,
      body =
          """
            check-cast p1, ${queue.menu.responseType}
            iget-object p1, p1, ${queue.menu.responseItems}
            invoke-interface {p1}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object p1
            return-object p1
        """,
  )
}

private fun BytecodePatchContext.installQueueSelection(queue: JamQueueAbi) {
  val owner = mutableClassDefBy(queue.selection.definingClass)
  val local = queue.selection.getMutableMethod()
  val name = local.name
  local.setName("patch_jamLocalSelection")
  owner.addBridge(
      name,
      listOf(queue.item.type, "Z"),
      "Z",
      5,
      local.accessFlags,
      """
            invoke-static {p1}, $JAM_MIRROR->selection(Ljava/lang/Object;)I
            move-result v0
            if-gez v0, :done
            ${invokeKind(queue.selection)} {p0, p1, p2}, $local
            move-result v0
            :done
            return v0
        """,
  )
}

private fun BytecodePatchContext.installQueueCallback(queue: JamQueueAbi) {
  val callback = mutableClassDefBy(queue.callback.type)
  val constructor = queue.callback.constructor.getMutableMethod()
  constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
    constructor.addInstructions(
        index,
        "invoke-static {p0}, $JAM_COMPLETION->attach(Ljava/lang/Object;)V",
    )
  }
  installCallbackBridge(callback, queue.callback.success, queue, true)
  installCallbackBridge(callback, queue.callback.failure, queue, false)
}

private fun BytecodePatchContext.installCallbackBridge(
    callback: MutableClass,
    reference: MethodReference,
    queue: JamQueueAbi,
    succeeds: Boolean,
) {
  val native = reference.getMutableMethod()
  val name = native.name
  native.setName(if (succeeds) "patch_jamLocalSuccess" else "patch_jamLocalFailure")
  val completion =
      if (succeeds)
          """
        check-cast p1, ${queue.callback.responseType}
        iget-object v0, p1, ${queue.callback.responseItems}
        iget-object v1, p0, ${queue.callback.manager}
        invoke-static {p0, v0, v1}, $JAM_COMPLETION->succeeded(Ljava/lang/Object;Ljava/util/List;$ACCESS)V
    """
      else
          """
        invoke-static {p0}, $JAM_COMPLETION->failed(Ljava/lang/Object;)V
    """
  callback.addBridge(
      name,
      reference.parameters(),
      "V",
      4,
      native.accessFlags,
      """
            ${invokeKind(reference)} {p0, p1}, $native
            $completion
            return-void
        """,
  )
}

private fun BytecodePatchContext.installNativeQueueListAdapter(storage: QueueStorageAbi) {
  val nativeList = mutableClassDefBy(NATIVE_LIST)
  nativeList.interfaces.add(storage.laneType)
  nativeList.addBridge(
      storage.laneMove.name,
      listOf("I", "I"),
      "V",
      3,
      body =
          """
            invoke-virtual {p0, p1, p2}, $NATIVE_LIST->move(II)V
            return-void
        """,
  )
  nativeList.addBridge(
      storage.attachListener.name,
      listOf(storage.listenerType),
      "V",
      2,
      body =
          """
            invoke-virtual {p0, p1}, $NATIVE_LIST->addListener(Ljava/lang/Object;)V
            return-void
        """,
  )
  nativeList.addBridge(
      storage.detachListener.name,
      listOf(storage.listenerType),
      "V",
      2,
      body =
          """
            invoke-virtual {p0, p1}, $NATIVE_LIST->removeListener(Ljava/lang/Object;)V
            return-void
        """,
  )
}

private fun BytecodePatchContext.installQueueItemAccess(item: QueueItemAbi) {
  val menuPayload =
      requireNotNull(item.menuPayload) { "Unable to install Jam queue-item menu payload bridge" }
  val metadataAccess = "Lapp/morphe/extension/music/jam/YtmBridge\$MetadataAccess;"
  concreteImplementationsOf(item.metadataType).forEach { implementation ->
    val metadata = mutableClassDefBy(implementation.type)
    metadata.interfaces.add(metadataAccess)
    listOf("patch_jamTitle" to item.title, "patch_jamArtist" to item.artist).forEach {
        (name, accessor) ->
      installNativeAccessor(metadata, name, accessor)
    }
  }
  item.implementations.forEach { implementation ->
    val nativeItem = mutableClassDefBy(implementation.type)
    check(implementsType(nativeItem.type, menuPayload.type)) {
      "Jam queue item ${nativeItem.type} cannot provide the resolved menu payload"
    }
    nativeItem.interfaces.add(ITEM_ACCESS)
    installNativeAccessor(
        nativeItem,
        "patch_jamArtwork",
        item.artwork,
        resultType = "Ljava/lang/Object;",
    )
    nativeItem.addBridge(
        "patch_jamMenuPayload",
        emptyList(),
        "Ljava/lang/Object;",
        1,
        body =
            """
                check-cast p0, ${menuPayload.type}
                return-object p0
            """,
    )
  }
  val artwork = mutableClassDefBy(item.artwork.returnType)
  artwork.interfaces.add(ARTWORK_ACCESS)
  artwork.addBridge(
      "patch_jamThumbnailEntries",
      emptyList(),
      "[Ljava/lang/Object;",
      2,
      body =
          """
            iget-object v0, p0, ${item.artworkList}
            invoke-interface {v0}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """,
  )
  val thumbnail = mutableClassDefBy(item.thumbnailType)
  thumbnail.interfaces.add(THUMBNAIL_ACCESS)
  thumbnail.addReferenceGetter("patch_jamThumbnailUrl", item.thumbnailUrl)
}

private fun BytecodePatchContext.installQueueManagerCapture(queue: JamQueueAbi) {
  val original = queue.constructor.getMutableMethod()
  val preservedThisRegister = requireNotNull(original.implementation).registerCount
  val constructor =
      original.cloneMutable(additionalRegisters = original.numberOfParameterRegisters + 1)
  mutableClassDefBy(original.definingClass).methods.apply {
    remove(original)
    add(constructor)
  }
  constructor.addInstructions(0, "move-object/from16 v$preservedThisRegister, p0")
  constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
    constructor.addInstructions(
        index,
        "invoke-static/range {v$preservedThisRegister .. v$preservedThisRegister}, $BRIDGE->capture($ACCESS)V",
    )
  }
}

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }
