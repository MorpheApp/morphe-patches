/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BLOCKS = "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks;"
private const val STATE = "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks\$State;"
private const val TIMELINE = "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks\$Timeline;"
private const val FRAME = "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks\$Frame;"

/** Parameter semantics come from the constructor selected at the sampler's allocation site. */
internal data class CaptionCueFields(
    val id: FieldReference,
    val time: FieldReference,
    val visible: FieldReference,
    val full: FieldReference,
    val settings: FieldReference,
)

internal fun findCaptionCueFields(constructor: Method): CaptionCueFields {
    check(isCaptionCueConstructor(constructor)) { "Unsupported caption cue constructor" }
    val body = checkNotNull(constructor.implementation)
    check(body.tryBlocks.isEmpty() && body.instructions.none { it is OffsetInstruction }) {
        "Branching caption cue constructor requires control-flow analysis"
    }
    val parameters = constructor.parameterTypes.map { it.toString() }
    val aliases = mutableMapOf<Int, Int>()
    var register = body.registerCount - 7
    aliases[register++] = -1
    parameters.forEachIndexed { index, type ->
        aliases[register] = index
        register += if (type == "J" || type == "D") 2 else 1
    }
    val fields = mutableMapOf<Int, FieldReference>()
    for (instruction in body.instructions) {
        val op = instruction.opcode.name.uppercase(java.util.Locale.ROOT).replace('-', '_')
        if (
            op.startsWith("MOVE") &&
                !op.startsWith("MOVE_RESULT") &&
                instruction is TwoRegisterInstruction
        ) {
            val source = aliases[instruction.registerB]
            aliases.remove(instruction.registerA)
            if (instruction.opcode.setsWideRegister()) aliases.remove(instruction.registerA + 1)
            if (source != null) aliases[instruction.registerA] = source
        } else if (op.startsWith("IPUT") && instruction is TwoRegisterInstruction) {
            if (aliases[instruction.registerB] != -1) continue
            val ref =
                (instruction as ReferenceInstruction).reference as? FieldReference
                    ?: error("Invalid cue field")
            check(fields.values.none { it.toString() == ref.toString() }) {
                "Caption cue field is overwritten"
            }
            val parameter = aliases[instruction.registerA] ?: continue
            if (parameter < 0) continue
            check(
                ref.definingClass == constructor.definingClass && ref.type == parameters[parameter],
            ) {
                "Caption cue field type mismatch"
            }
            check(fields.put(parameter, ref) == null) {
                "Ambiguous caption cue parameter assignment"
            }
        } else if (
            instruction is OneRegisterInstruction &&
                instruction.opcode.setsRegister() &&
                instruction.opcode != Opcode.CHECK_CAST
        ) {
            aliases.remove(instruction.registerA)
            if (instruction.opcode.setsWideRegister()) aliases.remove(instruction.registerA + 1)
        }
    }
    check(fields.keys == setOf(0, 1, 2, 3, 4)) { "Incomplete caption cue constructor assignments" }
    return CaptionCueFields(
        fields.getValue(0),
        fields.getValue(1),
        fields.getValue(2),
        fields.getValue(3),
        fields.getValue(4),
    )
}

private fun BytecodePatchContext.resolveCaptionDefaultSettings(
    settingsType: String,
): FieldReference {
    val settings = classDefByOrNull(settingsType) ?: error("Caption settings class missing")
    val default =
        settings.fields.single {
            it.type == settings.type && AccessFlags.STATIC.isSet(it.accessFlags)
        }
    check(
        settings.methods.any { method ->
            val args = method.parameterTypes.map { it.toString() }
            method.name == "<init>" &&
                args.take(5) == listOf("I", "I", "I", "Z", "Z") &&
                args.drop(5).let { it.isEmpty() || (it.size == 1 && it.single().startsWith("L")) }
        },
    ) {
        "Unsupported native caption default placement in ${settings.type}"
    }
    return default
}

internal data class CaptionWindowLines(
    val cue: FieldReference,
    val visible: FieldReference,
    val full: FieldReference,
)

