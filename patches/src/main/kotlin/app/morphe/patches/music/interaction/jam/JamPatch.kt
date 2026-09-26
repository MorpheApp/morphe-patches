/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

/**
 * # Jam patch boundary
 *
 * `JamPatch.kt` wires dependencies, resources and preferences. `Fingerprints.kt` contains semantic
 * entry points: queue diagnostics, platform calls, and native view resource names. `JamAbi.kt` and
 * `JamUiAbi.kt` resolve native relationships from those matches. They must fail on missing or
 * ambiguous targets; do not add obfuscated class/method names or select an arbitrary candidate to
 * make a version patch successfully.
 *
 * The installers add casts, field accessors, native calls and interception points. `JamBridge.kt`
 * contains their shared method emitter. Use the project's `BytecodeUtils` instruction matching and
 * reverse-index helpers for insertion; keep queue policy and UI behavior in
 * `extensions/music/.../jam`.
 *
 * In particular:
 * - Host playback uses `MediaController.TransportControls.skipToQueueItem` with the native
 *   persistent item ID. The queue mutation notifier is **not** a play operation. Never substitute
 *   an enqueue endpoint on playback failure.
 * - `JamClock.playHost` confirms the selected ID and playing state off the native queue executor.
 *   Paused selection is resumed only once the requested ID is active.
 * - Player metadata is handled by `JamMetadata`, which overrides the actual full-player and
 *   mini-player `setText` calls. The presenter is found through `player_page`, `mini_player_title`
 *   and `mini_player_subtitle` resources. All caching, text selection and restoration run in Java
 *   on the UI thread; no timer races or custom text-register dataflow analysis are needed.
 * - Queue lane selection, bounds checking and reorder predecessor calculation are extension
 *   interface defaults. Smali only exposes native primitives.
 *
 * The native ABI resolver is still substantial because Jam mirrors YTM's native observable queue,
 * item models, gestures and player components. Removing literal obfuscation names does not prove
 * compatibility with uninspected versions. Runtime integration remains opt-in. Compatibility is
 * restricted to the exact APK versions that have passed the local validation gates.
 *
 * ## Compatibility and release gates
 *
 * YT Music 9.15.51 ARM64 remains the baseline. Versions 9.35.54, 9.36.50 and 9.37.54 are
 * experimental targets with local patch, SDK DEX verification and APK construction evidence.
 * The user reports device tests passing on 9.15.51 and 9.37.54; the other two remain pending.
 * See docs/jam-validation.md for the validation evidence and remaining limitations.
 *
 * Discovery follows `APK -> Fingerprint -> JamQueueAbi/JamUiAbi -> validation -> installation`.
 * Dynamic fingerprints may consume earlier resolved types. Android calls, diagnostics, resources,
 * `methodCall` and `fieldAccess` relationships take precedence over implementation names. Clock
 * model construction and inaccessible native operations remain bridges; clock defaults, drag
 * policy, metadata fallback and queue decisions live in extension Java. No obfuscated version
 * branches are permitted. More than one valid semantic candidate must fail, including within an
 * alternative resolver; an unrelated candidate with no capability may be discarded.
 *
 * `JamPatchRegressionTest` uses the real patch and supplied APK, including negative fixtures with
 * missing and duplicate required constructors. `validateJam` requires an Android SDK and uses
 * `SdkDexVerifier` before constructing the output APK. A normal Gradle build alone is not release
 * evidence. Run it with `-PjamApk` and `-PjamOutput`; omitting the APK skips fixture tests and
 * cannot satisfy release gates.
 *
 * Every additional experimental version needs unique resolution, ABI validation, full patch
 * application, SDK bytecode verification and APK construction. Promotion from experimental support
 * also requires installation and the same two-device smoke matrix as the baseline. Preserve the
 * experimental target flag until that evidence exists for the exact version.
 *
 * The local Binder bridge advertises additive protocol version 1 and capabilities
 * `queue-revisions`, `stable-item-ids` and `stale-edit-rejection`. Missing envelopes are treated as
 * the original v1 bridge; malformed envelopes, unsupported required versions and missing
 * capabilities are rejected. Network framing is unchanged. Pairing binds the selected package names
 * to a locally generated capability token. Companion forks may use their own signing keys without
 * modifying the patch.
 *
 * Experimental prereleases may be published after the local gates for user device testing. Test
 * the published prerelease through a clean Morphe Manager setup, including source metadata, patch
 * discovery, dependency resolution, APK building, installation, two-device pairing and reconnect.
 * Local artifacts do not satisfy that consumer gate. Keep the upstream PR draft and its review
 * threads unresolved until the coordinated evidence supports the responses.
 *
 * Device testing is performed manually by the user for this release. Never infer a pass from
 * installation or a build. Remote transport controls, bulk/playlist/offline enqueue, exhaustive
 * Doze/network stress, separate radio-loss failover and physical camera validation remain
 * documented limitations.
 */
package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import java.util.logging.Logger

internal val supportedJamVersions = setOf("9.15.51", "9.35.54", "9.36.50", "9.37.54")

