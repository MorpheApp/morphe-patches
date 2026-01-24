package app.morphe.patches.youtube.misc.contexthook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private lateinit var browseIdField: FieldReference
private lateinit var clientInfoField: FieldReference
private lateinit var clientVersionField: FieldReference
private lateinit var messageLiteBuilderField: FieldReference
private lateinit var messageLiteBuilderMethod: MethodReference
private lateinit var osNameField: FieldReference

enum class Endpoint(
    vararg val parentFingerprints: Fingerprint,
    var smaliInstructions: String = "",
) {
    BROWSE(BrowseEndpointParentFingerprint),
    GUIDE(GuideEndpointConstructorFingerprint),
    REEL(
        ReelCreateItemsEndpointConstructorFingerprint,
        ReelItemWatchEndpointConstructorFingerprint,
        ReelWatchSequenceEndpointConstructorFingerprint,
    );
}

val clientContextHookPatch = bytecodePatch(
    description = "Hooks the context body of the endpoint.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        BuildDummyClientContextBodyFingerprint.let {
            it.method.apply {
                val clientInfoIndex = it.instructionMatches.last().index
                val clientVersionIndex = it.instructionMatches[2].index
                val messageLiteBuilderIndex = it.instructionMatches.first().index

                clientInfoField =
                    getInstruction<ReferenceInstruction>(clientInfoIndex).reference as FieldReference
                clientVersionField =
                    getInstruction<ReferenceInstruction>(clientVersionIndex).reference as FieldReference
                messageLiteBuilderField =
                    getInstruction<ReferenceInstruction>(messageLiteBuilderIndex).reference as FieldReference
            }
        }

        AuthenticationChangeListenerFingerprint.method.apply {
            val messageLiteBuilderIndex =
                indexOfMessageLiteBuilderReference(this, messageLiteBuilderField.definingClass)

            messageLiteBuilderMethod =
                getInstruction<ReferenceInstruction>(messageLiteBuilderIndex).reference as MethodReference
        }

        BuildClientContextBodyFingerprint.match(
            BuildClientContextBodyConstructorFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val osNameIndex = it.instructionMatches[1].index
                osNameField =
                    getInstruction<ReferenceInstruction>(osNameIndex).reference as FieldReference
            }
        }

        BrowseEndpointConstructorFingerprint.match(
            BrowseEndpointParentFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val browseIdIndex = it.instructionMatches.last().index
                browseIdField =
                    getInstruction<ReferenceInstruction>(browseIdIndex).reference as FieldReference
            }
        }
    }

    finalize {
        val helperMethodName = "patch_setClientContext"

        Endpoint.entries.filter {
            it.smaliInstructions.isNotEmpty()
        }.forEach { endpoint ->
            endpoint.parentFingerprints.forEach { parentFingerprint ->
                // Use locally declared fingerprint because internally fingerprint caches the match.
                // Could use Fingerprint.clearMatch() but creating a new instance also works.
                val endpointRequestBodyFingerprint = Fingerprint(
                    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
                    returnType = "V",
                    parameters = listOf(),
                )

                endpointRequestBodyFingerprint.match(
                    parentFingerprint.originalClassDef
                ).let {
                    it.method.apply {
                        it.classDef.methods.add(
                            ImmutableMethod(
                                definingClass,
                                helperMethodName,
                                emptyList(),
                                "V",
                                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                                annotations,
                                null,
                                MutableMethodImplementation(5),
                            ).toMutable().apply {
                                addInstructionsWithLabels(
                                    0,
                                    """
                                        invoke-virtual { p0 }, $messageLiteBuilderMethod
                                        move-result-object v0
                                        iget-object v0, v0, $messageLiteBuilderField
                                        check-cast v0, ${clientInfoField.definingClass}
                                        iget-object v1, v0, $clientInfoField
                                        if-eqz v1, :ignore
                                    """ + endpoint.smaliInstructions +
                                    """
                                        :ignore
                                        return-void
                                    """,
                                )
                            }
                        )

                        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                            addInstructionsAtControlFlowLabel(
                                index,
                                "invoke-direct/range { p0 .. p0 }, $definingClass->$helperMethodName()V"
                            )
                        }
                    }
                }
            }
        }
    }
}

fun addClientVersionHook(endPoint: Endpoint, descriptor: String) {
    val smaliInstructions = if (endPoint == Endpoint.BROWSE) """
        iget-object v3, p0, $browseIdField
        iget-object v2, v1, $clientVersionField
        invoke-static { v3, v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $clientVersionField
        """ else """
        iget-object v2, v1, $clientVersionField
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $clientVersionField
        """

    endPoint.smaliInstructions += smaliInstructions
}

fun addOSNameHook(endPoint: Endpoint, descriptor: String) {
    val smaliInstructions = """
        iget-object v2, v1, $osNameField
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $osNameField
        """

    endPoint.smaliInstructions += smaliInstructions
}
