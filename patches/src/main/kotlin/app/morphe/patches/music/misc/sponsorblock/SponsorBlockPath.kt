package app.morphe.patches.music.misc.sponsorblock

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.videoIdHook
import app.morphe.patches.music.video.information.videoInformationPatch
import app.morphe.patches.music.video.information.videoTimeHook
import app.morphe.util.adoptChild
import app.morphe.util.fingerprint.matchOrThrow
import app.morphe.util.fingerprint.methodOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

// The descriptor for the Java extension class that handles all SponsorBlock logic at runtime.
private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/sponsorblock/SegmentPlaybackController;"

// ─────────────────────────────────────────────────────────────────────────────
// BYTECODE PATCH
// Hooks into the YTM seekbar and music player timebar to:
//   1. Provide the bar Rect bounds to the extension (so it knows where to draw)
//   2. Draw colored SponsorBlock segment indicators on the seekbar
//   3. Track the current video time and video ID so segments can be fetched and skipped
// ─────────────────────────────────────────────────────────────────────────────
private val sponsorBlockBytecodePatch = bytecodePatch(
    description = "sponsorBlockBytecodePatch",
) {
    dependsOn(
        resourceMappingPatch,
        videoInformationPatch,
    )

    execute {

        // ── Step 0: Resolve the seekbar class resource ID ────────────────────
        // The 'inline_time_bar_ad_break_marker_color' color resource is used as a
        // wide literal in the YTM seekbar constructor. It uniquely identifies the class.
        // We look it up from the resource mapping and store it so the fingerprint can use it.
        inlineTimeBarAdBreakMarkerColorId =
            resourceMappingPatch.resourceOf("color", "inline_time_bar_ad_break_marker_color")

        // ── Step 1: Hook video time ───────────────────────────────────────────
        // Called every ~1000ms; the extension uses this to check for upcoming segments to skip.
        videoTimeHook(EXTENSION_CLASS_DESCRIPTOR, "setVideoTime")

        // ── Step 2: Fullscreen / mini-player seekbar ─────────────────────────
        // This is the seekbar shown when the player is expanded or in the queue.
        // We need:
        //   a) The name of the Rect field (via rectangleFieldInvalidatorFingerprint)
        //   b) To inject into onDraw to draw colored segments and set thickness

        var rectangleFieldName =
            with(rectangleFieldInvalidatorFingerprint.methodOrThrow(seekBarConstructorFingerprint)) {
                // Find the index of the invalidate() call in this method
                val invalidateIndex = indexOfFirstInstructionOrThrow {
                    getReference<MethodReference>()?.name == "invalidate"
                }
                // Walk backwards from invalidate() to find the Rect field being referenced
                val rectangleIndex =
                    indexOfFirstInstructionReversedOrThrow(invalidateIndex) {
                        getReference<FieldReference>()?.type == "Landroid/graphics/Rect;"
                    }
                val rectangleReference =
                    getInstruction<ReferenceInstruction>(rectangleIndex).reference
                (rectangleReference as FieldReference).name
            }

        seekbarOnDrawFingerprint.methodOrThrow(seekBarConstructorFingerprint).apply {

            // a) Pass the Rect field name to the extension so it can read bar bounds via reflection
            addInstructions(
                0, """
                    move-object/from16 v0, p0
                    const-string v1, "$rectangleFieldName"
                    invoke-static {v0, v1}, $EXTENSION_CLASS_DESCRIPTOR->setSponsorBarRect(Ljava/lang/Object;Ljava/lang/String;)V
                    """,
            )

            // b) Capture the seekbar thickness (the result of Math.round() for the bar height)
            //    so that the SponsorBlock overlay has the same visual thickness
            val roundIndex = indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.name == "round"
            } + 1
            val roundRegister = getInstruction<OneRegisterInstruction>(roundIndex).registerA
            addInstruction(
                roundIndex + 1,
                "invoke-static {v$roundRegister}, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->setSponsorBarThickness(I)V",
            )

            // c) Draw segment color bars just before the scrubber thumb circle is drawn
            val drawCircleIndex = indexOfFirstInstructionReversedOrThrow {
                getReference<MethodReference>()?.name == "drawCircle"
            }
            val drawCircleInstruction = getInstruction<FiveRegisterInstruction>(drawCircleIndex)
            addInstruction(
                drawCircleIndex,
                "invoke-static {v${drawCircleInstruction.registerC}, v${drawCircleInstruction.registerE}}, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->drawSponsorTimeBars(Landroid/graphics/Canvas;F)V",
            )
        }

        // ── Step 3: Main player timebar (MusicPlaybackControlsTimeBar) ────────
        // This is the timebar rendered in the standard music player (collapsed view).
        // Same approach: capture Rect bounds, then draw segments before the thumb circle.

        rectangleFieldName =
            musicPlaybackControlsTimeBarOnMeasureFingerprint.matchOrThrow().let {
                with(it.method) {
                    val rectangleIndex =
                        indexOfFirstInstructionReversedOrThrow(it.instructionMatches.last().index) {
                            opcode == Opcode.IGET_OBJECT &&
                                    getReference<FieldReference>()?.type == "Landroid/graphics/Rect;"
                        }
                    val rectangleReference =
                        getInstruction<ReferenceInstruction>(rectangleIndex).reference
                    (rectangleReference as FieldReference).name
                }
            }

        musicPlaybackControlsTimeBarDrawFingerprint.methodOrThrow().apply {

            // Pass Rect field name to extension (inject at position 1, after super.draw())
            addInstructions(
                1, """
                    move-object/from16 v0, p0
                    const-string v1, "$rectangleFieldName"
                    invoke-static {v0, v1}, $EXTENSION_CLASS_DESCRIPTOR->setSponsorBarRect(Ljava/lang/Object;Ljava/lang/String;)V
                    """,
            )

            // Draw segments before the thumb circle
            val drawCircleIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.name == "drawCircle"
            }
            val drawCircleInstruction = getInstruction<FiveRegisterInstruction>(drawCircleIndex)
            addInstruction(
                drawCircleIndex,
                "invoke-static {v${drawCircleInstruction.registerC}, v${drawCircleInstruction.registerE}}, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->drawSponsorTimeBars(Landroid/graphics/Canvas;F)V",
            )
        }

        // ── Step 4: Hook video ID ─────────────────────────────────────────────
        // Called whenever the video changes; triggers segment download for the new video.
        videoIdHook("$EXTENSION_CLASS_DESCRIPTOR->setVideoId(Ljava/lang/String;)V")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RESOURCE / SETTINGS PATCH  (public — this is what the patcher exposes to users)
