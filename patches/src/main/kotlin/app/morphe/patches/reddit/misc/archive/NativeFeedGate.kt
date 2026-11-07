package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val GATE = "Lapp/morphe/extension/reddit/patches/archive/FeedGateSection;"
private const val OBJ = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"

/** Preserve the host post container, inserting one gate between its header and action bar. */
context(context: BytecodePatchContext)
internal fun installNativeFeedGate() = with(context) {
    // Resolve the interface installed during execute, without retaining mutable
    // host methods across other patches' execute blocks.
    val sectionInterface = classDefBy(SECTION).interfaces.single()
    val signatures = classDefBy(sectionInterface).methods
    val content = signatures.single { it.returnType == "V" && it.parameterTypes.size == 3 }
    val key = signatures.single { it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
    fun bridge(name: String, registers: Int, code: String) = replaceArchiveBridge(name, registers, code, GATE)
    val group = Fingerprint(name = "toString", filters = listOf(string("FeedPostSection(linkId="))).classDef
    val ctor = group.methods.single { it.name == "<init>" }
    val fields = constructorFields(ctor)
    if (fields.size != 11 || fields[0].type != "Ljava/lang/String;" || fields[6].type != "Z"
        || fields[9].type != "Ljava/util/Set;" || sectionInterface !in group.interfaces)
        throw PatchException("Unexpected feed post container for manual undelete")
    val classes = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
    classDefForEach { classes.add(it) }
    val mapper = classes.flatMap { it.methods }.single { m -> m.returnType == sectionInterface
        && m.parameterTypes.size == 2 && m.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == ctor.toString()
    } == true }
    val mapperClass = classDefBy(mapper.definingClass)
    val classic = Fingerprint(name = "toString", filters = listOf(string("ClassicPostElement(linkId="))).classDef
    val classicSection = classes.single { sectionInterface in it.interfaces && it.fields.any { f -> f.type == classic.type } }
    val classicField = classicSection.fields.single { it.type == classic.type }
    // Reuse the wrapper delegate already verified by installNativeArchiveFeed.
    val wrapperDelegate = classDefBy(ARCHIVE_CLASS).methods.single { it.name == "sectionModel" }
        .implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .distinctBy { it.toString() }.single { it.type == sectionInterface }
    replaceArchiveBridge("classicModel", 3, """
        const/16 v1, 0x8
        :unwrap
        instance-of v0, p0, ${wrapperDelegate.definingClass}
        if-eqz v0, :model
        if-eqz v1, :none
        check-cast p0, ${wrapperDelegate.definingClass}
        iget-object p0, p0, $wrapperDelegate
        add-int/lit8 v1, v1, -0x1
        goto :unwrap
        :model
        instance-of v0, p0, ${classicSection.type}
        if-eqz v0, :none
        check-cast p0, ${classicSection.type}
        iget-object v0, p0, $classicField
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """, "Lapp/morphe/extension/reddit/patches/archive/RecoveryPatch;")
    // Compact cards combine metadata and content in one renderer. Render a native
    // header-only clone above the gate, and omit that header in the blurred clone.
    val classicRenderer = mutableClassDefBy(classicSection.type).methods.single {
        it.name == content.name && it.parameterTypes == content.parameterTypes
    }
    val classicInstructions = classicRenderer.implementation!!.instructions.toList()
    val headerIndex = classicInstructions.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
            ref.returnType == "V" && ref.parameterTypes.firstOrNull() == classic.type
                    && "Lcom/reddit/feeds/data/FeedType;" in ref.parameterTypes
        } == true
    }
    if (headerIndex < 0) throw PatchException("Missing compact feed header renderer")
    val classicRegisters = classicRenderer.implementation!!.registerCount
    val (classicPatched, classicShift) = classicRenderer.addFreeRegisters(2)
    classicPatched.addInstructions(classicShift, "move-object/16 v${classicRegisters + 1}, v${classicRegisters - 4}")
    val headerCall = headerIndex + classicShift + 1
    classicPatched.addInstructionsWithLabels(headerCall, """
        invoke-static/range { v${classicRegisters + 1} .. v${classicRegisters + 1} }, $RECOVERY->hideClassicHeader($OBJ)Z
        move-result v$classicRegisters
        if-nez v$classicRegisters, :withoutHeader
    """, ExternalLabel("withoutHeader", classicPatched.implementation!!.instructions[headerCall + 1]))
    val mapperCtor = mapperClass.methods.single { it.name == "<init>" &&
        it.parameterTypes.firstOrNull() == "Lcom/reddit/feeds/data/FeedType;" }
    val parameter = mapperCtor.implementation!!.registerCount - mapperCtor.parameterTypes.size
    // R8 reuses the FeedType parameter register for the mapper's Kotlin class token
    // after storing the parameter. Only its first store represents FeedType.
    val feedType = mapperCtor.implementation!!.instructions.first { it.opcode == Opcode.IPUT_OBJECT &&
        (it as TwoRegisterInstruction).registerA == parameter }
        .let { (it as ReferenceInstruction).reference as FieldReference }

    bridge("prepare", 18, buildString {
        appendLine("move-object/from16 v15, p0\nmove-object/from16 v13, p1")
        appendLine("check-cast v15, ${group.type}\ncheck-cast v13, ${mapper.definingClass}")
        appendLine("iget-object v0, v15, ${fields[1]}\niget-object v1, v15, ${fields[0]}")
        appendLine("iget-boolean v2, v15, ${fields[6]}\niget-object v3, v13, $feedType\ncheck-cast v3, Ljava/lang/Enum;")
        appendLine("invoke-static { v0, v1, v2, v3 }, $GATE->group(${LIST}Ljava/lang/String;ZLjava/lang/Enum;)$LIST")
        appendLine("move-result-object v14\niget-object v0, v15, ${fields[1]}\nif-eq v0, v14, :unchanged")
        appendLine("invoke-static { v14 }, $ARCHIVE_CLASS->immutableList($LIST)$LIST\nmove-result-object v14\ncheck-cast v14, ${fields[1].type}")
        appendLine("new-instance v0, ${group.type}")
        fields.forEachIndexed { i, f ->
            appendLine(if (i == 1) "move-object v${i + 1}, v14" else
                "${if (f.type == "Z") "iget-boolean" else "iget-object"} v${i + 1}, v15, $f")
        }
        appendLine("invoke-direct/range { v0 .. v11 }, $ctor\nreturn-object v0\n:unchanged\nreturn-object p0")
    })
    val mutable = mutableClassDefBy(mapper.definingClass).methods.single { it.toString() == mapper.toString() }
    val instructions = mutable.implementation!!.instructions.toList()
    val callIndex = instructions.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == ctor.toString() }
    val instance = when (val call = instructions[callIndex]) {
        is RegisterRangeInstruction -> call.startRegister
        is FiveRegisterInstruction -> call.registerC
        else -> throw PatchException("Unexpected feed post constructor invocation")
    }
    val count = mutable.implementation!!.registerCount
    val (patched, shift) = mutable.addFreeRegisters(3)
    val mapperThis = count - mapper.parameterTypes.size - 1
    patched.addInstructions(shift, "move-object/16 v${count + 2}, v$mapperThis")
    patched.addInstructionsAtControlFlowLabel(callIndex + shift + 2, """
        move-object/16 v$count, v$instance
        move-object/16 v${count + 1}, v${count + 2}
        invoke-static/range { v$count .. v${count + 1} }, $GATE->prepare($OBJ$OBJ)$OBJ
        move-result-object v$count
        check-cast v$count, ${group.type}
        move-object/16 v$instance, v$count
    """)
    val external = listOf("MetadataHeaderElement(linkId=", "PostActionScoreBarElement(linkId=",
        "ModeratorActionElement(linkId=", "PostStatsElement(identifier=", "PostStatsUnavailableElement(identifier=")
        .map { Fingerprint(name = "toString", filters = listOf(string(it))).method.definingClass }
    bridge("role", 2, buildString {
        external.forEachIndexed { i, type ->
            appendLine("instance-of v0, p0, $type\nif-nez v0, :outside")
        }
        appendLine("const/4 v0, 0x0\nreturn v0\n:outside\nconst/4 v0, 0x1\nreturn v0")
    })
    mutableClassDefBy(GATE).interfaces.add(sectionInterface)
    addNativeMethod(GATE, key, "invoke-virtual { p0 }, $GATE->key()Ljava/lang/String;\nmove-result-object v0\nreturn-object v0")
    addNativeMethod(GATE, content, "invoke-virtual { p0, p1, p2, p3 }, $GATE->render($OBJ${OBJ}I)V\nreturn-void")

    val compose = Fingerprint(name = "setContent", definingClass = "Landroidx/compose/ui/platform/",
        parameters = listOf("Lkotlin/jvm/functions/Function2;")).classDef
    val constructor = compose.methods.single { it.name == "<init>" && it.parameterTypes.size == 1 }
    val setContent = compose.methods.single { it.name == "setContent" }
    if (!AccessFlags.PUBLIC.isSet(constructor.accessFlags)) throw PatchException("Native content view is inaccessible")
    bridge("contentView", 3, """
        new-instance v0, ${compose.type}
        check-cast p0, ${constructor.parameterTypes[0]}
        invoke-direct { v0, p0 }, $constructor
        invoke-virtual { v0, p1 }, $setContent
        return-object v0
    """)
    println("Installed native feed gate during archive finalization")
}
