/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4685
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.loop

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.music.video.information.onMusicCreateHook
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/LoopVideoPatch;"

private const val TIME_BAR_CLASS =
    "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;"

@Suppress("unused")
val loopVideoPatch = patch(
    name = "Loop video",
    description = "Adds A/B loop markers on the seekbar. Long-press to set loop points.",
) {
    dependsOn(
        sharedExtensionPatch,
        musicVideoInformationPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_loop_video", summary = true),
        )

        onMusicCreateHook(EXTENSION_CLASS, "initialize")
        musicVideoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
        musicVideoIdHook("$EXTENSION_CLASS->videoIdChanged(Ljava/lang/String;)V")

        // Clamp real player seeks (in-app + media-session) to [A, B].
        LoopMediaPlayerSeekFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1, p2}, $EXTENSION_CLASS->clampSeekPosition(J)J
                move-result-wide p1
            """
        )

        val onMeasureFp = LoopMusicTimeBarOnMeasureFingerprint
        val onMeasureMatch = onMeasureFp.instructionMatches.first()
        val rectField = onMeasureMatch.fieldReference

        // Override onTouchEvent to observe long-presses.
        // Parent (Labjf) is obfuscated; invoke-super uses the name resolved at patch time.
        val timeBarClass = mutableClassDefBy(TIME_BAR_CLASS)

        val existingOnTouch = timeBarClass.methods.find { it.name == "onTouchEvent" }
        if (existingOnTouch != null) {
            existingOnTouch.addInstructions(
                0,
                "invoke-static {p0, p1}, $EXTENSION_CLASS->onTouchEvent(Landroid/view/View;Landroid/view/MotionEvent;)V",
            )
        } else {
            val superclassName = timeBarClass.superclass!!
            val onTouchEventMethod = ImmutableMethod(
                timeBarClass.type,
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
                        invoke-static {p0, p1}, $EXTENSION_CLASS->onTouchEvent(Landroid/view/View;Landroid/view/MotionEvent;)V
                        invoke-super {p0, p1}, $superclassName->onTouchEvent(Landroid/view/MotionEvent;)Z
                        move-result v0
                        return v0
                    """,
                )
            }
            timeBarClass.methods.add(onTouchEventMethod)
        }

        // Clamp thumb pixel to [A, B]. c(F) only called from the player's touch handler.
        LoopMusicTimeBarSeekFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, $EXTENSION_CLASS->clampSeekX(F)F
                move-result p1
            """,
        )

        LoopMusicTimeBarDrawFingerprint.method.apply {
            val freeRegister = findFreeRegister(1)
            addInstructions(
                1,
                """
                    iget-object v$freeRegister, p0, $rectField
                    invoke-static {v$freeRegister}, $EXTENSION_CLASS->setSeekbarRectangle(Landroid/graphics/Rect;)V
                """,
            )

            // Formatting class is obfuscated; match by signature.
            val formatterCalls = findInstructionIndicesReversedOrThrow(
                methodCall(
                    parameters = listOf("J"),
                    returnType = "Ljava/lang/CharSequence;",
                    opcodes = listOf(Opcode.INVOKE_STATIC),
                ),
            )
            val drawTextCalls = findInstructionIndicesReversedOrThrow(
                methodCall(
                    definingClass = "Landroid/graphics/Canvas;",
                    name = "drawText",
                    parameters = listOf("Ljava/lang/String;", "F", "F", "Landroid/graphics/Paint;"),
                ),
            )

            // Each formatter call feeds one time label: the first drawText after it.
            // The remaining-time formatter negates its argument; the elapsed one does not.
            formatterCalls.forEach { formatterIndex ->
                val labelIndex = drawTextCalls.filter { it > formatterIndex }.minOrNull()
                    ?: error("No drawText after formatter at index $formatterIndex")

                val isRemaining = (1..4).any { offset ->
                    val index = formatterIndex - offset
                    index >= 0 &&
                        getInstruction<BuilderInstruction>(index).opcode == Opcode.NEG_LONG
                }
                val formatMethod =
                    if (isRemaining) "formatScrubRemainingOrDefault" else "formatScrubOrDefault"

                // drawText's receiver is registerC; the String argument is the next register.
                val stringRegister = when (val instruction = getInstruction<Any>(labelIndex)) {
                    is BuilderInstruction35c -> instruction.registerD
                    is BuilderInstruction3rc -> instruction.startRegister + 1
                    else -> error("Unexpected drawText instruction: $instruction")
                }
                require(stringRegister < 16) {
                    "drawText string register v$stringRegister is not a low register"
                }

                addInstructions(
                    labelIndex,
                    """
                        invoke-static {v$stringRegister}, $EXTENSION_CLASS->$formatMethod(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$stringRegister
                    """,
                )
            }

            // Branch targets reference instruction locations, so replacing return-void
            // makes every exit path draw markers; inserting before would be skipped.
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { returnIndex ->
                replaceInstruction(
                    returnIndex,
                    "invoke-static {p1}, $EXTENSION_CLASS->drawLoopMarkers(Landroid/graphics/Canvas;)V",
                )
                addInstruction(returnIndex + 1, "return-void")
            }
        }
    }
}
