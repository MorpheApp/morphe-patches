/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.matchSingle
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val OBJECT = "Ljava/lang/Object;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val MAP = "Ljava/util/Map;"
private const val VIEW = "Landroid/view/View;"

internal data class JamUiAbi(
    val clock: ClockAbi,
    val palette: PaletteAbi,
    val playback: PlaybackAbi,
    val currentItem: CurrentItemAbi,
    val nowPlaying: NowPlayingAbi,
    val artwork: ArtworkAbi,
    val queueRow: QueueRowAbi,
    val buttons: List<ButtonAbi>,
    val playbackIcon: PlaybackIconAbi,
    val autoplay: AutoplayUiAbi,
)

internal data class AutoplayUiAbi(
    val refresh: MethodReference,
    val limiter: FieldReference,
    val setLimit: MethodReference,
    val header: FieldReference,
    val clearHeader: MethodReference,
    val headerStart: Int,
    val headerIndexRegister: Int,
)

internal data class ClockAbi(
    val mediaState: MethodReference,
    val timeBarType: String,
    val timeBaseType: String,
    val setModel: MethodReference,
    val dragging: MethodReference,
    val modelType: String,
    val concreteModelType: String,
    val position: FieldReference,
    val duration: FieldReference,
    val trailingPosition: FieldReference,
    val overrideColors: List<FieldReference>,
    val active: FieldReference,
    val seek: MethodReference,
)

internal data class PaletteAbi(
    val type: String,
    val entry: MethodReference,
    val extractor: FieldReference,
    val extract: MethodReference,
    val publisher: FieldReference,
    val publish: MethodReference,
    val localPublish: MethodReference,
)

internal data class PlaybackAbi(val command: ProtoAbi, val routers: List<PlaybackRouterAbi>)

internal data class PlaybackRouterAbi(val type: String, val dispatch: MethodReference)

internal data class CurrentItemAbi(val type: String, val accessor: MethodReference)

internal data class NowPlayingAbi(
    val presenter: NowPlayingPresenterAbi,
    val menuEntry: MethodReference,
    val queueBindings: List<QueueBindingAbi>,
)

internal data class NowPlayingPresenterAbi(val type: String, val entry: MethodReference)

internal data class QueueBindingAbi(
    val type: String,
    val entry: MethodReference,
    val itemLookup: MethodReference,
    val refresh: MethodReference,
)

internal data class ArtworkAbi(
    val type: String,
    val update: MethodReference,
    val image: FieldReference,
)

internal data class QueueRowAbi(
    val type: String,
    val item: FieldReference,
    val rootView: MethodReference,
    val bind: MethodReference,
    val click: MethodReference,
    val menuAccessor: MethodReference,
    val menuPresenter: FieldReference,
    val menuContext: FieldReference,
    val menuPayload: QueueItemMenuPayloadAbi,
    val menuDispatch: MethodReference,
)

internal data class ButtonAbi(val type: String, val click: MethodReference)

internal data class PlaybackIconAbi(
    val render: MethodReference,
    val view: FieldReference,
    val constructor: MethodReference,
    val playing: FieldReference,
    val paused: FieldReference,
)

/**
 * Resolves presentation hooks from queue and platform relationships, never host obfuscation names.
 */
internal fun BytecodePatchContext.resolveJamUiAbi(queue: JamQueueAbi): JamUiAbi {
  val clock = resolveClock()
  val palette = resolvePalette()
  val playback = resolvePlayback()
  val currentItem = resolveCurrentItem()
  val artwork = resolveArtwork()
  val nowPlaying = resolveNowPlaying(currentItem, queue.item)
  val queueRow = resolveQueueRow(queue.item, nowPlaying.menuEntry)
  val buttons = resolveButtons()
  return JamUiAbi(
      clock,
      palette,
      playback,
      currentItem,
      nowPlaying,
      artwork,
      queueRow,
      buttons,
      resolvePlaybackIcon(),
      resolveAutoplayUi(queue),
  )
}

