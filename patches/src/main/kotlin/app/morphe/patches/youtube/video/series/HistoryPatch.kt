package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.layout.buttons.navigation.PivotBarRendererFingerprint
import app.morphe.patches.youtube.layout.buttons.navigation.PivotBarRendererListFingerprint
import app.morphe.patches.youtube.shared.YouTubeMainActivityOnBackPressedFingerprint
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*

private const val NAV = "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;"
private const val OUR_NAV = "${OUR_PREFIX}HistoryNavigation;"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

internal fun BytecodePatchContext.wireHistory() {
    val factory = PivotBarRendererFingerprint.method
    if (factory.parameterTypes.size != 1 || !AccessFlags.STATIC.isSet(factory.accessFlags))
        throw PatchException("Series Tracker: pivot factory changed")
    val instructions = factory.implementation!!.instructions.toList()
    val parse =
        instructions
            .mapNotNull { it.methodRef() }
            .first { it.name == "parseFrom" && it.parameterTypes.lastOrNull() == "[B" }
    val registryType = "Lcom/google/protobuf/ExtensionRegistryLite;"
    val registeredParse =
        classDefBy(parse.definingClass).methods.single {
            it.name == "parseFrom" &&
                it.parameterTypes.map(CharSequence::toString) ==
                    parse.parameterTypes.map(CharSequence::toString) + registryType
        }
    check(
        classDefBy(registryType).methods.any {
            it.name == "getGeneratedRegistry" && it.parameterTypes.isEmpty()
        }
    )
    val wrapperType = factory.parameterTypes.single().toString()
    val wrapper = classDefBy(wrapperType)
    val default =
        wrapper.fields.single { it.type == wrapperType && AccessFlags.STATIC.isSet(it.accessFlags) }
    val bridge = mutableClassDefBy(OUR_NAV)
    bridge.methods.removeIf { it.name == "buildNative" }
    val builder =
        ImmutableMethod(
                OUR_NAV,
                "buildNative",
                listOf(ImmutableMethodParameter("[B", emptySet(), null)),
                "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(3, emptyList(), emptyList(), emptyList()),
            )
            .toMutable()
    builder.addInstructions(
        0,
        """
        sget-object v0, ${default.definingClass}->${default.name}:${default.type}
        invoke-static {}, $registryType->getGeneratedRegistry()$registryType
        move-result-object v1
        invoke-static {v0, p0, v1}, ${registeredParse.definingClass}->${registeredParse.signature()}
        move-result-object v0
        check-cast v0, $wrapperType
        invoke-static {v0}, ${factory.definingClass}->${factory.signature()}
        move-result-object v0
        const/4 v1, 0x0
        invoke-virtual {v0, v1}, ${factory.returnType}->orElse(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        return-object v0
    """
            .trimIndent(),
    )
    bridge.methods.add(builder)
    val mutableFactory =
        mutableClassDefBy(factory.definingClass).methods.single {
            it.signature() == factory.signature()
        }
    var captures = 0
    instructions.indices.reversed().forEach { index ->
        val instruction = instructions[index]
        val ref = instruction.methodRef()
        if (
            ref?.name == "<init>" &&
                ref.definingClass == factory.definingClass &&
                ref.parameterTypes.firstOrNull() == "Lcom/google/protobuf/MessageLite;"
        ) {
            val range =
                instruction as? RegisterRangeInstruction
                    ?: throw PatchException(
                        "Series Tracker: pivot constructor register shape changed"
                    )
            val instance = range.startRegister
            val proto = instance + 1
            if (proto > 15)
                throw PatchException("Series Tracker: pivot registers exceed safe range")
            mutableFactory.addInstructions(
                index + 1,
                "invoke-static {v$proto, v$instance}, $OUR_NAV->capture(Lcom/google/protobuf/MessageLite;Ljava/lang/Object;)V",
            )
            captures++
        }
    }
    if (captures !in 4..5)
        throw PatchException(
            "Series Tracker: expected four or five native pivot constructors, found $captures"
        )
    var lists = 0
    run {
        val original = PivotBarRendererListFingerprint.method
        val clazz = classDefBy(original.definingClass)
        val ins = original.implementation?.instructions?.toList().orEmpty()
        ins.indices.reversed().forEach { i ->
            if (
                ins[i].methodRef()?.let {
                    it.definingClass == NAV && it.name == "getPivotBarRendererList"
                } == true
            ) {
                val result = ins[i + 1] as OneRegisterInstruction
                if (ins[i + 1].opcode != Opcode.MOVE_RESULT_OBJECT)
                    throw PatchException("Series Tracker: native pivot list return changed")
                mutableClassDefBy(clazz.type)
                    .methods
                    .single { it.signature() == original.signature() }
                    .addInstructions(
                        i + 2,
                        """
                    invoke-static/range {v${result.registerA} .. v${result.registerA}}, $OUR_NAV->navigation(Ljava/util/List;)Ljava/util/List;
                    move-result-object v${result.registerA}
                """
                            .trimIndent(),
                    )
                lists++
            }
        }
    }
    if (lists != 1)
        throw PatchException("Series Tracker: expected one native pivot list, found $lists")

    val visibility =
        mutableClassDefBy(NAV).methods.single {
            it.name == "navigationTabCreated" && it.parameterTypes.size == 2
        }
    if (visibility.implementation!!.registerCount < 3)
        throw PatchException("Series Tracker: navigation visibility needs a local register")
    visibility.addInstructions(
        0,
        """
        invoke-static {p0}, $OUR_NAV->keepButton(Ljava/lang/Enum;)Z
        move-result v0
        if-eqz v0, :native_visibility
        return-void
        :native_visibility
        nop
    """
            .trimIndent(),
    )
    val back = YouTubeMainActivityOnBackPressedFingerprint.method
    if (back.implementation!!.registerCount < 2)
        throw PatchException("Series Tracker: back navigation needs a local register")
    mutableClassDefBy(back.definingClass)
        .methods
        .single { it.signature() == back.signature() }
        .addInstructions(
            0,
            """
        invoke-static {}, ${OUR_PREFIX}HistoryUi;->onBack()Z
        move-result v0
        if-eqz v0, :native_back
        return-void
        :native_back
        nop
    """
                .trimIndent(),
        )

    // Match the History browse fragment by its route diagnostic, then wrap the page and toolbar.
    val browse = BrowseFragmentFingerprint.matchAll(1..1).single()
    val fragment = browse.originalClassDef
    val create = browse.originalMethod
    val createIns = create.implementation!!.instructions.toList()
    val diagnostic = createIns.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
            "Browse Fragment was given a navigation endpoint without browse data."
    }
    val endpointField =
        createIns
            .take(diagnostic)
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .last { it.definingClass == fragment.type && it.type.startsWith("L") }
    val routeMethod =
        fragment.methods
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { it.methodRef() }
            .filter {
                it.returnType == "Ljava/lang/String;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(endpointField.type)
            }
            .distinct()
            .firstOrNull() ?: throw PatchException("Series Tracker: browse route accessor missing")
    val mutableFragment = mutableClassDefBy(fragment.type)
    val hook =
        ImmutableMethod(
                fragment.type,
                "seriesTrackerHistoryView",
                listOf(ImmutableMethodParameter("Landroid/view/View;", emptySet(), null)),
                "Landroid/view/View;",
                AccessFlags.PUBLIC.value,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(4, emptyList(), emptyList(), emptyList()),
            )
            .toMutable()
    hook.addInstructions(
        0,
        """
        iget-object v0, p0, ${endpointField.definingClass}->${endpointField.name}:${endpointField.type}
        invoke-static {v0}, $routeMethod
        move-result-object v0
        invoke-static {p1, v0}, ${OUR_PREFIX}HistoryUi;->wrap(Landroid/view/View;Ljava/lang/String;)Landroid/view/View;
        move-result-object v0
        return-object v0
    """
            .trimIndent(),
    )
    mutableFragment.methods.add(hook)
    // Common toolbar wrapper is the final one-View -> View call in onCreateView.
    val contentIndex =
        createIns.indices.last { i ->
            createIns[i].methodRef()?.let {
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
                    it.returnType == "Landroid/view/View;"
            } == true
        }
    val call = createIns[contentIndex] as FiveRegisterInstruction
    val result = createIns.getOrNull(contentIndex + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || result !is OneRegisterInstruction)
        throw PatchException("Series Tracker: native page wrapper result missing")
    val view = result.registerA
    val instance = call.registerC
    if (view == instance)
        throw PatchException("Series Tracker: native page wrapper overwrites fragment receiver")
    mutableFragment.methods
        .single { it.signature() == create.signature() }
        .addInstructions(
            contentIndex + 2,
            """
        invoke-virtual {v$instance, v$view}, ${fragment.type}->seriesTrackerHistoryView(Landroid/view/View;)Landroid/view/View;
        move-result-object v$view
    """
                .trimIndent(),
        )

    // The playlist toolbar populates its own Menu, independently of the activity menu.
    val toolbar =
        classDefByOrNull("Landroid/support/v7/widget/Toolbar;")
            ?: throw PatchException("Series Tracker: native playlist toolbar missing")
    val menuGetter =
        toolbar.methods.singleOrNull {
            it.parameterTypes.isEmpty() &&
                it.returnType == "Landroid/view/Menu;" &&
                it.accessFlags and AccessFlags.PUBLIC.value != 0 &&
                it.accessFlags and AccessFlags.STATIC.value == 0
        } ?: throw PatchException("Series Tracker: native toolbar menu accessor is ambiguous")
    val mutableToolbar = mutableClassDefBy(toolbar.type)
    mutableToolbar.interfaces.add("${OUR_PREFIX}PlaylistMenu\$ToolbarSource;")
    val menuBridge =
        ImmutableMethod(
                toolbar.type,
                "seriesTrackerMenu",
                emptyList(),
                "Landroid/view/Menu;",
                AccessFlags.PUBLIC.value,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(2, emptyList(), emptyList(), emptyList()),
            )
            .toMutable()
    menuBridge.addInstructions(
        0,
        """
        invoke-virtual {p0}, $menuGetter
        move-result-object v0
        return-object v0
    """
            .trimIndent(),
    )
    mutableToolbar.methods.add(menuBridge)
}
