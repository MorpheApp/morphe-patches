/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import java.util.logging.Logger
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PREFETCH = "Lapp/morphe/extension/youtube/patches/DualCaptionPrefetch;"
private const val HOST = $$"Lapp/morphe/extension/youtube/patches/DualCaptionPrefetch$Host;"
private const val CALLBACK = $$"Lapp/morphe/extension/youtube/patches/DualCaptionPrefetch$PassiveCallback;"

private object NativeCaptionHandlerErrorFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Exception;"),
    filters = listOf(string("CabrSubtitleTrackHandler.java"), string("error retrieving subtitle")),
)

private data class CaptionPrefetchSupport(
    val constructor: Method,
    val loadTrack: Method,
    val requestConstructor: Method,
    val loader: FieldReference,
    val manager: FieldReference,
    val load: MethodReference,
    val callbackType: String,
    val error: Method,
    val success: Method,
    val trackFormat: FieldReference,
    val formatValue: FieldReference,
    val segmentedFormats: List<FieldReference>,
)

private fun Method.references() = implementation?.instructions?.mapNotNull {
    (it as? ReferenceInstruction)?.reference
}.orEmpty().toList()

/** Read-only: every bridge dependency and native guard must resolve before any mutation. */
private fun BytecodePatchContext.resolveCaptionPrefetch(): CaptionPrefetchSupport {
    val requestTrack = CaptionFileParserFingerprint.instructionMatches[1]
        .instruction.getReference<FieldReference>()!!
    val request = classDefBy(requestTrack.definingClass)
    val trackType = requestTrack.type
    val requestConstructor = request.methods.single {
        it.name == "<init>" && it.parameterTypes == listOf(trackType)
    }
    val assignments = requestConstructor.implementation!!.instructions.filter {
        it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>() == requestTrack
    }
    check(assignments.size == 1 && (assignments.single() as TwoRegisterInstruction).let {
        // One parameter, no wide registers. Verify assignment is this.track = track.
        val count = requestConstructor.implementation!!.registerCount
        it.registerA == count - 1 && it.registerB == count - 2
    }) { "Caption request constructor does not assign its track parameter" }
    val getter = request.methods.single { it.name == "patch_getCaptionTrack" }
    check(getter.references().filterIsInstance<FieldReference>().single() == requestTrack) {
        "Caption request getter disagrees with constructor"
    }

    val handler = NativeCaptionHandlerErrorFingerprint.classDef
    val error = NativeCaptionHandlerErrorFingerprint.method
    val loadTrack = handler.methods.single {
        it.returnType == "V" && it.parameterTypes == listOf(trackType) &&
                it.references().filterIsInstance<MethodReference>().any { call ->
                    call.definingClass == request.type && call.name == "<init>" &&
                            call.parameterTypes == listOf(trackType)
                }
    }
    val instructions = loadTrack.implementation!!.instructions.toList()
    val construction = instructions.indexOfFirst {
        it.getReference<MethodReference>()?.let { call ->
            call.definingClass == request.type && call.name == "<init>"
        } == true
    }
    check(construction >= 0)
    // Resolve the method actually used after native request construction; never guess a/b.
    val load = instructions.drop(construction + 1).mapNotNull {
        it.getReference<MethodReference>()?.takeIf { call ->
            it.opcode == Opcode.INVOKE_INTERFACE && call.returnType == "V" &&
                    call.parameterTypes.size == 2 && call.parameterTypes[0] == request.type
        }
    }.single()
    val loaderClass = classDefBy(load.definingClass)
    check(AccessFlags.INTERFACE.isSet(loaderClass.accessFlags))
    val callbackType = load.parameterTypes[1].toString()
    check(loaderClass.methods.count {
        it.returnType == "V" && it.parameterTypes == listOf(request.type, callbackType)
    } == 2) { "Native cached/alternate caption loading contract changed" }
    val callback = classDefBy(callbackType)
    check(AccessFlags.INTERFACE.isSet(callback.accessFlags) && AccessFlags.PUBLIC.isSet(callback.accessFlags)
            && handler.interfaces.contains(callbackType))
    val nativeError = callback.methods.single {
        it.returnType == "V" && it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Exception;")
    }
    val success = callback.methods.single {
        it.returnType == "V" && it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
    }
    check(nativeError.name == error.name && callback.methods.count() == 2)
    val handlerSuccess = handler.methods.single {
        it.name == success.name && it.parameterTypes == success.parameterTypes && it.returnType == "V"
    }
    check(handlerSuccess.implementation != null)
    val loader = handler.fields.single { it.type == load.definingClass }
    val manager = handler.fields.single { it.type == SubtitleManagerFingerprint.classDef.type }
    check(loadTrack.references().filterIsInstance<FieldReference>().contains(loader))
    val constructor = handler.methods.single {
        it.name == "<init>" && it.parameterTypes.count { type -> type == loader.type } == 1 &&
                it.parameterTypes.count { type -> type == manager.type } == 1
    }
    for (field in listOf(loader, manager)) {
        val writes = constructor.implementation!!.instructions.filter {
            it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>() == field
        }
        val parameter = constructor.parameterTypes.indexOf(field.type)
        val firstParameter = constructor.implementation!!.registerCount - 1 -
                constructor.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val offset = constructor.parameterTypes.take(parameter).sumOf { if (it == "J" || it == "D") 2 else 1 }
        check(writes.size == 1 && (writes.single() as TwoRegisterInstruction).let {
            it.registerA == firstParameter + 1 + offset && it.registerB == firstParameter
        }) { "Caption handler does not retain its native constructor dependency" }
    }

    // Resolve obfuscated enum fields from their constructor's semantic name string.
    // JADX display names are not Dex field names and must never be used in a hook.
    val guard = instructions.take(construction)
    val segmentedFormats = guard.filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { field -> field.definingClass == field.type &&
                AccessFlags.ENUM.isSet(classDefBy(field.type).accessFlags) }
        .distinctBy { it.toString() }
    check(segmentedFormats.size == 2 && segmentedFormats.map { it.type }.distinct().size == 1)
    val formatClass = classDefBy(segmentedFormats.first().type)
    val initializer = formatClass.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
    val semanticFormats = listOf("DASH_FMP4_TT_WEBVTT", "DASH_FMP4_TT_FMT3").map { name ->
        val start = initializer.withIndex().single {
            it.value.getReference<StringReference>()?.string == name
        }.index
        val constructionBlock = initializer.drop(start + 1).takeWhile { it.opcode != Opcode.NEW_INSTANCE }
        check(constructionBlock.mapNotNull { it.getReference<MethodReference>() }.count {
            it.name == "<init>" && it.definingClass == formatClass.type &&
                    it.parameterTypes.firstOrNull() == "Ljava/lang/String;"
        } == 1)
        constructionBlock.filter { it.opcode == Opcode.SPUT_OBJECT }.mapNotNull {
            it.getReference<FieldReference>()?.takeIf { field -> field.definingClass == formatClass.type && field.type == formatClass.type }
        }.single()
    }
    check(semanticFormats.toSet() == segmentedFormats.toSet()) { "Native segmented caption guard changed" }
    val trackFormat = guard.filter { it.opcode == Opcode.IGET }.mapNotNull {
        it.getReference<FieldReference>()?.takeIf { field -> field.definingClass == trackType && field.type == "I" }
    }.distinctBy { it.toString() }.single()
    val formatValue = guard.filter { it.opcode == Opcode.IGET }.mapNotNull {
        it.getReference<FieldReference>()?.takeIf { field ->
            field.definingClass == segmentedFormats.first().type && field.type == "I"
        }
    }.distinctBy { it.toString() }.single()
    check(guard.count { it.opcode == Opcode.IF_EQ || it.opcode == Opcode.IF_NE } >= 2)
    // Verify extension methods before beginning optional native mutations.
    val passive = classDefBy(CALLBACK)
    check(passive.methods.any { it.name == "onSuccess" } && passive.methods.any { it.name == "onFailure" })
    return CaptionPrefetchSupport(constructor, loadTrack, requestConstructor, loader, manager,
        load, callbackType, nativeError, handlerSuccess, trackFormat, formatValue, segmentedFormats)
}