internal fun findCaptionWindowLines(setter: Method, fields: CaptionCueFields): CaptionWindowLines {
    val aliases = mutableMapOf<Int, FieldReference>()
    val lines = mutableMapOf<String, FieldReference>()
    val code = setter.implementation!!.instructions
    for (instruction in code) {
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode == Opcode.IGET_OBJECT && field != null) {
            aliases[(instruction as TwoRegisterInstruction).registerA] = field
            continue
        }
        val op = instruction.opcode.name.uppercase(java.util.Locale.ROOT).replace('-', '_')
        if (op.startsWith("MOVE_OBJECT") && instruction is TwoRegisterInstruction) {
            val from = aliases[instruction.registerB]
            aliases.remove(instruction.registerA)
            if (from != null) aliases[instruction.registerA] = from
            continue
        }
        val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (
            instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
                method?.parameterTypes == listOf("Ljava/util/List;", "Ljava/lang/CharSequence;") &&
                method.returnType == "V"
        ) {
            val args =
                when (instruction) {
                    is FiveRegisterInstruction ->
                        listOf(instruction.registerC, instruction.registerD)
                    is RegisterRangeInstruction ->
                        listOf(instruction.startRegister, instruction.startRegister + 1)
                    else -> error("Caption split arguments")
                }
            val list = checkNotNull(aliases[args[0]])
            val text = checkNotNull(aliases[args[1]])
            check(list.definingClass == setter.definingClass && list.type == "Ljava/util/List;")
            check(text.toString() in setOf(fields.visible.toString(), fields.full.toString()))
            check(lines.put(text.toString(), list) == null)
        }
        if (
            instruction is OneRegisterInstruction &&
                instruction.opcode.setsRegister() &&
                instruction.opcode != Opcode.CHECK_CAST
        )
            aliases.remove(instruction.registerA)
    }
    check(lines.size == 2)
    val visible = lines.getValue(fields.visible.toString())
    val full = lines.getValue(fields.full.toString())
    check(visible.toString() != full.toString())
    val cue =
        code
            .filter { it.opcode == Opcode.IPUT_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .single {
                it.type == fields.visible.definingClass && it.definingClass == setter.definingClass
            }
    return CaptionWindowLines(cue, visible, full)
}

private const val LAYOUT_ARGS =
    "Ljava/lang/CharSequence;Landroid/text/TextPaint;ILandroid/text/Layout\$Alignment;FFZ"

private data class CaptionLayoutFactory(
    val method: Method,
    val allocation: Int,
    val invocation: Int,
    val register: Int,
)

