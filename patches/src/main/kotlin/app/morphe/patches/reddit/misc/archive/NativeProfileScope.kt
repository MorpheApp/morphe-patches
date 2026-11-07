package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val SCOPE = "Lapp/morphe/extension/reddit/patches/archive/NativeProfileSection;"
private const val OBJ = "Ljava/lang/Object;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION2 = "Lkotlin/jvm/functions/Function2;"

/** Use the host CompositionLocalProvider so restart scopes inherit the same ownership policy. */
context(context: BytecodePatchContext)
internal fun installNativeProfileScope(composer: String, consume: MethodReference) = with(context) {
    val localType = consume.parameterTypes.single().toString()
    val local = classDefBy(localType)
    val provides = local.methods.single { it.parameterTypes == listOf(OBJ) && it.returnType.startsWith("L") }
    // StaticProvidableCompositionLocal has no policy/state fields. R8 removes its
    // constructor, so use the host factory instead of synthesizing a constructor call.
    val staticLocal = classDefBy { it.superclass == localType && it.fields.none() &&
        !AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.methods.any { method ->
            method.parameterTypes == listOf(OBJ) && method.returnType == provides.returnType
        }
    }
    val providerParameters = listOf(provides.returnType, FUNCTION2, composer, "I")
    val providerOwner = classDefBy { it.methods.any { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes == providerParameters && method.returnType == "V"
    } }
    val provider = providerOwner.methods.single { AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes == providerParameters && it.returnType == "V" }
    val factory = providerOwner.methods.single { AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes == listOf(FUNCTION0) && it.returnType == staticLocal.type }
    if (!AccessFlags.PUBLIC.isSet(staticLocal.accessFlags) || !AccessFlags.PUBLIC.isSet(factory.accessFlags)
        || !AccessFlags.PUBLIC.isSet(provider.accessFlags)) throw PatchException("Inaccessible profile composition local bridge")

    fun bridge(name: String, registers: Int, code: String) = replaceArchiveBridge(name, registers, code, SCOPE)
    bridge("newLocal", 2, """
        invoke-static { p0 }, $factory
        move-result-object v0
        return-object v0
    """)
    bridge("current", 2, """
        check-cast p0, ${consume.definingClass}
        sget-object v0, $SCOPE->LOCAL:$OBJ
        check-cast v0, $localType
        invoke-virtual { p0, v0 }, $consume
        move-result-object v0
        return-object v0
    """)
    bridge("provide", 5, """
        check-cast p0, $localType
        invoke-virtual { p0, p1 }, $provides
        move-result-object p0
        check-cast p3, $composer
        const/4 v0, 0x0
        invoke-static { p0, p2, p3, v0 }, $provider
        return-void
    """)
    val sectionInterface = classDefBy(NATIVE_SECTION).interfaces.single()
    val signatures = classDefBy(sectionInterface).methods
    val key = signatures.single { it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
    val content = signatures.single { it.returnType == "V" && it.parameterTypes.size == 3 }
    mutableClassDefBy(SCOPE).interfaces.add(sectionInterface)
    addNativeMethod(SCOPE, key, "invoke-virtual { p0 }, $SCOPE->key()Ljava/lang/String;\nmove-result-object v0\nreturn-object v0")
    addNativeMethod(SCOPE, content, "invoke-virtual { p0, p1, p2, p3 }, $SCOPE->render($OBJ${OBJ}I)V\nreturn-void")

    // Bind from the screen's actual feed host at every composition. A constructor-only
    // binding cannot cover retained screens or children composed before feed decoration.
    val bindings = mutableListOf<String>()
    val renderers = mutableListOf<String>()
    listOf("Posts", "Comments").forEachIndexed { index, kind ->
        val screen = mutableClassDefBy("Lcom/reddit/profile/submitted${kind.lowercase()}feed/ui/screens/Submitted${kind}FeedScreen;")
        val viewModel = classDefBy("Lcom/reddit/profile/submitted${kind.lowercase()}feed/viewmodel/Submitted${kind}ViewModel;")
        val modelField = screen.fields.single { it.type == viewModel.type }
        val host = nativeFeedState.classDef
        val hostField = screen.fields.single { it.type in host.interfaces &&
            classDefBy(it.type).methods.none { method -> method.name == "onEvent" } }
        val param = viewModel.fields.single { field -> classDefByOrNull(field.type)?.methods?.any {
            method -> method.name == "toString" && method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == "UserParam(username="
            } == true
        } == true }
        val username = constructorFields(classDefBy(param.type).methods.single { it.name == "<init>" })[0]
        val session = viewModel.fields.single { it.type == "Lcom/reddit/session/Session;" }
        bindings += """
            instance-of v0, p0, ${screen.type}
            if-eqz v0, :screen${index + 1}
            check-cast p0, ${screen.type}
            iget-object v0, p0, $hostField
            instance-of v1, v0, ${host.type}
            if-eqz v1, :none
            iget-object v1, p0, $modelField
            if-eqz v1, :none
            iget-object v3, v1, $session
            iget-object v1, v1, $param
            iget-object v1, v1, $username
            const/4 v2, $index
            invoke-static { v0, v1, v2, v3 }, $ARCHIVE_CLASS->bindSession(${OBJ}Ljava/lang/String;Z$OBJ)V
            return-object v0
            :screen${index + 1}
        """
        val original = screen.methods.single { it.parameterTypes == listOf(composer, "I") && it.returnType == "V" }
        val delegate = ImmutableMethod(screen.type, "morpheProfileContent", original.parameters,
            original.returnType, original.accessFlags, original.annotations, original.hiddenApiRestrictions,
            original.implementation!!).toMutable()
        screen.methods.add(delegate)
        // A restart begins inside the provider, at the original screen group. Its
        // callback must re-enter that group, not insert a second provider before it.
        val callbacks = original.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { ref -> ref.name == "<init>" }?.definingClass
        }.distinct()
        var restartCalls = 0
        callbacks.forEach { callback ->
            mutableClassDefBy(callback).methods.forEach { method ->
                method.implementation?.instructions?.toList()?.withIndex()?.forEach { (at, instruction) ->
                    if (((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == original.toString()) {
                        val replacement = when (instruction) {
                            is FiveRegisterInstruction -> BuilderInstruction35c(instruction.opcode, instruction.registerCount,
                                instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF,
                                instruction.registerG, delegate)
                            is RegisterRangeInstruction -> BuilderInstruction3rc(instruction.opcode,
                                instruction.startRegister, instruction.registerCount, delegate)
                            else -> throw PatchException("Unexpected profile restart invocation")
                        }
                        method.replaceInstruction(at, replacement)
                        restartCalls++
                    }
                }
            }
        }
        if (restartCalls != 1) throw PatchException("Expected one $kind screen restart callback, found $restartCalls")
        val entry = ImmutableMethod(screen.type, original.name, original.parameters, original.returnType,
            original.accessFlags, original.annotations, original.hiddenApiRestrictions,
            MutableMethodImplementation(3)).toMutable()
        entry.addInstructions(0, "invoke-static { p0, p1, p2 }, $SCOPE->renderScreen($OBJ${OBJ}I)V\nreturn-void")
        screen.methods.remove(original)
        screen.methods.add(entry)
        renderers += """
            instance-of v0, p0, ${screen.type}
            if-eqz v0, :content${index + 1}
            check-cast p0, ${screen.type}
            check-cast p1, $composer
            invoke-virtual { p0, p1, p2 }, $delegate
            return-void
            :content${index + 1}
        """
    }
    bridge("bindScreen", 5, bindings.joinToString("\n") + "\n:none\nconst/4 v0, 0x0\nreturn-object v0")
    bridge("screenContent", 4, renderers.joinToString("\n") + "\nreturn-void")
}
