/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.contexthook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

private lateinit var clientFormFactorFieldRef : WeakReference<FieldReference>
private lateinit var clientInfoFieldRef : WeakReference<FieldReference>
private lateinit var clientVersionFieldRef : WeakReference<FieldReference>
private lateinit var messageLiteBuilderFieldRef : WeakReference<FieldReference>
private lateinit var messageLiteBuilderMethodRef : WeakReference<MethodReference>
private lateinit var osNameFieldRef : WeakReference<FieldReference>

enum class Endpoint(
    vararg val parentFingerprints: Fingerprint,
    var smaliInstructions: String = "",
) {
    BROWSE(BrowseEndpointParentFingerprint),
    GET_WATCH(
        GetWatchEndpointConstructorPrimaryFingerprint,
        GetWatchEndpointConstructorSecondaryFingerprint,
    ),
    GUIDE(GuideEndpointConstructorFingerprint),
    NEXT(NextEndpointParentFingerprint),
    PLAYER(PlayerEndpointParentFingerprint),
    REEL(
        ReelCreateItemsEndpointConstructorFingerprint,
        ReelItemWatchEndpointConstructorFingerprint,
        ReelWatchSequenceEndpointConstructorFingerprint,
    ),
    SEARCH(SearchRequestBuildParametersFingerprint),
    TRANSCRIPT(TranscriptEndpointConstructorFingerprint);
}

val clientContextHookPatch = bytecodePatch(
    description = "Hooks the context body of the endpoint.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val clientInfoField : FieldReference
        val clientVersionField : FieldReference
        val messageLiteBuilderField : FieldReference

        BuildDummyClientContextBodyFingerprint.let {
            it.method.apply {
                val clientInfoIndex = it.instructionMatches.last().index
                val clientVersionIndex = it.instructionMatches[2].index
                val messageLiteBuilderIndex = it.instructionMatches.first().index

                clientInfoField = getInstruction<ReferenceInstruction>(
                    clientInfoIndex
                ).reference as FieldReference
                clientInfoFieldRef = WeakReference(clientInfoField)

                clientVersionField = getInstruction<ReferenceInstruction>(
                    clientVersionIndex
                ).reference as FieldReference
                clientVersionFieldRef = WeakReference(clientVersionField)

                messageLiteBuilderField = getInstruction<ReferenceInstruction>(
                    messageLiteBuilderIndex
                ).reference as FieldReference
                messageLiteBuilderFieldRef = WeakReference(messageLiteBuilderField)
            }
        }

        val messageLiteBuilderMethod : MethodReference
        AuthenticationChangeListenerFingerprint.method.apply {
            val messageLiteBuilderIndex = indexOfMessageLiteBuilderReference(
                this, messageLiteBuilderField.definingClass
            )

            messageLiteBuilderMethod = getInstruction<ReferenceInstruction>(
                messageLiteBuilderIndex
            ).reference as MethodReference
            messageLiteBuilderMethodRef = WeakReference(messageLiteBuilderMethod)
        }

        val osNameField : FieldReference
        BuildClientContextBodyFingerprint.match(
            BuildClientContextBodyConstructorFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val osNameIndex = it.instructionMatches[1].index
                osNameField = getInstruction<ReferenceInstruction>(
                    osNameIndex
                ).reference as FieldReference
                osNameFieldRef = WeakReference(osNameField)
            }
        }

        val clientFormFactorOrdinalReference = ClientFormFactorEnumOrdinalFingerprint.match(
            ClientFormFactorEnumConstructorFingerprint.originalClassDef
        ).method as MethodReference

        val setClientFormFactorFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf("L"),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET,
                    definingClass = CLIENT_INFO_CLASS_DESCRIPTOR,
                    type = "I"
                ),
                methodCall(
                    reference = clientFormFactorOrdinalReference
                )
            )
        )

        val clientFormFactorField : FieldReference
        setClientFormFactorFingerprint.let {
            it.method.apply {
                val clientFormFactorIndex = it.instructionMatches.first().index
                clientFormFactorField = getInstruction<ReferenceInstruction>(
                    clientFormFactorIndex
                ).reference as FieldReference
                clientFormFactorFieldRef = WeakReference(clientFormFactorField)
            }
        }
    }

    finalize {
        val clientInfoField = clientInfoFieldRef.get()!!
        val messageLiteBuilderField = messageLiteBuilderFieldRef.get()
        val messageLiteBuilderMethod = messageLiteBuilderMethodRef.get()
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
                    // 21.05+ clobbers p0 register.
                    it.method.cloneMutableAndPreserveParameters().apply {
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
                                    """
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

fun addClientFormFactorHook(endPoint: Endpoint, descriptor: String) {
    val clientFormFactorField = clientFormFactorFieldRef.get()
    val smaliInstructions = """
        iget v2, v1, $clientFormFactorField
        invoke-static { v2 }, $descriptor
        move-result v2
        iput v2, v1, $clientFormFactorField
        """

    endPoint.smaliInstructions += smaliInstructions
}

fun addClientVersionHook(endPoint: Endpoint, descriptor: String) {
    val clientVersionField = clientVersionFieldRef.get()
    val smaliInstructions = """
        iget-object v2, v1, $clientVersionField
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $clientVersionField
        """

    endPoint.smaliInstructions += smaliInstructions
}

fun addOSNameHook(endPoint: Endpoint, descriptor: String) {
    val osNameField = osNameFieldRef.get()
    val smaliInstructions = """
        iget-object v2, v1, $osNameField
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $osNameField
        """

    endPoint.smaliInstructions += smaliInstructions
}
