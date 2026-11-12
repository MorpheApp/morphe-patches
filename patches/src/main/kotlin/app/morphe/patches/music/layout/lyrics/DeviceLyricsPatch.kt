/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4722
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.music.layout.lyrics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BRIDGE = "Lapp/morphe/extension/music/patches/DeviceLyricsTab;"
private const val TABBED_VIEW = "Lcom/google/android/apps/youtube/music/ui/tabs/TabbedView;"

private object SideloadedFactoryFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("com/google/android/apps/youtube/music/player/sideloaded/SideloadedWatchNextResponseFactory")
)

private object SideloadedTabFingerprint : Fingerprint(
    classFingerprint = SideloadedFactoryFingerprint,
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    filters = listOf(
        literal(58174010L),
        fieldAccess(opcode = Opcode.IPUT, type = "I", location = MatchAfterImmediately())
    )
)

private fun bridgeMethod(name: String) = Fingerprint(definingClass = BRIDGE, name = name).method

internal fun addDeviceLyricsHooks() {
    val wrapper = SideloadedTabFingerprint.instructionMatches.last().fieldReference.definingClass
    val addTab = Fingerprint(
        parameters = listOf(wrapper), returnType = "V",
        filters = listOf(
            methodCall(name = "copyOnWrite"),
            methodCall(name = "add", parameters = listOf("Ljava/lang/Object;"), returnType = "Z")
        )
    ).originalMethod
    val calls = Fingerprint(
        classFingerprint = SideloadedFactoryFingerprint,
        returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
        filters = listOf(methodCall(reference = addTab), methodCall(reference = addTab, location = MatchAfterWithin(500)))
    )
    calls.instructionMatches.reversed().forEach { match ->
        val registers = match.instruction.registersUsed
        calls.method.addInstruction(match.index + 1,
            "invoke-static { v${registers[0]}, v${registers[1]} }, $BRIDGE->onDeviceTabs(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
    val message = classDefBy(wrapper).superclass!!
    val defaultInstance = classDefBy(wrapper).fields.single { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == wrapper }
    mutableClassDefBy(wrapper).methods.add(ImmutableMethod(
        wrapper, "patch_parseDeviceLyricsTab", listOf(ImmutableMethodParameter("[B", null, null)),
        "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null, null, MutableMethodImplementation(2)
    ).toMutable().apply {
        addInstructions(0, """
            sget-object v0, $defaultInstance
            invoke-static { v0, p0 }, $message->parseFrom($message[B)$message
            move-result-object v0
            return-object v0
        """)
    })
    bridgeMethod("parseTab").addInstructions(0, """
        invoke-static { p1 }, $wrapper->patch_parseDeviceLyricsTab([B)Ljava/lang/Object;
        move-result-object p0
        return-object p0
    """)
    bridgeMethod("appendTab").addInstructions(0, """
        check-cast p0, ${addTab.definingClass}
        check-cast p1, $wrapper
        invoke-virtual { p0, p1 }, $addTab
        return-void
    """)

    val addPage = Fingerprint(
        definingClass = TABBED_VIEW,
        returnType = "V",
        parameters = listOf("Landroid/view/View;", "Landroid/view/View;", "Landroid/view/View;", "L", "I"),
        filters = listOf(
            fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = TABBED_VIEW, type = "L"),
            methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = listOf("I", "L"), returnType = "V", location = MatchAfterWithin(3))
        )
    )
    val addPageFingerprint = addPage
    val addPageMethod = addPageFingerprint.method
    val adapterField = addPageFingerprint.instructionMatches[0].fieldReference
    val adapterAdd = addPageFingerprint.instructionMatches[1].methodReference
    val adapter = classDefBy(adapterField.type)
    val entryType = adapterAdd.parameterTypes[1].toString()
    val entryModel = classDefBy(entryType).fields.single { it.type == addPageMethod.parameterTypes[3].toString() }
    val snapshot = Fingerprint(
        definingClass = TABBED_VIEW, parameters = emptyList(), returnType = "I",
        filters = listOf(
            methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = "L"),
            methodCall(name = "size", parameters = emptyList(), returnType = "I", location = MatchAfterWithin(3))
        )
    ).instructionMatches.first().methodReference
    val remove = Fingerprint(definingClass = adapter.type, parameters = listOf(entryType), returnType = "V").originalMethod
    val pagerMediator = Fingerprint(
        parameters = listOf("Lcom/google/android/material/tabs/TabLayout;"), returnType = "V",
        strings = listOf("TabLayoutMediator is already attached")
    )
    val instantSelection = Fingerprint(
        classFingerprint = pagerMediator, parameters = listOf("I"), returnType = "V",
        filters = listOf(literal(0L), methodCall(
            definingClass = "Landroidx/viewpager2/widget/ViewPager2;", parameters = listOf("I", "Z"),
            returnType = "V", location = MatchAfterWithin(3)
        ))
    ).originalMethod
    val select = Fingerprint(
        definingClass = adapter.type, name = instantSelection.name, parameters = listOf("I"), returnType = "V"
    ).originalMethod
    val mapIndex = Fingerprint(
        definingClass = TABBED_VIEW, parameters = listOf("I"), returnType = "I",
        filters = listOf(methodCall(name = "getLayoutDirection"))
    ).originalMethod
    bridgeMethod("removeTab").let { original ->
        val replacement = ImmutableMethod(
            BRIDGE, original.name, original.parameters, original.returnType,
            original.accessFlags, original.annotations, null, MutableMethodImplementation(6)
        ).toMutable()
        mutableClassDefBy(BRIDGE).methods.apply {
            remove(original)
            add(replacement)
        }
        replacement.addInstructionsWithLabels(0, """
            check-cast p0, $TABBED_VIEW
            iget-object v0, p0, $adapterField
            invoke-interface { v0 }, $snapshot
            move-result-object v1
            invoke-interface { v1 }, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;
            move-result-object v1
            :next
            invoke-interface { v1 }, Ljava/util/Iterator;->hasNext()Z
            move-result v2
            if-eqz v2, :done
            invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v2
            check-cast v2, $entryType
            iget-object v3, v2, $entryModel
            if-ne v3, p1, :next
            const/4 v3, 0x0
            invoke-virtual { p0, v3 }, $mapIndex
            move-result v3
            invoke-interface { v0, v3 }, $select
            invoke-interface { v0, v2 }, $remove
            :done
            return-void
        """)
    }
    val tabType = addPageMethod.parameterTypes[3].toString()
    val rendererField = classDefBy(tabType).fields.single {
        !AccessFlags.STATIC.isSet(it.accessFlags) && classDefBy(it.type).superclass == message
    }
    bridgeMethod("serializeRenderer").addInstructions(0, """
        check-cast p0, $tabType
        iget-object p0, p0, $rendererField
        invoke-virtual { p0 }, $message->toByteArray()[B
        move-result-object p0
        return-object p0
    """)
    addPageMethod.addInstructions(0, """
        invoke-static { p0, p3, p4 }, $BRIDGE->onTabContent(Landroid/view/ViewGroup;Landroid/view/View;Ljava/lang/Object;)Landroid/view/View;
        move-result-object p3
    """)
}