private fun BytecodePatchContext.resolveClock(): ClockAbi {
  val mediaState = MediaSessionStateFingerprint.matchSingle().originalMethod
  val timeBar = PlaybackTimeBarFingerprint.matchSingle().originalClassDef
  val setModel = clockModelSetterFingerprint(timeBar.type).matchSingle().originalMethod
  val timeBase = classDefBy(setModel.definingClass)
  val dragging = clockDraggingFingerprint(timeBase.type).matchSingle().originalMethod
  val model = classDefBy(setModel.parameters().single())
  val modelMatch = clockMutableModelFingerprint(model.type).matchSingle()
  val concrete = modelMatch.originalClassDef to modelMatch.originalMethod
  val setter = concrete.second
  val writes =
      setter.findInstructionIndicesReversedOrThrow(
          fieldAccess(definingClass = concrete.first.type, type = "J", opcode = Opcode.IPUT_WIDE)
      )
  fun timestamp(parameterRegister: Int): FieldReference {
    val write =
        writes
            .filter {
              setter.getInstruction<TwoRegisterInstruction>(it).registerA ==
                  setter.p0Register + parameterRegister
            }
            .singleOrNull()
            ?: error("Missing or ambiguous â€“ Jam clock timestamp parameter p$parameterRegister")
    return requireNotNull(setter.getInstruction(write).getReference<FieldReference>())
  }
  val colorBinding = clockOverrideColorsFingerprint(timeBar.type, model.type).matchSingle()
  val overrideColors =
      listOf(2, 4).map { index ->
        val accessor =
            colorBinding.instructionMatches[index].instruction.getReference<MethodReference>()!!
        clockModelFieldFingerprint(concrete.first.type, accessor)
            .matchSingle()
            .instructionMatches[0]
            .getFieldAccessed()
      }
  require(overrideColors.distinct().size == 2) {
    "Jam clock override colors must be distinct fields"
  }
  val activeGetter =
      clockLabelEnabledFingerprint(timeBar.type, model.type, dragging)
          .matchSingle()
          .instructionMatches[0]
          .instruction
          .getReference<MethodReference>()!!
  val active =
      clockModelFieldFingerprint(concrete.first.type, activeGetter)
          .matchSingle()
          .instructionMatches[0]
          .getFieldAccessed()
  val seek = resolveSeek(timeBar)
  return ClockAbi(
      mediaState,
      timeBar.type,
      timeBase.type,
      setModel,
      dragging,
      model.type,
      concrete.first.type,
      timestamp(1),
      timestamp(5),
      timestamp(7),
      overrideColors,
      active,
      seek,
  )
}

private fun BytecodePatchContext.resolveSeek(timeBar: ClassDef): MethodReference {
  val controls =
      timeBarOwnerFingerprint(timeBar.type)
          .matchAll()
          .map { it.originalClassDef }
          .distinctBy { it.type }
  val controlInterfaces = controls.flatMap { implementedInterfaces(it.type) }.toSet()
  require(controlInterfaces.isNotEmpty()) {
    "Unable to resolve Jam playback-control listener interfaces"
  }
  return seekForwarderFingerprint(controlInterfaces).matchSingle().originalMethod
}

