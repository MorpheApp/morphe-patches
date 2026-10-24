/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.DexVerifier
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patcher.patch.Patch
import app.morphe.patches.youtube.interaction.ask.askFlyoutMenuPatch
import app.morphe.patches.youtube.interaction.downloads.downloadsPatch
import app.morphe.patches.youtube.interaction.feedactions.feedVideoActionsPatch
import app.morphe.patches.youtube.interaction.loop.loopVideoPatch
import app.morphe.patches.youtube.interaction.reload.reloadVideoButtonPatch
import app.morphe.patches.youtube.interaction.savetowatchlater.saveToWatchLaterButtonPatch
import app.morphe.patches.youtube.layout.buttons.action.hideVideoActionButtonsPatch
import app.morphe.patches.youtube.layout.buttons.overlay.hidePlayerOverlayButtonsPatch
import app.morphe.patches.youtube.layout.dearrow.deArrowPatch
import app.morphe.patches.youtube.layout.feedcards.feedCardLayoutPatch
import app.morphe.patches.youtube.layout.flyout.addToQueuePatch
import app.morphe.patches.youtube.layout.hide.general.hideLayoutComponentsPatch
import app.morphe.patches.youtube.layout.hide.relatedvideos.hideRelatedVideosPatch
import app.morphe.patches.youtube.layout.livering.openChannelOfLiveAvatarPatch
import app.morphe.patches.youtube.layout.miniplayer.miniplayerPatch
import app.morphe.patches.youtube.layout.originaltitles.restoreOriginalTitlesPatch
import app.morphe.patches.youtube.layout.player.fullscreen.exitFullscreenPatch
import app.morphe.patches.youtube.layout.player.fullscreen.openVideosFullscreenPatch
import app.morphe.patches.youtube.layout.playlistautoplay.disablePlaylistAutoplayPatch
import app.morphe.patches.youtube.layout.returnyoutubedislike.returnYouTubeDislikePatch
import app.morphe.patches.youtube.layout.sharesheet.openSystemShareSheetPatch
import app.morphe.patches.youtube.misc.backgroundplayback.backgroundPlaybackPatch
import app.morphe.patches.youtube.misc.fix.videoactionbar.restoreOldVideoActionBarPatch
import app.morphe.patches.youtube.misc.medianotification.mediaNotificationControlsPatchYouTube
import app.morphe.patches.youtube.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.youtube.video.series.seriesTrackerPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.analysis.ClassPath
import com.android.tools.smali.dexlib2.analysis.ClassProvider
import com.android.tools.smali.dexlib2.analysis.DexClassProvider
import com.android.tools.smali.dexlib2.analysis.MethodAnalyzer
import com.android.tools.smali.dexlib2.analysis.RegisterType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory

/** Applies the real patch and runs SDK DEX verification. Playback behavior still requires a phone. */
fun main(arguments: Array<String>) {
    require(arguments.size in 1..3) {
        "Usage: PlaylistQueueApkBuildKt <stock-apk> [standalone|combined|unselected] [unsigned-output]"
    }
    val input = File(arguments[0]).canonicalFile
    require(input.isFile) { "Stock APK does not exist: $input" }
    val mode = arguments.getOrElse(1) { "standalone" }
    require(mode in setOf("standalone", "combined", "unselected"))
    val sdk = System.getenv("ANDROID_HOME") ?: error("ANDROID_HOME is required for SDK verification")
    // Add to queue includes the native queue, so it is only selected in combined mode.
    val relatedPatches = setOf(
        saveToWatchLaterButtonPatch, downloadsPatch, openSystemShareSheetPatch(), loopVideoPatch,
        reloadVideoButtonPatch,
        disablePlaylistAutoplayPatch, hidePlayerOverlayButtonsPatch, hideVideoActionButtonsPatch,
        restoreOriginalTitlesPatch, deArrowPatch, openChannelOfLiveAvatarPatch, returnYouTubeDislikePatch,
        hideLayoutComponentsPatch, hideRelatedVideosPatch, exitFullscreenPatch, openVideosFullscreenPatch,
        mediaNotificationControlsPatchYouTube, backgroundPlaybackPatch,
        spoofVideoStreamsPatch, restoreOldVideoActionBarPatch,
        feedVideoActionsPatch, feedCardLayoutPatch, seriesTrackerPatch, askFlyoutMenuPatch, miniplayerPatch
    )
    val selected = when (mode) {
        "standalone" -> setOf(nativePlaylistQueuePatch)
        "combined" -> relatedPatches + addToQueuePatch
        else -> relatedPatches
    }
    fun requiresQueue(patch: Patch<*>, visited: MutableSet<Patch<*>> = mutableSetOf()): Boolean =
        patch == nativePlaylistQueuePatch || visited.add(patch) && patch.dependencies.any { requiresQueue(it, visited) }
    check(relatedPatches.none { requiresQueue(it) }) {
        "Unrelated patches must not select Native playlist queue as a dependency"
    }
    val workspace = createTempDirectory("playlist-queue-$mode").toFile()
    val sdkVerifier = SdkDexVerifier(File(sdk))
    val verifier = object : DexVerifier by sdkVerifier {
        override fun verifyDexDirectory(dexDir: File) {
            sdkVerifier.verifyDexDirectory(dexDir)
            verifyNativePanelAssignment(dexDir, mode != "unselected")
            verifyNativeRowMenuPaths(dexDir, mode != "unselected")
            verifyNativeFullscreenActions(dexDir, mode != "unselected")
            verifyNativeQueueUndo(dexDir, mode != "unselected")
        }
    }
    Patcher(PatcherConfig(input, workspace, verifier = verifier)).use { patcher ->
        patcher += selected
        runBlocking {
            patcher().collect { result ->
                result.exception?.let { throw it }
                println("Applied: ${result.patch.name}")
            }
        }
        val result = patcher.get()
        arguments.getOrNull(2)?.let { result.applyTo(File(it)) }
        println("$mode: APK patched and SDK DEX/resource verification completed: $workspace")
    }
}