// Adds all SponsorBlock toggles and segment-category preferences to the
// YouTube Music settings screen.
// ─────────────────────────────────────────────────────────────────────────────

// Keys for the two extra PreferenceCategory nodes inside the SponsorBlock screen
private const val SEGMENTS_CATEGORY_KEY = "sb_diff_segments"
private const val ABOUT_CATEGORY_KEY    = "sb_about"

// The key of the top-level SponsorBlock PreferenceScreen that settingsPatch creates
private const val SB_PREFERENCE_SCREEN_KEY = "revanced_preference_screen_sponsor_block"

// Path to the settings header XML managed by settingsPatch
// (matches the constant used elsewhere in morphe-patches music settings)
private const val SETTINGS_HEADER_PATH = "res/xml/revanced_prefs.xml"

@Suppress("unused")
val sponsorBlockPatch = resourcePatch(
    name = "SponsorBlock",
    description = "Adds options to enable and configure SponsorBlock, " +
        "which can skip undesired video segments such as non-music sections.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(
        sponsorBlockBytecodePatch,
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {

        // ── Helpers ───────────────────────────────────────────────────────────
        // All of these write directly into SETTINGS_HEADER_PATH using DOM manipulation,
        // matching the pattern used by other Morphe YTM resource patches.

        fun addSwitchPreference(
            screenKey: String,
            key: String,
            defaultValue: String,
            dependencyKey: String = "",
        ) {
            document(SETTINGS_HEADER_PATH).use { doc ->
                val tags = doc.getElementsByTagName("PreferenceScreen")
                List(tags.length) { tags.item(it) as Element }
                    .filter { it.getAttribute("android:key").contains(screenKey) }
                    .forEach {
                        it.adoptChild("SwitchPreference") {
                            setAttribute("android:title",   "@string/revanced_$key")
                            setAttribute("android:summary", "@string/revanced_${key}_sum")
                            setAttribute("android:key",          key)
                            setAttribute("android:defaultValue", defaultValue)
                            if (dependencyKey.isNotEmpty())
                                setAttribute("android:dependency", dependencyKey)
                        }
                    }
            }
        }

        fun addPreferenceWithIntent(
            screenKey: String,
            key: String,
            dependencyKey: String,
            targetPackage: String,
            targetClass: String,
        ) {
            document(SETTINGS_HEADER_PATH).use { doc ->
                val tags = doc.getElementsByTagName("PreferenceScreen")
                List(tags.length) { tags.item(it) as Element }
                    .filter { it.getAttribute("android:key").contains(screenKey) }
                    .forEach {
                        it.adoptChild("Preference") {
                            setAttribute("android:title",   "@string/revanced_$key")
                            setAttribute("android:summary", "@string/revanced_${key}_sum")
                            setAttribute("android:key",        key)
                            setAttribute("android:dependency", dependencyKey)
                            adoptChild("intent") {
                                setAttribute("android:targetPackage", targetPackage)
                                setAttribute("android:data",          key)
                                setAttribute("android:targetClass",   targetClass)
                            }
                        }
                    }
            }
        }

        fun addPreferenceCategoryInsideScreen(screenKey: String, categoryKey: String) {
            document(SETTINGS_HEADER_PATH).use { doc ->
                val tags = doc.getElementsByTagName("PreferenceScreen")
                List(tags.length) { tags.item(it) as Element }
                    .filter { it.getAttribute("android:key").contains(screenKey) }
                    .forEach {
                        it.adoptChild("PreferenceCategory") {
                            setAttribute("android:title", "@string/revanced_$categoryKey")
                            setAttribute("android:key",   categoryKey)
                        }
                    }
            }
        }

        fun addSegmentPreference(key: String, dependencyKey: String, targetPackage: String, targetClass: String) {
            document(SETTINGS_HEADER_PATH).use { doc ->
                val tags = doc.getElementsByTagName("PreferenceCategory")
                List(tags.length) { tags.item(it) as Element }
                    .filter { it.getAttribute("android:key") == SEGMENTS_CATEGORY_KEY }
                    .forEach {
                        it.adoptChild("Preference") {
                            setAttribute("android:title",   "@string/revanced_$key")
                            setAttribute("android:summary", "@string/revanced_${key}_sum")
                            setAttribute("android:key",        key)
                            setAttribute("android:dependency", dependencyKey)
                            adoptChild("intent") {
                                setAttribute("android:targetPackage", targetPackage)
                                setAttribute("android:data",          key)
                                setAttribute("android:targetClass",   targetClass)
                            }
                        }
                    }
            }
        }

        fun addAboutPreference(key: String, url: String) {
            document(SETTINGS_HEADER_PATH).use { doc ->
                val tags = doc.getElementsByTagName("PreferenceCategory")
                List(tags.length) { tags.item(it) as Element }
                    .filter { it.getAttribute("android:key") == ABOUT_CATEGORY_KEY }
                    .forEach {
                        it.adoptChild("Preference") {
                            setAttribute("android:title",   "@string/revanced_$key")
                            setAttribute("android:summary", "@string/revanced_${key}_sum")
                            setAttribute("android:key", key)
                            adoptChild("intent") {
                                setAttribute("android:action", "android.intent.action.VIEW")
                                setAttribute("android:data",   url)
                            }
                        }
                    }
            }
        }

        // ── Read the YTM package name (used for intent routing to settings) ───
        val ytmPackageName = "com.google.android.apps.youtube.music"
        val activityHookTargetClass =
            "$ytmPackageName.settings.fragment.AdvancedPrefsFragmentCompat"

        // ── Build the settings tree ───────────────────────────────────────────

        // Top-level toggle and basic options
        addSwitchPreference(SB_PREFERENCE_SCREEN_KEY, "sb_enabled", "true")
        addSwitchPreference(SB_PREFERENCE_SCREEN_KEY, "sb_toast_on_skip",             "true",  "sb_enabled")
        addSwitchPreference(SB_PREFERENCE_SCREEN_KEY, "sb_toast_on_connection_error", "true",  "sb_enabled")
        addPreferenceWithIntent(SB_PREFERENCE_SCREEN_KEY, "sb_api_url", "sb_enabled", ytmPackageName, activityHookTargetClass)

        // Segment-category sub-section
        addPreferenceCategoryInsideScreen(SB_PREFERENCE_SCREEN_KEY, SEGMENTS_CATEGORY_KEY)

        listOf(
            "sb_segments_sponsor",
            "sb_segments_selfpromo",
            "sb_segments_interaction",
            "sb_segments_intro",
            "sb_segments_outro",
            "sb_segments_preview",
            "sb_segments_hook",
            "sb_segments_filler",
            "sb_segments_nomusic",   // renamed below to music_offtopic
        ).forEach { key ->
            addSegmentPreference(key, "sb_enabled", ytmPackageName, activityHookTargetClass)
        }

        // About sub-section
        addPreferenceCategoryInsideScreen(SB_PREFERENCE_SCREEN_KEY, ABOUT_CATEGORY_KEY)
        addAboutPreference("sb_about_api", "https://sponsor.ajay.app")

        // ── Fix the "nomusic" key to match the SponsorBlock API category name ─
        // The SponsorBlock API uses "music_offtopic" for the "non-music" category.
        // We wrote "sb_segments_nomusic" above for readability, then rename it here.
        get(SETTINGS_HEADER_PATH).apply {
            writeText(
                readText().replace(
                    "\"sb_segments_nomusic",
                    "\"sb_segments_music_offtopic",
                ),
            )
        }
    }
}
