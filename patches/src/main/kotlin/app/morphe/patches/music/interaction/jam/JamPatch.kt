package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BRIDGE = "Lapp/morphe/extension/music/jam/YtmBridge;"
private const val ACCESS = "Lapp/morphe/extension/music/jam/YtmBridge\$QueueAccess;"

private val jamResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val service = doc.createElement("service")
            service.setAttribute("android:name", "app.morphe.extension.music.jam.JamBridgeService")
            service.setAttribute("android:exported", "true")
            doc.getElementsByTagName("application").item(0).appendChild(service)
            val queries = doc.getElementsByTagName("queries").item(0)
                ?: doc.createElement("queries").also { doc.documentElement.appendChild(it) }
            queries.appendChild(doc.createElement("package").apply {
                setAttribute("android:name", "app.morphe.jam.companion")
            })
        }
        document("res/layout/player_bottom_sheet.xml").use { doc ->
            val root = doc.documentElement
            val tabs = (0 until root.childNodes.length).map { root.childNodes.item(it) }
                .filterIsInstance<org.w3c.dom.Element>()
                .single { it.getAttribute("android:id") == "@id/bottom_sheet_tabbed_view" }
            root.insertBefore(doc.createElement("app.morphe.extension.music.jam.JamBar").apply {
                setAttribute("android:layout_width", "match_parent")
                setAttribute("android:layout_height", "48dp")
            }, tabs)
        }
        document("res/layout/watch_while_layout.xml").use { doc ->
            // Original 20dp peek plus the integrated 48dp Jam row.
            doc.documentElement.setAttribute("app:bottomSheetPeekHeight", "68dp")
        }
    }
}

