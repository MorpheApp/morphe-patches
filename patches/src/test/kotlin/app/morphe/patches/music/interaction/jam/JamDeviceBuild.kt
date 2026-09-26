/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patches.all.misc.clone.cloneAppPatch
import app.morphe.patches.music.ad.hideAdsPatch
import app.morphe.patches.music.layout.lyrics.lyricsPatch
import app.morphe.patches.music.layout.miniplayer.miniplayerPreviousNextButtonsPatch
import app.morphe.patches.music.misc.backgroundplayback.backgroundPlaybackPatch
import app.morphe.patches.music.misc.gms.gmsCoreSupportPatch
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

private const val PROBE_PACKAGE = "app.morphe.jam.next.music"

fun main(arguments: Array<String>) {
  require(arguments.size in 1..2) { "Usage: JamDeviceBuildKt <input-apk> [output-apk]" }

  val input = File(arguments[0]).canonicalFile
  require(input.isFile) { "Input APK does not exist: $input" }

  val output = arguments.getOrNull(1)?.let(::File)?.canonicalFile
  output?.parentFile?.mkdirs()

  cloneAppPatch.options["packageName"] = PROBE_PACKAGE

  val selectedPatches =
      setOf(
          gmsCoreSupportPatch,
          hideAdsPatch,
          jamQueueProbePatch,
          lyricsPatch,
          miniplayerPreviousNextButtonsPatch,
          backgroundPlaybackPatch,
      )
  val workspace = Files.createTempDirectory("jam-device-build")

  val sdk =
      System.getenv("ANDROID_HOME")
          ?: System.getenv("ANDROID_SDK_ROOT")
          ?: error(
              "Jam release validation requires ANDROID_HOME or ANDROID_SDK_ROOT for DEX verification"
          )
  Patcher(PatcherConfig(input, workspace.toFile(), verifier = SdkDexVerifier(File(sdk)))).use {
      patcher ->
    patcher += selectedPatches
    runBlocking {
      patcher().collect { result ->
        result.exception?.let { throw it }
        println("Applied: ${result.patch.name}")
      }
    }

    val patched = patcher.get()
    if (output == null) {
      println("Jam patch applied and DEX verified with Android SDK tools: $workspace")
    } else {
      patched.applyTo(output)
      println("Unsigned isolated device probe: $output")
    }
  }
}