/** Verify the emitted UI bridge reaches the native setter and size subject, not playback. */
private fun verifyNativeFullscreenActions(directory: File, selected: Boolean) {
    val classes = directory.listFiles()!!.filter { it.extension == "dex" }.flatMap { file ->
        file.inputStream().buffered().use {
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it).classes.toList()
        }
    }
    val extension = "Lapp/morphe/extension/youtube/patches/queue/QueueFullscreenActions;"
    val controllerInterface = "${extension.removeSuffix(";")}\$Controller;"
    val controllers = classes.filter { controllerInterface in it.interfaces }
    if (!selected) {
        check(controllers.isEmpty()) { "Fullscreen queue UI bridge leaked into an unselected build" }
        println("Native fullscreen playlist bridge absent when unselected")
        return
    }
    val controller = controllers.single()
    fun references(name: String) = controller.methods.single { it.name == name }.implementation!!
        .instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
    val setter = controller.methods.single { method -> method.implementation?.instructions?.any {
        (it as? ReferenceInstruction)?.reference?.toString()?.startsWith("$extension->onModelLoaded(") == true
    } == true }
    val refresh = references("patch_refreshQuickActions")
    val reset = refresh.first()
    check(reset.definingClass == controller.type && reset.toString() != setter.toString()) {
        "Fullscreen refresh must dispose the cached native UI before setting a new model"
    }
    val nativeReset = controller.methods.single { it.toString() == reset.toString() }
    check(nativeReset.implementation!!.instructions.any {
        (it as? ReferenceInstruction)?.reference?.toString() == setter.toString()
    }) { "Fullscreen refresh no longer uses the original native model cleanup" }
    check(refresh.any { it.toString() == setter.toString() }) {
        "Fullscreen refresh does not reuse the native model setter"
    }
    check(refresh.any { it.parameterTypes == listOf("Ljava/lang/Object;") && it.returnType == "V" &&
            it.definingClass != controller.type }) { "Fullscreen refresh lost the native size emission" }
    check(references("patch_parseQuickActions").any {
        it.definingClass == "Lcom/google/protobuf/ExtensionRegistryLite;" && it.name == "getGeneratedRegistry"
    }) { "Fullscreen model parser does not retain native extensions" }
    println("Native fullscreen playlist cleanup, setter, size refresh and registry bridges verified")
}