@Suppress("unused")
val jamQueueProbePatch = bytecodePatch(
    name = "Jam queue sharing",
    description = "Adds a native Jam queue panel and authenticated bridge to the Jam compatibility layer. Experimental, YTM 9.15.51 only.",
    default = false,
) {
    dependsOn(sharedExtensionPatch, settingsPatch, jamResources, musicVideoInformationPatch)
    compatibleWith(Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = COMPATIBILITY_YOUTUBE_MUSIC.apkFileType,
        signatures = COMPATIBILITY_YOUTUBE_MUSIC.signatures,
        targets = COMPATIBILITY_YOUTUBE_MUSIC.targets.filter { it.version == "9.15.51" }
    ))

    execute {
        val match = QueueEnqueueFingerprint.matchAll(1..1).single()
        val enqueue = match.method
        val manager = match.classDef
        val commandType = enqueue.parameterTypes.single().toString()
        val command = classDefBy(commandType)
        val registry = "Lcom/google/protobuf/ExtensionRegistryLite;"
        val hierarchy = generateSequence(command.superclass) { type ->
            classDefByOrNull(type)?.superclass
        }.mapNotNull { classDefByOrNull(it) }.toList()
        val parser = hierarchy.flatMap { it.methods }.single {
            it.name == "parseFrom" && it.parameterTypes.map { p -> p.toString() } ==
                listOf(it.definingClass, "[B", registry) && it.returnType == it.definingClass &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val defaultInstance = command.fields.single {
            it.type == commandType && AccessFlags.STATIC.isSet(it.accessFlags) &&
                AccessFlags.PUBLIC.isSet(it.accessFlags)
        }
        val executor = manager.fields.single { it.type == "Ljava/util/concurrent/Executor;" }
        val constructor = manager.methods.single { it.name == "<init>" }
        manager.interfaces.add(ACCESS)

        fun addBridge(name: String, parameters: List<String>, result: String, registers: Int, code: String) {
            manager.methods.add(ImmutableMethod(
                manager.type, name, parameters.map { ImmutableMethodParameter(it, null, null) },
                result, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null, null, MutableMethodImplementation(registers)
            ).toMutable().apply { addInstructions(0, code) })
        }
        addBridge("patch_jamExecutor", emptyList(), "Ljava/util/concurrent/Executor;", 2, """
            iget-object v0, p0, $executor
            return-object v0
        """)
        val nativeEnqueueName = enqueue.name
        enqueue.setName("patch_jamLocalEnqueue")
        addBridge(nativeEnqueueName, listOf(commandType), "V", 4, """
            invoke-virtual {p1}, $commandType->toByteArray()[B
            move-result-object v0
            invoke-static {p0, v0}, Lapp/morphe/extension/music/jam/JamUi;->offer($ACCESS[B)Z
            move-result v0
            if-eqz v0, :local
            return-void
            :local
            invoke-virtual {p0, p1}, $enqueue
            return-void
        """)
        addBridge("patch_jamEnqueue", listOf("[B"), "V", 4, """
            sget-object v0, $defaultInstance
            invoke-static {}, $registry->getGeneratedRegistry()$registry
            move-result-object v1
            invoke-static {v0, p1, v1}, $parser
            move-result-object v0
            check-cast v0, $commandType
            invoke-virtual {p0, v0}, $enqueue
            return-void
        """)
        // Deliberately version-locked. Fail closed if the inspected queue ABI changes.
        check(manager.type == "Lnyo;") { "Unsupported native queue ABI" }
        fun requireMethod(type: String, name: String, args: List<String>, result: String) {
            check(classDefBy(type).methods.count { it.name == name && it.parameterTypes.map { p -> p.toString() } == args && it.returnType == result } == 1) { "Queue ABI mismatch: $type->$name" }
        }
        requireMethod("Lazbj;", "p", listOf("I"), "Ljava/util/List;")
        requireMethod("Lazbj;", "d", listOf("I"), "Laitp;")
        requireMethod("Lazbj;", "a", emptyList(), "I")
        requireMethod("Lazbj;", "f", emptyList(), "Lazbe;")
        requireMethod("Lazcf;", "t", emptyList(), "Ljava/lang/String;")
        requireMethod("Lazcj;", "m", emptyList(), "J")
        requireMethod("Lnog;", "h", emptyList(), "Ljava/lang/String;")
        requireMethod("Lnyo;", "e", listOf("Lnoq;"), "V")
        requireMethod("Laitp;", "j", listOf("I", "I"), "V")
        requireMethod("Lazcl;", "c", listOf("Lazck;", "Lazck;"), "V")
        // Preserve callback identity in wrappers, after native success has updated the queue.
        val callback = mutableClassDefBy("Lnyl;")
        val callbackConstructor = callback.methods.single { it.name == "<init>" }
        val callbackTail = callbackConstructor.implementation!!.instructions.toList()
        check(callbackTail.last().opcode == Opcode.RETURN_VOID)
        check(callbackTail[callbackTail.lastIndex - 1].getReference<FieldReference>()?.toString() == "Lnyl;->c:Ljava/lang/String;")
        callbackConstructor.addInstructions(callbackTail.lastIndex,
            "invoke-static {p0}, Lapp/morphe/extension/music/jam/JamCompletion;->attach(Ljava/lang/Object;)V")
        for (name in listOf("ht", "hs")) {
            val native = callback.methods.single { it.name == name }
            val argument = native.parameterTypes.single().toString()
            native.setName("patch_jam_" + name)
            val completion = if (name == "ht") """
                check-cast p1, Loil;
                iget-object v0, p1, Loil;->a:Ljava/util/List;
                iget-object v1, p0, Lnyl;->a:Lnyo;
                invoke-static {p0, v0, v1}, Lapp/morphe/extension/music/jam/JamCompletion;->succeeded(Ljava/lang/Object;Ljava/util/List;$ACCESS)V
            """ else """
                invoke-static {p0}, Lapp/morphe/extension/music/jam/JamCompletion;->failed(Ljava/lang/Object;)V
            """
            callback.methods.add(ImmutableMethod(callback.type, name,
                listOf(ImmutableMethodParameter(argument, null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                MutableMethodImplementation(4)).toMutable().apply {
                    addInstructions(0, """
                        invoke-virtual {p0, p1}, $native
                        $completion
                        return-void
                    """)
                })
        }
        addBridge("patch_jamItems", emptyList(), "[Ljava/lang/Object;", 3, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Lazbj;->p(I)Ljava/util/List;
            move-result-object v0
            invoke-interface {v0}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """)
        addBridge("patch_jamArtist", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 3, """
            instance-of v0, p1, Lnog;
            if-eqz v0, :none
            check-cast p1, Lnog;
            invoke-interface {p1}, Lnog;->g()Ljava/lang/String;
            move-result-object v0
            return-object v0
            :none
            const-string v0, ""
            return-object v0
        """)
        // Resolve participant-owned menu metadata without executing a queue mutation.
        addBridge("patch_jamRequestMenu", listOf("[B"), "Ljava/util/concurrent/Future;", 5, """
            sget-object v0, $defaultInstance
            invoke-static {}, $registry->getGeneratedRegistry()$registry
            move-result-object v1
            invoke-static {v0, p1, v1}, $parser
            move-result-object v0
            check-cast v0, Lboht;
            iget-object v1, p0, Lnyo;->h:Lohq;
            invoke-virtual {p0}, Lnyo;->patch_jamExecutor()Ljava/util/concurrent/Executor;
            move-result-object v2
            invoke-virtual {v1, v0, v2}, Lohq;->a(Lboht;Ljava/util/concurrent/Executor;)Lcom/google/common/util/concurrent/ListenableFuture;
            move-result-object v0
            return-object v0
        """)
        addBridge("patch_jamMenuItems", listOf("Ljava/lang/Object;"), "[Ljava/lang/Object;", 2, """
            check-cast p1, Loil;
            iget-object p1, p1, Loil;->a:Ljava/util/List;
            invoke-interface {p1}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object p1
            return-object p1
        """)
        addBridge("patch_jamCreateItem", listOf("[B", "J"), "Ljava/lang/Object;", 8, """
            sget-object v1, Lbxxt;->a:Lbxxt;
            invoke-static {}, $registry->getGeneratedRegistry()$registry
            move-result-object v2
            invoke-static {v1, p1, v2}, $parser
            move-result-object v1
            check-cast v1, Lbxxt;
            iget-object v3, p0, Lnyo;->e:Lnuv;
            new-instance v0, Lnpa;
            invoke-direct {v0, p2, p3, v1, v3}, Lnpa;-><init>(JLbxxt;Lnuv;)V
            return-object v0
        """)
        addBridge("patch_jamDisplayedList", emptyList(), "Ljava/lang/Object;", 2, """
            iget-object v0, p0, Lnyo;->k:Lnzd;
            iget-object v0, v0, Lnzd;->e:Laitp;
            return-object v0
        """)
        addBridge("patch_jamDisplayedList", listOf("Ljava/lang/Object;"), "V", 4, """
            iget-object v0, p0, Lnyo;->k:Lnzd;
            iget-object v1, v0, Lnzd;->e:Laitp;
            if-eqz v1, :set
            invoke-interface {v1, v0}, Laitp;->n(Laito;)V
            :set
            check-cast p1, Laitp;
            iput-object p1, v0, Lnzd;->e:Laitp;
            invoke-interface {p1, v0}, Laitp;->fG(Laito;)V
            invoke-virtual {v0}, Lnzd;->m()V
            return-void
        """)
        addBridge("patch_jamRefreshDisplay", emptyList(), "V", 2, """
            iget-object v0, p0, Lnyo;->k:Lnzd;
            invoke-virtual {v0}, Lnzd;->m()V
            return-void
        """)
        addBridge("patch_jamViewThread", listOf("Ljava/lang/Runnable;"), "V", 3, """
            iget-object v0, p0, Lnyo;->k:Lnzd;
            iget-object v0, v0, Lnzd;->b:Landroid/os/Handler;
            invoke-virtual {v0, p1}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z
            return-void
        """)
        addBridge("patch_jamAutoplayItems", emptyList(), "[Ljava/lang/Object;", 3, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            const/4 v1, 0x1
            invoke-virtual {v0, v1}, Lazbj;->p(I)Ljava/util/List;
            move-result-object v0
            invoke-interface {v0}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """)
        addBridge("patch_jamDisplayedAutoplay", emptyList(), "Ljava/lang/Object;", 2, """
            iget-object v0, p0, Lnyo;->v:Lnzd;
            iget-object v0, v0, Lnzd;->e:Laitp;
            return-object v0
        """)
        addBridge("patch_jamDisplayedAutoplay", listOf("Ljava/lang/Object;"), "V", 4, """
            iget-object v0, p0, Lnyo;->v:Lnzd;
            iget-object v1, v0, Lnzd;->e:Laitp;
            if-eqz v1, :set
            invoke-interface {v1, v0}, Laitp;->n(Laito;)V
            :set
            check-cast p1, Laitp;
            iput-object p1, v0, Lnzd;->e:Laitp;
            invoke-interface {p1, v0}, Laitp;->fG(Laito;)V
            invoke-virtual {v0}, Lnzd;->m()V
            return-void
        """)
        addBridge("patch_jamRefreshAutoplay", emptyList(), "V", 2, """
            iget-object v0, p0, Lnyo;->v:Lnzd;
            invoke-virtual {v0}, Lnzd;->m()V
            return-void
        """)
        addBridge("patch_jamRemoveFrom", listOf("I", "I"), "V", 4, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            invoke-virtual {v0, p1}, Lazbj;->p(I)Ljava/util/List;
            move-result-object v0
            invoke-interface {v0, p2}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lnoq;
            invoke-virtual {p0, v0}, Lnyo;->e(Lnoq;)V
            return-void
        """)
        addBridge("patch_jamMoveFrom", listOf("I", "I", "I"), "V", 8, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            invoke-virtual {v0, p1}, Lazbj;->d(I)Laitp;
            move-result-object v0
            invoke-interface {v0, p2, p3}, Laitp;->j(II)V
            invoke-interface {v0, p3}, Laitp;->get(I)Ljava/lang/Object;
            move-result-object v1
            check-cast v1, Lazck;
            const/4 v2, 0x0
            if-eqz p3, :notify
            add-int/lit8 v3, p3, -0x1
            invoke-interface {v0, v3}, Laitp;->get(I)Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Lazck;
            :notify
            iget-object v0, p0, Lnyo;->l:Lcicl;
            invoke-interface {v0}, Lcicl;->gG()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lazcl;
            invoke-interface {v0, v1, v2}, Lazcl;->c(Lazck;Lazck;)V
            return-void
        """)
        val mirrorList = mutableClassDefBy("Lapp/morphe/extension/music/jam/NativeQueueList;")
        mirrorList.interfaces.add("Laitp;")
        for ((nativeName, extensionName) in listOf("fG" to "addListener", "n" to "removeListener")) {
            mirrorList.methods.add(ImmutableMethod(mirrorList.type, nativeName,
                listOf(ImmutableMethodParameter("Laito;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                MutableMethodImplementation(2)).toMutable().apply {
                    addInstructions(0, """
                        invoke-virtual {p0, p1}, ${mirrorList.type}->$extensionName(Ljava/lang/Object;)V
                        return-void
                    """)
                })
        }
        val remove = manager.methods.single { it.name == "e" && it.parameterTypes.map { p -> p.toString() } == listOf("Lnoq;") }
        remove.setName("patch_jamLocalRemove")
        addBridge("e", listOf("Lnoq;"), "V", 3, """
            invoke-static {p1}, Lapp/morphe/extension/music/jam/JamMirror;->remove(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :done
            invoke-virtual {p0, p1}, $remove
            :done
            return-void
        """)
        val observable = mutableClassDefBy("Lnzd;")
        val currentIndex = observable.methods.single { it.name == "q" && it.parameterTypes.isEmpty() && it.returnType == "I" }
        currentIndex.setName("patch_jamLocalCurrent")
        observable.methods.add(ImmutableMethod(observable.type, "q", emptyList(), "I",
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null,
            MutableMethodImplementation(3)).toMutable().apply {
                addInstructions(0, """
                    iget-object v0, p0, Lnzd;->e:Laitp;
                    invoke-static {v0}, Lapp/morphe/extension/music/jam/JamMirror;->current(Ljava/lang/Object;)I
                    move-result v0
                    const/4 v1, -0x2
                    if-ne v0, v1, :done
                    invoke-direct {p0}, $currentIndex
                    move-result v0
                    :done
                    return v0
                """)
            })
        // Native current-item comparison otherwise collides with local queue IDs.
        val selection = mutableClassDefBy("Lnou;")
        val localSelection = selection.methods.single { it.name == "b" && it.parameterTypes.map { p -> p.toString() } == listOf("Lnoq;", "Z") && it.returnType == "Z" }
        localSelection.setName("patch_jamLocalSelection")
        selection.methods.add(ImmutableMethod(selection.type, "b",
            listOf(ImmutableMethodParameter("Lnoq;", null, null), ImmutableMethodParameter("Z", null, null)), "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            MutableMethodImplementation(5)).toMutable().apply {
                addInstructions(0, """
                    invoke-static {p1}, Lapp/morphe/extension/music/jam/JamMirror;->selection(Ljava/lang/Object;)I
                    move-result v0
                    if-gez v0, :done
                    invoke-virtual {p0, p1, p2}, $localSelection
                    move-result v0
                    :done
                    return v0
                """)
            })
        val commitMove = observable.methods.single { it.name == "x" && it.parameterTypes.map { p -> p.toString() } == listOf("I", "I") }
        commitMove.setName("patch_jamLocalMove")
        observable.methods.add(ImmutableMethod(observable.type, "x",
            listOf(ImmutableMethodParameter("I", null, null), ImmutableMethodParameter("I", null, null)), "V",
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null,
            MutableMethodImplementation(5)).toMutable().apply {
                addInstructions(0, """
                    iget-object v0, p0, Lnzd;->e:Laitp;
                    invoke-static {v0, p1, p2}, Lapp/morphe/extension/music/jam/JamMirror;->move(Ljava/lang/Object;II)Z
                    move-result v0
                    if-eqz v0, :local
                    const/4 v0, 0x0
                    iput-object v0, p0, Lnzd;->n:Lnza;
                    return-void
                    :local
                    invoke-direct {p0, p1, p2}, $commitMove
                    return-void
                """)
            })
        addBridge("patch_jamVideoId", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 3, """
            check-cast p1, Lazcf;
            invoke-interface {p1}, Lazcf;->t()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)
        addBridge("patch_jamTitle", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 3, """
            instance-of v0, p1, Lnog;
            if-eqz v0, :fallback
            check-cast p1, Lnog;
            invoke-interface {p1}, Lnog;->h()Ljava/lang/String;
            move-result-object v0
            return-object v0
            :fallback
            invoke-virtual {p0, p1}, Lnyo;->patch_jamVideoId(Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)
        addBridge("patch_jamItemId", listOf("Ljava/lang/Object;"), "J", 4, """
            check-cast p1, Lazcj;
            invoke-interface {p1}, Lazcj;->m()J
            move-result-wide v0
            return-wide v0
        """)
        addBridge("patch_jamCurrent", emptyList(), "I", 2, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            invoke-virtual {v0}, Lazbj;->a()I
            move-result v0
            return v0
        """)
        addBridge("patch_jamLocal", emptyList(), "Z", 3, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            invoke-virtual {v0}, Lazbj;->f()Lazbe;
            move-result-object v0
            sget-object v1, Lazbe;->a:Lazbe;
            if-ne v0, v1, :remote
            const/4 v0, 0x1
            return v0
            :remote
            const/4 v0, 0x0
            return v0
        """)
        addBridge("patch_jamRemove", listOf("I"), "V", 4, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Lazbj;->p(I)Ljava/util/List;
            move-result-object v0
            invoke-interface {v0, p1}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lnoq;
            invoke-virtual {p0, v0}, Lnyo;->e(Lnoq;)V
            return-void
        """)
        addBridge("patch_jamMove", listOf("I", "I"), "V", 7, """
            iget-object v0, p0, Lnyo;->c:Lazbj;
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Lazbj;->d(I)Laitp;
            move-result-object v0
            invoke-interface {v0, p1, p2}, Laitp;->j(II)V
            invoke-interface {v0, p2}, Laitp;->get(I)Ljava/lang/Object;
            move-result-object v1
            check-cast v1, Lazck;
            const/4 v2, 0x0
            if-eqz p2, :notify
            add-int/lit8 v3, p2, -0x1
            invoke-interface {v0, v3}, Laitp;->get(I)Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Lazck;
            :notify
            iget-object v0, p0, Lnyo;->l:Lcicl;
            invoke-interface {v0}, Lcicl;->gG()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lazcl;
            invoke-interface {v0, v1, v2}, Lazcl;->c(Lazck;Lazck;)V
            return-void
        """)
        check(classDefBy("Lcbfw;").fields.any { it.name=="c" && it.type=="Lblin;" }) { "Thumbnail ABI mismatch" }
        addBridge("patch_jamThumbnail", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 4, """
            instance-of v0, p1, Lnpa;
            if-eqz v0, :restored
            check-cast p1, Lnpa;
            iget-object v0, p1, Lnpa;->c:Lbxxg;
            iget-object v0, v0, Lbxxg;->g:Lcbfw;
            goto :thumbnail
            :restored
            instance-of v0, p1, Lnox;
            if-eqz v0, :generic
            check-cast p1, Lnox;
            iget-object v0, p1, Lnox;->a:Lbxxg;
            iget-object v0, v0, Lbxxg;->g:Lcbfw;
            goto :thumbnail
            :generic
            instance-of v0, p1, Lnog;
            if-eqz v0, :empty
            check-cast p1, Lnog;
            invoke-interface {p1}, Lnog;->e()Lcbfw;
            move-result-object v0
            :thumbnail
            if-eqz v0, :empty
            iget-object v0, v0, Lcbfw;->c:Lblin;
            invoke-interface {v0}, Ljava/util/List;->size()I
            move-result v1
            if-eqz v1, :empty
            add-int/lit8 v1, v1, -0x1
            invoke-interface {v0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lcbfv;
            iget-object v0, v0, Lcbfv;->c:Ljava/lang/String;
            return-object v0
            :empty
            const-string v0, ""
            return-object v0
        """)
        addBridge("patch_jamWatchItem", listOf("Ljava/lang/Object;"), "[B", 3, """
            instance-of v0, p1, Lnpa;
            if-eqz v0, :restored
            check-cast p1, Lnpa;
            iget-object v0, p1, Lnpa;->c:Lbxxg;
            goto :endpoint
            :restored
            check-cast p1, Lnox;
            iget-object v0, p1, Lnox;->a:Lbxxg;
            :endpoint
            iget-object v0, v0, Lbxxg;->l:Lboht;
            invoke-virtual {v0}, Lboht;->toByteArray()[B
            move-result-object v0
            return-object v0
        """)
        val clock = "Lapp/morphe/extension/music/jam/JamClock;"
        val clockBar = "Lapp/morphe/extension/music/jam/JamClock\$Bar;"
        val mediaState = mutableClassDefBy("Lii;").methods.single { it.name == "n" && it.parameterTypes.singleOrNull() == "Landroid/support/v4/media/session/PlaybackStateCompat;" }
        val mediaInstructions = mediaState.implementation!!.instructions.toList()
        val mediaIndex = mediaInstructions.indexOfFirst { it.getReference<com.android.tools.smali.dexlib2.iface.reference.MethodReference>()?.toString() == "Landroid/media/session/MediaSession;->setPlaybackState(Landroid/media/session/PlaybackState;)V" }
        check(mediaIndex >= 0)
        val mediaRegister = (mediaInstructions[mediaIndex] as FiveRegisterInstruction).registerC
        mediaState.addInstructions(mediaIndex+1,"invoke-static/range {v$mediaRegister .. v$mediaRegister}, $clock->capture(Landroid/media/session/MediaSession;)V")
        val timeBase=mutableClassDefBy("Layww;")
        val setModel=timeBase.methods.single { it.name=="s" && it.parameterTypes.singleOrNull()=="Layxc;" }
        setModel.setName("patch_jamOriginalModel")
        timeBase.methods.add(ImmutableMethod(timeBase.type,"s",listOf(ImmutableMethodParameter("Layxc;",null,null)),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(2)).toMutable().apply{addInstructions(0,"""
            invoke-static {p0, p1}, $clock->model(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object p1
            check-cast p1, Layxc;
            invoke-virtual {p0, p1}, $setModel
            return-void
        """)})
        val timeBar=mutableClassDefBy("Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;")
        timeBar.interfaces.add(clockBar)
        timeBar.methods.add(ImmutableMethod(timeBar.type,"patch_jamModel",listOf(ImmutableMethodParameter("J",null,null),ImmutableMethodParameter("J",null,null)),"Ljava/lang/Object;",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(7)).toMutable().apply{addInstructions(0,"""
            new-instance v0, Laywy;
            invoke-direct {v0}, Laywy;-><init>()V
            iput-wide p1, v0, Laywy;->c:J
            iput-wide p3, v0, Laywy;->a:J
            iput-wide p1, v0, Laywy;->b:J
            const/4 v1, -0x1
            iput v1, v0, Laywy;->g:I
            iput v1, v0, Laywy;->h:I
            const/4 v1, 0x1
            iput-boolean v1, v0, Laywy;->j:Z
            return-object v0
        """)})
        timeBar.methods.add(ImmutableMethod(timeBar.type,"patch_jamRestore",listOf(ImmutableMethodParameter("Ljava/lang/Object;",null,null)),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(2)).toMutable().apply{addInstructions(0,"""
            check-cast p1, Layxc;
            invoke-virtual {p0, p1}, $setModel
            invoke-virtual {p0}, Landroid/view/View;->invalidate()V
            return-void
        """)})
        timeBar.methods.add(ImmutableMethod(timeBar.type,"patch_jamClock",listOf(ImmutableMethodParameter("J",null,null),ImmutableMethodParameter("J",null,null)),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(6)).toMutable().apply{addInstructions(0,"""
            invoke-virtual {p0}, Layww;->t()Z
            move-result v0
            if-nez v0, :done
            invoke-virtual {p0, p1, p2, p3, p4}, ${timeBar.type}->patch_jamModel(JJ)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Layxc;
            invoke-virtual {p0, v0}, $setModel
            invoke-virtual {p0}, Landroid/view/View;->invalidate()V
            :done
            return-void
        """)})
        val seekClass=mutableClassDefBy("Laytr;")
        val originalSeek=seekClass.methods.single { it.name=="f" && it.parameterTypes.map{p->p.toString()}==listOf("J","Lbzmg;") }
        originalSeek.setName("patch_jamLocalSeek")
        seekClass.methods.add(ImmutableMethod(seekClass.type,"f",listOf(ImmutableMethodParameter("J",null,null),ImmutableMethodParameter("Lbzmg;",null,null)),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(5)).toMutable().apply{addInstructions(0,"""
            invoke-static {p1, p2}, $clock->offerSeek(J)Z
            move-result v0
            if-nez v0, :done
            invoke-virtual {p0, p1, p2, p3}, $originalSeek
            :done
            return-void
        """)})
        val palette = "Lapp/morphe/extension/music/jam/JamPalette;"
        val paletteAccess = "Lapp/morphe/extension/music/jam/JamPalette\$Source;"
        val paletteClass = mutableClassDefBy("Lrld;")
        paletteClass.interfaces.add(paletteAccess)
        val localPalette = paletteClass.methods.single { it.name == "b" && it.parameterTypes.isEmpty() }
        localPalette.setName("patch_jamRestorePalette")
        fun paletteMethod(name: String, args: List<String>, result: String, registers: Int, code: String) {
            paletteClass.methods.add(ImmutableMethod(paletteClass.type,name,args.map { ImmutableMethodParameter(it,null,null) },result,AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(registers)).toMutable().apply{addInstructions(0,code)})
        }
        paletteMethod("b",emptyList(),"V",2,"""
            invoke-static {p0}, $palette->intercept($paletteAccess)Z
            move-result v0
            if-nez v0, :done
            invoke-virtual {p0}, $localPalette
            :done
            return-void
        """)
        paletteMethod("patch_jamExtract",listOf("Landroid/graphics/Bitmap;"),"Ljava/lang/Object;",3,"""
            iget-object v0, p0, Lrld;->d:Lpzv;
            invoke-virtual {v0, p1}, Lpzv;->a(Landroid/graphics/Bitmap;)Lpzt;
            move-result-object v0
            return-object v0
        """)
        paletteMethod("patch_jamPublish",listOf("Ljava/lang/Object;"),"V",3,"""
            check-cast p1, Lpzt;
            iget-object v0, p0, Lrld;->c:Lckmq;
            invoke-virtual {v0, p1}, Lckmq;->iU(Ljava/lang/Object;)V
            invoke-virtual {p0, p1}, Lrld;->a(Lpzt;)V
            return-void
        """)
        val playback = "Lapp/morphe/extension/music/jam/JamPlayback;"
        val routerAccess = "Lapp/morphe/extension/music/jam/JamPlayback\$Router;"
        val nowAccess = "Lapp/morphe/extension/music/jam/JamPlayback\$NowUi;"
        for(routerType in listOf("Laodv;","Laodr;")) {
        val routerClass = mutableClassDefBy(routerType)
        routerClass.interfaces.add(routerAccess)
        val dispatch = routerClass.methods.single { it.name == "c" && it.parameterTypes.map { p -> p.toString() } == listOf("Lboht;", "Ljava/util/Map;") }
        dispatch.setName("patch_jamOriginalDispatch")
        fun routerMethod(name: String, args: List<String>, registers: Int, code: String) {
            routerClass.methods.add(ImmutableMethod(routerClass.type,name,args.map { ImmutableMethodParameter(it,null,null) },"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(registers)).toMutable().apply{addInstructions(0,code)})
        }
        routerMethod("c",listOf("Lboht;","Ljava/util/Map;"),5,"""
            invoke-static {p0}, $playback->capture($routerAccess)V
            if-eqz p1, :local
            invoke-virtual {p1}, Lboht;->toByteArray()[B
            move-result-object v0
            invoke-static {p0, p1, p2, v0}, $playback->offer(${routerAccess}Ljava/lang/Object;Ljava/lang/Object;[B)Z
            move-result v0
            if-eqz v0, :local
            return-void
            :local
            invoke-virtual {p0, p1, p2}, $dispatch
            return-void
        """)
        routerMethod("patch_jamDispatch",listOf("Ljava/lang/Object;","Ljava/lang/Object;"),3,"""
            check-cast p1, Lboht;
            check-cast p2, Ljava/util/Map;
            invoke-virtual {p0, p1, p2}, $dispatch
            return-void
        """)
        routerMethod("patch_jamWatch",listOf("[B"),5,"""
            sget-object v0, $defaultInstance
            invoke-static {}, $registry->getGeneratedRegistry()$registry
            move-result-object v1
            invoke-static {v0, p1, v1}, $parser
            move-result-object v0
            check-cast v0, Lboht;
            invoke-static {}, Ljava/util/Collections;->emptyMap()Ljava/util/Map;
            move-result-object v1
            invoke-virtual {p0, v0, v1}, $dispatch
            return-void
        """)
        }
        val currentItem = mutableClassDefBy("Lnop;")
        val localItem = currentItem.methods.single { it.name == "a" && it.parameterTypes.isEmpty() }
        localItem.setName("patch_jamLocalItem")
        currentItem.methods.add(ImmutableMethod(currentItem.type,"a",emptyList(),"Lj\$/util/Optional;",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(2)).toMutable().apply{addInstructions(0,"""
            invoke-static {}, Lapp/morphe/extension/music/jam/JamMirror;->now()Ljava/lang/Object;
            move-result-object v0
            if-eqz v0, :local
            invoke-static {v0}, Lj${'$'}/util/Optional;->of(Ljava/lang/Object;)Lj${'$'}/util/Optional;
            move-result-object v0
            return-object v0
            :local
            invoke-virtual {p0}, $localItem
            move-result-object v0
            return-object v0
        """)})
        for (type in listOf("Lrpb;","Lrrv;","Lrsa;")) {
            val ui = mutableClassDefBy(type);ui.interfaces.add(nowAccess)
            val entry = ui.methods.single { it.name == if(type=="Lrpb;") "q" else "o" }
            if(type!="Lrpb;") {
                val instructions=entry.implementation!!.instructions.toList()
                instructions.withIndex().filter { it.value.getReference<com.android.tools.smali.dexlib2.iface.reference.MethodReference>()?.toString()=="Lazbl;->b(I)Lazcf;" }.reversed().forEach { (index,_) ->
                    val register=(instructions[index+1] as OneRegisterInstruction).registerA
                    entry.addInstructions(index+2,"""
                        invoke-static/range {v$register .. v$register}, $playback->chooseItem(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, Lazcf;
                    """)
                }
            }
            entry.addInstructions(0,"invoke-static/range {p0 .. p0}, $playback->observe($nowAccess)V")
            val refreshCode=if(type=="Lrpb;") """
                iget-object v0, p0, Lrpb;->aF:Lcicl;
                invoke-interface {v0}, Lcicl;->gG()Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :refresh
                check-cast v0, Lngk;
                iget-object v0, v0, Lngk;->e:Lcom/google/android/apps/youtube/music/ui/image/AutoCropImageView;
                invoke-static {v0}, Lapp/morphe/extension/music/jam/JamArtwork;->bind(Landroid/widget/ImageView;)V
                :refresh
                invoke-virtual {p0}, Lrpb;->q()V
            """ else "invoke-virtual {p0}, Ltk;->eB()V"
            ui.methods.add(ImmutableMethod(type,"patch_jamRefreshNow",emptyList(),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(2)).toMutable().apply{addInstructions(0,"$refreshCode\nreturn-void")})
        }
        val artwork=mutableClassDefBy("Lngk;")
        val localArtwork=artwork.methods.single{it.name=="x"&&it.parameterTypes.map{p->p.toString()}==listOf("Landroid/graphics/Bitmap;")};localArtwork.setName("patch_jamLocalArtwork")
        artwork.methods.add(ImmutableMethod(artwork.type,"x",listOf(ImmutableMethodParameter("Landroid/graphics/Bitmap;",null,null)),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(3)).toMutable().apply{addInstructions(0,"""
            iget-object v0, p0, Lngk;->e:Lcom/google/android/apps/youtube/music/ui/image/AutoCropImageView;
            invoke-static {v0, p1}, Lapp/morphe/extension/music/jam/JamArtwork;->choose(Landroid/widget/ImageView;Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;
            move-result-object p1
            invoke-virtual {p0, p1}, $localArtwork
            return-void
        """)})
        val queueRow=mutableClassDefBy("Lqtp;")
        val menuRow="Lapp/morphe/extension/music/jam/JamMenu\$Row;"
        queueRow.interfaces.add(menuRow)
        fun menuMethod(name:String,args:List<String>,result:String,registers:Int,code:String) {
            queueRow.methods.add(ImmutableMethod(queueRow.type,name,args.map{ImmutableMethodParameter(it,null,null)},result,AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(registers)).toMutable().apply{addInstructions(0,code)})
        }
        menuMethod("patch_jamMenuItem",emptyList(),"Ljava/lang/Object;",2,"""
            iget-object v0, p0, Lqtp;->k:Lnoq;
            return-object v0
        """)
        menuMethod("patch_jamShowMenu",listOf("Landroid/view/View;","Ljava/lang/Object;"),"V",7,"""
            move-object v0, p2
            check-cast v0, Lnog;
            invoke-interface {v0}, Lnog;->c()Lbuzv;
            move-result-object v1
            if-eqz v1, :done
            instance-of v0, p2, Lnpa;
            if-eqz v0, :restored
            check-cast p2, Lnpa;
            iget-object v3, p2, Lnpa;->c:Lbxxg;
            goto :show
            :restored
            check-cast p2, Lnox;
            iget-object v3, p2, Lnox;->a:Lbxxg;
            :show
            iget-object v0, p0, Lqtp;->o:Lqfg;
            move-object v2, p1
            iget-object p0, p0, Lqtp;->R:Larcv;
            invoke-interface {v0, v1, v2, v3, p0}, Lqfg;->k(Lbuzv;Landroid/view/View;Ljava/lang/Object;Larcv;)V
            :done
            return-void
        """)
        val bindRow=queueRow.methods.single{it.name=="o" && it.parameterTypes.map{p->p.toString()}==listOf("Lbdlx;","Lbxxg;","Lnoq;")}
        bindRow.setName("patch_jamBindRow")
        menuMethod("o",listOf("Lbdlx;","Lbxxg;","Lnoq;"),"V",5,"""
            invoke-virtual {p0, p1, p2, p3}, $bindRow
            invoke-virtual {p0}, Lqtp;->a()Landroid/view/View;
            move-result-object v0
            invoke-static {v0, p0, p3}, Lapp/morphe/extension/music/jam/JamMenu;->bind(Landroid/view/View;${menuRow}Ljava/lang/Object;)V
            return-void
        """)
        val localClick=queueRow.methods.single{it.name=="fV"};localClick.setName("patch_jamLocalClick")
        queueRow.methods.add(ImmutableMethod(queueRow.type,"fV",listOf(ImmutableMethodParameter("Landroid/view/View;",null,null)),"Z",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(3)).toMutable().apply{addInstructions(0,"""
            iget-object v0, p0, Lqtp;->k:Lnoq;
            invoke-static {v0}, $playback->queueTap(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :done
            invoke-virtual {p0, p1}, $localClick
            move-result v0
            :done
            return v0
        """)})
        for(type in listOf("Lrnx;","Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControls;")) {
            val buttons=mutableClassDefBy(type);val nativeClick=buttons.methods.single{it.name=="onClick"};nativeClick.setName("patch_jamLocalClick")
            buttons.methods.add(ImmutableMethod(type,"onClick",listOf(ImmutableMethodParameter("Landroid/view/View;",null,null)),"V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(3)).toMutable().apply{addInstructions(0,"""
                invoke-static {p1}, $playback->playButton(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :local
                return-void
                :local
                invoke-virtual {p0, p1}, $nativeClick
                return-void
            """)})
        }
        // Post installation until after the activity has created its content view.
        MusicActivityOnCreateFingerprint.method.addInstructions(0,
            "invoke-static/range {p0 .. p0}, Lapp/morphe/extension/music/jam/JamUi;->install(Landroid/app/Activity;)V")
        // Optimized constructors can overwrite p0 before returning. Preserve the
        // receiver after its last field assignment in a register unused for the
        // entire remaining straight-line tail, then publish only at return.
        val implementation = constructor.implementation!!
        val instructions = implementation.instructions.toList()
        val lastStore = instructions.indexOfLast {
            it.opcode == Opcode.IPUT_OBJECT &&
                it.getReference<FieldReference>()?.definingClass == manager.type
        }
        check(lastStore >= 0) { "No manager field initialization found" }
        val receiver = (instructions[lastStore] as TwoRegisterInstruction).registerB
        val tail = instructions.drop(lastStore + 1)
        check(tail.none { it is OffsetInstruction }) { "Unsupported branching constructor tail" }
        val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        check(returns.size == 1 && returns.single().index > lastStore)
        val used = mutableSetOf(receiver)
        tail.forEach { instruction ->
            when (instruction) {
                is RegisterRangeInstruction -> used.addAll(instruction.startRegister until
                    instruction.startRegister + instruction.registerCount)
                is FiveRegisterInstruction -> used.addAll(listOf(instruction.registerC,
                    instruction.registerD, instruction.registerE, instruction.registerF,
                    instruction.registerG).take(instruction.registerCount))
                is ThreeRegisterInstruction -> used.addAll(listOf(instruction.registerA,
                    instruction.registerB, instruction.registerC))
                is TwoRegisterInstruction -> used.addAll(listOf(instruction.registerA, instruction.registerB))
                is OneRegisterInstruction -> used.add(instruction.registerA)
            }
        }
        val saved = (0 until implementation.registerCount).first { it !in used }
        constructor.addInstructions(returns.single().index,
            "invoke-static/range {v$saved .. v$saved}, $BRIDGE->capture($ACCESS)V")
        constructor.addInstructions(lastStore + 1, "move-object/16 v$saved, v$receiver")

        PreferenceScreen.PLAYER.addPreferences(NonInteractivePreference(
            key = "morphe_music_jam_probe",
            tag = "app.morphe.extension.music.jam.JamProbePreference",
            selectable = true,
        ))
    }
}