/** Both native fill and outline layouts must be supported before any bytecode is changed. */
private fun findCaptionLayoutFactories(methods: Iterable<Method>): List<CaptionLayoutFactory> {
    val factories =
        methods
            .filter {
                it.returnType == "Landroid/text/StaticLayout;" && it.parameterTypes == listOf("I")
            }
            .map { method ->
                val code = checkNotNull(method.implementation).instructions.toList()
                val calls =
                    code.withIndex().filter { (_, instruction) ->
                        val ref =
                            (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.definingClass == "Landroid/text/StaticLayout;" &&
                            ref.name == "<init>" &&
                            ref.parameterTypes.joinToString("") == LAYOUT_ARGS
                    }
                val call = calls.single()
                val range =
                    call.value as? RegisterRangeInstruction
                        ?: error("Caption layout constructor requires range")
                check(range.registerCount == 8)
                val allocation =
                    code
                        .withIndex()
                        .single { (i, instruction) ->
                            i < call.index &&
                                instruction.opcode == Opcode.NEW_INSTANCE &&
                                (instruction as OneRegisterInstruction).registerA ==
                                    range.startRegister &&
                                (instruction as ReferenceInstruction).reference.toString() ==
                                    "Landroid/text/StaticLayout;"
                        }
                        .index
                check(
                    code.subList(allocation + 1, call.index).none { instruction ->
                        instruction is OneRegisterInstruction &&
                            instruction.opcode.setsRegister() &&
                            instruction.registerA == range.startRegister
                    },
                ) {
                    "Caption layout allocation register overwritten"
                }
                CaptionLayoutFactory(method, allocation, call.index, range.startRegister)
            }
    check(factories.size == 2) { "Expected native fill and outline layout factories" }
    return factories
}

private data class StableCaptionSupport(
    val layout: CaptionNativeLayout,
    val fields: CaptionCueFields,
    val defaults: FieldReference,
    val setter: Method,
    val lines: CaptionWindowLines,
    val row: Triple<FieldReference, Method, Method>,
    val presenter: Method,
    val factories: List<CaptionLayoutFactory>,
)

internal val stableCaptionBlocksPatch =
    bytecodePatch(
        description = "Presents automatic speech captions in stable measured two-line blocks.",
    ) {
        dependsOn(translatedRollingCaptionFixPatch)
        execute {
            val support =
                optionalCaptionSupport("stable caption blocks") {
                    val layout = resolveCaptionNativeLayout()
                    val fields = findCaptionCueFields(layout.cueConstructor)
                    val defaults = resolveCaptionDefaultSettings(fields.settings.type)
                    val setter =
                        getCaptionWindowSetterFingerprint(layout.cueType).methodOrNull
                            ?: error("Caption window not found")
                    val window = classDefBy(setter.definingClass) ?: error("Caption window missing")
                    val lines = findCaptionWindowLines(setter, fields)
                    val rowCandidates =
                        window.fields.mapNotNull { field ->
                            val row = classDefByOrNull(field.type) ?: return@mapNotNull null
                            if (row.superclass != "Landroid/view/View;") return@mapNotNull null
                            val getters =
                                row.methods.filter {
                                    it.returnType == "Landroid/text/StaticLayout;" &&
                                        it.parameterTypes.isEmpty() &&
                                        AccessFlags.PUBLIC.isSet(it.accessFlags)
                                }
                            if (getters.size != 1) return@mapNotNull null
                            val set =
                                row.methods.single {
                                    it.returnType == "V" &&
                                        it.parameterTypes == listOf("Ljava/lang/CharSequence;")
                                }
                            Triple(field, getters.single(), set)
                        }
                    val row = rowCandidates.single()
                    check(
                        window.methods.count {
                            it.name == "onMeasure" && it.parameterTypes == listOf("I", "I")
                        } == 1,
                    )
                    val ctor =
                        classDefBy(layout.timelineType)!!.methods.single {
                            it.name == "<init>" && it.parameterTypes == listOf("Ljava/util/List;")
                        }
                    check(ctor.implementation!!.registerCount >= 3)
                    check(
                        ctor.implementation!!.instructions.count {
                            it.opcode == Opcode.RETURN_VOID
                        } == 1,
                    )
                    check(AccessFlags.PUBLIC.isSet(layout.sampler.accessFlags))
                    val overlay =
                        getCaptionOverlayFactoryFingerprint(layout.cueType, window.type)
                            .classDefOrNull ?: error("Caption overlay missing")
                    val presenter =
                        overlay.methods.single { method ->
                            method.returnType == "V" &&
                                method.parameterTypes == listOf("Ljava/util/List;") &&
                                method.implementation?.instructions?.any {
                                    val ref =
                                        (it as? ReferenceInstruction)?.reference as? MethodReference
                                    ref?.definingClass == setter.definingClass &&
                                        ref.name == setter.name &&
                                        ref.parameterTypes == setter.parameterTypes
                                } == true
                        }
                    val cueClass = classDefBy(layout.cueType)!!
                    val timeline = classDefBy(layout.timelineType)!!
                    check(
                        cueClass.methods.none {
                            it.name in
                                setOf(
                                    "patch_getBlockFrame",
                                    "patch_copyBlockFrame",
                                    "patch_getOriginalCaptionVisible",
                                )
                        },
                    )
                    check(timeline.fields.none { it.name == "morpheBlockState" })
                    check(timeline.methods.none { it.name == "patch_getOriginalCaptions" })
                    check(
                        window.methods.none {
                            it.name in
                                setOf(
                                    "patch_getCaptionCue",
                                    "patch_getCaptionMeasureRow",
                                    "patch_getFullCaptionLines",
                                    "patch_getVisibleCaptionLines",
                                )
                        },
                    )
                    check(
                        classDefBy(row.first.type)!!.methods.none {
                            it.name in setOf("patch_getCaptionLayout", "patch_setCaptionText")
                        },
                    )
                    StableCaptionSupport(
                        layout,
                        fields,
                        defaults,
                        setter,
                        lines,
                        row,
                        presenter,
                        findCaptionLayoutFactories(classDefBy(row.first.type)!!.methods),
                    )
                } ?: return@execute
            val (layout, fields, defaults, setter, lines, row, presenter, factories) = support
            hookParsedCaptionTrack("$BLOCKS->setTrack(Ljava/lang/Object;)V")
            mutableClassDefBy(layout.cueType).apply {
                interfaces.add("${BLOCKS.removeSuffix(";")}\$Cue;")
                addHelperMethod(
                    "patch_getOriginalCaptionVisible",
                    "Ljava/lang/CharSequence;",
                    smali = "iget-object v0, p0, ${fields.visible}\nreturn-object v0",
                )
                addHelperMethod(
                    "patch_getBlockFrame",
                    FRAME,
                    smali =
                        """
    iget-object v0, p0, ${fields.full}
    invoke-static { v0 }, $BLOCKS->frameOf(Ljava/lang/CharSequence;)$FRAME
    move-result-object v0
    return-object v0
   """,
                )
                addHelperMethod(
                    "patch_copyBlockFrame",
                    "Ljava/lang/Object;",
                    parameters = listOf(FRAME),
                    freeRegisters = 7,
                    smali =
                        """
    new-instance v0, $type
    iget v1, p0, ${fields.id}
    iget-wide v2, p0, ${fields.time}
    iget-object v4, p0, ${fields.visible}
    iget-object v5, p0, ${fields.full}
    invoke-static { v4, p1 }, $BLOCKS->carryFrame(Ljava/lang/CharSequence;$FRAME)Ljava/lang/CharSequence;
    move-result-object v4
    invoke-static { v5, p1 }, $BLOCKS->carryFrame(Ljava/lang/CharSequence;$FRAME)Ljava/lang/CharSequence;
    move-result-object v5
    sget-object v6, $defaults
    invoke-direct/range { v0 .. v6 }, ${layout.cueConstructor}
    return-object v0
   """,
                )
            }
            for (factory in factories) {
                mutableClassDefBy(factory.method.definingClass)
                    .methods
                    .single {
                        it.name == factory.method.name &&
                            it.parameterTypes == factory.method.parameterTypes
                    }
                    .apply {
                        val r = factory.register
                        replaceInstruction(
                            factory.invocation,
                            "invoke-static/range { v${r+1} .. v${r+7} }, $BLOCKS->createLayout($LAYOUT_ARGS)Landroid/text/StaticLayout;",
                        )
                        addInstructions(factory.invocation + 1, "move-result-object v$r")
                        removeInstruction(factory.allocation)
                    }
            }
            mutableClassDefBy(row.first.type).apply {
                interfaces.add("${BLOCKS.removeSuffix(";")}\$Row;")
                addHelperMethod(
                    "patch_getCaptionLayout",
                    "Landroid/text/StaticLayout;",
                    smali =
                        "invoke-virtual { p0 }, ${row.second}\nmove-result-object v0\nreturn-object v0",
                )
                addHelperMethod(
                    "patch_setCaptionText",
                    "V",
                    parameters = listOf("Ljava/lang/CharSequence;"),
                    smali = "invoke-virtual { p0, p1 }, ${row.third}\nreturn-void",
                )
            }
            mutableClassDefBy(setter.definingClass).apply {
                interfaces.add("${BLOCKS.removeSuffix(";")}\$Window;")
                for ((name, field) in
                    listOf(
                        "patch_getCaptionCue" to lines.cue,
                        "patch_getCaptionMeasureRow" to row.first,
                        "patch_getFullCaptionLines" to lines.full,
                        "patch_getVisibleCaptionLines" to lines.visible,
                    )) addHelperMethod(
                    name,
                    if (field.type == "Ljava/util/List;") field.type else "Ljava/lang/Object;",
                    smali = "iget-object v0, p0, $field\nreturn-object v0",
                )
                methods
                    .single { it.name == "onMeasure" && it.parameterTypes == listOf("I", "I") }
                    .addInstructions(
                        0,
                        "invoke-static/range { p0 .. p2 }, $BLOCKS->prepare(Landroid/view/View;II)V",
                    )
            }
            mutableClassDefBy(presenter.definingClass)
                .methods
                .single {
                    it.name == presenter.name && it.parameterTypes == presenter.parameterTypes
                }
                .addInstructions(
                    0,
                    """
   invoke-static { p1 }, $BLOCKS->centerBlocks(Ljava/util/List;)Ljava/util/List;
   move-result-object p1
  """,
                )
            mutableClassDefBy(layout.timelineType).apply {
                interfaces.add(TIMELINE)
                val state = "$type->morpheBlockState:$STATE"
                instanceFields.add(
                    ImmutableField(
                            type,
                            "morpheBlockState",
                            STATE,
                            AccessFlags.PRIVATE.value,
                            null,
                            null,
                            null,
                        )
                        .toMutable(),
                )
                methods
                    .single {
                        it.name == "<init>" && it.parameterTypes == listOf("Ljava/util/List;")
                    }
                    .apply {
                        val at =
                            implementation!!.instructions.indexOfLast {
                                it.opcode == Opcode.RETURN_VOID
                            }
                        addInstructions(
                            at,
                            "invoke-static {}, $BLOCKS->takeState()$STATE\nmove-result-object v0\niput-object v0, p0, $state",
                        )
                    }
                val sampler = methods.single {
                    it.name == layout.sampler.name &&
                        it.parameterTypes == listOf("J") &&
                        it.returnType == "Ljava/util/List;"
                }
                val originalName = sampler.name
                val owner = type
                val access = sampler.accessFlags
                sampler.name = "patch_getOriginalCaptions"
                methods.add(
                    ImmutableMethod(
                            type,
                            originalName,
                            listOf(ImmutableMethodParameter("J", null, null)),
                            "Ljava/util/List;",
                            access,
                            null,
                            null,
                            MutableMethodImplementation(5),
                        )
                        .toMutable()
                        .apply {
                            addInstructions(
                                0,
                                """
     invoke-virtual/range { p0 .. p2 }, $owner->patch_getOriginalCaptions(J)Ljava/util/List;
     move-result-object v0
     iget-object v1, p0, $state
     invoke-static { p0, v0, p1, p2, v1 }, $BLOCKS->present(${TIMELINE}Ljava/util/List;J$STATE)Ljava/util/List;
     move-result-object v0
     return-object v0
    """,
                            )
                        },
                )
            }
        }
    }