private fun BytecodePatchContext.resolvePalette(): PaletteAbi {
  val candidates =
      PalettePublicationFingerprint.matchAll().mapNotNull { match ->
        val source = match.originalClassDef
        val publish = match.instructionMatches[0].instruction.getReference<MethodReference>()!!
        val local = match.instructionMatches[1].instruction.getReference<MethodReference>()!!
        val extractors =
            paletteExtractorFingerprint(
                    local.parameterTypes[0].toString(),
                    source.fields.map { it.type }.toSet(),
                )
                .matchAll(0..1)
        if (extractors.isEmpty()) return@mapNotNull null
        val extract = extractors.single().originalMethod
        val extractor =
            source.fields.filter { it.type == extract.definingClass }.singleOrNull()
                ?: error("Missing or ambiguous â€“ Jam palette extractor field")
        val publisher =
            source.fields.filter { it.type == publish.definingClass }.singleOrNull()
                ?: error("Missing or ambiguous â€“ Jam palette publisher field")
        PaletteAbi(
            source.type,
            match.originalMethod,
            extractor,
            extract,
            publisher,
            publish,
            local,
        )
      }
  return candidates.singleOrNull() ?: error("Missing or ambiguous â€“ Jam player palette pipeline")
}

private fun BytecodePatchContext.resolvePlayback(): PlaybackAbi {
  val anchor = AccountScopedCommandRouterFingerprint.matchSingle()
  val outer = anchor.originalClassDef
  val peer = classDefBy(anchor.originalMethod.returnType)
  val dispatchMatch = accountRouterDispatchFingerprint(outer.type).matchSingle()
  val outerDispatch = dispatchMatch.originalMethod
  val forwarder = dispatchMatch.instructionMatches[0].instruction.getReference<MethodReference>()!!
  require(forwarder.parameterTypes == outerDispatch.parameterTypes) {
    "Jam router changes endpoint parameters"
  }
  val peerDispatch =
      accountPeerDispatchFingerprint(peer.type, forwarder).matchSingle().originalMethod
  val command = resolveProto(outerDispatch.parameters()[0], "account command-router endpoint")
  fun router(owner: ClassDef, dispatch: Method): PlaybackRouterAbi {
    return PlaybackRouterAbi(owner.type, dispatch)
  }
  return PlaybackAbi(command, listOf(router(outer, outerDispatch), router(peer, peerDispatch)))
}

private fun BytecodePatchContext.resolveCurrentItem(): CurrentItemAbi {
  val source = CurrentPlaybackItemSourceFingerprint.matchSingle()
  val accessor = source.instructionMatches[0].instruction.getReference<MethodReference>()!!
  require(source.originalClassDef.fields.any { it.type == accessor.definingClass }) {
    "Jam current-item source must be owned by the watch page"
  }
  return CurrentItemAbi(accessor.definingClass, accessor)
}

private fun BytecodePatchContext.resolveNowPlaying(
    current: CurrentItemAbi,
    item: QueueItemAbi,
): NowPlayingAbi {
  val watchPage = WatchPageStateFingerprint.matchSingle().originalClassDef
  val menuMatch =
      nowPlayingMenuEntryFingerprint(watchPage.type, current.type, current.accessor).matchSingle()
  val menuEntry = menuMatch.originalMethod
  val presenterMatch = nowPlayingRefreshFingerprint(current.accessor).matchSingle()
  val presenter =
      NowPlayingPresenterAbi(presenterMatch.originalClassDef.type, presenterMatch.originalMethod)
  val bindings =
      queueBindingFingerprint(item.videoId.definingClass).matchAll().mapNotNull { match ->
        val candidate = match.originalClassDef
        val entry = match.originalMethod
        run {
          val lookup = match.instructionMatches[0].instruction.getReference<MethodReference>()!!
          val ownerTypes = interfaceClosure(candidate.type)
          val refresh =
              queuePresenterRefreshFingerprint(ownerTypes, candidate.methods)
                  .matchAll(0..1)
                  .singleOrNull()
                  ?.originalMethod ?: return@mapNotNull null
          QueueBindingAbi(candidate.type, entry, lookup, refresh)
        }
      }
  require(bindings.isNotEmpty()) { "Unable to resolve Jam queue item binding presenters" }
  return NowPlayingAbi(presenter, menuEntry, bindings)
}

