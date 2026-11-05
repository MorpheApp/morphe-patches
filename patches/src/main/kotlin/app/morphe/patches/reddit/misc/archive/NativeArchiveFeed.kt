package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val CONTROLLER = "Lapp/morphe/extension/reddit/patches/archive/ArchiveController;"
internal const val SECTION = "Lapp/morphe/extension/reddit/patches/archive/ArchiveSection;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"

internal val nativeFeedModel = Fingerprint(name = "toString", returnType = "Ljava/lang/String;",
    parameters = emptyList(), filters = listOf(string("Feed(sections=")))
internal val nativeFeedState = Fingerprint(filters = listOf(string("Feed.ViewModel.createViewState")))
internal val nativeProfileSectionKey = Fingerprint(returnType = "Ljava/lang/String;", parameters = emptyList(),
    isStatic = false, filters = listOf(string("profile_no_content_banner")))

private fun Method.calls(): List<MethodReference> = implementation!!.instructions.mapNotNull {
    (it as? ReferenceInstruction)?.reference as? MethodReference
}.distinctBy { it.toString() }

/** Constructor-parameter assignments, verified instead of relying on obfuscated field names. */
internal fun constructorFields(constructor: Method): List<FieldReference> {
    if (constructor.parameterTypes.any { it == "J" || it == "D" }) throw PatchException("Unexpected wide native feed field")
    val start = constructor.implementation!!.registerCount - constructor.parameterTypes.size - 1
    return constructor.parameterTypes.indices.map { index ->
        constructor.implementation!!.instructions.filter {
            it.opcode in setOf(Opcode.IPUT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_OBJECT) &&
                (it as TwoRegisterInstruction).registerA == start + index + 1 && it.registerB == start &&
                ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                    field.definingClass == constructor.definingClass && field.type == constructor.parameterTypes[index]
                }
        }.single().let { (it as ReferenceInstruction).reference as FieldReference }
    }
}

context(context: BytecodePatchContext)
private fun addNativeMethod(owner: String, signature: Method, code: String) {
    val method = ImmutableMethod(owner, signature.name,
        signature.parameterTypes.map { ImmutableMethodParameter(it.toString(), emptySet(), null) },
        signature.returnType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        emptySet(), emptySet(), MutableMethodImplementation(signature.parameterTypes.size + 2)).toMutable()
    method.addInstructions(0, code)
    val target = context.mutableClassDefBy(owner)
    if (target.methods.any { it.name == method.name && it.parameterTypes == method.parameterTypes })
        throw PatchException("Native archive method collision: $method")
    target.methods.add(method)
}

