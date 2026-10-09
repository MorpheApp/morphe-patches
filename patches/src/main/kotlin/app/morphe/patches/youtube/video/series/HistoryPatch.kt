/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

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
import app.morphe.patches.youtube.misc.backgesture.YouTubeMainActivityOnBackPressedFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val NAV = "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;"
private const val OUR_NAV = "${OUR_PREFIX}HistoryNavigation;"

internal fun BytecodePatchContext.wireHistory() {
    // Build native History pivot items from the same renderer factory YouTube uses.
    val factory = PivotBarRendererFingerprint.method
    val parseIndex = factory.indexOfFirstInstructionOrThrow(
        methodCall(name = "parseFrom", parameters = listOf("L", "[B"))
    )
    val parse = factory.getInstruction(parseIndex).getReference<MethodReference>()!!
    val registeredParse = RegisteredParseFingerprint(parse).method
    val registry = GeneratedRegistryFingerprint.method
    val wrapperType = factory.parameterTypes.single().toString()
    val default = classDefBy(wrapperType).fields.single {
        it.type == wrapperType && AccessFlags.STATIC.isSet(it.accessFlags)
    }

    // Replace the extension placeholder with a method that calls the obfuscated factory.
    HistoryNavigationBuilderFingerprint.let {
        it.classDef.methods.remove(it.method)
        it.classDef.methods.add(
            ImmutableMethod(
                OUR_NAV,
                "buildNative",
                listOf(ImmutableMethodParameter("[B", null, null)),
                "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        sget-object v0, $default
                        invoke-static { }, $registry
                        move-result-object v1
                        invoke-static { v0, p0, v1 }, $registeredParse
                        move-result-object v0
                        check-cast v0, $wrapperType
                        invoke-static { v0 }, $factory
                        move-result-object v0
                        const/4 v1, 0x0
                        invoke-virtual { v0, v1 }, ${factory.returnType}->orElse(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v0
                        return-object v0
                    """
                )
            }
        )
    }

    // Capture each native pivot item together with the renderer it was built from.
    factory.findInstructionIndicesReversedOrThrow(
        methodCall(
            definingClass = factory.definingClass,
            name = "<init>",
            parameters = listOf(
                "Lcom/google/protobuf/MessageLite;", "L", "L", "L", "L", "Z", "L", "L", "L"
            )
        )
    ).forEach { index ->
        val instance = factory.getInstruction<RegisterRangeInstruction>(index).startRegister
        val proto = instance + 1
        factory.addInstructions(
            index + 1,
            "invoke-static/range { v$instance .. v$proto }, " +
                    "$OUR_NAV->capture(Ljava/lang/Object;Lcom/google/protobuf/MessageLite;)V"
        )
    }

    PivotBarRendererListFingerprint.method.apply {
        val index = indexOfFirstInstructionOrThrow(
            methodCall(definingClass = NAV, name = "getPivotBarRendererList")
        )
        val register = getInstruction<OneRegisterInstruction>(index + 1).registerA

        addInstructions(
            index + 2,
            """
                invoke-static/range { v$register .. v$register }, $OUR_NAV->navigation(Ljava/util/List;)Ljava/util/List;
                move-result-object v$register
            """
        )
    }

    NavigationTabCreatedFingerprint.method.addInstructions(
        0,
        """
            invoke-static { p0 }, $OUR_NAV->keepButton(Ljava/lang/Enum;)Z
            move-result v0
            if-eqz v0, :native_visibility
            return-void
            :native_visibility
            nop
        """
    )

    YouTubeMainActivityOnBackPressedFingerprint.method.addInstructions(
        0,
        """
            invoke-static { }, ${OUR_PREFIX}HistoryUi;->onBack()Z
            move-result v0
            if-eqz v0, :native_back
            return-void
            :native_back
            nop
        """
    )

    // Match the History browse fragment by its route diagnostic, then wrap the page and toolbar.
    BrowseFragmentFingerprint.let { browse ->
        val fragment = browse.classDef
        val create = browse.method
        val diagnostic = browse.instructionMatches.single().index
        val endpointIndex = create.indexOfFirstInstructionReversedOrThrow(
            diagnostic,
            fieldAccess(definingClass = "this", type = "L"),
        )
        val endpointField = create.getInstruction(endpointIndex).getReference<FieldReference>()!!
        val routeMethod = BrowseRouteFingerprint(endpointField.type)
            .match(browse.originalClassDef)
            .instructionMatches
            .single()
            .getInstruction<ReferenceInstruction>()
            .reference as MethodReference

        fragment.methods.add(
            ImmutableMethod(
                fragment.type,
                "patch_seriesTrackerHistoryView",
                listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
                "Landroid/view/View;",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $endpointField
                        invoke-static { v0 }, $routeMethod
                        move-result-object v0
                        invoke-static { p1, v0 }, ${OUR_PREFIX}HistoryUi;->wrap(Landroid/view/View;Ljava/lang/String;)Landroid/view/View;
                        move-result-object v0
                        return-object v0
                    """
                )
            }
        )

        // Common toolbar wrapper is the final one-View -> View call in onCreateView.
        val contentIndex = create.indexOfFirstInstructionReversedOrThrow(
            methodCall(
                parameters = listOf("Landroid/view/View;"),
                returnType = "Landroid/view/View;",
            )
        )
        val instance = create.getInstruction(contentIndex).registersUsed.first()
        val view = create.getInstruction<OneRegisterInstruction>(contentIndex + 1).registerA
        if (view == instance) {
            throw PatchException("Native page wrapper overwrites the fragment register")
        }

        create.addInstructions(
            contentIndex + 2,
            """
                invoke-virtual { v$instance, v$view }, ${fragment.type}->patch_seriesTrackerHistoryView(Landroid/view/View;)Landroid/view/View;
                move-result-object v$view
            """
        )
    }

    // The playlist toolbar populates its own Menu, independently of the activity menu.
    ToolbarMenuFingerprint.let {
        it.classDef.interfaces.add("${OUR_PREFIX}PlaylistMenu\$ToolbarSource;")
        it.classDef.methods.add(
            ImmutableMethod(
                it.classDef.type,
                "patch_seriesTrackerMenu",
                null,
                "Landroid/view/Menu;",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0 }, ${it.method}
                        move-result-object v0
                        return-object v0
                    """
                )
            }
        )
    }
}
