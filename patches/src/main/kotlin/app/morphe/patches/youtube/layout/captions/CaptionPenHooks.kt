/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val STYLES = "Lapp/morphe/extension/youtube/patches/captions/CaptionPenStyles;"
private const val CARRIER = $$"Lapp/morphe/extension/youtube/patches/captions/CaptionPenStyles$CaptionCarrier;"
private const val TRACK = $$"Lapp/morphe/extension/youtube/patches/captions/CaptionTranslationList$CaptionTrack;"

private object SegmentPenFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/Deque;", "Lorg/xml/sax/Attributes;", "Ljava/lang/String;"),
    filters = listOf(
        string("p"),
        methodCall(smali = "Lorg/xml/sax/Attributes;->getValue(Ljava/lang/String;)Ljava/lang/String;",
            location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("I"), returnType = "L",
            location = MatchAfterWithin(5)),
        string("t"), string("ac"), string("<br/>")
    )
)

/** File-backed native caption parsing, with an original-track request available. */
private object CachedParserFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;", "L"),
    filters = listOf(
        methodCall(smali = "Ljava/io/File;->exists()Z"),
        methodCall(smali = "Ljava/io/FileInputStream;-><init>(Ljava/io/File;)V"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/io/InputStream;", "L"), returnType = "Ljava/lang/Object;")
    )
)

/** Asynchronous request callback: the decoder itself runs synchronously here. */
private object NetworkParserFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Ljava/lang/Object;"), returnType = "Ljava/lang/Object;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "V", location = MatchAfterWithin(8))
    ),
    custom = { method, _ ->
        method.name == "run" && method.parameterTypes.isEmpty() &&
            method.implementation?.tryBlocks?.isNotEmpty() == true
    }
)

private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference

/** Scope each decoder call by its request identity, including native exception exits. */
private fun MutableMethod.scopeDecoder(invoke: Int, requestRegister: String) {
    val impl = implementation ?: error("Caption parser has no native implementation")
    val instructions = impl.instructions.toList()
    check(instructions.getOrNull(invoke + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT)
    val indexes = mutableMapOf<Int, Int>()
    var address = 0
    instructions.forEachIndexed { i, instruction ->
        indexes[address] = i
        address += instruction.codeUnits
    }
    val start = indexes.entries.single { it.value == invoke }.key
    val catches = impl.tryBlocks.filter {
        start >= it.startCodeAddress && start < it.startCodeAddress + it.codeUnitCount
    }.flatMap { it.exceptionHandlers }.map {
        indexes[it.handlerCodeAddress] ?: error("Caption parser handler does not resolve")
    }.distinct()
    check(catches.isNotEmpty()) { "Caption parser cannot guarantee exception cleanup" }
    check(catches.all { instructions[it].opcode == Opcode.MOVE_EXCEPTION })
    val edits = catches.map { (it + 1) to
        "invoke-static { $requestRegister }, $STYLES->endCaption(Ljava/lang/Object;)V" } +
        listOf(
            (invoke + 2) to "invoke-static { $requestRegister }, $STYLES->endCaption(Ljava/lang/Object;)V",
            invoke to "invoke-static { $requestRegister }, $STYLES->beginCaption(Ljava/lang/Object;)V"
        )
    edits.distinctBy { it.first }.sortedByDescending { it.first }.forEach { (i, smali) ->
        addInstructions(i, smali)
    }
}

/** Structural, obfuscation-independent resolution before any host mutations. */
context(ctx: BytecodePatchContext)
internal fun resolveCaptionPenHooks(trackType: String): () -> Unit {
    val cache = CachedParserFingerprint
    val network = NetworkParserFingerprint
    val penLookup = SegmentPenFingerprint.instructionMatches[2].getMethodCalled()
    check(penLookup.parameterTypes.toList() == listOf("I") &&
        !AccessFlags.STATIC.isSet(penLookup.accessFlags) && penLookup.implementation != null)

    val cachedMethod = cache.method
    val cachedCode = cachedMethod.implementation!!.instructions.toList()
    val nativeTrackField = cachedCode.filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { it.fieldRef() }.filter { it.type == trackType }.distinct().singleOrNull()
        ?: error("Unable to resolve cached caption request identity")
    val requestClass = ctx.mutableClassDefBy(nativeTrackField.definingClass)
    check(requestClass.fields.any { it.name == nativeTrackField.name && it.type == trackType })

    val networkMethod = network.method
    val networkCode = networkMethod.implementation!!.instructions.toList()
    val parserIndex = network.instructionMatches[0].index
    val callbackIndex = network.instructionMatches[2].index
    check(parserIndex + 2 < callbackIndex)
    val callback = networkMethod.getInstruction<FiveRegisterInstruction>(callbackIndex)
    check(callback.registerCount == 3)
    val keyRegister = callback.registerD
    val keyField = networkCode.subList(parserIndex + 2, callbackIndex)
        .filter { it.opcode == Opcode.IGET_OBJECT &&
            (it as TwoRegisterInstruction).registerA == keyRegister }
        .mapNotNull { it.fieldRef() }
        .filter { it.definingClass == networkMethod.definingClass &&
            it.type == "Ljava/lang/Object;" }.distinct().singleOrNull()
        ?: error("Unable to resolve caption callback request identity")
    val networkClass = ctx.mutableClassDefBy(networkMethod.definingClass)

    val cacheIndex = cache.instructionMatches[2].index
    check(cachedCode.getOrNull(cacheIndex + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT)

    return {
        requestClass.interfaces.add(CARRIER)
        requestClass.addTranslationHelper("universalCaptions_styleTrack", emptyList(), TRACK, 1, """
            iget-object v0, p0, $nativeTrackField
            return-object v0
        """)
        networkClass.interfaces.add(CARRIER)
        networkClass.addTranslationHelper("universalCaptions_styleTrack", emptyList(), TRACK, 2, """
            iget-object v0, p0, $keyField
            instance-of v1, v0, ${requestClass.type}
            if-eqz v1, :unknown
            check-cast v0, ${requestClass.type}
            invoke-virtual { v0 }, ${requestClass.type}->universalCaptions_styleTrack()$TRACK
            move-result-object v0
            return-object v0
            :unknown
            const/4 v0, 0x0
            return-object v0
        """)
        networkMethod.scopeDecoder(parserIndex, "p0")
        cachedMethod.scopeDecoder(cacheIndex, "p1")
        penLookup.addInstructions(0, """
            invoke-static { p1 }, $STYLES->getPenId(I)I
            move-result p1
        """)
    }
}
