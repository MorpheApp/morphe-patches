package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.reddit.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION = "Lapp/morphe/extension/reddit/patches/NsfwNavigationPatch;"

@Suppress("unused")
val nsfwNavigationPatch = patch(
    name = "Fix NSFW warning navigation",
    description = "Makes the NSFW warning return to the preceding page instead of Home, including the Android back gesture.",
    default = false
) {
    compatibleWith(nsfwCompatibility)
    dependsOn(settingsPatch)
    execute {
        val replaceScreen = NsfwReplaceScreenFingerprint.method
        val navigateHome = NsfwHomeNavigationFingerprint.method
        Fingerprint(
            returnType = "V",
            filters = listOf(
                newInstance("Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;"),
                methodCall(parameters = listOf("Landroid/content/Context;", "Lcom/reddit/screen/BaseScreen;", "L"), returnType = "V")
            )
        ).apply {
            method.apply {
                val index = instructionMatches.last().index
                val call = getInstruction<FiveRegisterInstruction>(index)
                addInstructionsWithLabels(index, """
                    invoke-static { v${call.registerC}, v${call.registerD} }, $replaceScreen
                    return-void
                """)
            }
        }

        val viewModel = "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel;"
        val warningHandler = Fingerprint(
            definingClass = "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel\$1;",
            name = "access\$invokeSuspend\$handleEvent", returnType = "Ljava/lang/Object;",
            filters = listOf(
                methodCall(parameters = listOf("Lcom/reddit/session/mode/common/SessionMode;"), returnType = "Z"),
                methodCall(smali = navigateHome.toString()),
                fieldAccess(definingClass = "Lkotlin/Unit;", opcode = Opcode.SGET_OBJECT)
            )
        )
        warningHandler.method.apply {
            val index = warningHandler.instructionMatches.first().index
            val unit = warningHandler.instructionMatches.last().getFieldAccessed()
            // Intercept before the session split: the signed-in arm clears the
            // navigation stack and replaces it with Home before the other arm runs.
            val navigateBackField = Fingerprint(definingClass = viewModel, name = "<init>")
                .originalClassDef.fields.single { it.type == "Lkotlin/jvm/functions/Function0;" }
            val register = findFreeRegister(index)
            addInstructionsWithLabels(index, """
                iget-object v$register, p0, $navigateBackField
                invoke-interface { v$register }, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
                sget-object v$register, $unit
                return-object v$register
            """)
        }

        // The same sheet installs a disabled no-op BackHandler. Wire the gesture to its native navigateBack callback.
        Fingerprint(
            definingClass = "Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;",
            filters = listOf(methodCall(parameters = listOf("I", "I", "L", "Lkotlin/jvm/functions/Function0;", "Z"), returnType = "V"))
        ).apply {
            method.apply {
                val index = instructionMatches.single().index
                val call = getInstruction<FiveRegisterInstruction>(index)
                val callback = call.registerF
                val enabled = call.registerG
                addInstructionsWithLabels(index, """
                    const/4 v$enabled, 0x1
                    new-instance v$callback, Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet${'$'}onInitialize${'$'}1${'$'}1;
                    invoke-direct { v$callback, p0 }, Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet${'$'}onInitialize${'$'}1${'$'}1;-><init>(Ljava/lang/Object;)V
                """)
            }
        }

        Fingerprint(filters = listOf(resourceLiteral(ResourceType.STRING, "nsfw_no_thanks")))
            .matchAll(1..4).forEach { match ->
                val index = match.instructionMatches.single().index
                val register = match.method.getInstruction<OneRegisterInstruction>(index).registerA
                match.method.addInstructions(index + 1, """
                    invoke-static {}, $EXTENSION->goBackLabelResource()I
                    move-result v$register
                """)
            }
    }
}
