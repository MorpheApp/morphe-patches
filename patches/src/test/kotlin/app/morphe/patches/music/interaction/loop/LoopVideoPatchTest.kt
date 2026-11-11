/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.loop

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patches.music.layout.sponsorblock.musicSponsorBlockPatch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import java.io.File

class LoopVideoPatchTest {
    @Test
    fun `loop video patch applies and builds APK`() {
        val apkPath = System.getProperty("jamApk")
        assumeTrue(!apkPath.isNullOrBlank()) {
            "Pass -PjamApk to verify loop video patch"
        }
        val workspace = createTempDirectory("loop-video-test")
        Patcher(PatcherConfig(Path(apkPath).toFile(), workspace.toFile())).use { patcher ->
            patcher += setOf(loopVideoPatch, musicSponsorBlockPatch)
            runBlocking {
                patcher().collect { result ->
                    result.exception?.let { throw it }
                    println("Applied: ${result.patch.name}")
                }
            }
            val outputApk = workspace.resolve("patched.apk").toFile()
            patcher.get().applyTo(outputApk)
            println("Patched APK: $outputApk")
        }
    }
}