private fun BytecodePatchContext.resolveArtwork(): ArtworkAbi {
  val imageType = ArtworkCropFingerprint.matchSingle().originalClassDef.type
  val match = nowPlayingArtworkFingerprint(imageType).matchSingle()
  val image = match.instructionMatches[0].getFieldAccessed()
  return ArtworkAbi(match.originalClassDef.type, match.originalMethod, image)
}

private fun BytecodePatchContext.resolveQueueRow(
    item: QueueItemAbi,
    nowPlayingEntry: MethodReference,
): QueueRowAbi {
  val menuMatch = nowPlayingMenuDispatchFingerprint(nowPlayingEntry).matchSingle()
  val nowPlayingMethod = menuMatch.originalMethod
  val menuDispatch = menuMatch.instructionMatches[0].instruction.getReference<MethodReference>()!!
  val itemTypes = (listOf(item.type) + item.implementations.map { it.type }).distinct()
  val candidates =
      itemTypes
          .flatMap { itemType -> queueRowBindingFingerprint(itemType).matchAllOrNull().orEmpty() }
          .mapNotNull { match ->
            val row = match.originalClassDef
            val bind = match.originalMethod
            val itemField = match.instructionMatches[0].getFieldAccessed()
            val root =
                queueRowRootFingerprint(row.type).matchAll(0..1).singleOrNull()?.originalMethod
                    ?: return@mapNotNull null
            val click =
                queueRowLongClickFingerprint(row.type).matchAll(0..1).singleOrNull()?.originalMethod
                    ?: return@mapNotNull null
            val menuAccessor =
                queueMenuAccessorFingerprint(
                        item.metadataType,
                        menuDispatch.parameterTypes[0].toString(),
                    )
                    .matchSingle()
                    .originalMethod
            val menuPayload =
                nowPlayingMethod
                    .findInstructionIndicesReversed(
                        methodCall(
                            parameters = emptyList(),
                            returnType = menuAccessor.returnType,
                        )
                    )
                    .map { nowPlayingMethod.getInstruction(it).getReference<MethodReference>()!! }
                    .filter { method ->
                      method.parameters().isEmpty() &&
                          method.returnType == menuAccessor.returnType &&
                          classDefByOrNull(method.definingClass)?.let {
                            AccessFlags.INTERFACE.isSet(it.accessFlags)
                          } == true &&
                          item.implementations.all { implementation ->
                            implementsType(implementation.type, method.definingClass)
                          }
                    }
                    .distinctBy { it.toString() }
                    .map { QueueItemMenuPayloadAbi(it.definingClass) }
                    .also { require(it.size <= 1) { "Ambiguous Jam queue menu payload: $it" } }
                    .singleOrNull() ?: return@mapNotNull null
            val presenter =
                row.fields
                    .filter { it.type == menuDispatch.definingClass }
                    .also { require(it.size <= 1) { "Ambiguous Jam row presenter in ${row.type}" } }
                    .singleOrNull() ?: return@mapNotNull null
            val context =
                row.fields
                    .filter { it.type == menuDispatch.parameters()[3] }
                    .also { require(it.size <= 1) { "Ambiguous Jam row context in ${row.type}" } }
                    .singleOrNull() ?: return@mapNotNull null
            QueueRowAbi(
                row.type,
                itemField,
                root,
                bind,
                click,
                menuAccessor,
                presenter,
                context,
                menuPayload,
                menuDispatch,
            )
          }
          .distinctBy { it.type to it.bind.toString() }
  return candidates.singleOrNull()
      ?: error("Missing or ambiguous â€“ Jam queue row and menu presenter")
}

private fun BytecodePatchContext.resolveButtons(): List<ButtonAbi> {
  val controls = PlaybackControlViewsFingerprint.matchSingle().originalClassDef
  val presenter = PlayerMetadataViewsFingerprint.matchSingle().originalClassDef
  return (playbackControlsClickFingerprint(controls.type).matchAll() +
          playbackButtonClickFingerprint(controls.type).matchAll() +
          playbackButtonClickFingerprint(presenter.type).matchAll())
      .map { match -> ButtonAbi(match.originalClassDef.type, match.originalMethod) }
      .distinctBy { it.type }
}

