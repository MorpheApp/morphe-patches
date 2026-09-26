/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getMutableMethod
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CLOCK = "Lapp/morphe/extension/music/jam/JamClock;"
private const val OBJECT = "Ljava/lang/Object;"
private const val CLOCK_BAR = "Lapp/morphe/extension/music/jam/JamClock\$Bar;"
private const val PALETTE = "Lapp/morphe/extension/music/jam/JamPalette;"
private const val PALETTE_SOURCE = "Lapp/morphe/extension/music/jam/JamPalette\$Source;"
private const val PLAYBACK = "Lapp/morphe/extension/music/jam/JamPlayback;"
private const val ROUTER_ACCESS = "Lapp/morphe/extension/music/jam/JamPlayback\$Router;"
private const val NOW_ACCESS = "Lapp/morphe/extension/music/jam/JamPlayback\$NowUi;"
private const val PLAYER_ICON = "Lapp/morphe/extension/music/jam/JamPlayerState\$Icon;"
private const val PLAYER_STATE = "Lapp/morphe/extension/music/jam/JamPlayerState;"
private const val MENU_ROW = "Lapp/morphe/extension/music/jam/JamMenu\$Row;"
private const val ITEM_ACCESS = "Lapp/morphe/extension/music/jam/YtmBridge\$ItemAccess;"

/** Installs presentation bridges after [JamUiAbi] has resolved each native relationship. */
internal fun BytecodePatchContext.installJamUiBridges(ui: JamUiAbi, queue: JamQueueAbi) {
  installClock(ui.clock)
  installAutoplayUi(ui.autoplay)
  installPalette(ui.palette)
  installPlayback(ui.playback)
  installCurrentItem(ui.currentItem)
  installNowPlaying(ui.nowPlaying, queue.item)
  installArtwork(ui.artwork)
  installQueueRow(ui.queueRow, queue.item)
  ui.buttons.forEach(::installButton)
  installPlaybackIcon(ui.playbackIcon)
}

private fun BytecodePatchContext.installPlaybackIcon(icon: PlaybackIconAbi) {
  val owner = mutableClassDefBy(icon.render.definingClass)
  val render = icon.render.getMutableMethod()
  val name = render.name
  val modelType = icon.constructor.definingClass
  render.setName("patch_jamLocalIcon")
  owner.interfaces.add(PLAYER_ICON)
  owner.addBridge(
      name,
      listOf(modelType),
      "V",
      2,
      render.accessFlags,
      """
      invoke-static {p0, p1}, $PLAYER_STATE->model($PLAYER_ICON${OBJECT})${OBJECT}
      move-result-object p1
      check-cast p1, $modelType
      ${invokeKind(icon.render)} {p0, p1}, $render
      return-void
      """,
  )
  owner.addBridge(
      "patch_jamView",
      emptyList(),
      "Landroid/view/View;",
      2,
      body = """
      iget-object v0, p0, ${icon.view}
      return-object v0
      """,
  )
  owner.addBridge(
      "patch_jamState",
      listOf("Z"),
      OBJECT,
      5,
      body = """
      if-eqz p1, :paused
      sget-object v1, ${icon.playing}
      goto :create
      :paused
      sget-object v1, ${icon.paused}
      :create
      new-instance v0, $modelType
      const/4 v2, 0x0
      invoke-direct {v0, v1, v2}, ${icon.constructor}
      return-object v0
      """,
  )
  owner.addBridge(
      "patch_jamRender",
      listOf(OBJECT),
      "V",
      2,
      body = """
      check-cast p1, $modelType
      ${invokeKind(icon.render)} {p0, p1}, $render
      return-void
      """,
  )
}