/** Check the emitted native video bridge and queue-only menu dispatch paths. */
private fun verifyNativeRowMenuPaths(directory: File, selected: Boolean) {
    val dexFiles = directory.listFiles()!!.filter { it.extension == "dex" }.map { file ->
        file.inputStream().buffered().use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it) }
    }
    val classes = dexFiles.flatMap { it.classes }
    val queueRow = "${EXTENSION_CLASS.removeSuffix(";")}\$QueueRow;"
    val videoIdInterface = "Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils\$FlyoutMenuVideoIdInterface;"
    val extensionOwner = EXTENSION_CLASS.removePrefix("L").removeSuffix(";")
    val rowTypes = classes.filter { queueRow in it.interfaces }
    val menuOpenCalls = classes.flatMap { owner -> owner.methods.mapNotNull { method ->
        method.implementation?.instructions?.toList()?.let { instructions ->
            instructions.indexOfFirst { instruction ->
                (instruction as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                    "L$extensionOwner;->onRowMenuOpened(Ljava/util/Map;)V") == true
            }.takeIf { it >= 0 }?.let { Triple(owner, method, instructions) }
        }
    } }
    val menuDispatches = classes.flatMap { owner -> owner.methods.mapNotNull { method ->
        method.implementation?.instructions?.toList()?.let { instructions ->
            instructions.indexOfFirst { instruction ->
                (instruction as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                    "L$extensionOwner;->onQueueMenuCommand(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)") == true
            }.takeIf { index -> index >= 0 && instructions.getOrNull(index + 2)?.opcode == Opcode.IF_NEZ &&
                    instructions.getOrNull(index + 3)?.opcode == Opcode.GOTO_16 }
                ?.let { Triple(owner, method, instructions) }
        }
    } }
    val saveEndpointInterface = "Lapp/morphe/extension/youtube/patches/queue/QueueSaveActions\$NativeEndpoint;"
    val saveEndpoints = classes.filter { saveEndpointInterface in it.interfaces }
    val saveDispatches = classes.flatMap { it.methods }.mapNotNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val index = instructions.indexOfFirst {
            (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                "L$extensionOwner;->onQueueSaveCommand(") == true
        }
        if (index < 0) null else Triple(method, instructions, index)
    }
    val taggedSheetGuards = classes.flatMap { it.methods }.mapNotNull { method ->
        if (method.returnType != "V" || method.parameterTypes.size != 12) return@mapNotNull null
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val index = instructions.indexOfFirst {
            (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                "L$extensionOwner;->isQueueSaveSelection(") == true
        }
        if (index < 0) null else instructions to index
    }
    val scopedRowFactories = classes.flatMap { it.methods }.filter {
        it.name in setOf("patch_buildQueueSaveRows", "patch_decodeQueueSaveRows")
    }
    check(saveEndpoints.isEmpty() && saveDispatches.isEmpty() && taggedSheetGuards.isEmpty() &&
            scopedRowFactories.isEmpty()) {
        "Queue batch Save hooks must not modify the player's native Save paths"
    }
    println("Experimental batch Save hooks, endpoint bridge and shared row factories absent")
    val batchEndpointInterface = "Lapp/morphe/extension/youtube/patches/queue/QueueSaveActions\$Endpoint;"
    val batchPickerInterface = "Lapp/morphe/extension/youtube/patches/queue/QueueSaveActions\$Picker;"
    val batchEndpoints = classes.filter { batchEndpointInterface in it.interfaces }
    val batchPickers = classes.filter { batchPickerInterface in it.interfaces }
    val argumentTransfers = classes.flatMap { it.methods }.mapNotNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val index = instructions.indexOfFirst {
            (it as? ReferenceInstruction)?.reference?.toString()?.contains("->onQueueSaveArguments(") == true
        }
        if (index < 0) null else Triple(method, instructions, index)
    }
    val batchRequests = classes.flatMap { it.methods }.mapNotNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val index = instructions.indexOfFirst {
            (it as? ReferenceInstruction)?.reference?.toString()?.contains("QueueSaveActions;->onPickerRequest(") == true
        }
        if (index < 0) null else Triple(method, instructions, index)
    }
    val responseHooks = classes.flatMap { it.methods }.filter { method ->
        method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference?.toString()?.contains("QueueSaveActions;->onPickerResponse(") == true
        } == true
    }
    val queueStartHooks = classes.flatMap { it.methods }.flatMap { method ->
        method.implementation?.instructions?.filter {
            (it as? ReferenceInstruction)?.reference?.toString()
                ?.contains("NativePlaylistQueuePatch;->prepareQueueStartContext(") == true
        }?.toList() ?: emptyList()
    }
    check(queueStartHooks.size == if (selected) 1 else 0) {
        "Queue minimized-start hook must exist only when selected"
    }
    // Other patches use a cached insertion index in the watch resolver. A new
    // entry hook must not move their filters before native descriptor construction.
    val watch = classes.flatMap { it.methods }.single { method ->
        !method.definingClass.startsWith("Lapp/morphe/extension/") && method.parameterTypes.size == 2 && method.parameterTypes[1] == "Ljava/util/Map;" &&
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "start_watch_minimized"
                } == true
    }.implementation!!.instructions.toList()
    val queueContextIndex = watch.indexOfFirst {
        (it as? ReferenceInstruction)?.reference?.toString()?.contains("->prepareQueueStartContext(") == true
    }
    val queueCommandIndex = watch.indexOfFirst {
        (it as? ReferenceInstruction)?.reference?.toString()?.contains("->prepareQueueVideoCommand(") == true
    }
    check((queueCommandIndex >= 0) == selected) { "Queue watch normalization must exist only when selected" }
    if (selected) {
        check(queueCommandIndex in 0 until queueContextIndex
                && watch[queueCommandIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT
                && watch[queueCommandIndex + 2].opcode == Opcode.CHECK_CAST) {
            "Normalize and restore the native command type before context hooks or descriptor construction"
        }
    }
    val cardObserverIndex = watch.indexOfFirst {
        (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(
            "Lapp/morphe/extension/youtube/patches/FeedCardLayoutPatch;->onWatchCommand(") == true
    }
    if (selected && cardObserverIndex >= 0) {
        check(queueContextIndex in 0 until cardObserverIndex) {
            "Queue context must be transformed before the existing feed-card watch observer"
        }
    }
    watch.forEachIndexed { index, instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (call?.name != "onVideoIntentLoaded" || call.definingClass !in setOf(
                "Lapp/morphe/extension/youtube/patches/OpenChannelOfLiveAvatarPatch;",
                "Lapp/morphe/extension/youtube/patches/components/CommentsFilter;")) return@forEachIndexed
        val getter = watch[index - 2] as FiveRegisterInstruction
        val receiver = getter.registerC
        val lastWrite = (0 until index - 2).last {
            (watch[it] as? OneRegisterInstruction)?.registerA == receiver
        }
        check(watch[lastWrite].opcode == Opcode.MOVE_RESULT_OBJECT) {
            "Video intent filter ran before the native playback descriptor was constructed"
        }
        val construction = (watch[lastWrite - 1] as ReferenceInstruction).reference as MethodReference
        val videoGetter = (watch[index - 2] as ReferenceInstruction).reference as MethodReference
        check(construction.returnType == videoGetter.definingClass)
    }
    val feedbackInterface = "Lapp/morphe/extension/youtube/patches/queue/QueueSaveFeedback\$Callback;"
    val feedbackCallbacks = classes.filter { feedbackInterface in it.interfaces }
    val feedbackHooks = classes.flatMap { it.methods }.mapNotNull { method ->
        val calls = method.implementation?.instructions?.filter {
            (it as? ReferenceInstruction)?.reference?.toString()?.let { reference ->
                reference.contains("QueueSaveActions;->onEditCreated(") ||
                        reference.contains("QueueSaveActions;->onEditResult(")
            } == true
        }?.toList() ?: emptyList()
        if (calls.isEmpty()) null else method to calls
    }
    if (selected) {
        val callback = feedbackCallbacks.single()
        check(callback.fields.count { it.name == "patch_queueSaveSelection" } == 1)
        check(feedbackHooks.size == 2 && feedbackHooks.sumOf { it.second.size } == 2)
        check(feedbackHooks.any { it.first.definingClass == callback.type })
        println("Queue-only save callback ownership and native success feedback bridges present")
    } else {
        check(feedbackCallbacks.isEmpty() && feedbackHooks.isEmpty()) {
            "Queue save confirmation hooks leaked into an unselected build"
        }
    }
    if (selected) {
        check(batchEndpoints.isEmpty()) { "Transient endpoint instance markers must not own a batch selection" }
        val picker = batchPickers.single()
        val (factory, factoryInstructions, transferIndex) = argumentTransfers.single()
        val transfer = factoryInstructions[transferIndex] as FiveRegisterInstruction
        val serialization = factoryInstructions[transferIndex + 1] as ReferenceInstruction
        check(factory.parameterTypes.size == 1 &&
                serialization.reference.toString().endsWith("->toByteArray()[B") &&
                (serialization as FiveRegisterInstruction).registerC == transfer.registerC) {
            "Owned selection must enter native saved arguments before endpoint serialization overwrites its register"
        }
        check(batchRequests.isEmpty()) { "Queue Save must retain the stock single-video picker request" }
        val getter = picker.methods.single { it.name == "patch_getQueueSaveVideoIds" }.implementation!!.instructions
        check(getter.any { (it as? ReferenceInstruction)?.reference?.toString()?.contains("->getQueueSaveVideoIds(") == true })
        check(responseHooks.size == 1 && responseHooks.single().definingClass != picker.type)
        check(picker.methods.count { it.name == "patch_parseQueueSaveResponse" } == 1)
        println("Queue-owned batch Save: serialized selection, saved arguments and destination commands wired; picker request untouched")
    } else {
        check(batchEndpoints.isEmpty() && batchPickers.isEmpty() && argumentTransfers.isEmpty() &&
                batchRequests.isEmpty() && responseHooks.isEmpty()) {
            "Native batch Save bridge leaked into a patch-unselected build"
        }
    }
    if (!selected) {
        check(rowTypes.isEmpty() && menuOpenCalls.isEmpty() && menuDispatches.isEmpty() &&
                saveEndpoints.isEmpty() && saveDispatches.isEmpty() && taggedSheetGuards.isEmpty() &&
                scopedRowFactories.isEmpty()) {
            "Native playlist row menu bridge leaked into an unselected build"
        }
        println("Native playlist row menu bridge absent when unselected")
        return
    }

    val videoType = rowTypes.singleOrNull()
        ?: error("Expected one queue row type in emitted DEX, found ${rowTypes.size}")
    check(videoIdInterface in videoType.interfaces) {
        "Native queue row does not implement the shared FlyoutUtils video-ID interface"
    }
    val videoIdGetter = videoType.methods.single { it.name == "patch_getVideoId" }
    val getterInstructions = videoIdGetter.implementation!!.instructions.toList()
    val getterField = getterInstructions.single { it.opcode == Opcode.IGET_OBJECT }
        .let { (it as ReferenceInstruction).reference as FieldReference }
    check(getterField.definingClass == videoType.type && getterField.type == "Ljava/lang/String;" &&
            getterInstructions.last().opcode == Opcode.RETURN_OBJECT) {
        "Queue row's shared video-ID getter does not return its native String field"
    }
    check(classes.flatMap { it.methods }.any { method ->
        method.name != "patch_getVideoId" && method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                    (instruction as? ReferenceInstruction)?.reference == getterField
        } == true
    }) { "Binder-derived video-ID field is not read by native code in emitted DEX" }

    val extensionClass = classes.single { it.type == EXTENSION_CLASS }
    val rowMenuHelper = extensionClass.methods.single { it.name == "onRowMenuOpened" }
    val rowMenuInstructions = rowMenuHelper.implementation!!.instructions.toList()
    val instanceOfTypes = rowMenuInstructions.filter { it.opcode == Opcode.INSTANCE_OF }
        .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }.toSet()
    check(queueRow in instanceOfTypes && videoIdInterface in instanceOfTypes) {
        "Row menu helper does not guard both queue ownership and the native row's shared video-ID interface"
    }
    check(rowMenuInstructions.any { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
                (instruction as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                    "Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils;->extractFlyoutIdFromMap(Ljava/util/Map;)V"
                ) == true
    }) { "Shared row menu video context extractor is not reached by the emitted helper" }

    val menuOpen = menuOpenCalls.singleOrNull()
        ?: error("Expected one row menu context extraction call, found ${menuOpenCalls.size}")
    val (menuOwner, menuMethod, openInstructions) = menuOpen
    val openCallIndex = openInstructions.indexOfFirst { instruction ->
        (instruction as? ReferenceInstruction)?.reference?.toString()?.startsWith(
            "L$extensionOwner;->onRowMenuOpened(Ljava/util/Map;)V") == true
    }
    val openCall = openInstructions[openCallIndex] as FiveRegisterInstruction
    val mapResult = openInstructions.getOrNull(openCallIndex - 1) as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
    val mapFactory = openInstructions.getOrNull(openCallIndex - 2) as? ReferenceInstruction
    val factoryReference = mapFactory?.reference as? MethodReference
    check(mapResult != null && openCallIndex >= 2 &&
            openInstructions[openCallIndex - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            mapResult.registerA == openCall.registerC && factoryReference != null &&
            factoryReference.returnType.startsWith("L") &&
            factoryReference.parameterTypes.map { it.toString() } == List(4) { "Ljava/lang/Object;" }) {
        "Row menu context extraction is not immediately after its native four-object map factory"
    }
    println("Native row menu context bridge: ${menuOwner.type}->${menuMethod.name}, shared interface and native String getter verified")

    val dispatch = menuDispatches.singleOrNull()
        ?: error("Expected one consumed native menu removal path, found ${menuDispatches.size}")
    val dispatchInstructions = dispatch.third
    val removeIndex = dispatchInstructions.indexOfFirst { instruction ->
        (instruction as? ReferenceInstruction)?.reference?.toString()?.startsWith(
            "L$extensionOwner;->onQueueMenuCommand(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)") == true
    }
    check(removeIndex >= 0 && dispatchInstructions[removeIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            dispatchInstructions[removeIndex + 2].opcode == Opcode.IF_NEZ &&
            dispatchInstructions[removeIndex + 3].opcode == Opcode.GOTO_16) {
        "Native removal result does not branch between the original callback and dismissal"
    }
    val branchAddresses = HashMap<Int, Int>()
    var address = 0
    dispatchInstructions.forEachIndexed { index, instruction ->
        branchAddresses[address] = index
        address += instruction.codeUnits
    }
    fun branchTarget(index: Int): Int {
        val branch = dispatchInstructions[index] as OffsetInstruction
        val instructionAddress = branchAddresses.entries.single { it.value == index }.key
        return branchAddresses[instructionAddress + branch.codeOffset]
            ?: error("Native row menu branch target is not an instruction boundary")
    }
    val callbackIndex = branchTarget(removeIndex + 2)
    val dismissalIndex = branchTarget(removeIndex + 3)
    check(dispatchInstructions[callbackIndex].opcode == Opcode.CHECK_CAST &&
            dispatchInstructions.getOrNull(callbackIndex + 1)?.opcode == Opcode.INVOKE_INTERFACE &&
            dispatchInstructions[dismissalIndex].opcode == Opcode.INVOKE_VIRTUAL &&
            (dispatchInstructions[dismissalIndex] as ReferenceInstruction).reference
                .let { reference -> reference is MethodReference && reference.returnType == "V" &&
                        reference.parameterTypes.isEmpty() } &&
            dispatchInstructions.getOrNull(dismissalIndex + 1)?.opcode == Opcode.RETURN_VOID) {
        "Native removal branch does not preserve the original callback and terminal dismissal paths"
    }
    println("Native row menu dispatch preserves original callback and dismissal branches")
}

/** SDK structural checks do not catch a base protobuf value stored in a concrete panel field. */
private fun verifyNativePanelAssignment(directory: File, selected: Boolean) {
    val dexFiles = directory.listFiles()!!.filter { it.extension == "dex" }.map { file ->
        file.inputStream().buffered().use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it) }
    }
    val playbackInterface = EXTENSION_CLASS.removeSuffix(";") + "\$NativePlayback;"
    val owners = dexFiles.flatMap { it.classes }.filter { playbackInterface in it.interfaces }
    check(owners.size == if (selected) 1 else 0) { "Unexpected native playback bridge inclusion" }
    val panelInterface = EXTENSION_CLASS.removeSuffix(";") + "\$QueuePanel;"
    val panels = dexFiles.flatMap { it.classes }.filter { panelInterface in it.interfaces }
    check(panels.size == if (selected) 1 else 0) { "Queue header ownership must be included only with this patch" }
    val shufflePresses = dexFiles.flatMap { it.classes }.filter { !it.type.startsWith("Lapp/morphe/") }
        .flatMap { it.methods }.filter { method -> method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                "$EXTENSION_CLASS->onQueueShuffle(") == true
        } == true }
    check(shufflePresses.size == if (selected) 1 else 0) {
        "Queue shuffle command must intercept one native listener only when selected"
    }
    val transitionHook = "$EXTENSION_CLASS->onPlaybackTransition(${EXTENSION_CLASS.removeSuffix(";")}\$PlaylistController;)V"
    val transitions = dexFiles.flatMap { it.classes }.filter { !it.type.startsWith("Lapp/morphe/") }
        .flatMap { it.methods }.filter { method -> method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference?.toString() == transitionHook
        } == true }
    check(transitions.size == if (selected) 1 else 0) { "Unexpected native playback transition observer inclusion" }
    if (!selected) return
    val transition = transitions.single()
    check(transition.returnType == "V" && transition.parameterTypes == List(3) { "Ljava/lang/String;" }) {
        "Reload readiness must observe the playback descriptor transition, not a playlist model bind"
    }
    val transitionCode = transition.implementation!!.instructions.toList()
    check(transitionCode.indices.filter { transitionCode[it].opcode == Opcode.RETURN_VOID }.all { index ->
        index > 0 && (transitionCode[index - 1] as? ReferenceInstruction)?.reference?.toString() == transitionHook
    }) { "Every completed native transition, including standalone playback, must notify queue ownership" }
    println("Native playback transitions notify queue ownership even without a playlist model")
    val shufflePress = shufflePresses.single().implementation!!.instructions.toList()
    val shuffleIndex = shufflePress.indexOfFirst {
        (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(
            "$EXTENSION_CLASS->onQueueShuffle(") == true
    }
    check(shufflePress[shuffleIndex + 1].opcode == Opcode.MOVE_RESULT &&
            shufflePress[shuffleIndex + 2].opcode == Opcode.IF_EQZ &&
            shufflePress[shuffleIndex + 3].opcode == Opcode.RETURN_VOID &&
            shufflePress.drop(shuffleIndex + 4).any {
                (it as? ReferenceInstruction)?.reference?.toString() ==
                        "Landroid/widget/ImageView;->isSelected()Z"
            }) { "A consumed queue shuffle must return before the original fallback toggle" }
    val panelFlag = panels.single().methods.single { it.name == "patch_isQueueOwned" }
        .implementation!!.instructions.single { it.opcode == Opcode.IGET_BOOLEAN }
        .let { (it as ReferenceInstruction).reference as FieldReference }
    check(dexFiles.flatMap { it.classes }.flatMap { it.methods }.single { it.name == "patch_markQueueRows" && it.implementation != null }
        .implementation!!.instructions.any {
            it.opcode == Opcode.IPUT_BOOLEAN && (it as ReferenceInstruction).reference == panelFlag
        }) { "Publishing local rows must also mark the exact native header model" }
    verifyNativeShuffle(dexFiles.flatMap { it.classes })
    val clear = owners.single().methods.single { it.name == "patch_clearQueue" }
        .implementation!!.instructions.toList()
    val wrapper = owners.single()
    val autoplayType = wrapper.fields.single { it.name == "patch_lastAutoplay" }.type
    val clearCalls = clear.mapIndexedNotNull { index, instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { index to it }
    }
    val initialize = clearCalls.first { (_, reference) ->
        reference.definingClass == wrapper.type && reference.parameterTypes == listOf(autoplayType)
    }.first
    val releaseFlag = clear.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IPUT_BOOLEAN &&
                ((instruction as ReferenceInstruction).reference as FieldReference).name == "patch_localQueue"
    }
    val responseType = wrapper.methods.single { it.name == "patch_localPanel" }.returnType
    val hydrate = clearCalls.single { (_, reference) -> reference.parameterTypes == listOf(responseType) }.first
    val dispose = clearCalls.single { (_, reference) ->
        reference.name == "patch_disposeWithoutPlayback"
    }.first
    check(releaseFlag >= 0 && releaseFlag < initialize && initialize < hydrate && hydrate < dispose) {
        "Clear queue must release local interception, hydrate ordinary navigation, then dispose the old queue"
    }
    val guardedDispose = dexFiles.flatMap { it.classes }.flatMap { it.methods }
        .single { it.name == "patch_disposeWithoutPlayback" && it.implementation != null }
    val guardedCalls = guardedDispose.implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)
    }.toList()
    val beginChange = dexFiles.flatMap { it.classes }.flatMap { it.methods }
        .single { it.toString() == guardedCalls.first().toString() }
    val acquireChange = beginChange.implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)
    }.single { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;" }
    check(guardedCalls.size == 4 && guardedCalls[2] == guardedCalls[3] &&
            guardedCalls[2].definingClass == acquireChange.definingClass &&
            guardedCalls[2].parameterTypes == listOf("Ljava/lang/Object;") &&
            guardedDispose.implementation!!.tryBlocks.single().exceptionHandlers.single().exceptionType == null) {
        "Native disposal must acquire the existing queue guard and release it on success and failure"
    }
    println("Native clear queue restores ordinary navigation and guards selected-item callbacks during disposal")
    val providers: List<ClassProvider> = dexFiles.map(::DexClassProvider)
    val classPath = ClassPath(providers)
    val bridge = owners.single().methods.single { it.name == "patch_localPanel" }
    val analyzer = MethodAnalyzer(classPath, bridge, null, false)
    analyzer.analysisException?.let { throw it }
    for (instruction in analyzer.analyzedInstructions) {
        val store = instruction.instruction
        if (store.opcode != Opcode.IPUT_OBJECT) continue
        val field = (store as ReferenceInstruction).reference as FieldReference
        val value = instruction.getPreInstructionRegisterType((store as TwoRegisterInstruction).registerA)
        val valueType = value.type
        check(value.category == RegisterType.NULL || value.category == RegisterType.REFERENCE &&
                valueType != null && classPath.getClass(field.type).getCommonSuperclass(valueType).type == field.type) {
            "Native playback bridge stores $value in incompatible field $field"
        }
    }
    println("Native playback panel field assignments: compatible register types")
}

