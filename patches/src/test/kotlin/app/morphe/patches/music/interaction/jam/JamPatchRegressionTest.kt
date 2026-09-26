/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.getMutableMethod
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

class JamPatchRegressionTest {
  @Test
  fun `Jam accepts only the exact validated versions`() {
    for (version in listOf("9.15.51", "9.35.54", "9.36.50", "9.37.54")) {
      assertTrue(isSupportedJamVersion(version), version)
    }
    for (version in listOf("9.15", "9.15.52", "9.34.52", "9.35.55", "9.37.55", "9.38.1", "10.0.0")) {
      assertFalse(isSupportedJamVersion(version), version)
    }
  }

  @Test
  fun `both player layouts resolve clicks and share the native state renderer`() {
    val apkPath = System.getProperty("jamApk")
    assumeTrue(!apkPath.isNullOrBlank()) { "Pass -PjamApk to verify both native player layouts" }
    val fixture = bytecodePatch {
      dependsOn(resourceMappingPatch)
      execute {
        val ui = resolveJamUiAbi(resolveJamQueueAbi())
        val presenter = PlayerMetadataViewsFingerprint.matchSingle().originalClassDef
        val controls = PlaybackControlViewsFingerprint.matchSingle().originalClassDef
        val miniClicks = playbackButtonClickFingerprint(presenter.type).matchAll()
        val fullClicks = playbackControlsClickFingerprint(controls.type).matchAll()
        for (click in miniClicks + fullClicks) {
          assertTrue(ui.buttons.any { it.click == click.originalMethod })
        }
        // Both surfaces must feed the same renderer. Otherwise only one icon would mirror.
        for (owner in listOf(presenter, controls)) {
          assertTrue(owner.fields.any { it.type == ui.playbackIcon.render.definingClass })
        }
        assertTrue(ui.playbackIcon.playing != ui.playbackIcon.paused)
      }
    }
    val workspace = createTempDirectory("jam-player-layouts")
    Patcher(PatcherConfig(kotlin.io.path.Path(apkPath).toFile(), workspace.toFile())).use { patcher ->
      patcher += setOf(fixture)
      runBlocking {
        patcher().collect { result ->
          assertTrue(result.exception == null, result.exception?.stackTraceToString().orEmpty())
        }
      }
    }
  }

  @Test fun `queue ABI rejects a missing required constructor`() = rejectInvalidConstructor(false)

  @Test fun `queue ABI rejects ambiguous constructors`() = rejectInvalidConstructor(true)

  @Test
  fun `dispatcher resolution ignores unused merged fields and rejects ambiguous enqueue fields`() {
    val apkPath = System.getProperty("jamApk")
    assumeTrue(!apkPath.isNullOrBlank()) { "Pass -PjamApk to exercise real APK resolver failures" }
    val fixture = bytecodePatch {
      execute {
        val queue = resolveJamQueueAbi()
        val manager = mutableClassDefBy(queue.managerType)
        val extraDispatcher =
            ImmutableField(
                    manager.type,
                    "jamUnusedDispatcher",
                    queue.menu.dispatcher.type,
                    AccessFlags.PRIVATE.value,
                    null,
                    null,
                    null,
                )
                .toMutable()
        manager.fields.add(extraDispatcher)

        // A same-type field alone must not change which native dispatcher is selected.
        assertEquals(queue.menu.dispatcher, resolveJamQueueAbi().menu.dispatcher)

        // This intentionally invalid fixture is inspected only, never serialized or installed.
        queue.enqueue.getMutableMethod().addInstructions(
            0,
            "iget-object v0, p0, $extraDispatcher",
        )
        val failure = assertFailsWith<IllegalStateException> { resolveJamQueueAbi() }
        assertTrue(failure.message.orEmpty().contains("dispatcher field"))
      }
    }
    val workspace = createTempDirectory("jam-dispatcher-resolution")
    Patcher(PatcherConfig(kotlin.io.path.Path(apkPath).toFile(), workspace.toFile())).use { patcher ->
      patcher += setOf(fixture)
      runBlocking {
        patcher().collect { result ->
          assertTrue(result.exception == null, result.exception?.stackTraceToString().orEmpty())
        }
      }
    }
  }