private fun BytecodePatchContext.installClock(clock: ClockAbi) {
  val mediaState = clock.mediaState.getMutableMethod()
  val stateCall =
      mediaState.indexOfFirstInstructionOrThrow(
          methodCall(
              "Landroid/media/session/MediaSession;->setPlaybackState(Landroid/media/session/PlaybackState;)V"
          )
      )
  val receiver =
      when (val instruction = mediaState.instructions[stateCall]) {
        is FiveRegisterInstruction -> instruction.registerC
        is RegisterRangeInstruction -> instruction.startRegister
        else -> error("Unsupported Jam MediaSession state invocation")
      }
  mediaState.addInstructions(
      stateCall + 1,
      "invoke-static/range {v$receiver .. v$receiver}, $CLOCK->capture(Landroid/media/session/MediaSession;)V",
  )

  val timeBase = mutableClassDefBy(clock.timeBaseType)
  val setModel = clock.setModel.getMutableMethod()
  val setModelName = setModel.name
  setModel.setName("patch_jamOriginalModel")
  timeBase.addBridge(
      setModelName,
      listOf(clock.modelType),
      "V",
      2,
      setModel.accessFlags,
      """
            invoke-static {p0, p1}, $CLOCK->model(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object p1
            check-cast p1, ${clock.modelType}
            ${invokeKind(clock.setModel)} {p0, p1}, $setModel
            return-void
        """,
  )

  val timeBar = mutableClassDefBy(clock.timeBarType)
  timeBar.interfaces.add(CLOCK_BAR)
  timeBar.addBridge(
      "patch_jamCreateModel",
      listOf("J", "J", "I", "Z"),
      "Ljava/lang/Object;",
      8,
      body =
          """
            new-instance v0, ${clock.concreteModelType}
            invoke-direct {v0}, ${clock.concreteModelType}-><init>()V
            iput-wide p1, v0, ${clock.position}
            iput-wide p3, v0, ${clock.duration}
            iput-wide p1, v0, ${clock.trailingPosition}
            iput p5, v0, ${clock.overrideColors[0]}
            iput p5, v0, ${clock.overrideColors[1]}
            iput-boolean p6, v0, ${clock.active}
            return-object v0
        """,
  )
  timeBar.addBridge(
      "patch_jamRestore",
      listOf("Ljava/lang/Object;"),
      "V",
      2,
      body =
          """
            check-cast p1, ${clock.modelType}
            ${invokeKind(clock.setModel)} {p0, p1}, $setModel
            invoke-virtual {p0}, Landroid/view/View;->invalidate()V
            return-void
        """,
  )
  installNativeAccessor(timeBar, "patch_jamDragging", clock.dragging)

  val seekOwner = mutableClassDefBy(clock.seek.definingClass)
  val seek = clock.seek.getMutableMethod()
  val seekName = seek.name
  seek.setName("patch_jamLocalSeek")
  seekOwner.addBridge(
      seekName,
      seek.parameters(),
      "V",
      5,
      seek.accessFlags,
      """
            invoke-static {p1, p2}, $CLOCK->offerSeek(J)Z
            move-result v0
            if-nez v0, :done
            ${invokeKind(clock.seek)} {p0, p1, p2, p3}, $seek
            :done
            return-void
        """,
  )
}

private fun BytecodePatchContext.installPalette(palette: PaletteAbi) {
  val source = mutableClassDefBy(palette.type)
  source.interfaces.add(PALETTE_SOURCE)
  val local = palette.entry.getMutableMethod()
  val name = local.name
  local.setName("patch_jamRestorePalette")
  source.addBridge(
      name,
      emptyList(),
      "V",
      2,
      local.accessFlags,
      """
            invoke-static {p0}, $PALETTE->intercept($PALETTE_SOURCE)Z
            move-result v0
            if-nez v0, :done
            ${invokeKind(palette.entry)} {p0}, $local
            :done
            return-void
        """,
  )
  source.addBridge(
      "patch_jamExtract",
      listOf("Landroid/graphics/Bitmap;"),
      "Ljava/lang/Object;",
      3,
      body =
          """
            iget-object v0, p0, ${palette.extractor}
            ${invokeKind(palette.extract)} {v0, p1}, ${palette.extract}
            move-result-object v0
            return-object v0
        """,
  )
  source.addBridge(
      "patch_jamPublish",
      listOf("Ljava/lang/Object;"),
      "V",
      3,
      body =
          """
            check-cast p1, ${palette.extract.returnType}
            iget-object v0, p0, ${palette.publisher}
            ${invokeKind(palette.publish)} {v0, p1}, ${palette.publish}
            ${invokeKind(palette.localPublish)} {p0, p1}, ${palette.localPublish}
            return-void
        """,
  )
}

