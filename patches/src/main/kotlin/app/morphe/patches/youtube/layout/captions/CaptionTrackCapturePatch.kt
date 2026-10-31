/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

private const val TRACKS = "Lapp/morphe/extension/youtube/patches/CaptionTracks;"
private object NativeCaptionHandlerErrorFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Exception;"),
    filters = listOf(string("CabrSubtitleTrackHandler.java"), string("error retrieving subtitle")),
)

/** Observe selected-source loads and cached results; never construct or enqueue a second request. */
internal val captionTrackCapturePatch = bytecodePatch {
    dependsOn(versionCheckPatch, translatedCaptionPensPatch, videoIdPatch)
    execute {
        if (!is_21_10_or_greater) return@execute
        val hooks = try {
            val requestTrack = CaptionFileParserFingerprint.instructionMatches[1]
                .fieldReference
            val handler = NativeCaptionHandlerErrorFingerprint.classDef
            val error = NativeCaptionHandlerErrorFingerprint.method
            val selected = handler.methods.single { method ->
                method.returnType == "V" && method.parameterTypes == listOf(requestTrack.type) &&
                        method.implementation?.instructions?.any {
                            it.getReference<MethodReference>()?.let { call ->
                                call.definingClass == requestTrack.definingClass && call.name == "<init>" &&
                                        call.parameterTypes == listOf(requestTrack.type)
                            } == true
                        } == true
            }
            val success = handler.interfaces.map { classDefBy(it) }.filter { callback ->
                callback.methods.any { it.name == error.name && it.parameterTypes == error.parameterTypes }
            }.flatMap { it.methods }.single {
                it.returnType == "V" && it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
            }
            val completed = handler.methods.single {
                it.name == success.name && it.parameterTypes == success.parameterTypes && it.returnType == "V"
            }
            check(completed.implementation != null && selected.implementation != null)
            Pair(selected, completed)
        } catch (error: PatchException) {
            return@execute Logger.getLogger(this::class.java.name).warning("Caption source capture unavailable: ${error.message}")
        } catch (error: IllegalStateException) {
            return@execute Logger.getLogger(this::class.java.name).warning("Caption source capture unavailable: ${error.message}")
        } catch (error: NoSuchElementException) {
            return@execute Logger.getLogger(this::class.java.name).warning("Caption source capture unavailable: ${error.message}")
        }
        val handler = mutableClassDefBy(hooks.first.definingClass)
        handler.methods.single { it.toString() == hooks.first.toString() }.addInstructions(0,
            "invoke-static/range { p1 .. p1 }, $TRACKS->onSelectedTrack(Ljava/lang/Object;)V")
        handler.methods.single { it.toString() == hooks.second.toString() }.addInstructions(0,
            "invoke-static/range { p1 .. p2 }, $TRACKS->onNativeResult(Ljava/lang/Object;Ljava/lang/Object;)V")
        hookVideoId("$TRACKS->onVideoId(Ljava/lang/String;)V")
        setExtensionIsPatchIncluded(TRACKS)
    }
}
