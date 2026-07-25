/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2214
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.chapters

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.findFieldFromToString
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ChaptersHookPatch;"

private const val EXTENSION_TIMELINE_MARKER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ChaptersHookPatch$TimelineMarker;"

val chaptersHookPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch, videoIdPatch)

    execute {
        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")

        getTimelineMarkersArrayFingerprint(
            TimelineMarkerFingerprint.classDef.type
        ).method.apply {
            val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            addInstruction(
                index,
                "invoke-static { v$register }, $EXTENSION_CLASS->" +
                        "setTimelineMarkers([$EXTENSION_TIMELINE_MARKER_INTERFACE)V"
            )
        }

        TimelineMarkerFingerprint.let {
            it.classDef.apply {
                interfaces.add(EXTENSION_TIMELINE_MARKER_INTERFACE)

                val startMillis = it.method.findFieldFromToString("startMillis=")
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getStartMillis",
                        listOf(),
                        "J",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-wide v0, p0, $startMillis
                                return-wide v0
                            """
                        )
                    }
                )

                val endMillis = it.method.findFieldFromToString("endMillis=")
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getEndMillis",
                        listOf(),
                        "J",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-wide v0, p0, $endMillis
                                return-wide v0
                            """
                        )
                    }
                )

                val title = it.method.findFieldFromToString("title=")
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getTitle",
                        listOf(),
                        "Ljava/lang/CharSequence;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-object v0, p0, $title
                                return-object v0
                            """
                        )
                    }
                )
            }
        }
    }
}