private fun BytecodePatchContext.installPlayback(playback: PlaybackAbi) {
  playback.routers.forEach { router ->
    val owner = mutableClassDefBy(router.type)
    owner.interfaces.add(ROUTER_ACCESS)
    val dispatch = router.dispatch.getMutableMethod()
    val dispatchName = dispatch.name
    dispatch.setName("patch_jamLocalDispatch")
    owner.addBridge(
        dispatchName,
        dispatch.parameters(),
        "V",
        5,
        dispatch.accessFlags,
        """
                invoke-static/range {p0 .. p0}, $PLAYBACK->capture($ROUTER_ACCESS)V
                invoke-virtual {p1}, ${playback.command.type}->toByteArray()[B
                move-result-object v0
                invoke-static {p0, p1, p2, v0}, $PLAYBACK->offer(${ROUTER_ACCESS}Ljava/lang/Object;Ljava/lang/Object;[B)Z
                move-result v0
                if-eqz v0, :local
                return-void
                :local
                ${invokeKind(router.dispatch)} {p0, p1, p2}, $dispatch
                return-void
            """,
    )
    owner.addBridge(
        "patch_jamDispatch",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "V",
        3,
        accessFlags = AccessFlags.PUBLIC.value,
        body =
            """
                check-cast p1, ${playback.command.type}
                check-cast p2, Ljava/util/Map;
                ${invokeKind(router.dispatch)} {p0, p1, p2}, $dispatch
                return-void
            """,
    )
    owner.addBridge(
        "patch_jamWatch",
        listOf("[B"),
        "V",
        4,
        accessFlags = AccessFlags.PUBLIC.value,
        body =
            playback.command.decode("p1", "v0") +
                """
                invoke-static {}, Ljava/util/Collections;->emptyMap()Ljava/util/Map;
                move-result-object v1
                ${invokeKind(router.dispatch)} {p0, v0, v1}, $dispatch
                return-void
            """,
    )
  }
}

private fun BytecodePatchContext.installCurrentItem(current: CurrentItemAbi) {
  val owner = mutableClassDefBy(current.type)
  val local = current.accessor.getMutableMethod()
  val name = local.name
  local.setName("patch_jamLocalItem")
  owner.addBridge(
      name,
      emptyList(),
      "Lj$/util/Optional;",
      2,
      local.accessFlags,
      """
            invoke-static {}, Lapp/morphe/extension/music/jam/JamMirror;->now()Ljava/lang/Object;
            move-result-object v0
            if-eqz v0, :local
            invoke-static {v0}, Lj$/util/Optional;->of(Ljava/lang/Object;)Lj$/util/Optional;
            move-result-object v0
            return-object v0
            :local
            ${invokeKind(current.accessor)} {p0}, $local
            move-result-object v0
            return-object v0
        """,
  )
}

