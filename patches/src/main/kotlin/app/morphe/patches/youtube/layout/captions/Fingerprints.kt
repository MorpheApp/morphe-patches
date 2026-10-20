/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private object SubtitleManagerFingerprintClassFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "Landroid/view/accessibility/CaptioningManager;"),
    filters = listOf(
        fieldAccess("Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;"),
        methodCall("Landroid/view/accessibility/CaptioningManager;->isEnabled()Z"),
    ),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags)
    }
)

internal object SubtitleManagerFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string(""),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Z"
        ),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterWithin(3))
    )
)

/**
 * YouTube 20.26+
 */
internal object NoVolumeCaptionsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45692436L)
    )
)

internal object TimedTextUrlFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            smali = $$"Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest$Builder;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            smali = "Lorg/chromium/net/UploadDataProviders;->create(Ljava/nio/ByteBuffer;)Lorg/chromium/net/UploadDataProvider;"
        )
    )
)


/**
 * Resolves to the caption track class. 21.02+
 */
internal object CaptionTrackIsDisableOptionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("DISABLE_CAPTIONS_OPTION"),
        // Language code.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Resolves to the caption track class. Before 21.02 the class is abstract and uses getters.
 */
internal object CaptionTrackIsDisableOptionLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("DISABLE_CAPTIONS_OPTION"),
        // Language code.
        methodCall(
            definingClass = "this",
            parameters = listOf(),
            returnType = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            location = MatchAfterWithin(1)
        )
    )
)

/**
 * 21.02+
 */
internal object CaptionTrackIsAutoTranslatedFingerprint : Fingerprint(
    classFingerprint = CaptionTrackIsDisableOptionFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        // Vss id.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = InstructionLocation.MatchAfterAnywhere()
        ),
        string("t", location = MatchAfterImmediately()),
        methodCall(
            smali = "Ljava/lang/String;->startsWith(Ljava/lang/String;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Before 21.02.
 */
internal object CaptionTrackIsAutoTranslatedLegacyFingerprint : Fingerprint(
    classFingerprint = CaptionTrackIsDisableOptionLegacyFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        // Vss id.
        methodCall(
            definingClass = "this",
            parameters = listOf(),
            returnType = "Ljava/lang/String;",
            location = InstructionLocation.MatchAfterAnywhere()
        ),
        string("t", location = MatchAfterWithin(1)),
        methodCall(
            smali = "Ljava/lang/String;->startsWith(Ljava/lang/String;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Resolves to the caption tracks manager class.
 */
internal object CaptionTracksManagerDirectTracksFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        string("AUTO_TRANSLATE_CAPTIONS_OPTION")
    )
)

internal object CaptionTracksManagerAutoTranslateTracksFingerprint : Fingerprint(
    classFingerprint = CaptionTracksManagerDirectTracksFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        // Url of the source caption track.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;"
        ),
        methodCall(
            smali = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
            location = MatchAfterImmediately()
        ),
        string("&tlang=", location = MatchAfterImmediately())
    )
)


/**
 * Subtitle manager method that returns the direct caption tracks.
 *
 * @param directTracksMethod Caption tracks manager method that returns the direct tracks.
 */
internal class SubtitleManagerDirectTracksFingerprint(directTracksMethod: MethodReference) : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        // Caption tracks manager.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = directTracksMethod.definingClass
        ),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(reference = directTracksMethod, location = MatchAfterImmediately())
    )
)

internal object DefaultCaptionTrackFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        methodCall("Landroid/view/accessibility/CaptioningManager;->isEnabled()Z"),
    )
)

internal object SetSubtitleTrackFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        // 21.40+ removed the "setSubtitleTrack name:%s languageCode:%s ..." log string,
        // but the Throwable used for the log statement remains.
        newInstance("Ljava/lang/Throwable;")
    )
)



internal data class CaptionTranslationModel(
    val track: ClassDef,
    val translated: Method,
    val direct: Method,
    val language: Reference,
    val vss: Reference,
)

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Method.hasText(text: String) = code().any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
}
private fun <T> Iterable<T>.unique(role: String): T = toList().let {
    check(it.size == 1) { "Universal Captions: $role expected once, found ${it.size}: $it" }
    it.single()
}

/** Resolve only the native translation model. Never identify or intercept caption policy methods. */
internal fun resolveCaptionTranslationModel(classes: Iterable<ClassDef>): CaptionTranslationModel {
    val all = classes.distinctBy { it.type }
    fun Method.booleanWith(text: String) = returnType == "Z" && parameterTypes.isEmpty() && hasText(text)
    val track = all.filter { type -> type.methods.any { it.booleanWith("DISABLE_CAPTIONS_OPTION") } }
        .unique("caption track model")
    val languageMethod = track.methods.filter { it.booleanWith("DISABLE_CAPTIONS_OPTION") }
        .unique("language sentinel")
    val vssMethod = track.methods.filter { method ->
        method.booleanWith("t") && method.code().any {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Ljava/lang/String;" && ref.name == "startsWith"
        }
    }.unique("translated track identity")

    fun accessor(method: Method, role: String): Reference = method.code().mapNotNull { instruction ->
        val ref = (instruction as? ReferenceInstruction)?.reference
        when {
            instruction.opcode == Opcode.IGET_OBJECT && ref is FieldReference &&
                    ref.definingClass == track.type && ref.type == "Ljava/lang/String;" -> ref
            instruction.opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
                    ref is MethodReference && ref.definingClass == track.type &&
                    ref.parameterTypes.isEmpty() && ref.returnType == "Ljava/lang/String;" -> ref
            else -> null
        }
    }.distinct().unique(role)

    fun Method.listWith(text: String) = returnType == "Ljava/util/List;" && parameterTypes.isEmpty() && hasText(text)
    val model = all.filter { type -> type.methods.any { it.listWith("AUTO_TRANSLATE_CAPTIONS_OPTION") } }
        .filter { type -> type.methods.any { it.listWith("&tlang=") } }.unique("caption list model")
    val translated = model.methods.filter { it.listWith("&tlang=") }.unique("translation list")
    return CaptionTranslationModel(track, translated,
        model.methods.filter { it.listWith("AUTO_TRANSLATE_CAPTIONS_OPTION") }.unique("direct list"),
        accessor(languageMethod, "language accessor"),
        accessor(vssMethod, "vss accessor"))
}