private fun BytecodePatchContext.resolvePlaybackIcon(): PlaybackIconAbi {
  val controls = PlaybackControlViewsFingerprint.matchSingle().originalClassDef
  val render = playbackIconFingerprint(controls.fields.map { it.type }.toSet()).matchSingle()
  val constructor =
      playbackIconModelConstructorFingerprint(render.originalMethod.parameterTypes.single().toString())
          .matchSingle()
          .originalMethod
  val stateType = constructor.parameterTypes[0].toString()
  require(AccessFlags.ENUM.isSet(classDefBy(stateType).accessFlags)) {
    "Jam playback icon state must be an enum"
  }
  val view =
      render.originalClassDef.fields.singleOrNull { it.type == "Landroid/widget/ImageView;" }
          ?: error("Missing or ambiguous Jam playback icon view")
  fun state(name: String) =
      playbackIconStateFingerprint(stateType, name).matchSingle()
          .instructionMatches[1].instruction.getReference<FieldReference>()!!
  return PlaybackIconAbi(render.originalMethod, view, constructor, state("PLAYING"), state("PAUSED"))
}

private fun BytecodePatchContext.implementedInterfaces(type: String): Set<String> {
  val interfaces = mutableSetOf<String>()
  fun visit(candidate: String) {
    classDefByOrNull(candidate)?.interfaces?.forEach { parent ->
      if (interfaces.add(parent)) visit(parent)
    }
  }
  visit(type)
  return interfaces
}

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun String.isReferenceType(): Boolean = startsWith("L") || startsWith("[")

private fun <T> T?.requireValue(concept: String): T = this ?: error("Unable to resolve $concept")

private fun BytecodePatchContext.resolveAutoplayUi(queue: JamQueueAbi): AutoplayUiAbi {
  val owner =
      autoplaySectionOwnerFingerprint(queue.managerType, queue.displays.autoplay.type)
          .matchSingle()
          .originalClassDef
  val refresh = autoplaySectionRefreshFingerprint(owner.type).matchSingle()
  val setLimit = refresh.instructionMatches[1].instruction.getReference<MethodReference>()!!
  val method = refresh.originalMethod
  val limiter =
      method
          .findInstructionIndicesReversedOrThrow(
              fieldAccess(
                  definingClass = owner.type,
                  type = setLimit.definingClass,
                  opcode = Opcode.IGET_OBJECT,
              )
          )
          .map { method.getInstruction(it).getReference<FieldReference>()!! }
          .distinct()
          .singleOrNull() ?: error("Missing or ambiguous Jam autoplay display limiter")
  val clear = refresh.instructionMatches[2]
  val headerInstruction = method.getInstruction(clear.index - 1)
  val header =
      headerInstruction.getReference<FieldReference>() ?: error("Missing Jam autoplay header field")
  check(headerInstruction.opcode == Opcode.IGET_OBJECT && header.definingClass == owner.type) {
    "Unexpected Jam autoplay header access"
  }
  val headerCreation = autoplayHeaderCreationFingerprint(header).match(method, owner)
  val headerStart = headerCreation.instructionMatches[0].index
  val addHeader =
      method
          .findInstructionIndicesReversedOrThrow(
              methodCall(name = "add", parameters = listOf("I", OBJECT), returnType = "V")
          )
          .single()
  val headerIndexRegister = method.getInstruction<FiveRegisterInstruction>(addHeader).registerD
  check(headerIndexRegister < method.p0Register && method.p0Register > 0) {
    "Unexpected Jam autoplay header index register"
  }
  return AutoplayUiAbi(
      method,
      limiter,
      setLimit,
      header,
      clear.instruction.getReference<MethodReference>()!!,
      headerStart,
      headerIndexRegister,
  )
}