private fun BytecodePatchContext.installNowPlaying(now: NowPlayingAbi, item: QueueItemAbi) {
  val presenter = mutableClassDefBy(now.presenter.type)
  presenter.interfaces.add(NOW_ACCESS)
  val entry = now.presenter.entry.getMutableMethod()
  entry.addInstructions(0, "invoke-static/range {p0 .. p0}, $PLAYBACK->observe($NOW_ACCESS)V")
  presenter.addBridge(
      "patch_jamRefreshNow",
      emptyList(),
      "V",
      2,
      body =
          """
            const/4 v0, 0x0
            ${invokeKind(now.presenter.entry)} {p0, v0}, $entry
            return-void
        """,
  )
  // Intercept the actual player/mini-player writes, including asynchronous
  // native updates. Text selection, caching and restoration live in Java.
  nowPlayingTextWritesFingerprint(now.presenter.type).matchAllMethodIndicesForEach { index ->
    val call = instructions[index] as ReferenceInstruction
    val receiver = call.invokeRegister(0) ?: error("Missing player text receiver")
    val text = call.invokeRegister(1) ?: error("Missing player text argument")
    val target =
        "Lapp/morphe/extension/music/jam/JamMetadata;->setText(Landroid/widget/TextView;Ljava/lang/CharSequence;)V"
    replaceInstruction(
        index,
        if (call is RegisterRangeInstruction) {
          "invoke-static/range {v$receiver .. v$text}, $target"
        } else {
          "invoke-static {v$receiver, v$text}, $target"
        },
    )
  }
  now.queueBindings.forEach { binding ->
    val owner = mutableClassDefBy(binding.type)
    owner.interfaces.add(NOW_ACCESS)
    val bindingMethod = binding.entry.getMutableMethod()
    val instructions = bindingMethod.instructions.toList()
    bindingMethod
        .findInstructionIndicesReversedOrThrow(methodCall(reference = binding.itemLookup))
        .forEach { index ->
          val result =
              instructions.getOrNull(index + 1) as? OneRegisterInstruction
                  ?: error("Unable to resolve Jam queue item lookup result register")
          require(instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "Unable to resolve Jam queue item lookup result"
          }
          val register = result.registerA
          bindingMethod.addInstructions(
              index + 2,
              """
                invoke-static/range {v$register .. v$register}, $PLAYBACK->chooseItem(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$register
                check-cast v$register, ${item.videoId.definingClass}
            """,
          )
        }
    bindingMethod.addInstructions(
        0,
        "invoke-static/range {p0 .. p0}, $PLAYBACK->observe($NOW_ACCESS)V",
    )
    owner.addBridge(
        "patch_jamRefreshNow",
        emptyList(),
        "V",
        1,
        body =
            """
                ${invokeKind(binding.refresh)} {p0}, ${binding.refresh}
                return-void
            """,
    )
  }
}

private fun BytecodePatchContext.installArtwork(artwork: ArtworkAbi) {
  val owner = mutableClassDefBy(artwork.type)
  val local = artwork.update.getMutableMethod()
  val name = local.name
  local.setName("patch_jamLocalArtwork")
  owner.addBridge(
      name,
      listOf("Landroid/graphics/Bitmap;"),
      "V",
      3,
      local.accessFlags,
      """
            iget-object v0, p0, ${artwork.image}
            invoke-static {v0}, Lapp/morphe/extension/music/jam/JamArtwork;->bind(Landroid/widget/ImageView;)V
            invoke-static {v0, p1}, Lapp/morphe/extension/music/jam/JamArtwork;->choose(Landroid/widget/ImageView;Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;
            move-result-object p1
            ${invokeKind(artwork.update)} {p0, p1}, $local
            return-void
        """,
  )
}

private fun BytecodePatchContext.installQueueRow(row: QueueRowAbi, item: QueueItemAbi) {
  val owner = mutableClassDefBy(row.type)
  owner.interfaces.add(MENU_ROW)
  owner.addBridge(
      "patch_jamMenuItem",
      emptyList(),
      "Ljava/lang/Object;",
      2,
      body =
          """
            iget-object v0, p0, ${row.item}
            return-object v0
        """,
  )
  owner.addBridge(
      "patch_jamShowMenu",
      listOf("Landroid/view/View;", "Ljava/lang/Object;"),
      "V",
      7,
      body =
          """
            move-object v0, p2
            check-cast v0, ${item.metadataType}
            ${invokeKind(row.menuAccessor)} {v0}, ${row.menuAccessor}
            move-result-object v1
            if-eqz v1, :done
            check-cast p2, $ITEM_ACCESS
            invoke-interface {p2}, $ITEM_ACCESS->patch_jamMenuPayload()Ljava/lang/Object;
            move-result-object v3
            iget-object v0, p0, ${row.menuPresenter}
            move-object v2, p1
            iget-object p0, p0, ${row.menuContext}
            ${invokeKind(row.menuDispatch)} {v0, v1, v2, v3, p0}, ${row.menuDispatch}
            :done
            return-void
        """,
  )
  val bind = row.bind.getMutableMethod()
  val bindName = bind.name
  bind.setName("patch_jamBindRow")
  owner.addBridge(
      bindName,
      bind.parameters(),
      "V",
      5,
      bind.accessFlags,
      """
            ${invokeKind(row.bind)} {p0, p1, p2, p3}, $bind
            ${invokeKind(row.rootView)} {p0}, ${row.rootView}
            move-result-object v0
            invoke-static {v0, p0, p3}, Lapp/morphe/extension/music/jam/JamMenu;->bind(Landroid/view/View;${MENU_ROW}Ljava/lang/Object;)V
            return-void
        """,
  )
  val click = row.click.getMutableMethod()
  val clickName = click.name
  click.setName("patch_jamLocalClick")
  owner.addBridge(
      clickName,
      listOf("Landroid/view/View;"),
      "Z",
      3,
      click.accessFlags,
      """
            iget-object v0, p0, ${row.item}
            invoke-static {v0}, $PLAYBACK->queueTap(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :done
            ${invokeKind(row.click)} {p0, p1}, $click
            move-result v0
            :done
            return v0
        """,
  )
}