internal fun isSupportedJamVersion(version: String): Boolean = version in supportedJamVersions

private fun warnUnsupportedJamVersion(version: String) {
  Logger.getLogger("Jam queue sharing").warning(
      "Skipping Jam queue sharing on YouTube Music $version. " +
          "Supported versions: ${supportedJamVersions.joinToString()}. No Jam changes will be applied."
  )
}

private val jamResources = resourcePatch {
  dependsOn(versionCheckPatch)
  execute {
    if (!isSupportedJamVersion(packageMetadata.versionName)) {
      return@execute warnUnsupportedJamVersion(packageMetadata.versionName)
    }
    document("AndroidManifest.xml").use { doc ->
      val permissions = doc.getElementsByTagName("uses-permission")
      if (
          (0 until permissions.length).none {
            (permissions.item(it) as org.w3c.dom.Element).getAttribute("android:name") ==
                "android.permission.ACCESS_WIFI_STATE"
          }
      ) {
        doc.documentElement.appendChild(
            doc.createElement("uses-permission").apply {
              setAttribute("android:name", "android.permission.ACCESS_WIFI_STATE")
            }
        )
      }
      val service = doc.createElement("service")
      service.setAttribute("android:name", "app.morphe.extension.music.jam.JamBridgeService")
      service.setAttribute("android:exported", "true")
      doc.getElementsByTagName("application").item(0).appendChild(service)
      val queries =
          doc.getElementsByTagName("queries").item(0)
              ?: doc.createElement("queries").also { doc.documentElement.appendChild(it) }
      queries.appendChild(
          doc.createElement("package").apply {
            setAttribute("android:name", "app.morphe.jam.companion")
          }
      )
    }
    document("res/layout/player_bottom_sheet.xml").use { doc ->
      val root = doc.documentElement
      val tabs =
          (0 until root.childNodes.length)
              .map { root.childNodes.item(it) }
              .filterIsInstance<org.w3c.dom.Element>()
              .single { it.getAttribute("android:id") == "@id/bottom_sheet_tabbed_view" }
      root.insertBefore(
          doc.createElement("app.morphe.extension.music.jam.JamBar").apply {
            setAttribute("android:layout_width", "match_parent")
            setAttribute("android:layout_height", "48dp")
          },
          tabs,
      )
    }
    document("res/layout/watch_while_layout.xml").use { doc ->
      doc.documentElement.setAttribute("app:bottomSheetPeekHeight", "68dp")
    }
  }
}

/**
 * Connects semantic YouTube Music anchors to the stable Jam extension API. Native discovery is kept
 * in [JamAbi] and [JamUiAbi]; generated methods are small access or interception bridges.
 */
@Suppress("unused")
val jamQueueProbePatch =
    bytecodePatch(
        name = "Jam queue sharing",
        description =
            "Shares the host queue and playback controls through an authenticated Jam bridge. " +
                "Supports YouTube Music 9.15.51; 9.35.54, 9.36.50 and 9.37.54 are experimental " +
                "and require device testing. Other versions are skipped with a warning. " +
                "Root installation is not supported.",
        default = true,
    ) {
      dependsOn(
          sharedExtensionPatch,
          settingsPatch,
          jamResources,
          musicVideoInformationPatch,
          resourceMappingPatch,
          versionCheckPatch,
      )
      compatibleWith(
          Compatibility(
              name = "YouTube Music",
              packageName = "com.google.android.apps.youtube.music",
              apkFileType = COMPATIBILITY_YOUTUBE_MUSIC.apkFileType,
              signatures = COMPATIBILITY_YOUTUBE_MUSIC.signatures,
              targets =
                  COMPATIBILITY_YOUTUBE_MUSIC.targets.filter {
                    it.version in supportedJamVersions
                  },
          )
      )

      execute {
        if (!isSupportedJamVersion(packageMetadata.versionName)) {
          return@execute warnUnsupportedJamVersion(packageMetadata.versionName)
        }
        val baseQueue = resolveJamQueueAbi()
        val ui = resolveJamUiAbi(baseQueue)
        val queue =
            baseQueue.copy(item = baseQueue.item.copy(menuPayload = ui.queueRow.menuPayload))

        installJamQueueBridges(queue)
        installJamUiBridges(ui, queue)

        MusicActivityOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, Lapp/morphe/extension/music/jam/JamUi;->install(Landroid/app/Activity;)V",
        )
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_jam_probe",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences =
                    setOf(
                        SwitchPreference(key = "morphe_music_jam_enabled", summary = true),
                        NonInteractivePreference(
                            key = "morphe_music_jam_download",
                            tag = "app.morphe.extension.music.jam.JamDownloadPreference",
                            selectable = true,
                        ),
                        NonInteractivePreference(
                            key = "morphe_music_jam_controls",
                            tag = "app.morphe.extension.music.jam.JamProbePreference",
                            selectable = true,
                        ),
                        NonInteractivePreference(
                            key = "morphe_music_jam_companion_package",
                            tag = "app.morphe.extension.music.jam.JamCompanionPackagePreference",
                            selectable = true,
                        ),
                    ),
            )
        )
      }
    }
