package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.layout.buttons.navigation.PivotBarRendererFingerprint
import app.morphe.patches.youtube.layout.buttons.navigation.PivotBarRendererListFingerprint
import app.morphe.patches.youtube.shared.YouTubeMainActivityOnBackPressedFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*

private const val NAV = "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;"
private const val OUR_NAV = "${OUR_PREFIX}HistoryNavigation;"

internal fun BytecodePatchContext.wireHistory() {
    val factory = PivotBarRendererFingerprint.method
    if (factory.parameterTypes.size != 1 || !AccessFlags.STATIC.isSet(factory.accessFlags))
        throw PatchException("Series Tracker: pivot factory changed")
    val instructions = factory.implementation!!.instructions.toList()
    val parseIndex =
        factory.indexOfFirstInstructionOrThrow(
            methodCall(name = "parseFrom", parameters = listOf("L", "[B"))
        )
    val parse = instructions[parseIndex].getReference<MethodReference>()!!
    val registeredParse = RegisteredParseFingerprint(parse).matchAll(1..1).single().method
    val registry = GeneratedRegistryFingerprint.matchAll(1..1).single().method
    val wrapperType = factory.parameterTypes.single().toString()
    val wrapper = classDefBy(wrapperType)
    val default =
        wrapper.fields.single { it.type == wrapperType && AccessFlags.STATIC.isSet(it.accessFlags) }
    val bridgeMatch = HistoryNavigationBuilderFingerprint.matchAll(1..1).single()
    val bridge = bridgeMatch.classDef
    bridge.methods.remove(bridgeMatch.method)
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
        invoke-static {}, $registry
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
    val captures = factory.findInstructionIndicesReversedOrThrow {
        getReference<MethodReference>()?.let { ref ->
            ref.name == "<init>" &&
                ref.definingClass == factory.definingClass &&
                ref.parameterTypes.firstOrNull() == "Lcom/google/protobuf/MessageLite;"
        } == true
    }
    if (captures.size !in 4..5)
        throw PatchException(
            "Series Tracker: expected four or five native pivot constructors, found ${captures.size}"
        )
    captures.forEach { index ->
        val range =
            instructions[index] as? RegisterRangeInstruction
                ?: throw PatchException("Series Tracker: pivot constructor register shape changed")
        val instance = range.startRegister
        val proto = instance + 1
        if (proto > 15) throw PatchException("Series Tracker: pivot registers exceed safe range")
        factory.addInstructions(
            index + 1,
            "invoke-static {v$proto, v$instance}, $OUR_NAV->capture(Lcom/google/protobuf/MessageLite;Ljava/lang/Object;)V",
        )
    }
    val listMethod = PivotBarRendererListFingerprint.method
    val listIndices =
        listMethod.findInstructionIndicesReversedOrThrow(
            methodCall(definingClass = NAV, name = "getPivotBarRendererList")
        )
    if (listIndices.size != 1)
        throw PatchException(
            "Series Tracker: expected one native pivot list, found ${listIndices.size}"
        )
    val listIndex = listIndices.single()
    val resultInstruction = listMethod.getInstruction<Instruction>(listIndex + 1)
    if (
        resultInstruction.opcode != Opcode.MOVE_RESULT_OBJECT ||
            resultInstruction !is OneRegisterInstruction
    )
        throw PatchException("Series Tracker: native pivot list return changed")
    val listRegister = resultInstruction.registerA
    listMethod.addInstructions(
        listIndex + 2,
        """
        invoke-static/range {v$listRegister .. v$listRegister}, $OUR_NAV->navigation(Ljava/util/List;)Ljava/util/List;
        move-result-object v$listRegister
        """
            .trimIndent(),
    )

    val visibility = NavigationTabCreatedFingerprint.matchAll(1..1).single().method
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
    back.addInstructions(
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
    val diagnostic = browse.instructionMatches.single().index
    val endpointIndex =
        create.indexOfFirstInstructionReversedOrThrow(
            diagnostic,
            fieldAccess(definingClass = "this", type = "L"),
        )
    val endpointField = createIns[endpointIndex].getReference<FieldReference>()!!
    val routeMethod =
        BrowseRouteFingerprint(endpointField.type)
            .match(fragment)
            .instructionMatches
            .single()
            .getInstruction<ReferenceInstruction>()
            .reference as MethodReference
    val mutableFragment = browse.classDef
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
        create.indexOfFirstInstructionReversedOrThrow(
            methodCall(
                parameters = listOf("Landroid/view/View;"),
                returnType = "Landroid/view/View;",
            )
        )
    val call = createIns[contentIndex] as FiveRegisterInstruction
    val result = createIns.getOrNull(contentIndex + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || result !is OneRegisterInstruction)
        throw PatchException("Series Tracker: native page wrapper result missing")
    val view = result.registerA
    val instance = call.registerC
    if (view == instance)
        throw PatchException("Series Tracker: native page wrapper overwrites fragment receiver")
    browse.method.addInstructions(
        contentIndex + 2,
        """
        invoke-virtual {v$instance, v$view}, ${fragment.type}->seriesTrackerHistoryView(Landroid/view/View;)Landroid/view/View;
        move-result-object v$view
    """
            .trimIndent(),
    )

    // The playlist toolbar populates its own Menu, independently of the activity menu.
    val toolbarMatch = ToolbarMenuFingerprint.matchAll(1..1).single()
    val toolbar = toolbarMatch.originalClassDef
    val menuGetter = toolbarMatch.originalMethod
    val mutableToolbar = toolbarMatch.classDef
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
