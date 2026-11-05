package app.morphe.validation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patches.reddit.misc.archive.replaceArchiveBridge
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable

private const val PROBE = "Lapp/morphe/test/ArchiveProbe;"
private const val ARCHIVE = "Lapp/morphe/extension/reddit/patches/archive/"

/** Isolated host-runtime fixture for the pinned 2026.40.0 input; never part of the MPP. */
context(context: BytecodePatchContext)
fun installNativeProbe() = with(context) {
    fun bridge(name: String, registers: Int, code: String) = replaceArchiveBridge(name, registers, code, PROBE)
    val unit = classDefBy("Lkotlin/Unit;").fields.single { it.type == "Lkotlin/Unit;" }
    bridge("unit", 1, "sget-object v0, $unit\nreturn-object v0")
    listOf("newState", "readState", "writeState").forEach { name ->
        // Copy generated bridge bodies; do not make package-private release methods public.
        val source = classDefBy("${ARCHIVE}ProfileArchivePatch;").methods.single { it.name == name }
        val owner = mutableClassDefBy(PROBE)
        val old = owner.methods.single { it.name == name }
        owner.methods.remove(old)
        owner.methods.add(ImmutableMethod(PROBE, old.name, old.parameters, old.returnType,
            old.accessFlags, old.annotations, old.hiddenApiRestrictions, source.implementation!!).toMutable())
    }
    val model = Fingerprint(name = "toString", filters = listOf(string("ProfileEmptyFeedPresentationModel(targetFeed="))).method.definingClass
    val feed = Fingerprint(name = "toString", filters = listOf(string("Feed(sections="))).classDef
    check(feed.type == "Ld6j;") { "Update the pinned instrumentation fixture for this feed model" }
    bridge("fixtureFeed", 17, """
        new-instance v0, $model
        invoke-static { p0 }, Lcom/reddit/profile/model/ProfileFeedType;->valueOf(Ljava/lang/String;)Lcom/reddit/profile/model/ProfileFeedType;
        move-result-object v1
        move-object v2, p1
        move/from16 v3, p3
        move/from16 v4, p4
        invoke-direct { v0, v1, v2, v3, v4 }, $model-><init>(Lcom/reddit/profile/model/ProfileFeedType;Ljava/lang/String;ZZ)V
        new-instance v1, Lux0;
        const/4 v2, 0x3
        invoke-direct { v1, v0, v2 }, Lux0;-><init>(Ljava/lang/Object;I)V
        invoke-static { v1 }, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
        move-result-object v1
        invoke-static { v1 }, Lg4p0;->a0(Ljava/lang/Iterable;)Lzep;
        move-result-object v1
        new-instance v0, Ld6j;
        const/4 v2, 0x0
        const/4 v3, 0x0
        const/4 v4, 0x0
        const/4 v5, 0x0
        const/4 v6, 0x0
        const/4 v7, 0x0
        const/4 v8, 0x0
        const/4 v9, 0x0
        move v10, p2
        const/4 v11, 0x0
        invoke-direct/range { v0 .. v11 }, Ld6j;-><init>(Lzep;Lhuk0;ZZZLc6j;Ljava/lang/String;ZLvvf0;ZLy4j;)V
        return-object v0
    """)
    bridge("sections", 1, "check-cast p0, Ld6j;\niget-object p0, p0, Ld6j;->a:Lzep;\nreturn-object p0")
    val composeView = Fingerprint(name = "setContent", definingClass = "Landroidx/compose/ui/platform/", parameters = listOf("Lkotlin/jvm/functions/Function2;")).classDef
    val constructor = composeView.methods.single { it.name == "<init>" && it.parameterTypes.size == 1 }
    val activity = mutableClassDefBy("Lapp/morphe/test/ArchiveProbe${'$'}ProbeActivity;")
    activity.setSuperClass(constructor.parameterTypes[0].toString())
    activity.methods.forEach { method ->
        method.implementation?.instructions?.withIndex()?.toList()?.forEach { (index, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == "Landroid/app/Activity;" && ref.name == "<init>")
                method.replaceInstruction(index, "invoke-direct { p0 }, ${activity.superclass}-><init>()V")
            else if (ref?.definingClass == "Landroid/app/Activity;" && ref.name == "onCreate")
                method.replaceInstruction(index, "invoke-super { p0, p1 }, ${activity.superclass}->onCreate(Landroid/os/Bundle;)V")
        }
    }
    bridge("mount", 4, """
        new-instance v0, ${composeView.type}
        check-cast p0, ${constructor.parameterTypes[0]}
        invoke-direct { v0, p0 }, $constructor
        new-instance v1, Lapp/morphe/test/ArchiveProbe${'$'}Content;
        invoke-direct { v1 }, Lapp/morphe/test/ArchiveProbe${'$'}Content;-><init>()V
        invoke-virtual { v0, v1 }, ${composeView.type}->setContent(Lkotlin/jvm/functions/Function2;)V
        invoke-static { p0, v0 }, $PROBE->mountView(Landroid/app/Activity;Landroid/view/View;)V
        return-void
    """)
    bridge("newLazyState", 2, """
        new-instance v0, Landroidx/compose/foundation/lazy/b;
        const/4 v1, 0x0
        invoke-direct { v0, v1, v1 }, Landroidx/compose/foundation/lazy/b;-><init>(II)V
        return-object v0
    """)
    val lazy = Fingerprint(isStatic = true, returnType = "V", filters = listOf(literal(53695811L))).method
    bridge("lazyColumn", 16, """
        const/4 v0, 0x0
        move-object v1, p0
        check-cast v1, Landroidx/compose/foundation/lazy/b;
        const/4 v2, 0x0
        const/4 v3, 0x0
        const/4 v4, 0x0
        const/4 v5, 0x0
        const/4 v6, 0x0
        const/4 v7, 0x0
        const/4 v8, 0x0
        move-object v9, p1
        move-object v10, p2
        check-cast v10, Li7c;
        const/4 v11, 0x0
        const/16 v12, 0x1fd
        invoke-static/range { v0 .. v12 }, $lazy
        return-void
    """)
    mutableClassDefBy("Lapp/morphe/test/ArchiveProbe${'$'}ItemContent;").interfaces.add("Lndk;")
    bridge("addItems", 9, """
        check-cast p0, Laqr;
        invoke-interface { p1 }, Ljava/util/List;->size()I
        move-result v1
        add-int/lit8 v1, v1, 0x1
        new-instance v2, Lapp/morphe/test/ArchiveProbe${'$'}Keys;
        invoke-direct { v2, p1 }, Lapp/morphe/test/ArchiveProbe${'$'}Keys;-><init>(Ljava/util/List;)V
        new-instance v3, Lapp/morphe/test/ArchiveProbe${'$'}ContentTypes;
        invoke-direct { v3 }, Lapp/morphe/test/ArchiveProbe${'$'}ContentTypes;-><init>()V
        new-instance v5, Lapp/morphe/test/ArchiveProbe${'$'}ItemContent;
        invoke-direct { v5, p1 }, Lapp/morphe/test/ArchiveProbe${'$'}ItemContent;-><init>(Ljava/util/List;)V
        new-instance v4, Landroidx/compose/runtime/internal/a;
        const v0, 0x41725034
        const/4 p2, 0x1
        invoke-direct { v4, v0, p2, v5 }, Landroidx/compose/runtime/internal/a;-><init>(IZLldk;)V
        move-object v0, p0
        invoke-virtual { v0, v1, v2, v3, v4 }, Laqr;->h(ILkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/internal/a;)V
        return-void
    """)
    bridge("key", 1, "check-cast p0, Lw6c;\ninvoke-interface { p0 }, Lw6c;->b()Ljava/lang/String;\nmove-result-object p0\nreturn-object p0")
    bridge("renderSection", 4, """
        check-cast p0, Lw6c;
        check-cast p1, Li7c;
        sget-object v0, Ltri;->o:Ltri;
        const/4 v1, 0x0
        invoke-interface { p0, v0, p1, v1 }, Lw6c;->c(Ltri;Li7c;I)V
        return-void
    """)
    bridge("inspectPosition", 4, """
        check-cast p0, Landroidx/compose/foundation/lazy/b;
        invoke-virtual { p0 }, Landroidx/compose/foundation/lazy/b;->h()Lmqr;
        move-result-object v0
        const/4 v1, 0x2
        new-array v1, v1, [I
        iget-object v2, v0, Lmqr;->a:Lnqr;
        if-eqz v2, :offset
        iget v2, v2, Lnqr;->a:I
        const/4 p0, 0x0
        aput v2, v1, p0
        :offset
        iget v2, v0, Lmqr;->b:I
        const/4 p0, 0x1
        aput v2, v1, p0
        return-object v1
    """)
    val info = Fingerprint(name = "toString", filters = listOf(string("VisibleItemInfo(index="))).classDef.type
    val event = Fingerprint(name = "toString", filters = listOf(string("VisibleItemsChangedEvent(items="))).classDef.type
    bridge("emitVisible", 9, """
        check-cast p0, Landroidx/compose/foundation/lazy/b;
        invoke-virtual { p0 }, Landroidx/compose/foundation/lazy/b;->h()Lmqr;
        move-result-object v0
        iget-object v0, v0, Lmqr;->k:Ljava/util/List;
        invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
        move-result-object v0
        new-instance v1, Ljava/util/ArrayList;
        invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
        :loop
        invoke-interface { v0 }, Ljava/util/Iterator;->hasNext()Z
        move-result v2
        if-eqz v2, :done
        invoke-interface { v0 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v2
        check-cast v2, Lnqr;
        iget-object v4, v2, Lnqr;->l:Ljava/lang/Object;
        invoke-static { v4 }, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v4
        iget v5, v2, Lnqr;->a:I
        iget v6, v2, Lnqr;->q:I
        move v7, v6
        new-instance v3, $info
        invoke-direct { v3, v4, v5, v6, v7 }, $info-><init>(Ljava/lang/String;III)V
        invoke-virtual { v1, v3 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
        goto :loop
        :done
        invoke-static { v1 }, $PROBE->acceptVisible(Ljava/util/List;)Z
        move-result v0
        if-eqz v0, :return
        new-instance v0, $event
        invoke-direct { v0, v1 }, $event-><init>(Ljava/util/ArrayList;)V
        :return
        return-void
    """)
    val clientOwner = mutableClassDefBy("${ARCHIVE}ArchiveClient;")
    val oldClient = clientOwner.methods.single { it.name == "<init>" && it.parameterTypes.isEmpty() }
    val client = ImmutableMethod(oldClient.definingClass, oldClient.name, oldClient.parameters, oldClient.returnType,
        oldClient.accessFlags, oldClient.annotations, oldClient.hiddenApiRestrictions, MutableMethodImplementation(2)).toMutable()
    clientOwner.methods.remove(oldClient)
    clientOwner.methods.add(client)
    client.addInstructions(0, """
        invoke-static {}, ${ARCHIVE}ArchiveFixture;->transport()Ljava/lang/Object;
        move-result-object v0
        check-cast v0, ${ARCHIVE}ArchiveClient${'$'}Transport;
        invoke-direct { p0, v0 }, ${ARCHIVE}ArchiveClient;-><init>(${ARCHIVE}ArchiveClient${'$'}Transport;)V
        return-void
    """)
}
