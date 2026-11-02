/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.watchlater

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.dex.DexVerifier
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patches.youtube.interaction.feedactions.feedVideoActionsPatch
import app.morphe.patches.youtube.interaction.savetowatchlater.saveToWatchLaterButtonPatch
import app.morphe.patches.youtube.layout.flyout.addToQueuePatch
import app.morphe.patches.youtube.layout.hide.general.hideLayoutComponentsPatch
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory

/** Verify the emitted hooks, including hooks installed after cloneParameters replaces a method. */
fun main(arguments: Array<String>) {
    require(arguments.size in 1..2) { "Usage: WatchLaterApkBuildKt <stock-apk> [standalone|combined]" }
    val input = File(arguments[0]).canonicalFile
    require(input.isFile)
    val combined = when (arguments.getOrElse(1) { "standalone" }) {
        "standalone" -> false
        "combined" -> true
        else -> error("Unknown mode")
    }
    val sdk = File(System.getenv("ANDROID_HOME") ?: error("ANDROID_HOME is required"))
    val workspace = createTempDirectory("watch-later-apk").toFile()
    val nativeVerifier = SdkDexVerifier(sdk)
    val verifier = object : DexVerifier by nativeVerifier {
        override fun verifyDexDirectory(dexDir: File) {
            nativeVerifier.verifyDexDirectory(dexDir)
            verifyWatchLaterHooks(dexDir)
        }
    }
    val patches = if (combined) setOf(watchLaterPatch, feedVideoActionsPatch,
        saveToWatchLaterButtonPatch, hideLayoutComponentsPatch, addToQueuePatch) else setOf(watchLaterPatch)
    Patcher(PatcherConfig(input, workspace, verifier = verifier, validate = true)).use { patcher ->
        patcher += patches
        runBlocking { patcher().collect { it.exception?.let { error -> throw error } } }
        patcher.get()
    }
    println("Watch later ${if (combined) "combined" else "standalone"} APK hooks and SDK verification passed")
}

private fun verifyWatchLaterHooks(directory: File) {
    val extension = "Lapp/morphe/extension/youtube/patches/WatchLaterPatch;"
    val methods = directory.listFiles()!!.filter { it.extension == "dex" }.flatMap { file ->
        file.inputStream().buffered().use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it) }
            .classes.filter { !it.type.startsWith("Lapp/morphe/") }.flatMap { it.methods }
    }
    val hooks = methods.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                ?.takeIf { it.definingClass == extension }?.let { it.name to method }
        } ?: emptyList()
    }.groupBy({ it.first }, { it.second })
    for (name in listOf("onHostLayout", "interceptHandleTouch", "widenDragHandle", "onEditCreated",
        "onEditResult", "allowElementDrag")) {
        check(hooks[name]?.size == 1) { "Missing or duplicated emitted Watch later hook: $name" }
    }
    check(hooks["onHostLayout"]!!.single().let {
        it.definingClass == "Lcom/facebook/litho/ComponentHost;" && it.name == "onLayout"
    }) { "The layout hook must be installed on the replacement method, not the discarded original" }
    check(hooks["widenDragHandle"]!!.single().name == "<init>")
    check((hooks["enableLongPressDrag"]?.size ?: 0) >= 2)
}