  private fun rejectInvalidConstructor(ambiguous: Boolean) {
    val apkPath = System.getProperty("jamApk")
    assumeTrue(!apkPath.isNullOrBlank()) { "Pass -PjamApk to exercise real APK resolver failures" }
    val fixture = bytecodePatch {
      execute {
        val managerType = QueueEnqueueFingerprint.matchSingle().originalClassDef.type
        val manager = mutableClassDefBy(managerType)
        if (ambiguous) {
          // Deliberately invalid native capability, never serialized or installed.
          manager.addBridge("<init>", listOf("Ljava/lang/Throwable;"), "V", 2, body = "return-void")
        } else {
          manager.methods.removeAll { it.name == "<init>" }
        }
        assertFailsWith<PatchException> { resolveJamQueueAbi() }
      }
    }
    val workspace = createTempDirectory("jam-negative-resolution")
    Patcher(PatcherConfig(kotlin.io.path.Path(apkPath).toFile(), workspace.toFile())).use { patcher
      ->
      patcher += setOf(fixture)
      runBlocking {
        patcher().collect { result ->
          assertTrue(result.exception == null, result.exception?.stackTraceToString().orEmpty())
        }
      }
    }
  }

  @Test
  fun `Jam discovery has no version branches or implementation class anchors`() {
    val sourceRoot = kotlin.io.path.Path("src/main/kotlin/app/morphe/patches/music/interaction/jam")
    val prohibited =
        listOf(
            Regex("\\b\\d+\\.\\d+\\.\\d+\\b"),
            Regex("MusicPlaybackControls|WatchFragment|AutoCropImageView"),
            Regex("\\.(?:firstOrNull|first|lastOrNull|last)\\s*\\("),
            Regex(
                "\\b(?:methodKey|sameMethod|sameSignature|requireSingle|methodReferences|fieldReferences)\\s*\\("
            ),
            Regex("\\.methods\\.(?:filter|single|first)"),
            Regex("runCatching"),
            Regex("name\\s*=\\s*\"[a-z]{1,2}\""),
            Regex("const\\s+val\\s+\\w*(?:METHOD|CLASS|TYPE)\\w*\\s*=\\s*\"[a-z]{1,6}\""),
        )
    val violations =
        sourceRoot
            .walk()
            .filter {
              it.fileName.toString().endsWith("Fingerprints.kt") ||
                  it.fileName.toString().endsWith("Abi.kt")
            }
            .flatMap { path ->
              val name = path.fileName
              val source = path.readText()
              prohibited.flatMap { pattern ->
                pattern.findAll(source).map { "$name: ${it.value}" }.toList()
              }
            }
    assertTrue(violations.toList().isEmpty(), violations.joinToString("\n"))
  }

  @Test
  fun `Jam patch sources do not encode host obfuscation descriptors`() {
    val sourceRoot = kotlin.io.path.Path("src/main/kotlin/app/morphe/patches/music/interaction/jam")
    val descriptors = Regex("(?<![A-Za-z0-9_/])L[a-z]{1,6};")
    val matches =
        sourceRoot
            .walk()
            .filter { it.toString().endsWith(".kt") }
            .flatMap { source ->
              descriptors.findAll(source.readText()).map { "${source.fileName}: ${it.value}" }
            }
            .toList()
    assertTrue(
        matches.isEmpty(),
        "Jam patch sources contain host ABI literals: ${matches.joinToString()}",
    )
  }

  @Test
  fun `Jam fingerprints resolve against the supplied target APK`() {
    val apkPath = System.getProperty("jamApk")
    assumeTrue(!apkPath.isNullOrBlank()) {
      "Jam APK resolution test skipped; pass -PjamApk=/absolute/path/to/ytm.apk"
    }
    val workspace = createTempDirectory("jam-patch-resolution")
    Patcher(PatcherConfig(kotlin.io.path.Path(apkPath).toFile(), workspace.toFile())).use { patcher
      ->
      patcher += setOf(jamQueueProbePatch)
      runBlocking {
        patcher().collect { result ->
          assertTrue(result.exception == null, result.exception?.stackTraceToString().orEmpty())
        }
      }
      patcher.get()
    }
  }
}