context(context: BytecodePatchContext)
internal fun installNativeArchiveFeed(modelType: String, renderer: Method, composerInterface: String) = with(context) {
    val stateMethod = nativeFeedState.method
    if (stateMethod.parameterTypes.size != 1 || AccessFlags.STATIC.isSet(stateMethod.accessFlags))
        throw PatchException("Unexpected native feed composition signature")
    val composer = stateMethod.parameterTypes.single().toString()
    val hostCalls = stateMethod.calls()
    val rememberGet = hostCalls.single { it.definingClass == composer && it.returnType == OBJECT && it.parameterTypes.isEmpty() }
    val rememberSet = hostCalls.single { it.definingClass == composer && it.returnType == "V" && it.parameterTypes == listOf(OBJECT) }
    val groupStart = hostCalls.single { it.definingClass == composer && it.returnType == "V" && it.parameterTypes == listOf("I") }
    val groupEnd = hostCalls.single { it.definingClass == composer && it.returnType == "V" && it.parameterTypes.isEmpty() }
    replaceArchiveBridge("begin", 2, """
        check-cast p0, $composer
        const v0, 0x41724334
        invoke-virtual { p0, v0 }, $groupStart
        return-void
    """)
    replaceArchiveBridge("remembered", 2, """
        check-cast p0, $composer
        invoke-virtual { p0 }, $rememberGet
        move-result-object v0
        return-object v0
    """)
    replaceArchiveBridge("remember", 2, """
        check-cast p0, $composer
        invoke-virtual { p0, p1 }, $rememberSet
        return-void
    """)
    replaceArchiveBridge("end", 1, """
        check-cast p0, $composer
        invoke-virtual { p0 }, $groupEnd
        return-void
    """)

    val sectionKey = nativeProfileSectionKey.method
    val sectionOwner = classDefBy(sectionKey.definingClass)
    val sectionInterface = sectionOwner.interfaces.single { type ->
        val methods = classDefBy(type).methods
        methods.any { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" } &&
            methods.any { it.returnType == "V" && it.parameterTypes.size == 3 && it.parameterTypes[1] == composerInterface }
    }
    val sectionContent = classDefBy(sectionInterface).methods.single { it.returnType == "V" }
    val sectionKeySignature = classDefBy(sectionInterface).methods.single { it.returnType == "Ljava/lang/String;" }
    if (sectionContent.parameterTypes.last() != "I") throw PatchException("Unexpected native section renderer")
    val sectionBody = sectionOwner.methods.single { it.name == sectionContent.name && it.parameterTypes == sectionContent.parameterTypes }
    if (sectionBody.calls().none { it.toString() == renderer.toString() }) throw PatchException("Profile section does not call the expected renderer")
    val modelField = sectionOwner.fields.single { it.type == OBJECT }

    // b7f's feed converter may wrap the profile section in a QSF tracking section.
    // Resolve that container from its named builder lambda and verify key delegation;
    // do not depend on the feature flag or scan arbitrary Object fields at runtime.
    val trackingBuilder = Fingerprint(filters = listOf(newInstance(
        "Lcom/reddit/feeds/impl/RedditFeedQsfSectionWrapper${'$'}buildQsfTrackedSection${'$'}1;"
    ))).method
    val wrapperConstructor = trackingBuilder.calls().single { it.name == "<init>" &&
        it.parameterTypes.firstOrNull() == sectionInterface }
    val wrapper = classDefBy(wrapperConstructor.definingClass)
    if (trackingBuilder.returnType != wrapper.type || !wrapper.interfaces.any {
            it == sectionInterface || sectionInterface in classDefBy(it).interfaces
        } || !AccessFlags.PUBLIC.isSet(wrapper.accessFlags) ||
        wrapperConstructor.parameterTypes.size != 8 ||
        wrapperConstructor.parameterTypes[4] != "Lcom/reddit/qsf/components/QsfDeliveryContext;")
        throw PatchException("Unexpected native QSF section wrapper")
    val delegate = constructorFields(wrapper.methods.single { it.toString() == wrapperConstructor.toString() }).first()
    val wrapperKey = wrapper.methods.single { it.name == sectionKeySignature.name &&
        it.parameterTypes == sectionKeySignature.parameterTypes && it.returnType == sectionKeySignature.returnType }
    val keyInstructions = wrapperKey.implementation!!.instructions.toList()
    if (delegate.type != sectionInterface || !AccessFlags.PUBLIC.isSet(wrapper.fields.single {
            it.toString() == delegate.toString()
        }.accessFlags) ||
        keyInstructions.map { it.opcode } != listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE,
            Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT) ||
        (keyInstructions[0] as ReferenceInstruction).reference.toString() != delegate.toString() ||
        (keyInstructions[1] as ReferenceInstruction).reference.toString() != sectionKeySignature.toString())
        throw PatchException("Native QSF wrapper does not delegate the section key")
    replaceArchiveBridge("sectionModel", 3, """
        const/16 v1, 0x8
        :unwrap
        instance-of v0, p0, ${wrapper.type}
        if-eqz v0, :model
        if-eqz v1, :none
        check-cast p0, ${wrapper.type}
        iget-object p0, p0, $delegate
        add-int/lit8 v1, v1, -0x1
        goto :unwrap
        :model
        instance-of v0, p0, ${sectionOwner.type}
        if-eqz v0, :none
        check-cast p0, ${sectionOwner.type}
        iget-object p0, p0, $modelField
        instance-of v0, p0, $modelType
        if-eqz v0, :none
        return-object p0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    mutableClassDefBy(SECTION).interfaces.add(sectionInterface)
    addNativeMethod(SECTION, sectionKeySignature, """
        invoke-virtual { p0 }, $SECTION->key()Ljava/lang/String;
        move-result-object v0
        return-object v0
    """)
    addNativeMethod(SECTION, sectionContent, """
        invoke-virtual { p0, p2 }, $SECTION->render(Ljava/lang/Object;)V
        return-void
    """)

    // Reuse the host snapshot state and remember lifecycle. Do not ship a second Compose runtime.
    val newState = Fingerprint(definingClass = "Landroidx/compose/runtime/", isStatic = true,
        parameters = listOf(OBJECT), filters = listOf(newInstance("Landroidx/compose/runtime/ParcelableSnapshotMutableState;"))).method
    val mutableState = classDefBy(newState.returnType)
    val setValue = mutableState.methods.single { it.name == "setValue" && it.parameterTypes == listOf(OBJECT) }
    val stateInterface = mutableState.interfaces.single { type -> classDefBy(type).methods.any { it.name == "getValue" } }
    val getValue = classDefBy(stateInterface).methods.single { it.name == "getValue" }
    replaceArchiveBridge("newState", 1, """
        invoke-static { p0 }, $newState
        move-result-object p0
        return-object p0
    """)
    replaceArchiveBridge("readState", 1, """
        check-cast p0, $stateInterface
        invoke-interface { p0 }, $getValue
        move-result-object p0
        return-object p0
    """)
    replaceArchiveBridge("writeState", 2, """
        check-cast p0, ${mutableState.type}
        invoke-interface { p0, p1 }, $setValue
        return-void
    """)
    val composerSave = classDefBy(composer).methods.single { it.toString() == rememberSet.toString() }
    val observerType = composerSave.implementation!!.instructions.filter { it.opcode == Opcode.INSTANCE_OF }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.single()
    val effect = classDefBy { owner -> owner.interfaces.contains(observerType) &&
        owner.fields.any { it.type == "Lkotlin/jvm/functions/Function1;" } &&
        owner.fields.count() == 2 }
    val lifecycle = effect.methods.filter { it.name != "<init>" && it.parameterTypes.isEmpty() && it.returnType == "V" }
    val onRemembered = lifecycle.single { method -> method.calls().any { it.definingClass == "Lkotlin/jvm/functions/Function1;" } }
    val onForgotten = lifecycle.single { it != onRemembered && it.calls().isNotEmpty() }
    val onAbandoned = lifecycle.single { it != onRemembered && it != onForgotten }
    mutableClassDefBy(CONTROLLER).interfaces.add(observerType)
    mapOf(onRemembered to "onRemembered", onForgotten to "onForgotten", onAbandoned to "onAbandoned").forEach { (host, local) ->
        addNativeMethod(CONTROLLER, host, """
            invoke-virtual { p0 }, $CONTROLLER->$local()V
            return-void
        """)
    }

    val feedToString = nativeFeedModel.method
    val feedType = feedToString.definingClass
    val feedConstructor = nativeFeedModel.classDef.methods.single { it.name == "<init>" }
    val feedFields = constructorFields(feedConstructor)
    val sections = feedToString.findFieldFromToString("sections=")
    val visible = feedToString.findFieldFromToString("isScreenVisible=")
    if (feedFields.first() != sections || visible.type != "Z" || feedFields.size != 11)
        throw PatchException("Unexpected native feed model shape")
    replaceArchiveBridge("feedSections", 1, """
        check-cast p0, $feedType
        iget-object p0, p0, $sections
        return-object p0
    """)
    replaceArchiveBridge("feedVisible", 1, """
        check-cast p0, $feedType
        iget-boolean p0, p0, $visible
        return p0
    """)
    val immutable = hostCalls.single { it.parameterTypes == listOf("Ljava/lang/Iterable;") && it.returnType == sections.type }
    replaceArchiveBridge("immutableList", 1, """
        invoke-static { p0 }, $immutable
        move-result-object p0
        return-object p0
    """)
    replaceArchiveBridge("copyFeed", 14, buildString {
        appendLine("check-cast p0, $feedType")
        appendLine("check-cast p1, ${sections.type}")
        appendLine("new-instance v0, $feedType")
        appendLine("move-object v1, p1")
        feedFields.drop(1).forEachIndexed { index, field ->
            val operation = if (field.type == "Z") "iget-boolean" else if (field.type.startsWith("L")) "iget-object" else "iget"
            appendLine("$operation v${index + 2}, p0, $field")
        }
        appendLine("invoke-direct/range { v0 .. v11 }, $feedConstructor")
        appendLine("return-object v0")
    })

    // Transform before the feed is handed to the lazy list, inside its existing composition group.
    val oldCount = stateMethod.implementation!!.registerCount
    val constructorCall = stateMethod.implementation!!.instructions.withIndex().single {
        ((it.value as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == feedConstructor.toString()
    }
    val receiver = when (val instruction = constructorCall.value) {
        is RegisterRangeInstruction -> instruction.startRegister
        is FiveRegisterInstruction -> instruction.registerC
        else -> throw PatchException("Unexpected feed constructor opcode")
    }
    val (patched, shift) = stateMethod.addFreeRegisters(2)
    patched.addInstructions(constructorCall.index + shift + 1, """
        move-object/16 v$oldCount, v$receiver
        move-object/16 v${oldCount + 1}, v${oldCount - 1}
        invoke-static/range { v$oldCount .. v${oldCount + 1} }, $ARCHIVE_CLASS->decorate(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v$oldCount
        check-cast v$oldCount, $feedType
        move-object/16 v$receiver, v$oldCount
    """)

    val visibleInfo = Fingerprint(name = "toString", filters = listOf(string("VisibleItemInfo(index="))).classDef
    val visibleKeyField = visibleInfo.fields.single { it.type == "Ljava/lang/String;" }
    replaceArchiveBridge("visibleKey", 2, """
        instance-of v0, p0, ${visibleInfo.type}
        if-eqz v0, :none
        check-cast p0, ${visibleInfo.type}
        iget-object v0, p0, $visibleKeyField
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    val visibleEvent = Fingerprint(name = "toString", filters = listOf(string("VisibleItemsChangedEvent(items="))).classDef
    val eventConstructor = mutableClassDefBy(visibleEvent.type).methods.single { it.name == "<init>" && it.parameterTypes == listOf("Ljava/util/ArrayList;") }
    eventConstructor.addInstructions(0, """
        invoke-static/range { p1 .. p1 }, $ARCHIVE_CLASS->onVisibleItems(Ljava/util/List;)V
    """)
}
