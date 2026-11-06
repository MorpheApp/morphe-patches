package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val RECOVERY = "Lapp/morphe/extension/reddit/patches/archive/RecoveryPatch;"
private const val RECOVERY_SECTION = "Lapp/morphe/extension/reddit/patches/archive/RecoverySection;"
private const val BINDING = "Lapp/morphe/extension/reddit/patches/archive/RecoveryBinding;"
private const val TARGET = "Lapp/morphe/extension/reddit/patches/archive/RecoveryTarget;"
private const val FIELDS = "Lapp/morphe/extension/reddit/patches/archive/ArchiveRecovery;"
private const val RICH = "Lapp/morphe/extension/reddit/patches/archive/ArchiveRichText;"
private const val OBJ = "Ljava/lang/Object;"
private const val STR = "Ljava/lang/String;"

/** Pinned, structurally checked host bridges. No runtime reflection or obfuscated names in Java. */
context(context: BytecodePatchContext)
internal fun installNativeRecovery(sectionInterface: String, sectionContent: Method, sectionKey: Method,
                                   converter: Method) = with(context) {
    val classes = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
    classDefForEach { classes.add(it) }
    fun model(marker: String): Method = Fingerprint(name = "toString", returnType = STR,
        filters = listOf(string(marker))).method
    // R8 outlines StringBuilder calls and reorders literal loads. Trace those small
    // helpers rather than guessing a field from register or declaration order.
    val traced = mutableMapOf<String, Map<String, FieldReference>>()
    fun fields(model: Method): Map<String, FieldReference> {
        val references = mutableListOf<FieldReference>()
        fun trace(m: Method, arguments: List<Any?>, depth: Int): Any? {
            if (depth > 12) throw PatchException("Recursive recovery toString helper: $m")
            val impl = m.implementation ?: throw PatchException("Missing recovery toString helper: $m")
            val registers = arrayOfNulls<Any>(impl.registerCount)
            val first = impl.registerCount - m.parameterTypes.size - if (AccessFlags.STATIC.isSet(m.accessFlags)) 0 else 1
            arguments.forEachIndexed { i, value -> registers[first + i] = value }
            var result: Any? = null
            val instructions = impl.instructions.toList()
            val addresses = instructions.runningFold(0) { offset, ins -> offset + ins.codeUnits }
            var pc = 0
            while (pc < instructions.size) {
                val index = pc++
                val ins = instructions[index]
                val ref = (ins as? ReferenceInstruction)?.reference
                when {
                    ins.opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) ->
                        pc = addresses.indexOf(addresses[index] + (ins as OffsetInstruction).codeOffset)
                    ins.opcode in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ) -> {
                        val value = registers[(ins as OneRegisterInstruction).registerA]
                        val zero = value == null || value == 0L
                        if (zero == (ins.opcode == Opcode.IF_EQZ)) pc = addresses.indexOf(addresses[index] + (ins as OffsetInstruction).codeOffset)
                    }
                    ref is StringReference -> registers[(ins as OneRegisterInstruction).registerA] = ref.string
                    ins is WideLiteralInstruction && ins is OneRegisterInstruction -> registers[ins.registerA] = ins.wideLiteral
                    ins.opcode in setOf(Opcode.IGET_OBJECT, Opcode.IGET, Opcode.IGET_BOOLEAN) -> {
                        references.add(ref as FieldReference)
                        registers[(ins as TwoRegisterInstruction).registerA] = "§${references.lastIndex}§"
                    }
                    ins.opcode == Opcode.NEW_INSTANCE && (ref as TypeReference).type == "Ljava/lang/StringBuilder;" ->
                        registers[(ins as OneRegisterInstruction).registerA] = StringBuilder()
                    ins.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) -> {
                        ins as TwoRegisterInstruction; registers[ins.registerA] = registers[ins.registerB]
                    }
                    ins.opcode in setOf(Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT) -> registers[(ins as OneRegisterInstruction).registerA] = result
                    ins.opcode == Opcode.RETURN_OBJECT -> return registers[(ins as OneRegisterInstruction).registerA]
                    ref is MethodReference -> {
                        val regs = when (ins) {
                            is FiveRegisterInstruction -> listOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG).take(ins.registerCount)
                            is RegisterRangeInstruction -> (ins.startRegister until ins.startRegister + ins.registerCount).toList()
                            else -> throw PatchException("Unexpected toString invoke: $m")
                        }
                        val values = regs.map { registers[it] }
                        result = when {
                            ref.definingClass == "Ljava/lang/StringBuilder;" -> {
                                val builder = values[0] as StringBuilder
                                when (ref.name) {
                                    "<init>", "append" -> { values.getOrNull(1)?.let { builder.append(it) }; builder }
                                    "toString" -> builder.toString()
                                    else -> throw PatchException("Unexpected StringBuilder operation: $ref")
                                }
                            }
                            ref.definingClass == "Ljava/lang/String;" && ref.name == "valueOf" -> values[0].toString()
                            (ref.returnType == "Ljava/lang/StringBuilder;" || "Ljava/lang/StringBuilder;" in ref.parameterTypes ||
                                ref.returnType == STR && ref.parameterTypes.size >= 2 && ins.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) -> {
                                val helper = classDefBy(ref.definingClass).methods.single { it.name == ref.name && it.parameterTypes == ref.parameterTypes && it.returnType == ref.returnType }
                                trace(helper, values, depth + 1)
                            }
                            else -> values.firstOrNull()
                        }
                    }
                }
            }
            return result
        }
        val output = trace(model, listOf(null), 0).toString()
        return Regex("(?:\\(|, )([A-Za-z0-9_]+)=§(\\d+)§").findAll(output).associate {
            (it.groupValues[1] + "=") to references[it.groupValues[2].toInt()]
        }
    }
    fun field(model: Method, label: String): FieldReference =
        traced.getOrPut(model.definingClass) { fields(model) }[label]
            ?: throw PatchException("Missing verified recovery field $label in $model")
    fun method(name: String, parameters: List<String>, result: String, registers: Int, code: String): Method {
        val generated = ImmutableMethod(RECOVERY, name,
            parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, result,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            MutableMethodImplementation(registers)).toMutable()
        generated.addInstructions(0, code)
        mutableClassDefBy(RECOVERY).methods.add(generated)
        return generated
    }
    fun bridge(name: String, registers: Int, code: String) = replaceArchiveBridge(name, registers, code, RECOVERY)

    // Reuse exactly the profile observer's verified lifecycle signatures.
    val observer = classDefBy(CONTROLLER).interfaces.single()
    mutableClassDefBy(BINDING).interfaces.add(observer)
    classDefBy(observer).methods.forEach { signature ->
        val implementation = classDefBy(CONTROLLER).methods.single {
            it.name == signature.name && it.parameterTypes == signature.parameterTypes
        }
        val call = implementation.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.single { it.definingClass == CONTROLLER }
        addNativeMethod(BINDING, signature, "invoke-virtual { p0 }, $BINDING->${call.name}()V\nreturn-void")
    }
    mutableClassDefBy(RECOVERY_SECTION).interfaces.add(sectionInterface)
    addNativeMethod(RECOVERY_SECTION, sectionKey,
        "invoke-virtual { p0 }, $RECOVERY_SECTION->key()$STR\nmove-result-object v0\nreturn-object v0")
    addNativeMethod(RECOVERY_SECTION, sectionContent,
        "invoke-virtual { p0, p1, p2, p3 }, $RECOVERY_SECTION->render($OBJ${OBJ}I)V\nreturn-void")
    bridge("key", 1, "check-cast p0, $sectionInterface\ninvoke-interface { p0 }, $sectionKey\nmove-result-object p0\nreturn-object p0")
    bridge("render", 4, """
        check-cast p0, $sectionInterface
        check-cast p1, ${sectionContent.parameterTypes[0]}
        check-cast p2, ${sectionContent.parameterTypes[1]}
        invoke-interface { p0, p1, p2, p3 }, $sectionContent
        return-void
    """)
    bridge("convert", 2, """
        check-cast p0, ${converter.definingClass}
        check-cast p1, ${converter.parameterTypes.single()}
        invoke-virtual { p0, p1 }, $converter
        move-result-object p0
        return-object p0
    """)

    data class Shape(val model: Method, val constructor: Method, val fields: List<FieldReference>) {
        val type get() = model.definingClass
    }
    fun shape(model: Method): Shape {
        val candidates = classDefBy(model.definingClass).methods.filter { it.name == "<init>" }.mapNotNull { constructor ->
            if (constructor.parameterTypes.any { it == "J" || it == "D" }) return@mapNotNull null
            val start = constructor.implementation!!.registerCount - constructor.parameterTypes.size - 1
            val origins = mutableMapOf<Int, Int>()
            (0..constructor.parameterTypes.size).forEach { origins[start + it] = it - 1 }
            val assignments = mutableMapOf<Int, MutableList<FieldReference>>()
            constructor.implementation!!.instructions.forEach { ins ->
                if (ins is TwoRegisterInstruction && ins.opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
                    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
                    origins[ins.registerB]?.let { origins[ins.registerA] = it } ?: origins.remove(ins.registerA)
                } else if (ins.opcode in setOf(Opcode.IPUT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_OBJECT)) {
                    ins as TwoRegisterInstruction
                    val f = (ins as ReferenceInstruction).reference as FieldReference
                    val origin = origins[ins.registerA]
                    if (origin != null && origin >= 0 && origins[ins.registerB] == -1 && f.definingClass == model.definingClass)
                        assignments.getOrPut(origin) { mutableListOf() }.add(f)
                } else if (ins.opcode.setsRegister() && ins.opcode != Opcode.CHECK_CAST && ins is OneRegisterInstruction) origins.remove(ins.registerA)
            }
            val fields = constructor.parameterTypes.indices.map { assignments[it]?.singleOrNull() }
            if (fields.any { it == null }) null else Shape(model, constructor, fields.filterNotNull())
        }
        if (candidates.size != 1) throw PatchException("Ambiguous recovery constructor: ${model.definingClass} (${candidates.size})")
        return candidates.single()
    }
    fun getter(name: String, root: String, path: List<FieldReference>): Method = method(name, listOf(OBJ), STR, 2, buildString {
        appendLine("move-object v0, p0")
        path.forEach { f ->
            appendLine("if-eqz v0, :none")
            appendLine("check-cast v0, ${f.definingClass}")
            appendLine("iget-object v0, v0, $f")
        }
        appendLine("return-object v0\n:none\nconst/4 v0, 0x0\nreturn-object v0")
    })
    // All transforms use low scratch registers and a separate contiguous constructor range.
    fun clone(name: String, s: Shape, transforms: Map<String, String>): Method = method(name,
        listOf(OBJ, FIELDS, "Z"), OBJ, s.fields.size + 20, buildString {
            appendLine("move-object/from16 v1, p0\ncheck-cast v1, ${s.type}")
            appendLine("move-object/from16 v2, p1\nmove/from16 v3, p2\nnew-instance v15, ${s.type}")
            s.fields.forEachIndexed { i, f ->
                val ref = f.type.startsWith("L") || f.type.startsWith("[")
                val get = if (ref) "iget-object" else if (f.type == "Z") "iget-boolean" else "iget"
                appendLine("$get v4, v1, $f")
                transforms[f.toString()]?.let { appendLine(it.replace("@", "field$i")) }
                if (ref && f.type != s.constructor.parameterTypes[i]) appendLine("check-cast v4, ${s.constructor.parameterTypes[i]}")
                appendLine("${if (ref) "move-object/from16" else "move/from16"} v${16 + i}, v4")
            }
            appendLine("invoke-direct/range { v15 .. v${15 + s.fields.size} }, ${s.constructor}\nreturn-object v15")
        })
    fun replace(which: String, helper: String = "field") = """
        iget-object v5, v2, $FIELDS->$which:$STR
        invoke-static { v4, v5 }, $RECOVERY->$helper($STR$STR)$STR
        move-result-object v4
    """
    fun whenBody(code: String) = """
        iget-object v5, v2, $FIELDS->body:$STR
        if-eqz v5, :@unchanged
        $code
        :@unchanged
    """
    val stamp = "invoke-static { v4, v3 }, $RECOVERY->stamp(${STR}Z)$STR\nmove-result-object v4"
    val richElementsMethod = Fingerprint(definingClass = "Lfys;", name = "q",
        parameters = listOf(STR, "Ljava/util/Map;"), returnType = "Ljava/util/ArrayList;").method
    // Verify the rich-text parser is the one called by the comment body mapper.
    val commentBody = shape(model("CommentBodyViewState(body="))
    val commentMapper = classDefBy { c -> c.methods.any { m -> m.returnType == commentBody.type &&
        m.parameterTypes.firstOrNull() == "Lcom/reddit/domain/model/Comment;" } }.methods.single {
        it.returnType == commentBody.type && it.parameterTypes.firstOrNull() == "Lcom/reddit/domain/model/Comment;"
    }
    if (commentMapper.implementation!!.instructions.none {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == richElementsMethod.toString()
        }) throw PatchException("Comment rich-text parser changed")
    bridge("richElements", 2, """
        const/4 v0, 0x0
        invoke-static { p0, v0 }, $richElementsMethod
        move-result-object p0
        invoke-static { p0 }, $ARCHIVE_CLASS->immutableList(Ljava/util/List;)Ljava/util/List;
        move-result-object p0
        return-object p0
    """)
    val updateElements = whenBody("""
        invoke-static { v5 }, $RICH->json($STR)$STR
        move-result-object v5
        invoke-static { v5 }, $RECOVERY->richElements($STR)$OBJ
        move-result-object v4
        check-cast v4, ${field(commentBody.model, "richTextElements=").type}
    """)
    data class TargetSpec(val s: Shape, val kind: Int, val id: FieldReference, val title: FieldReference? = null,
        val body: FieldReference? = null, val author: FieldReference? = null, val parent: FieldReference? = null,
        val removed: Boolean = false, val copier: Method)
    val specs = mutableListOf<TargetSpec>()
    fun simple(marker: String, kind: Int, title: String? = null, body: String? = null,
               author: String? = null, time: String? = null, id: String = "linkId=", parent: String? = null,
               extra: (Shape) -> Map<String, String> = { emptyMap() }): Shape {
        val s = shape(model(marker)); val transforms = mutableMapOf<String, String>()
        val titleField = title?.let { field(s.model, it) }
        val bodyField = body?.let { field(s.model, it) }
        val authorField = author?.let { field(s.model, it) }
        titleField?.let { transforms[it.toString()] = replace("title") }
        bodyField?.let { transforms[it.toString()] = replace("body", if (kind == 5) "richText" else if (kind in listOf(2, 6, 9)) "preview" else "field") }
        val represented = fields(s.model)
        val restored = if (titleField != null) "title" else "body"
        if (titleField != null || bodyField != null) {
            listOf("translatedTitle=", "translatedText=", "showTranslation=").forEach { label ->
                represented[label]?.let { f -> transforms[f.toString()] = """
                    iget-object v5, v2, $FIELDS->$restored:$STR
                    if-eqz v5, :@unchanged
                    const/4 v4, 0x0
                    :@unchanged
                """ }
            }
        }
        authorField?.let { transforms[it.toString()] = replace("author", "author") }
        time?.let { transforms[field(s.model, it).toString()] = stamp }
        transforms.putAll(extra(s))
        val copier = clone("copyRecovery$kind", s, transforms)
        specs.add(TargetSpec(s, kind, field(s.model, id), titleField, bodyField, authorField,
            parent?.let { field(s.model, it) }, kind == 7, copier))
        return s
    }
    simple("PostTitleElement(linkId=", 1, title = "title=")
    simple("PreviewTextElement(linkId=", 2, body = "text=")
    simple("MetadataHeaderElement(linkId=", 3, author = "authorNameWithPrefix=", time = "createdAt=",
        extra = { s -> mapOf(field(s.model, "details=").toString() to replace("author", "author")) })
    val classic = simple("ClassicPostElement(linkId=", 4, title = "title=", time = "createdAt=")
    val flairs = shape(model("PostFlairsElement(linkId="))
    val emptyFlairs = clone("copyEmptyClassicFlairs", flairs, mapOf(
        field(flairs.model, "flairs=").toString() to """
            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v4
            invoke-static { v4 }, $ARCHIVE_CLASS->immutableList(Ljava/util/List;)Ljava/util/List;
            move-result-object v4
            check-cast v4, ${field(flairs.model, "flairs=").type}
        """
    ))
    val classicHeader = clone("copyClassicHeader", classic, mapOf(
        field(classic.model, "hideTitle=").toString() to "const/4 v4, 0x1",
        field(classic.model, "thumbnail=").toString() to "const/4 v4, 0x0",
        field(classic.model, "flairs=").toString() to """
            invoke-static { v4, v2, v3 }, $emptyFlairs
            move-result-object v4
            check-cast v4, ${flairs.type}
        """
    ))
    bridge("classicHeader", 3, """
        const/4 v0, 0x0
        invoke-static { p0, v0, v0 }, $classicHeader
        move-result-object v0
        return-object v0
    """)
    val rich = simple("RichTextElement(linkId=", 5, body = "rtJsonText=")
    simple("ProfileCommentFeedElement(identifier=", 6, body = "commentPreview=", id = "identifier=", parent = "postId=")
    val removal = shape(model("PostRecoveryElement(identifier="))
    val removeCopy = method("copyRecovery7", listOf(OBJ, FIELDS, "Z"), OBJ, 13, """
        iget-object v5, p1, $FIELDS->body:$STR
        if-eqz v5, :unchanged
        check-cast p0, ${removal.type}
        iget-object v1, p0, ${field(removal.model, "identifier=")}
        move-object v2, v1
        const/4 v3, 0x0
        const/4 v4, 0x0
        invoke-static { v5 }, $RICH->json($STR)$STR
        move-result-object v5
        const/4 v6, 0x0
        const/4 v7, 0x0
        const/4 v8, 0x0
        new-instance v0, ${rich.type}
        invoke-direct/range { v0 .. v8 }, ${rich.constructor}
        return-object v0
        :unchanged
        return-object p0
    """)
    specs.add(TargetSpec(removal, 7, field(removal.model, "identifier="), removed = true, copier = removeCopy))
    val bodyCopy = clone("copyRecovery8", commentBody, mapOf(
        field(commentBody.model, "body=").toString() to replace("body"),
        field(commentBody.model, "rtJson=").toString() to replace("body", "richText"),
        field(commentBody.model, "richTextElements=").toString() to updateElements,
        field(commentBody.model, "isRemovedByCategoryAvailable=").toString() to whenBody("const/4 v4, 0x0"),
        field(commentBody.model, "richEntityReferences=").toString() to whenBody("""
            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v4
            invoke-static { v4 }, $ARCHIVE_CLASS->immutableList(Ljava/util/List;)Ljava/util/List;
            move-result-object v4
            check-cast v4, ${field(commentBody.model, "richEntityReferences=").type}
        """)
    ))
    specs.add(TargetSpec(commentBody, 8, field(commentBody.model, "commentIdWithKind="),
        body = field(commentBody.model, "body="), parent = field(commentBody.model, "postId="), copier = bodyCopy))
    val header = simple("CommentHeaderViewState(commentIdWithKind=", 9, body = "bodyPreview=",
        author = "authorName=", time = "timestamp=", id = "commentIdWithKind=")

    // Modern post detail uses one immutable model with nested title, author and content.
    val post = shape(model("PostUnitViewState(metadata="))
    val meta = shape(model("PostUnitMetadata(author="))
    val title = shape(model("PostUnitTitle(title="))
    val author = shape(classDefBy(field(meta.model, "author=").type).methods.single { it.name == "toString" })
    val text = shape(model("PostUnitGenericTextContent(rtjson="))
    val contentType = field(post.model, "content=").type
    val contentShapes = classes.filter { it.superclass == contentType && it.fields.any { f -> f.type == text.type } }
        .map { c -> shape(c.methods.single { it.name == "toString" }) }
    val textGetter = classDefBy(contentType).methods.single { it.returnType == text.type && it.parameterTypes.isEmpty() }
    val modernBody = method("modernBody", listOf(OBJ), STR, 3, """
        check-cast p0, ${post.type}
        iget-object v0, p0, ${field(post.model, "content=")}
        if-eqz v0, :none
        ${contentShapes.joinToString("\n") { "instance-of v1, v0, ${it.type}\nif-nez v1, :supported" }}
        const-string v0, "\\u0000"
        return-object v0
        :supported
        invoke-virtual { v0 }, $textGetter
        move-result-object v0
        if-eqz v0, :none
        iget-object v1, v0, ${field(text.model, "text=")}
        iget-object v0, v0, ${field(text.model, "rtjson=")}
        invoke-static { v1, v0 }, $RECOVERY->bodyText($STR$STR)$STR
        move-result-object v0
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    val modernId = getter("modernId", post.type, listOf(field(post.model, "metadata="), field(meta.model, "linkIdWithKind=")))
    val modernTitle = getter("modernTitle", post.type, listOf(field(post.model, "title="), field(title.model, "title=")))
    val modernAuthor = getter("modernAuthor", post.type, listOf(field(post.model, "metadata="), field(meta.model, "author="), field(author.model, "username=")))
    val authorCopy = clone("copyModernAuthor", author, mapOf(
        field(author.model, "username=").toString() to replace("author", "author"),
        field(author.model, "displayName=").toString() to replace("author", "author")))
    fun nested(call: Method, type: String) = """
        if-eqz v4, :@unchanged
        invoke-static { v4, v2, v3 }, $call
        move-result-object v4
        check-cast v4, $type
        :@unchanged
    """
    val groupedGetters = mutableMapOf<Int, List<Pair<Int, Method>>>()
    listOf("TitleWithThumbnailElement(linkId=" to 11, "TitleWithThumbnailCollapsedElement(linkId=" to 12).forEach { (marker, kind) ->
        val s = shape(model(marker))
        val titleField = field(s.model, "postTitleElement=")
        val titleSpec = specs.single { it.kind == 1 }
        val transforms = mutableMapOf(titleField.toString() to nested(titleSpec.copier, titleSpec.s.type))
        val getters = mutableListOf(4 to getter("groupTitle$kind", s.type, listOf(titleField, titleSpec.title!!)))
        if (kind == 11) {
            val previewField = field(s.model, "previewText=")
            val previewSpec = specs.single { it.kind == 2 }
            transforms[previewField.toString()] = nested(previewSpec.copier, previewSpec.s.type)
            getters.add(5 to getter("groupBody$kind", s.type, listOf(previewField, previewSpec.body!!)))
        }
        specs.add(TargetSpec(s, kind, field(s.model, "linkId="), copier = clone("copyRecovery$kind", s, transforms)))
        groupedGetters[kind] = getters
    }
    val metaCopy = clone("copyModernMetadata", meta, mapOf(
        field(meta.model, "author=").toString() to nested(authorCopy, author.type),
        field(meta.model, "timePosted=").toString() to stamp))
    val titleCopy = method("copyModernTitle", listOf(OBJ, FIELDS, "Z"), OBJ, 5, """
        iget-object v1, p1, $FIELDS->title:$STR
        if-eqz v1, :unchanged
        new-instance v0, ${title.type}
        invoke-direct { v0, v1 }, ${title.constructor}
        return-object v0
        :unchanged
        return-object p0
    """)
    val textCopy = method("copyModernText", listOf(OBJ, FIELDS, "Z"), OBJ, 8, """
        iget-object v3, p1, $FIELDS->body:$STR
        if-eqz v3, :unchanged
        invoke-static { v3 }, $RICH->json($STR)$STR
        move-result-object v1
        const/4 v2, 0x0
        new-instance v0, ${text.type}
        invoke-direct { v0, v1, v2, v3 }, ${text.constructor}
        return-object v0
        :unchanged
        return-object p0
    """)
    val contentCopies = contentShapes.mapIndexed { index, s ->
        val f = s.fields.single { it.type == text.type }
        val richList = field(s.model, "richTextItems=")
        s to clone("copyModernContent$index", s, mapOf(f.toString() to "invoke-static { v4, v2, v3 }, $textCopy\nmove-result-object v4\ncheck-cast v4, ${text.type}", richList.toString() to updateElements))
    }
    val self = contentShapes.single { it.model.implementation!!.instructions.any { ins ->
        ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == "Self(textContent=" } }
    val contentCopy = method("copyModernContent", listOf(OBJ, FIELDS, "Z"), OBJ, 10, buildString {
        appendLine("iget-object v0, p1, $FIELDS->body:$STR\nif-eqz v0, :unchanged")
        contentCopies.forEachIndexed { i, (s, call) ->
            appendLine("instance-of v0, p0, ${s.type}\nif-eqz v0, :next$i")
            appendLine("invoke-static { p0, p1, p2 }, $call\nmove-result-object v0\nreturn-object v0\n:next$i")
        }
        appendLine("if-nez p0, :unchanged") // Never replace media or another unsupported content model.
        appendLine("invoke-static { p0, p1, p2 }, $textCopy\nmove-result-object v1\ncheck-cast v1, ${text.type}")
        appendLine("const/4 v2, 0x0\niget-object v3, p1, $FIELDS->body:$STR")
        appendLine("invoke-static { v3 }, $RICH->json($STR)$STR\nmove-result-object v3")
        appendLine("invoke-static { v3 }, $RECOVERY->richElements($STR)$OBJ\nmove-result-object v3\ncheck-cast v3, ${self.constructor.parameterTypes[2]}")
        appendLine("const/4 v4, 0x0\nconst/4 v5, 0x0\nnew-instance v0, ${self.type}")
        appendLine("invoke-direct/range { v0 .. v5 }, ${self.constructor}\nreturn-object v0\n:unchanged\nreturn-object p0")
    })
    val postCopy = clone("copyRecovery10", post, mapOf(
        field(post.model, "metadata=").toString() to nested(metaCopy, meta.type),
        field(post.model, "title=").toString() to "invoke-static { v4, v2, v3 }, $titleCopy\nmove-result-object v4\ncheck-cast v4, ${title.type}",
        field(post.model, "content=").toString() to "invoke-static { v4, v2, v3 }, $contentCopy\nmove-result-object v4\ncheck-cast v4, $contentType",
        field(post.model, "postRemovedRetryM2=").toString() to whenBody("const/4 v4, 0x0")))

    // The saved/profile comment renderer has its ID on the section, separate from its
    // immutable presentation state. Keep that native section and its click handlers.
    val profile = shape(model("ProfileCommentFeedViewState(postTitle="))
    val profileMeta = shape(model("CommentMetaDataUiModel(prefixedSubredditName="))
    val profileMetaCopy = clone("copyProfileCommentMetadata", profileMeta,
        mapOf(field(profileMeta.model, "timePosted=").toString() to stamp))
    val textFactory = Fingerprint(definingClass = "Lij20;", name = "e0", parameters = listOf(STR),
        returnType = field(profile.model, "commentPreview=").type).method
    val plainType = textFactory.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.single()
    val plainField = classDefBy(plainType).fields.single { it.type == STR }
    val profileCopy = clone("copyProfileComment", profile, mapOf(
        field(profile.model, "commentMetaDataUiModel=").toString() to nested(profileMetaCopy, profileMeta.type),
        field(profile.model, "commentPreview=").toString() to whenBody("""
            invoke-static { v5 }, $RICH->preview($STR)$STR
            move-result-object v5
            invoke-static { v5 }, $textFactory
            move-result-object v4
        """)))
    val profileSectionCtor = classes.flatMap { it.methods }.single { it.name == "<init>" &&
        it.parameterTypes == listOf(profile.type, STR, STR) }
    val profileCtorStart = profileSectionCtor.implementation!!.registerCount - 4
    val profileSectionFields = (1..3).map { parameter ->
        profileSectionCtor.implementation!!.instructions.single { it.opcode == Opcode.IPUT_OBJECT &&
            (it as TwoRegisterInstruction).registerA == profileCtorStart + parameter && it.registerB == profileCtorStart }
            .let { (it as ReferenceInstruction).reference as FieldReference }
    }
    bridge("profileTarget", 11, """
        check-cast p0, ${profileSectionCtor.definingClass}
        check-cast p1, ${profile.type}
        const/4 v1, 0x6
        iget-object v2, p0, ${profileSectionFields[2]}
        check-cast v2, $STR
        iget-object v3, p0, ${profileSectionFields[1]}
        const/4 v4, 0x0
        iget-object v5, p1, ${field(profile.model, "commentPreview=")}
        instance-of v0, v5, $plainType
        if-eqz v0, :noPreview
        check-cast v5, $plainType
        iget-object v5, v5, $plainField
        goto :ready
        :noPreview
        const-string v5, "\\u0000"
        :ready
        const/4 v6, 0x0
        const/4 v7, 0x0
        new-instance v0, $TARGET
        invoke-direct/range { v0 .. v7 }, $TARGET-><init>(I$STR$STR$STR$STR${STR}Z)V
        return-object v0
    """)
    val profileRenderer = mutableClassDefBy(profileSectionCtor.definingClass).methods.single {
        it.name == sectionContent.name && it.parameterTypes == sectionContent.parameterTypes }
    val profileInstructions = profileRenderer.implementation!!.instructions.toList()
    val castIndex = profileInstructions.indexOfFirst { it.opcode == Opcode.CHECK_CAST &&
        ((it as ReferenceInstruction).reference as TypeReference).type == profile.type }
    if (castIndex < 0) throw PatchException("Missing native profile preview renderer")
    val stateRegister = (profileInstructions[castIndex] as OneRegisterInstruction).registerA
    val groupIndex = (castIndex + 1 until castIndex + 8).first { index ->
        ((profileInstructions[index] as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.parameterTypes == listOf("I") && it.returnType == it.definingClass } == true }
    val profileRegisters = profileRenderer.implementation!!.registerCount
    val profileComposer = (profileInstructions[groupIndex] as FiveRegisterInstruction).registerC
    val (profilePatched, profileShift) = profileRenderer.addFreeRegisters(4)
    profilePatched.addInstructions(profileShift, "move-object/16 v${profileRegisters + 3}, v${profileRegisters - 4}")
    profilePatched.addInstructionsAtControlFlowLabel(groupIndex + 2 + profileShift, """
        move-object/16 v$profileRegisters, v${profileRegisters + 3}
        move-object/16 v${profileRegisters + 1}, v$stateRegister
        move-object/16 v${profileRegisters + 2}, v$profileComposer
        invoke-static/range { v$profileRegisters .. v${profileRegisters + 2} }, $RECOVERY->recoverProfile($OBJ$OBJ$OBJ)$OBJ
        move-result-object v$profileRegisters
        check-cast v$profileRegisters, ${profile.type}
        move-object/16 v$stateRegister, v$profileRegisters
    """)

    bridge("describe", 11, buildString {
        specs.forEachIndexed { i, spec ->
            appendLine("instance-of v0, p0, ${spec.s.type}\nif-eqz v0, :next$i\ncheck-cast p0, ${spec.s.type}")
            appendLine("const/16 v1, ${spec.kind}\niget-object v2, p0, ${spec.id}")
            listOf(spec.parent, spec.title, spec.body, spec.author).forEachIndexed { n, f ->
                appendLine(if (f == null) "const/4 v${n + 3}, 0x0" else "iget-object v${n + 3}, p0, $f")
            }
            groupedGetters[spec.kind]?.forEach { (reg, call) -> appendLine("invoke-static { p0 }, $call\nmove-result-object v$reg") }
            if (spec.kind == 3) {
                appendLine("iget-object v8, p0, ${field(spec.s.model, "details=")}")
                appendLine("invoke-static { v6, v8 }, $RECOVERY->authorText($STR$STR)$STR\nmove-result-object v6")
            }
            appendLine("const/4 v7, ${if (spec.removed) 1 else 0}")
            if (spec.kind == 8) appendLine("iget-boolean v7, p0, ${field(commentBody.model, "isRemovedByCategoryAvailable=")}")
            if (spec.kind == 9) {
                val statusModel = model("CommentStatusViewState(isRemoved=")
                val status = header.fields.single { it.type == statusModel.definingClass }
                appendLine("iget-object v8, p0, $status\nif-eqz v8, :headerReady\niget-boolean v7, v8, ${field(statusModel, "isRemoved=")}\n:headerReady")
            }
            appendLine("new-instance v0, $TARGET\ninvoke-direct/range { v0 .. v7 }, $TARGET-><init>(I$STR$STR$STR$STR${STR}Z)V\nreturn-object v0\n:next$i")
        }
        appendLine("instance-of v0, p0, ${post.type}\nif-eqz v0, :none")
        appendLine("const/16 v1, 10")
        listOf(2 to modernId, 4 to modernTitle, 5 to modernBody, 6 to modernAuthor).forEach { (r, call) ->
            appendLine("invoke-static { p0 }, $call\nmove-result-object v$r")
        }
        appendLine("const/4 v3, 0x0\nconst/4 v7, 0x0\ncheck-cast p0, ${post.type}")
        appendLine("iget-object v8, p0, ${field(post.model, "postRemovedRetryM2=")}\nif-nez v8, :modernRemoved")
        appendLine("iget-object v8, p0, ${field(post.model, "metadata=")}\nif-eqz v8, :modernReady")
        appendLine("iget-object v8, v8, ${field(meta.model, "removedByModeratorBanner=")}\nif-eqz v8, :modernReady\n:modernRemoved\nconst/4 v7, 0x1\n:modernReady")
        appendLine("new-instance v0, $TARGET\ninvoke-direct/range { v0 .. v7 }, $TARGET-><init>(I$STR$STR$STR$STR${STR}Z)V\nreturn-object v0")
        appendLine(":none\nconst/4 v0, 0x0\nreturn-object v0")
    })
    bridge("copy", 4, buildString {
        (specs.map { it.s.type to it.copier } + (post.type to postCopy) + (profile.type to profileCopy)).forEachIndexed { i, (type, call) ->
            appendLine("instance-of v0, p0, $type\nif-eqz v0, :next$i")
            appendLine("invoke-static { p0, p1, p2 }, $call\nmove-result-object v0\nreturn-object v0\n:next$i")
        }
        appendLine("return-object p0")
    })

    // Insert inside each native restart group so an async state update re-enters this hook.
    val composer = sectionContent.parameterTypes[1].toString()
    val targets = setOf(specs.single { it.kind == 4 }.s.type, commentBody.type, header.type, post.type)
    val renderers = classes.flatMap { it.methods }.filter { m ->
        m.returnType == "V" && m.parameterTypes.contains(composer) && m.parameterTypes.any { it in targets } && m.implementation != null
    }.toList()
    if (renderers.none { commentBody.type in it.parameterTypes } || renderers.none { header.type in it.parameterTypes }
        || renderers.none { post.type in it.parameterTypes }) throw PatchException("Missing native recovery renderers")
    val installed = mutableSetOf<String>()
    var hookCount = 0
    renderers.forEach { renderer ->
        val instructions = renderer.implementation!!.instructions.toList()
        val group = instructions.withIndex().firstOrNull { (i, ins) -> i < 90 &&
            ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                ref.parameterTypes == listOf("I") && ref.returnType == ref.definingClass &&
                    classDefByOrNull(ref.definingClass)?.interfaces?.contains(composer) == true
            } == true } ?: return@forEach // Inline delegating renderers have no restart scope of their own.
        val old = renderer.implementation!!.registerCount
        val params = renderer.parameterTypes
        val words = params.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val start = old - words
        fun register(index: Int) = start + params.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
        val composerReg = when (val call = group.value) {
            is FiveRegisterInstruction -> call.registerC
            is RegisterRangeInstruction -> call.startRegister
            else -> throw PatchException("Unexpected native restart group invocation: $renderer")
        }
        val targetIndex = params.indexOfFirst { it in targets }
        val modelReg = register(targetIndex)
        installed.add(params[targetIndex].toString()); hookCount++
        val mutable = mutableClassDefBy(renderer.definingClass).methods.single {
            it.name == renderer.name && it.parameterTypes == params && it.returnType == renderer.returnType
        }
        var insertion = group.index + 1
        if (instructions.getOrNull(insertion)?.opcode == Opcode.MOVE_RESULT_OBJECT) insertion++
        fun aliases(register: Int): Set<Int> {
            val result = mutableSetOf(register)
            instructions.take(insertion).forEach { ins ->
                if (ins is TwoRegisterInstruction && ins.opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16,
                    Opcode.MOVE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
                    if (ins.registerB in result) result.add(ins.registerA) else result.remove(ins.registerA)
                } else if (ins.opcode.setsRegister() && ins.opcode != Opcode.CHECK_CAST && ins is OneRegisterInstruction) result.remove(ins.registerA)
            }
            return result
        }
        val modelAliases = aliases(modelReg)
        val modelSource = modelAliases.firstOrNull() ?: throw PatchException("Native model lost before restart group: $renderer")
        val flagIndex = params.indexOf(composer) + 1 + targetIndex / 10
        if (params.getOrNull(flagIndex) != "I") throw PatchException("Missing Compose changed flags: $renderer")
        val flagAliases = aliases(register(flagIndex))
        val mask = (7 shl (1 + 3 * (targetIndex % 10))).inv()
        val (patched, shift) = mutable.addFreeRegisters(3)
        patched.addInstructionsAtControlFlowLabel(insertion + shift, """
            move-object/16 v$old, v$modelSource
            move-object/16 v${old + 1}, v$composerReg
            invoke-static/range { v$old .. v${old + 1} }, $RECOVERY->recover($OBJ$OBJ)$OBJ
            move-result-object v$old
            check-cast v$old, ${params[targetIndex]}
        """ + modelAliases.joinToString("\n", prefix = "\n") { "move-object/16 v$it, v$old" } +
            "\nconst v${old + 2}, $mask\n" + flagAliases.joinToString("\n") {
                "and-int v$it, v$it, v${old + 2}"
            })
    }
    if (!installed.containsAll(targets)) throw PatchException("Missing native recovery restart scopes: ${targets - installed}")
    println("Installed native undelete: ${specs.size} feed/comment text models, modern post content, $hookCount Compose hooks and profile preview")
    installNativeFeedGate(sectionInterface, sectionContent, sectionKey)
}