private fun BytecodePatchContext.installButton(button: ButtonAbi) {
  val owner = mutableClassDefBy(button.type)
  val local = button.click.getMutableMethod()
  val name = local.name
  local.setName("patch_jamLocalClick")
  owner.addBridge(
      name,
      listOf("Landroid/view/View;"),
      "V",
      3,
      local.accessFlags,
      """
            invoke-static {p1}, $PLAYBACK->playButton(Landroid/view/View;)Z
            move-result v0
            if-eqz v0, :local
            return-void
            :local
            ${invokeKind(button.click)} {p0, p1}, $local
            return-void
        """,
  )
}

private fun ReferenceInstruction.invokeRegister(index: Int): Int? =
    when (this) {
      is FiveRegisterInstruction ->
          listOf(registerC, registerD, registerE, registerF, registerG)
              .take(registerCount)
              .getOrNull(index)
      is RegisterRangeInstruction -> (startRegister + index).takeIf { index < registerCount }
      else -> null
    }

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun BytecodePatchContext.installAutoplayUi(abi: AutoplayUiAbi) {
  val extension = "Lapp/morphe/extension/music/jam/JamMirror;"
  val contract = "Lapp/morphe/extension/music/jam/JamMirror\$AutoplayUi;"
  val owner = mutableClassDefBy(abi.refresh.definingClass)
  owner.interfaces.add(contract)
  val refresh = abi.refresh.getMutableMethod()
  val name = refresh.name
  refresh.setName("patch_jamLocalAutoplayUi")
  // Participants need the native boundary even when their local radio mode hides it.
  // The wrapper below still sets the mirrored suggestion count and clears an empty header.
  refresh.addInstructionsWithLabels(
      0,
      """
      invoke-static {}, $extension->active()Z
      move-result v0
      if-eqz v0, :local
      const/4 v${abi.headerIndexRegister}, 0x0
      goto :header
    """,
      ExternalLabel("local", refresh.getInstruction(0)),
      ExternalLabel("header", refresh.getInstruction(abi.headerStart)),
  )
  owner.addBridge(
      name,
      emptyList(),
      "V",
      1,
      refresh.accessFlags,
      """
        ${invokeKind(abi.refresh)} {p0}, $refresh
        invoke-static {p0}, $extension->autoplayUi($contract)V
        return-void
    """,
  )
  owner.addBridge(
      "patch_jamAutoplayLimit",
      listOf("I"),
      "V",
      3,
      body =
          """
        iget-object v0, p0, ${abi.limiter}
        ${invokeKind(abi.setLimit)} {v0, p1}, ${abi.setLimit}
        if-nez p1, :done
        iget-object v0, p0, ${abi.header}
        ${invokeKind(abi.clearHeader)} {v0}, ${abi.clearHeader}
        :done
        return-void
    """,
  )
  owner.addBridge(
      "patch_jamRefreshAutoplayUi",
      emptyList(),
      "V",
      1,
      body =
          """
        ${invokeKind(abi.refresh)} {p0}, ${owner.type}->$name()V
        return-void
    """,
  )
}
