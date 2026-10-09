/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.navigation.navigationBarPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsResourcePatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.PlayerInitFingerprint
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.VideoIdFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

internal const val SERIES_TRACKER_EXTENSION_PREFIX = "Lapp/morphe/extension/youtube/series/"
private const val EXTENSION_CLASS = "${SERIES_TRACKER_EXTENSION_PREFIX}SeriesTrackerPatch;"
private const val BUTTON = "${SERIES_TRACKER_EXTENSION_PREFIX}SeriesPlayerButton;"
private const val EXTENSION_PACKAGE = "app.morphe.extension.youtube.series"

internal fun MethodReference.signature() =
    name + "(" + parameterTypes.joinToString("") + ")" + returnType

private val seriesTrackerResourcesPatch = resourcePatch {
    dependsOn(legacyPlayerControlsResourcePatch)

    execute {
        copyResources(
            "seriestracker",
            ResourceGroup(
                "drawable",
                "morphe_series_tracker_button.xml",
                "morphe_series_tracker_button_bold.xml",
            ),
            ResourceGroup("layout", "morphe_series_tracker_history.xml"),
        )
    }

    finalize {
        addTopControl(
            "seriestracker",
            "@+id/morphe_series_tracker_button",
            "@+id/morphe_series_tracker_button"
        )
    }
}

@Suppress("unused")
val seriesTrackerPatch = bytecodePatch(
    name = "Series tracking",
    description = "Adds a series library to History with local progress and episode continuation. " +
            "This patch works with YouTube 21.02 and newer.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        videoInformationPatch,
        navigationBarPatch,
        seriesTrackerResourcesPatch,
        legacyPlayerControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (!is_21_02_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Series tracking' requires YouTube 21.02+"
            )
        }

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_series_tracker_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_series_tracker", summary = true),
                    SwitchPreference("morphe_series_tracker_show_history_tab", summary = true),
                    SwitchPreference("morphe_series_tracker_show_button", summary = true),
                    SwitchPreference(
                        "morphe_series_tracker_record_followed_progress",
                        summary = true,
                        tag = "$EXTENSION_PACKAGE.RecordingPreference"
                    ),
                    SwitchPreference(
                        "morphe_series_tracker_youtube_progress",
                        summary = true,
                        tag = "$EXTENSION_PACKAGE.SyncPreference"
                    ),
                    NonInteractivePreference(
                        "morphe_series_tracker_completion",
                        summaryKey = null,
                        tag = "$EXTENSION_PACKAGE.CompletionPreference",
                        selectable = true
                    )
                )
            )
        )

        PlayerInitFingerprint.classDef.apply {
            val idGetter = ControllerVideoIdFingerprint(
                VideoIdFingerprint.instructionMatches.first().getMethodCalled()
            ).originalMethod

            interfaces.add("${SERIES_TRACKER_EXTENSION_PREFIX}PlaybackBridge\$Source;")

            fun addGetter(name: String, returnType: String, target: String, wide: Boolean) {
                methods.add(
                    ImmutableMethod(
                        type,
                        name,
                        null,
                        returnType,
                        AccessFlags.PUBLIC.value,
                        null,
                        null,
                        MutableMethodImplementation(if (wide) 3 else 2),
                    ).toMutable().apply {
                        val suffix = if (wide) "wide" else "object"
                        addInstructions(
                            0,
                            """
                                $target
                                move-result-$suffix v0
                                return-$suffix v0
                            """
                        )
                    }
                )
            }

            addGetter(
                "patch_seriesTrackerVideoId",
                "Ljava/lang/String;",
                "invoke-virtual { p0 }, $idGetter",
                false
            )
            addGetter(
                "patch_seriesTrackerPosition",
                "J",
                "invoke-virtual { p0 }, $type->patch_getVideoTime()J",
                true
            )
        }

        MediaSessionFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = this.getInstruction<FiveRegisterInstruction>(index).registerC

                addInstruction(
                    index + 1,
                    "invoke-static/range { v$register .. v$register }, " +
                            "${SERIES_TRACKER_EXTENSION_PREFIX}PlaybackSession;->attach(Landroid/media/session/MediaSession;)V"
                )
            }
        }

        val accountContract = wirePrivacy()
        wireHistory()
        wireNativeHistory(accountContract)
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
        initializeTopControl(BUTTON)
    }
}