internal val dualCaptionPrefetchPatch = bytecodePatch(
    description = "Prefetches a secondary caption file through the existing native caption queue.",
) {
    dependsOn(versionCheckPatch, translatedCaptionPensPatch, videoIdPatch)
    execute {
        if (!is_21_10_or_greater) return@execute
        val support = try {
            resolveCaptionPrefetch()
        } catch (error: Exception) {
            if (error !is PatchException && error !is IllegalStateException &&
                error !is IllegalArgumentException && error !is NoSuchElementException) throw error
            return@execute Logger.getLogger(this::class.java.name).warning(
                "Secondary caption loading is unavailable: ${error.message}"
            )
        }
        val handler = mutableClassDefBy(support.constructor.definingClass)
        handler.interfaces.add(HOST)
        handler.addHelperMethod("patch_getManager", "Ljava/lang/Object;", smali = """
            iget-object v0, p0, ${support.manager}
            return-object v0
        """)
        handler.addHelperMethod("patch_supportsPrefetch", "Z", listOf("Ljava/lang/Object;"),
            freeRegisters = 3, smali = """
                check-cast p1, ${support.requestConstructor.parameterTypes[0]}
                iget v0, p1, ${support.trackFormat}
                sget-object v1, ${support.segmentedFormats[0]}
                iget v1, v1, ${support.formatValue}
                if-eq v0, v1, :unsupported
                sget-object v1, ${support.segmentedFormats[1]}
                iget v1, v1, ${support.formatValue}
                if-eq v0, v1, :unsupported
                const/4 v2, 0x1
                return v2
                :unsupported
                const/4 v2, 0x0
                return v2
            """)
        handler.addHelperMethod("patch_prefetch", "V", listOf("Ljava/lang/Object;", CALLBACK),
            freeRegisters = 2, smali = """
                check-cast p1, ${support.requestConstructor.parameterTypes[0]}
                new-instance v0, ${support.requestConstructor.definingClass}
                invoke-direct { v0, p1 }, ${support.requestConstructor}
                iget-object v1, p0, ${support.loader}
                invoke-interface { v1, v0, p2 }, ${support.load}
                return-void
            """)
        mutableClassDefBy(CALLBACK).apply {
            interfaces.add(support.callbackType)
            addHelperMethod(support.error.name, "V", listOf("Ljava/lang/Object;", "Ljava/lang/Exception;"),
                smali = """
                    invoke-virtual { p0, p1, p2 }, $CALLBACK->onFailure(Ljava/lang/Object;Ljava/lang/Exception;)V
                    return-void
                """)
            addHelperMethod(support.success.name, "V", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                smali = """
                    invoke-virtual { p0, p1, p2 }, $CALLBACK->onSuccess(Ljava/lang/Object;Ljava/lang/Object;)V
                    return-void
                """)
        }
        handler.methods.single { it.toString() == support.success.toString() }.addInstructions(0, """
            invoke-static/range { p1 .. p2 }, Lapp/morphe/extension/youtube/patches/DualCaptionTracks;->onNativeResult(Ljava/lang/Object;Ljava/lang/Object;)V
        """)
        // Capture at construction, and classify only actual native selected-track loads.
        // Passive requests call the loader directly and cannot enter the selection hook.
        val constructor = handler.methods.single { it.toString() == support.constructor.toString() }
        constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }.reversed().forEach { index ->
                constructor.addInstructions(index,
                    "invoke-static/range { p0 .. p0 }, $PREFETCH->capture(Ljava/lang/Object;)V")
            }
        handler.methods.single { it.toString() == support.loadTrack.toString() }.addInstructions(0, """
            invoke-static/range { p0 .. p0 }, $PREFETCH->capture(Ljava/lang/Object;)V
            invoke-static/range { p1 .. p1 }, $PREFETCH->onPrimaryTrack(Ljava/lang/Object;)V
        """)
        hookVideoId("$PREFETCH->onVideoId(Ljava/lang/String;)V")
    }
}
