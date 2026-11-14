/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4685
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.loop

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/LoopVideoPatch;"

val loopVideoPatch = patch(
    name = "A-B loop",
    description = "Adds an option to loop part of a song by long-pressing the seekbar to set the loop start and end.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_ab_loop", summary = true)
        )

        musicVideoIdHook("$EXTENSION_CLASS->videoIdChanged(Ljava/lang/String;)V")
        musicVideoTimeHook(EXTENSION_CLASS, "videoTimeChanged")

        // Keep seeks from the app and from the media session inside the loop.
        MedialibPlayerSeekToFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $EXTENSION_CLASS->clampSeekPosition(J)J
                move-result-wide p1
            """
        )

        val seekbarRectField: String

        TimeBarSetScrubPositionFingerprint.let {
            seekbarRectField = it.instructionMatches.first().fieldReference.toString()

            // Keep the dragged thumb inside the loop.
            it.method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->clampSeekX(F)F
                    move-result p1
                """
            )

            // The time bar does not override onTouchEvent, so add one that also passes
            // touches to the extension.
            it.classDef.apply {
                methods.add(
                    ImmutableMethod(
                        type,
                        "onTouchEvent",
                        listOf(ImmutableMethodParameter("Landroid/view/MotionEvent;", null, null)),
                        "Z",
                        AccessFlags.PUBLIC.value,
                        null,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-static { p0, p1 }, $EXTENSION_CLASS->onTouchEvent(Landroid/view/View;Landroid/view/MotionEvent;)V
                                invoke-super { p0, p1 }, $superclass->onTouchEvent(Landroid/view/MotionEvent;)Z
                                move-result v0
                                return v0
                            """
                        )
                    }
                )
            }
        }

        TimeBarDrawFingerprint.let {
            it.method.apply {
                // Show the time labels with milliseconds while dragging.
                // Hook the latter elapsed label first, so the remaining label index stays valid.
                listOf(
                    6 to "formatElapsedTime",
                    3 to "formatRemainingTime",
                ).forEach { (matchIndex, methodName) ->
                    val index = it.instructionMatches[matchIndex].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->$methodName(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """
                    )
                }

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            iget-object v0, p0, $seekbarRectField
                            invoke-static { p1, v0 }, $EXTENSION_CLASS->drawLoopMarkers(Landroid/graphics/Canvas;Landroid/graphics/Rect;)V
                        """
                    )
                }
            }
        }
    }
}