/** A local shuffle command changes the rows; native playback must then follow their visible order. */
private fun verifyNativeShuffle(classes: List<ClassDef>) {
    val mutableQueue = classes.single { type -> type.methods.any { it.name == "patch_clearShuffleOrder" } }
    val clear = mutableQueue.methods.single { it.name == "patch_clearShuffleOrder" }
        .implementation!!.instructions.toList()
    val disableIndex = clear.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL }
    val disable = (clear[disableIndex] as ReferenceInstruction).reference as MethodReference
    val nativeSetter = mutableQueue.methods.single { it.toString() == disable.toString() }
    check(disable.parameterTypes == listOf("Z") && disable.returnType == "V" &&
            (clear[disableIndex - 1] as NarrowLiteralInstruction).narrowLiteral == 0) {
        "Shuffle cache reset must disable the original mutable shuffler first"
    }
    fun linkFields(methodInstructions: Iterable<Instruction>) =
        methodInstructions.filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull {
            ((it as ReferenceInstruction).reference as FieldReference).takeIf { field -> field.type == "[I" }
        }.toSet()
    val resetLinks = linkFields(clear)
    check(resetLinks.size == 2 && resetLinks == linkFields(nativeSetter.implementation!!.instructions) &&
            clear.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT } > disableIndex) {
        "Shuffle reset must invalidate both original index maps after disabling their use"
    }
    val playback = classes.single { type -> type.methods.any {
        it.name == "patch_attachQueue" && it.implementation != null
    } }
    val attach = playback.methods.single { it.name == "patch_attachQueue" }.implementation!!.instructions.toList()
    val responseType = playback.methods.single { it.name == "patch_localPanel" }.returnType
    fun reference(index: Int) = (attach[index] as? ReferenceInstruction)?.reference as? MethodReference
    val clearIndex = attach.indices.single { reference(it)?.name == "patch_clearShuffleOrder" }
    val acceptIndex = attach.indices.single { index -> reference(index)?.let {
        it.returnType == "V" && it.parameterTypes == listOf(responseType)
    } == true }
    check(clearIndex < acceptIndex && attach.indices.none { index -> reference(index)?.let {
        it.name == disable.name && it.parameterTypes == listOf("Z") && it.returnType == "V"
    } == true }) {
        "Local rows must disable hidden shuffle before replacement and must not restore it afterward"
    }
    check(playback.methods.single { it.name == "patch_updateSnapshot" }.implementation!!.instructions.none {
        (it as? ReferenceInstruction)?.reference?.toString()?.contains("patch_clearShuffleOrder") == true
    }) { "Metadata-only publication must not reset shuffle order" }
    println("Native shuffle command: original index maps disabled, visible row order controls playback")
}

/** Undo consumes only local capabilities and preserves the modern snackbar click continuation. */
private fun verifyNativeQueueUndo(directory: File, selected: Boolean) {
    val extensionOwner = EXTENSION_CLASS.removePrefix("L").removeSuffix(";")
    val classes = directory.listFiles()!!.filter { it.extension == "dex" }.flatMap { file ->
        file.inputStream().buffered().use {
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it).classes.toList()
        }
    }
    val commandInterface = "Lapp/morphe/extension/youtube/patches/queue/QueueUndoActions\$Command;"
    val commandTypes = classes.filter { commandInterface in it.interfaces }
    val nativeMethods = classes.filter { !it.type.startsWith("Lapp/morphe/") }.flatMap { it.methods }
    val closeHook = "L$extensionOwner;->onQueuePlayerClosing()V"
    val closeHandlers = nativeMethods.filter { method -> method.implementation?.instructions?.any {
        (it as? ReferenceInstruction)?.reference?.toString() == closeHook
    } == true }
    val closeFeedback = nativeMethods.filter { it.name == "patch_showQueueFeedback" }
    check(closeHandlers.size == if (selected) 3 else 0) { "Unexpected queue player-close interception" }
    check(closeFeedback.size == if (selected) 1 else 0) { "Unexpected queue player-close feedback bridge" }
    if (selected) {
        val click = closeHandlers.single { it.name == "onClick" &&
                it.parameterTypes == listOf("Landroid/view/View;") }
        val code = click.implementation!!.instructions.toList()
        val hookIndex = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == closeHook }
        val original = (code[hookIndex + 1] as ReferenceInstruction).reference as MethodReference
        check(code[hookIndex].opcode == Opcode.INVOKE_STATIC &&
                (code[hookIndex] as FiveRegisterInstruction).registerCount == 0 &&
                code[hookIndex + 1].opcode == Opcode.INVOKE_VIRTUAL &&
                original.parameterTypes.isEmpty() && original.returnType == "V" &&
                code[hookIndex + 2].opcode == Opcode.RETURN_VOID) {
            "Player-close capture must preserve the original dismissal and listener return"
        }
        val fling = closeHandlers.single { method ->
            method.name == "onFling" && method.returnType == "Z" && method.parameterTypes ==
                    listOf("Landroid/view/MotionEvent;", "Landroid/view/MotionEvent;", "F", "F") ||
                    method.returnType == "V" && method.parameterTypes == listOf("Landroid/view/MotionEvent;", "F", "F")
        }
        if (fling.name != "onFling") {
            check(nativeMethods.count { method -> method.name == "onFling" &&
                    method.implementation?.instructions?.any {
                        (it as? ReferenceInstruction)?.reference?.toString() == fling.toString()
                    } == true } == 1) { "Legacy accepted-dismiss helper must belong to the native fling callback" }
        }
        val touch = closeHandlers.single { it.returnType == "Z" &&
                it.parameterTypes == listOf("Landroid/view/View;", "Landroid/view/MotionEvent;") }
        for ((gesture, expectedCalls) in listOf(fling to 2, touch to 1)) {
            val instructions = gesture.implementation!!.instructions.toList()
            val dismissCalls = instructions.indices.filter {
                (instructions[it] as? ReferenceInstruction)?.reference?.toString() == original.toString()
            }
            val captures = instructions.indices.filter {
                (instructions[it] as? ReferenceInstruction)?.reference?.toString() == closeHook
            }
            check(dismissCalls.size == expectedCalls && captures == dismissCalls.map { it - 1 } &&
                    captures.all { instructions[it].opcode == Opcode.INVOKE_STATIC &&
                            (instructions[it] as FiveRegisterInstruction).registerCount == 0 } &&
                    dismissCalls.all { instructions[it].opcode == Opcode.INVOKE_VIRTUAL &&
                            instructions[it + 1].opcode in if (gesture.returnType == "V") setOf(Opcode.RETURN_VOID)
                            else setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) }) {
                "Accepted gesture dismissals must each capture Undo and keep their original continuation"
            }
        }
        val feedback = closeFeedback.single().implementation!!.instructions.toList()
        val calls = feedback.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        val commandType = commandTypes.single().type
        val registryType = "Lcom/google/protobuf/ExtensionRegistryLite;"
        check(feedback.any { it.opcode == Opcode.SGET_OBJECT &&
                ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type == commandType
        } && calls.any { it.definingClass == registryType && it.name == "getGeneratedRegistry" } &&
                calls.any { it.name == "parseFrom" && it.parameterTypes.size == 3 &&
                        it.parameterTypes.drop(1) == listOf("[B", registryType) &&
                        it.returnType == it.parameterTypes.first()
                } && feedback.any { it.opcode == Opcode.CHECK_CAST &&
                        ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == commandType
                } && calls.any {
            it.definingClass == "Ljava/util/Collections;" && it.name == "emptyMap"
        } && calls.last().parameterTypes == listOf(commandType, "Ljava/util/Map;")) {
            "Player-close feedback must use the native command parser and activity dispatcher"
        }
        println("Queue player-close: click, fling and drag keep native dismissal; feedback uses native parser and dispatcher")
    }
    val snackbars = classes.filter { !it.type.startsWith("Lapp/morphe/") }.flatMap { it.methods }.filter { method ->
        method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(
                "L$extensionOwner;->onQueueUndoCommand(") == true
        } == true
    }
    val durationHook = "Lapp/morphe/extension/youtube/patches/queue/QueueSaveFeedback;->confirmationDuration(Lcom/google/protobuf/MessageLite;)I"
    val durationFactories = classes.filter { !it.type.startsWith("Lapp/morphe/") }.flatMap { it.methods }
        .filter { method -> method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference?.toString() == durationHook
        } == true }
    check(durationFactories.size == if (selected) 1 else 0) { "Unexpected queue save duration hook inclusion" }
    if (selected) {
        val factory = durationFactories.single()
        val code = factory.implementation!!.instructions.toList()
        val hook = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == durationHook }
        val guard = (hook + 1 until code.size).first { code[it].opcode == Opcode.IF_LEZ }
        val setter = (guard + 1 until code.size).first { code[it].opcode == Opcode.INVOKE_VIRTUAL }
        val reference = (code[setter] as ReferenceInstruction).reference as MethodReference
        val guardAddress = code.take(guard).sumOf { it.codeUnits }
        val skipTarget = guardAddress + (code[guard] as OffsetInstruction).codeOffset
        check(reference.definingClass == factory.returnType && reference.parameterTypes == listOf("I") &&
                reference.returnType == "V" && skipTarget > code.take(setter).sumOf { it.codeUnits }) {
            "Ordinary feedback must bypass the native duration setter"
        }
        val returned = (code.last() as OneRegisterInstruction).registerA
        check(code.last().opcode == Opcode.RETURN_OBJECT &&
                (code[setter] as FiveRegisterInstruction).registerC == returned) {
            "Queue save duration must update and return the original native snackbar builder"
        }
        println("Queue save duration: owned confirmation guard and original native builder setter verified")
    }
    if (!selected) {
        check(commandTypes.isEmpty() && snackbars.isEmpty()) { "Queue Undo hooks leaked into an unselected build" }
        return
    }
    val command = commandTypes.single()
    val parser = command.methods.single { it.name == "patch_parseQueueCommand" }
    check(parser.implementation!!.instructions.any {
        (it as? ReferenceInstruction)?.reference?.toString() ==
                "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;"
    }) { "Queue feedback parser lost the native extension registry" }
    val instructions = snackbars.single().implementation!!.instructions.toList()
    check(instructions.none {
        (it as? ReferenceInstruction)?.reference?.toString()?.contains("quantum/snackbar/Snackbar;") == true
    }) { "Queue Undo still targets the unrelated legacy share-panel snackbar" }
    val hooks = instructions.indices.filter { index ->
        (instructions[index] as? ReferenceInstruction)?.reference?.toString()?.startsWith(
            "L$extensionOwner;->onQueueUndoCommand(") == true
    }
    check(hooks.size == 3) { "Modern snackbar must handle all three endpoint slots" }
    for (hook in hooks) {
        check(instructions[hook + 1].opcode == Opcode.MOVE_RESULT &&
                instructions[hook + 2].opcode == Opcode.IF_NEZ &&
                instructions[hook + 3].opcode == Opcode.INVOKE_INTERFACE)
        val original = (instructions[hook + 3] as ReferenceInstruction).reference as MethodReference
        check(original.parameterTypes == listOf(command.type, "Ljava/util/Map;"))
        val nativeCall = instructions[hook + 3] as FiveRegisterInstruction
        val undoCall = instructions[hook] as FiveRegisterInstruction
        val result = (instructions[hook + 1] as OneRegisterInstruction).registerA
        check(undoCall.registerC == nativeCall.registerD &&
                result !in setOf(nativeCall.registerC, nativeCall.registerD, nativeCall.registerE)) {
            "Modern snackbar Undo must preserve every original callback input"
        }
        fun address(index: Int) = instructions.take(index).sumOf { it.codeUnits }
        check(address(hook + 2) + (instructions[hook + 2] as OffsetInstruction).codeOffset == address(hook + 4)) {
            "Consumed Undo must skip only its native dispatch and retain remaining click behavior"
        }
    }
    println("Queue Undo: modern snackbar's three slots, native registry, original callbacks and click continuation verified")
}
